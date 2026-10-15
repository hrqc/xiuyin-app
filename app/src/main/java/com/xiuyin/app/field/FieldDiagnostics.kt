package com.xiuyin.app.field

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** Shared session recorder. Record methods never perform disk I/O on the microphone or inference thread. */
class FieldDiagnostics private constructor(context: Context) {
    private val app: android.app.Application = context.applicationContext as android.app.Application
    private val sessionsRoot = File(app.filesDir, "field-sessions")
    private val exportRoot = File(app.filesDir, "field-exports")
    private val mutableState = MutableStateFlow(FieldDiagnosticsState())
    val state: StateFlow<FieldDiagnosticsState> = mutableState.asStateFlow()
    private val queue = ArrayBlockingQueue<() -> Unit>(256)
    private val accepting = AtomicBoolean(true)
    private val active = AtomicReference<String?>(null)
    private val consent = AtomicBoolean(false)
    private val consentGeneration = AtomicLong()
    private val stream = AtomicLong()
    private val faultMessage = AtomicReference<String?>(null)
    private val pendingFault = AtomicReference<String?>(null)
    private val metricLock = Any()
    private var countedEngine = RecognitionEngineSnapshot()
    private var totalDroppedBlocks = 0L
    private var totalDroppedSamples = 0L
    private var totalOverflows = 0L
    @Volatile private var failureListener: ((String) -> Unit)? = null
    @Volatile private var audio = AudioSnapshot()
    @Volatile private var engine = RecognitionEngineSnapshot()
    @Volatile private var captureAnchor: Pair<Long, Long>? = null
    @Volatile private var lastFrame: PianoRecognitionFrame? = null
    @Volatile private var lastFollowing: FieldFollowingTrace? = null
    private val recentLogs = ArrayDeque<Map<String, Any?>>()
    private val ring = FieldPcmRing()
    private val clipLock = Any()
    private data class PendingClip(val generation: Long, val streamId: Long, val eventSample: Long, val start: Long, val end: Long, val metadata: Map<String, Any?>)
    private val pendingClips = mutableListOf<PendingClip>()
    private var lastClipSample = Long.MIN_VALUE
    private var lastAutomaticClipSample = Long.MIN_VALUE
    private var lastAmbiguousSignature: Set<Int> = emptySet()
    private var wasFollowingLocked = false
    private var wasFollowingLost = false
    private var store: FieldSessionStore? = null
    private var lastFlushNanos = 0L
    private var lastQueuePublishNanos = 0L
    private var writableAtLastCheck = false
    private var lastDeviceSignature: List<Any?>? = null
    private val writerTaskTimes = LatencyStats()
    private val checkpointTimes = LatencyStats()
    private val writerQueueHighWater = AtomicLong()
    private var storageUuid: java.util.UUID? = null
    private val recognitionTimes = LatencyStats()
    private val followingTimes = LatencyStats()
    private val callbackAvailabilityTimes = LatencyStats()
    private var previousCpuMillis = Process.getElapsedCpuTime()
    private var previousCpuTimeNanos = SystemClock.elapsedRealtimeNanos()
    private var lastCpu: Double? = null
    private var lastPss: Int? = null
    private var lastEngineStatus: String? = null
    private var lastAudioStatus: String? = null
    @Volatile private var expectedAudioStop = true
    private var lastEngineRecordedAt = 0L
    private val crashMarker = File(sessionsRoot, "last-crash.json")
    companion object {
        @Volatile private var instance: FieldDiagnostics? = null
        fun get(context: Context): FieldDiagnostics = instance ?: synchronized(this) {
            instance ?: FieldDiagnostics(context).also { instance = it }
        }
    }
    init {
        Thread({
            try {
                store = FieldSessionStore(sessionsRoot, exportRoot, availableBytes = ::availableStorageBytes)
                writableAtLastCheck = requiredStore().writable()
                publish()
                if (crashMarker.exists()) {
                    val crash = FieldJson.objectValue(crashMarker.readText())
                    val sourceId = (crash["lastState"] as? Map<*, *>)?.get("sessionId") as? String
                    if (sourceId != null && store?.sessions()?.any { it.id == sourceId } == true) {
                        store?.appendToSession(sourceId, "app.log", crash + mapOf("recoveredCrashMarker" to true), reserveBytes = 8192)
                        val archivedMarker = File(File(sessionsRoot, sourceId), "recovered-crash-${java.util.UUID.randomUUID()}.json")
                        java.nio.file.Files.move(crashMarker.toPath(), archivedMarker.toPath())
                    } else synchronized(recentLogs) { recentLogs.addLast(crash) }
                    mutableState.update { it.copy(message = "检测到上次异常；未完成会话可导出，异常记录将保留") }
                }
                while (true) {
                    val task = queue.poll(500, TimeUnit.MILLISECONDS)
                    if (task != null) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        task.invoke()
                        writerTaskTimes.add((SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                    }
                    pendingFault.getAndSet(null)?.let { message ->
                        try { store?.markFault(message) } catch (error: Exception) {
                            mutableState.update { it.copy(error = "$message；故障标记写盘也失败：${error.message}") }
                        }
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (active.get() != null && now - lastFlushNanos >= 1_000_000_000L) {
                        val checkpointStarted = SystemClock.elapsedRealtimeNanos()
                        try { persistPerformance(); store?.checkpoint() } catch (error: Exception) { fail("诊断检查点失败：${error.message}") }
                        checkpointTimes.add((SystemClock.elapsedRealtimeNanos() - checkpointStarted) / 1e6)
                        lastFlushNanos = now
                        publish()
                    }
                    if (now - lastQueuePublishNanos >= 100_000_000L) {
                        mutableState.update { it.copy(writerQueueOccupancy = queue.size) }
                        lastQueuePublishNanos = now
                    }
                }
            } catch (error: Throwable) {
                fail("诊断写入线程停止：${error.message}")
                // Complete already queued control operations with the same visible error instead of hanging.
                store = null
                while (true) queue.take().invoke()
            }
        }, "field-diagnostics-writer").apply { isDaemon = true; start() }
        installCrashHandler()
    }
    fun setFailureListener(listener: ((String) -> Unit)?) { failureListener = listener; state.value.error?.let { listener?.invoke(it) } }
    fun setClipConsent(value: Boolean) {
        consent.set(value); consentGeneration.incrementAndGet()
        synchronized(clipLock) { pendingClips.clear() }
        mutableState.update { it.copy(clipConsent = value) }
        recordLog("INFO", "Field", if (value) "ClipConsentGranted" else "ClipConsentRevoked")
    }
    suspend fun startSession(label: String, metadata: Map<String, String> = emptyMap(), scoreSnapshot: FieldScoreSnapshot? = null): FieldSessionInfo = operation {
        check(active.get() == null) { "已有实地测试会话" }
        val disk = requiredStore()
        writableAtLastCheck = disk.writable()
        check(writableAtLastCheck) { "诊断目录不可写" }
        val info = try { disk.start(label, identity(), device(), metadata + mapOf("initialModelState" to engine.status.name), scoreSnapshot) } catch (error: Exception) {
            active.set(disk.active()?.id)
            publish()
            throw error
        }
        active.set(info.id); accepting.set(true); faultMessage.set(null)
        recognitionTimes.clear(); followingTimes.clear(); callbackAvailabilityTimes.clear(); writerTaskTimes.clear(); checkpointTimes.clear(); writerQueueHighWater.set(0); lastDeviceSignature = null
        synchronized(metricLock) { totalDroppedBlocks = 0; totalDroppedSamples = 0; totalOverflows = 0; countedEngine = engine }
        mutableState.update { it.copy(error = null, message = "FIELD TEST MODE：已开始本地增量诊断") }
        synchronized(recentLogs) { recentLogs.toList() }.forEach { disk.append("app.log", it + mapOf("beforeSessionStart" to true)) }
        if (crashMarker.exists()) {
            disk.append("app.log", FieldJson.objectValue(crashMarker.readText()) + mapOf("recoveredFromPreviousProcess" to true))
        }
        publish(); info
    }
    suspend fun endSession(reason: String = "USER_ENDED"): FieldSessionInfo? {
        endAudioStream()
        accepting.set(false)
        return operation {
            requiredStore().withExportReserve { persistPerformance() }
            val info = requiredStore().end(reason)
            active.set(null)
            mutableState.update { it.copy(message = "实地测试已结束；尚未保存 ZIP 的会话会保留") }
            publish(); info
        }
    }
    suspend fun refresh() { operation {
        val disk = requiredStore()
        disk.checkpoint(); disk.refreshAccounting(); disk.freeBytes()
        writableAtLastCheck = disk.writable()
        publish()
    } }
    suspend fun exportSession(sessionId: String): File = operation {
        requiredStore().withExportReserve {
        val disk = requiredStore()
        disk.appendToSession(sessionId, "app.log", logRecord("INFO", "Field", "ExportStart", sessionId))
        try {
            if (active.get() == sessionId) persistPerformance()
            disk.export(sessionId)
            disk.appendToSession(sessionId, "app.log", logRecord("INFO", "Field", "ExportSuccess", "本地 ZIP 已实际生成并通过完整性检查；尚未确认外部保存"))
            // Include the verified first generation's success event in the final checked archive.
            val file = disk.export(sessionId)
            mutableState.update { it.copy(message = "ZIP 已生成并校验，等待保存到所选位置") }
            publish(); file
        } catch (error: Exception) {
            try { disk.appendToSession(sessionId, "app.log", logRecord("ERROR", "Field", "ExportFailed", error.message.orEmpty())) }
            catch (loggingError: Exception) { error.addSuppressed(loggingError) }
            throw error
        }
        }
    }
    suspend fun markExported(sessionId: String) { operation { requiredStore().markExported(sessionId); mutableState.update { it.copy(message = "诊断 ZIP 已成功保存") }; publish() } }
    suspend fun clearSessions(ids: Set<String>) { operation { requiredStore().clear(ids); mutableState.update { it.copy(message = "所选诊断已清理") }; publish() } }
    fun recordLog(level: String, module: String, message: String, detail: String = "") {
        val record = logRecord(level, module, message, detail)
        synchronized(recentLogs) { if (recentLogs.size == 96) recentLogs.removeFirst(); recentLogs.addLast(record) }
        enqueue { requiredStore().append("app.log", record) }
    }
    fun recordAudio(snapshot: AudioSnapshot) {
        audio = snapshot
        val previous = lastAudioStatus
        lastAudioStatus = snapshot.status.name
        if (previous != snapshot.status.name) recordLog("INFO", "Audio", "Microphone${snapshot.status.name}", snapshot.statusMessage)
        val streamId = stream.get()
        val record = mapOf("type" to "AudioCaptureStatus", "timestamp" to System.currentTimeMillis(), "monotonicNanos" to SystemClock.elapsedRealtimeNanos(),
            "audioStreamId" to streamId, "engineSessionId" to engine.sessionId, "status" to snapshot.status.name,
            "readCompletedAtNanos" to snapshot.capturedAtNanos, "sampleRate" to snapshot.sampleRate, "frameSamples" to snapshot.frameSamples,
            "inputLevel" to snapshot.inputLevelDbfs, "noiseFloor" to snapshot.noiseFloorDbfs, "clipped" to snapshot.clipped,
            "audioSource" to snapshot.actualAudioSource, "actualInputRoute" to snapshot.actualInputRoute)
        enqueue { requiredStore().append("audio-events.jsonl", record) }
        if (active.get() != null && (snapshot.status.name in setOf("ERROR", "UNAVAILABLE", "SILENCED", "PERMISSION_DENIED") ||
                (!expectedAudioStop && previous == "LISTENING" && snapshot.status.name in setOf("STOPPING", "IDLE")))) {
            automaticClip("AUDIO_ERROR", lastFrame?.audioTimeSeconds, mapOf("status" to snapshot.status.name))
            fail("AudioEngineError：${snapshot.status.name}：${snapshot.statusMessage}")
        }
    }
    fun recordEngine(snapshot: RecognitionEngineSnapshot) {
        var overflowRaised = false
        synchronized(metricLock) {
            val previous = countedEngine
            if (active.get() != null) {
                val same = previous.sessionId == snapshot.sessionId
                overflowRaised = snapshot.overflowCount > if (same) previous.overflowCount else 0L
                totalDroppedBlocks += maxOf(0L, snapshot.droppedFrames - if (same) previous.droppedFrames else 0L)
                totalDroppedSamples += maxOf(0L, snapshot.droppedSamples - if (same) previous.droppedSamples else 0L)
                totalOverflows += maxOf(0L, snapshot.overflowCount - if (same) previous.overflowCount else 0L)
            }
            countedEngine = snapshot
        }
        engine = snapshot
        val at = SystemClock.elapsedRealtimeNanos()
        val overflow = synchronized(metricLock) { totalOverflows }
        if (snapshot.status.name != lastEngineStatus || at - lastEngineRecordedAt >= 100_000_000 || snapshot.overflowCount > 0) {
            lastEngineRecordedAt = at
            val record = mapOf("type" to "EngineStatus", "timestamp" to System.currentTimeMillis(), "monotonicNanos" to at,
                "audioStreamId" to stream.get(), "engineSessionId" to snapshot.sessionId, "status" to snapshot.status.name,
                "message" to snapshot.message, "frameCount" to snapshot.frameCount, "queueOccupancy" to snapshot.queueOccupancy,
                "queueCapacity" to snapshot.queueCapacity, "maxQueueOccupancy" to snapshot.maxQueueOccupancy,
                "droppedFrames" to snapshot.droppedFrames, "droppedSamples" to snapshot.droppedSamples,
                "overflowCount" to snapshot.overflowCount, "sessionOverflowCount" to overflow,
                "rejectedBeforeReadyFrames" to snapshot.rejectedBeforeReadyFrames, "submittedBlocks" to snapshot.submittedBlocks,
                "processedBlocks" to snapshot.processedBlocks, "submittedSamples" to snapshot.submittedSamples,
                "processedSamples" to snapshot.processedSamples, "modelLoadMs" to snapshot.modelLoadMs)
            enqueue { requiredStore().append("audio-events.jsonl", record) }
        }
        if (overflowRaised) {
            recordLog("ERROR", "Pitch", "QueueOverflow", "engineSessionId=${snapshot.sessionId}; overflowCount=${snapshot.overflowCount}; droppedSamples=${snapshot.droppedSamples}")
            automaticClip("AUDIO_ERROR", lastFrame?.audioTimeSeconds, mapOf("reason" to "QueueOverflow"))
            fail("QueueOverflow：识音队列溢出，输入已不连续")
        }
        val previous = lastEngineStatus
        lastEngineStatus = snapshot.status.name
        if (previous != snapshot.status.name) {
            val event = when (snapshot.status.name) { "LOADING" -> "ModelLoading"; "READY" -> "ModelReady"; "ERROR" -> "ModelFailed"; else -> "Model${snapshot.status.name}" }
            recordLog(if (snapshot.status.name == "ERROR") "ERROR" else "INFO", "Pitch", event, snapshot.message)
            if (active.get() != null && snapshot.status.name == "ERROR") {
                automaticClip("AUDIO_ERROR", lastFrame?.audioTimeSeconds, mapOf("reason" to snapshot.message))
                fail("AudioEngineError：${snapshot.message}")
            }
        }
    }
    /** Must be called while the host input lifecycle guard still owns this delivery. */
    fun bindFrameContext(frame: PianoRecognitionFrame): PianoRecognitionFrame = synchronized(clipLock) {
        frame.copy(fieldSessionId = active.get(), audioStreamId = stream.get(), engineSessionId = engine.sessionId)
    }
    private fun sourceMatches(sessionId: String?, audioStreamId: Long?, engineSessionId: Long?): Boolean =
        sessionId != null && sessionId == active.get() && audioStreamId == stream.get() && engineSessionId == engine.sessionId
    fun recordRecognition(frame: PianoRecognitionFrame) {
        if (active.get() == null) return
        if (!sourceMatches(frame.fieldSessionId, frame.audioStreamId, frame.engineSessionId)) {
            recordLog("WARNING", "Field", "StaleFrameRejected", "识音帧未绑定当前会话与音频流，未写入新流")
            return
        }
        val retained = frame.copy(rawOnsetScores = frame.rawOnsetScores.copyOf(), rawFrameScores = frame.rawFrameScores.copyOf())
        lastFrame = retained
        val at = SystemClock.elapsedRealtimeNanos()
        val streamId = frame.audioStreamId
        val engineId = frame.engineSessionId
        val capture = captureAnchor
        // PCM read-completion extrapolation only; hardware/acoustic input latency has not been calibrated.
        val estimate = capture?.let { (sample, nanos) -> nanos + ((frame.audioTimeSeconds * 48_000 - sample) / 48_000 * 1e9).toLong() }
        val availabilityMs = estimate?.let { (at - it) / 1e6 }?.takeIf { it >= 0 && it.isFinite() }
        val currentAudio = audio
        val ambiguous = retained.ambiguousOctaveMidi
        if (ambiguous.isNotEmpty() && ambiguous != lastAmbiguousSignature) {
            automaticClip("AMBIGUOUS_OCTAVE", retained.audioTimeSeconds, mapOf("frameIndex" to retained.frameIndex.toString(), "ambiguousMidi" to ambiguous.sorted().joinToString()))
        }
        lastAmbiguousSignature = ambiguous.toSet()
        enqueue {
            require(retained.rawOnsetScores.size == 88 && retained.rawFrameScores.size == 88) { "模型原始输出必须为 88 键" }
            recognitionTimes.add(retained.computeMs)
            availabilityMs?.let { callbackAvailabilityTimes.add(it) }
            val rawDetected = retained.notes.filter { it.onsetScore >= 0.60f && it.frameScore >= 0.50f }.map { it.midi }
            val records = mutableListOf<Pair<String, Map<String, Any?>>>()
            records += "audio-events.jsonl" to mapOf(
                "type" to "RecognitionFrame", "timestamp" to System.currentTimeMillis(), "monotonicNanos" to at,
                "audioStreamId" to streamId, "engineSessionId" to engineId, "frameIndex" to retained.frameIndex,
                "audioTimeSeconds" to retained.audioTimeSeconds, "availableAudioTimeSeconds" to retained.availableAudioTimeSeconds,
                "rawDetectedNotes" to rawDetected, "rawDetectedNotesDefinition" to "Raw model candidates at fixed display threshold onset >= .60 and frame >= .50; all 88 scores are retained.",
                "rawOnsetScores" to retained.rawOnsetScores, "rawFrameScores" to retained.rawFrameScores,
                "onsetProbability" to retained.rawOnsetScores, "frameProbability" to retained.rawFrameScores,
                "scoreDefinition" to "Raw neural sigmoid activations, not calibrated probabilities or accuracy", "confidence" to null,
                "noteEvidence" to retained.notes.map { mapOf("midi" to it.midi, "onsetScore" to it.onsetScore, "frameScore" to it.frameScore, "onset" to it.onset, "active" to it.active) },
                "sustainState" to retained.state.name, "pedalState" to retained.pedalState, "offsetSupported" to retained.offsetSupported,
                "noiseFloor" to currentAudio.noiseFloorDbfs, "inputLevel" to currentAudio.inputLevelDbfs, "clipped" to retained.clipped,
                "finalCandidates" to retained.onsetMidi.sorted(), "activeMidi" to retained.activeMidi.sorted(), "uncertainMidi" to retained.uncertainMidi.sorted(),
                "pendingOnsetMidi" to retained.pendingOnsetMidi.sorted(),
                "singlePitchReference" to retained.singlePitchReference?.toFieldLogRecord(),
                "candidateRejections" to retained.candidateRejections.mapKeys { it.key.toString() }, "ambiguousOctaveMidi" to retained.ambiguousOctaveMidi.sorted(),
                "finalDecision" to "UNDECIDED_BY_RECOGNIZER", "decisionSource" to "See score-following.jsonl with matching audioStreamId/frameIndex",
                "modelComputeMs" to retained.computeMs, "modelWindowMs" to retained.modelWindowMs,
                "readCompletionToCallbackMs" to availabilityMs, "latencyDefinition" to "PCM-read-completion sample-index estimate; not hardware acoustic end-to-end latency")
            retained.octaveDiagnostics.forEach { d -> records += "octave-diagnostics.jsonl" to mapOf(
                "timestamp" to System.currentTimeMillis(), "audioStreamId" to streamId, "engineSessionId" to engineId,
                "frameIndex" to retained.frameIndex, "audioTimeSeconds" to retained.audioTimeSeconds,
                "lowerNote" to d.lowerMidi, "upperCandidate" to d.upperMidi, "upperOctaveCandidate" to d.upperMidi,
                "lowerOnsetProbability" to d.lowerOnsetScore, "upperOnsetProbability" to d.upperOnsetScore,
                "lowerFrameProbability" to d.lowerFrameScore, "upperFrameProbability" to d.upperFrameScore,
                "temporalOffsetSeconds" to d.temporalOffsetSeconds, "temporalOffset" to d.temporalOffsetSeconds, "lowerFundamentalAmplitude" to d.lowerFundamentalAmplitude,
                "upperFundamentalAmplitude" to d.upperFundamentalAmplitude, "upperAttackEnergyRise" to d.upperAttackEnergyRise,
                "lowerAttackEnergyRise" to d.lowerAttackEnergyRise, "harmonicPrior" to d.harmonicPrior,
                "independentOnsetScore" to d.independentOnsetScore, "scoreContextWeight" to d.scoreContextWeight,
                "finalState" to d.finalState.name, "finalOctaveState" to d.finalState.name, "finalConfidence" to d.finalConfidence, "reason" to d.reason) }
            requiredStore().appendBatch(records)
        }
    }
    fun recordFollowing(trace: FieldFollowingTrace) {
        if (active.get() == null) return
        if (!sourceMatches(trace.fieldSessionId, trace.audioStreamId, trace.engineSessionId)) {
            recordLog("WARNING", "Field", "StaleFollowingRejected", "跟谱记录来源与当前流不一致，未重新归属到新流")
            return
        }
        trace.scoreAssistance?.let { assistance ->
            require(assistance.frameIndex == trace.frameIndex && assistance.fieldSessionId == trace.fieldSessionId &&
                assistance.audioStreamId == trace.audioStreamId && assistance.engineSessionId == trace.engineSessionId) {
                "谱面辅助证据必须绑定同一识音帧与会话"
            }
        }
        lastFollowing = trace
        val streamId = trace.audioStreamId
        val engineId = trace.engineSessionId
        val lost = trace.practiceState == "LOST" || trace.recoveryEvent == "TRACKING_LOST"
        if ((trace.locked && !wasFollowingLocked) || (lost && !wasFollowingLost)) {
            automaticClip(if (trace.locked) "SUSPECTED_FALSE_STOP" else "TRACKING_LOST", trace.evidenceAudioTimeSeconds ?: trace.audioTimeSeconds,
                mapOf("frameIndex" to trace.frameIndex.toString(), "decision" to trace.decision, "expectedNotes" to trace.expectedNotes.sorted().joinToString(), "detectedNotes" to trace.detectedNotes.sorted().joinToString(), "reason" to trace.reason))
        }
        wasFollowingLocked = trace.locked; wasFollowingLost = lost
        val timestamp = System.currentTimeMillis()
        enqueue {
            trace.followingLatencyMs?.let { followingTimes.add(it) }
            requiredStore().append("score-following.jsonl", mapOf("timestamp" to timestamp, "audioStreamId" to streamId,
                "engineSessionId" to engineId, "frameIndex" to trace.frameIndex, "audioTimeSeconds" to trace.audioTimeSeconds,
                "expectedMeasure" to trace.expectedMeasure, "expectedBeat" to trace.expectedBeat,
                "expectedNotes" to trace.expectedNotes.sorted(), "detectedNotes" to trace.detectedNotes.sorted(),
                "previousScorePosition" to trace.previousScorePosition, "currentScorePosition" to trace.currentScorePosition,
                "decision" to trace.decision, "practiceState" to trace.practiceState, "lockState" to trace.locked,
                "reason" to trace.reason, "recoveryEvent" to trace.recoveryEvent,
                "followingLatencyMs" to trace.followingLatencyMs, "confirmedDecision" to trace.confirmedDecision,
                "decisionEventId" to trace.decisionEventId, "evidenceAudioTimeSeconds" to trace.evidenceAudioTimeSeconds,
                "scoreAssistance" to trace.scoreAssistance?.toLogRecord()))
        }
    }
    /** A lifecycle disposition, never a note judgment or a reuse of the follower's last decision. */
    fun recordNotEvaluated(frame: PianoRecognitionFrame, reason: String, actualPracticeState: String,
                           actualPosition: String?, locked: Boolean) {
        if (active.get() == null || frame.fieldSessionId == null) return
        if (!sourceMatches(frame.fieldSessionId, frame.audioStreamId, frame.engineSessionId)) {
            recordLog("WARNING", "Field", "StaleDispositionRejected", "未判分帧不属于当前流；未重新归属")
            return
        }
        val at = System.currentTimeMillis()
        enqueue(allowAfterFault = true) {
            requiredStore().append("score-following.jsonl", mapOf(
                "timestamp" to at, "audioStreamId" to frame.audioStreamId, "engineSessionId" to frame.engineSessionId,
                "frameIndex" to frame.frameIndex, "audioTimeSeconds" to frame.audioTimeSeconds,
                "processingDisposition" to "NOT_EVALUATED", "decision" to "NOT_EVALUATED",
                "practiceState" to actualPracticeState, "lockState" to locked, "reason" to reason,
                "previousScorePosition" to actualPosition, "currentScorePosition" to actualPosition,
                "expectedMeasure" to null, "expectedBeat" to null,
                "expectedNotes" to emptyList<Int>(), "detectedNotes" to emptyList<Int>(),
                "observedCandidates" to frame.onsetMidi.sorted(),
                "recoveryEvent" to null, "followingLatencyMs" to null,
                "confirmedDecision" to false, "decisionEventId" to null, "evidenceAudioTimeSeconds" to null,
                "countsAsMusicalEvaluation" to false))
        }
        if (reason != "PRACTICE_COMPLETED" || actualPracticeState != "COMPLETED") {
            fail("跟谱帧未判分：$reason；frame=${frame.frameIndex}；请结束并导出故障记录")
        }
    }
    fun appendPcm(samples: ShortArray, count: Int, firstInputSample: Long) {
        if (active.get() == null || !accepting.get()) return
        synchronized(clipLock) {
            expectedAudioStop = false
            if (!ring.append(samples, count, firstInputSample)) {
                stream.incrementAndGet(); pendingClips.clear(); lastClipSample = Long.MIN_VALUE; lastAutomaticClipSample = Long.MIN_VALUE; lastAmbiguousSignature = emptySet(); wasFollowingLocked = false; wasFollowingLost = false; lastFrame = null; lastFollowing = null
                recordLog("WARNING", "Audio", "AudioStreamDiscontinuity", "PCM 索引不连续，旧片段请求取消")
            }
            captureAnchor = (firstInputSample + count) to SystemClock.elapsedRealtimeNanos()
            val end = firstInputSample + count
            val ready = pendingClips.filter { it.end <= end }
            pendingClips.removeAll(ready.toSet())
            ready.forEach { pending -> finishClip(pending, pending.end, false) }
        }
    }
    fun endAudioStream() {
        expectedAudioStop = true
        synchronized(clipLock) {
            val end = ring.endSample()
            pendingClips.toList().forEach { pending -> if (end != null && end > pending.eventSample) finishClip(pending, minOf(end, pending.end), true) }
            pendingClips.clear(); ring.clear(); captureAnchor = null
            stream.incrementAndGet(); lastClipSample = Long.MIN_VALUE; lastAutomaticClipSample = Long.MIN_VALUE; lastAmbiguousSignature = emptySet(); wasFollowingLocked = false; wasFollowingLost = false; lastFrame = null; lastFollowing = null
        }
        recordLog("INFO", "Audio", "MicrophoneStop", "audioStreamId=${stream.get()}")
    }
    fun recordFeedback(label: FieldFeedbackLabel, context: Map<String, String> = emptyMap(), audioTimeSeconds: Double? = null) {
        val sourceSession = context["fieldSessionId"]
        val sourceStream = context["audioStreamId"]?.toLongOrNull()
        val sourceEngine = context["engineSessionId"]?.toLongOrNull()
        val sourceBound = sourceMatches(sourceSession, sourceStream, sourceEngine)
        val record = logRecord("INFO", "Field", "UserFeedback", "用户反馈不作为真值，不自动解锁") + mapOf(
            "feedbackLabel" to label.name, "groundTruth" to null, "audioTimeSeconds" to audioTimeSeconds,
            "audioStreamId" to sourceStream, "engineSessionId" to sourceEngine, "evidenceFieldSessionId" to sourceSession,
            "frameIndex" to context["frameIndex"]?.toLongOrNull(), "sourceBound" to sourceBound, "sourceState" to if (sourceBound) "CURRENT_STREAM" else "STALE_OR_UNAVAILABLE", "context" to context)
        enqueue {
            requiredStore().append("app.log", record)
            mutableState.update { it.copy(message = "反馈已保存：${label.name}（用户意见不是系统真值，不自动解锁）") }
        }
        if (sourceBound) requestClip("USER_FEEDBACK", audioTimeSeconds, context + mapOf("feedbackLabel" to label.name))
        else if (consent.get()) recordLog("WARNING", "Field", "ClipUnavailable", "反馈帧没有可核对的当前流来源，只保存文字标签")
    }
    private fun automaticClip(reason: String, audioTimeSeconds: Double?, context: Map<String, String>) {
        if (audioTimeSeconds == null || !audioTimeSeconds.isFinite() || !consent.get()) return
        synchronized(clipLock) {
            val reference = lastFrame
            if (reference == null || !sourceMatches(reference.fieldSessionId, reference.audioStreamId, reference.engineSessionId)) {
                recordLog("WARNING", "Field", "ClipUnavailable", "自动事件缺少当前音频流来源")
                return
            }
            val sample = (audioTimeSeconds * 48000).toLong()
            if (lastAutomaticClipSample != Long.MIN_VALUE && sample - lastAutomaticClipSample < 480_000) {
                recordLog("INFO", "Field", "AutomaticClipCooldown", "10 秒冷却限制，避免异常持续时保存整段录音；reason=$reason")
                return
            }
            lastAutomaticClipSample = sample
            requestClip(reason, audioTimeSeconds, context + mapOf("automatic" to "true", "groundTruth" to "UNLABELED",
                "fieldSessionId" to reference.fieldSessionId.orEmpty(), "audioStreamId" to reference.audioStreamId.toString(),
                "engineSessionId" to reference.engineSessionId.toString()))
        }
    }
    fun requestClip(reason: String, audioTimeSeconds: Double?, context: Map<String, String> = emptyMap()) {
        if (!consent.get() || active.get() == null || !accepting.get()) return
        synchronized(clipLock) {
            if (!sourceMatches(context["fieldSessionId"], context["audioStreamId"]?.toLongOrNull(), context["engineSessionId"]?.toLongOrNull())) {
                recordLog("WARNING", "Field", "ClipUnavailable", "片段来源缺失或来自旧流，不截取当前录音")
                return
            }
            if (audioTimeSeconds == null || !audioTimeSeconds.isFinite() || audioTimeSeconds < 0) {
                recordLog("WARNING", "Field", "ClipUnavailable", "未提供可绑定的音频时刻"); return
            }
            val event = (audioTimeSeconds * 48_000).toLong()
            val start = ring.retainedStart()
            val currentEnd = ring.endSample()
            if (start == null || currentEnd == null || event < start || event >= currentEnd) {
                recordLog("WARNING", "Field", "ClipUnavailable", "事件超出五秒内存或当前音频流"); return
            }
            if (lastClipSample != Long.MIN_VALUE && event - lastClipSample in 0 until 72_000) {
                recordLog("INFO", "Field", "ClipTriggerCoalesced", "相邻事件共用上一片段；audioStreamId=${stream.get()},eventSample=$event,previousEventSample=$lastClipSample")
                return
            }
            if (pendingClips.size >= 8) { fail("短片段等待队列已满，已暂停诊断"); return }
            val pending = PendingClip(consentGeneration.get(), stream.get(), event, maxOf(start, event - 144_000), event + 72_000,
                mapOf("reason" to reason, "requestedAudioTimeSeconds" to audioTimeSeconds, "audioStreamId" to stream.get(),
                    "engineSessionId" to engine.sessionId, "context" to context, "consentGranted" to true,
                    "requestedPreSeconds" to 3.0, "requestedPostSeconds" to 1.5, "eventSample" to event))
            lastClipSample = event
            if (pending.end <= currentEnd) finishClip(pending, pending.end, false) else pendingClips += pending
        }
    }
    private fun finishClip(pending: PendingClip, end: Long, interrupted: Boolean) {
        val samples = ring.snapshot(pending.start, end)
        if (samples == null) { recordLog("WARNING", "Field", "ClipUnavailable", "五秒缓冲已不含完整请求范围；不替换为其它录音"); return }
        val sessionId = active.get()
        enqueue(allowAfterFault = true) {
            if (consent.get() && consentGeneration.get() == pending.generation && active.get() == sessionId) {
                requiredStore().saveClip(samples, pending.metadata + mapOf("postInterrupted" to interrupted,
                    "actualPreSeconds" to (pending.eventSample - samples.firstSample) / 48_000.0,
                    "actualPostSeconds" to (samples.endSample - pending.eventSample) / 48_000.0))
                publish()
            }
            samples.samples.fill(0)
        }
    }
    fun recordException(error: Throwable, lastState: Map<String, String> = emptyMap()) {
        val record = exceptionRecord(error, lastState)
        enqueue { requiredStore().append("app.log", record) }
    }
    private fun exceptionRecord(error: Throwable, lastState: Map<String, String>): Map<String, Any?> = logRecord("CRITICAL", "App", "Exception", error.stackTraceToString().take(32_768)) + mapOf(
        "exceptionType" to error.javaClass.name, "exceptionMessage" to error.message, "lastState" to lastState,
        "audioStreamId" to stream.get(), "engineSessionId" to engine.sessionId, "lastAudioState" to audio.status.name,
        "lastFrameIndex" to lastFrame?.frameIndex, "lastRecognitionState" to lastFrame?.state?.name,
        "lastScorePosition" to lastFollowing?.currentScorePosition, "lastDecision" to lastFollowing?.decision)
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val record = exceptionRecord(error, mapOf("thread" to thread.name, "sessionId" to (active.get() ?: "NONE")))
                val latch = CountDownLatch(1)
                if (queue.offer { try { requiredStore().append("app.log", record); requiredStore().checkpoint() } finally { latch.countDown() } }) latch.await(500, TimeUnit.MILLISECONDS)
                // One bounded emergency marker is also written when the writer is unavailable or the app was outside Field mode.
                FileOutputStream(crashMarker).use { it.write(FieldJson.encode(record).toByteArray()); it.fd.sync() }
            } catch (_: Throwable) { /* The platform's original fatal handler must still run. */ }
            previous?.uncaughtException(thread, error) ?: run { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    private fun enqueue(allowAfterFault: Boolean = false, action: () -> Unit) {
        val acceptedSession = active.get() ?: return
        if (!allowAfterFault && !accepting.get()) return
        writerQueueHighWater.accumulateAndGet((queue.size + 1).coerceAtMost(256).toLong()) { previous, current -> maxOf(previous, current) }
        if (!queue.offer {
            try { if (active.get() == acceptedSession) action() } catch (error: Throwable) { fail("诊断写入失败：${error.message}") }
        }) fail("QueueOverflow：诊断写入队列已满，已暂停收音与跟谱")
    }
    private suspend fun <T> operation(action: () -> T): T {
        val result = CompletableDeferred<T>()
        mutableState.update { it.copy(busy = true) }
        writerQueueHighWater.accumulateAndGet((queue.size + 1).coerceAtMost(256).toLong()) { previous, current -> maxOf(previous, current) }
        if (!queue.offer {
            try { result.complete(action()) } catch (error: Throwable) { fail("实地诊断操作失败：${error.message}"); result.completeExceptionally(error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }) {
            mutableState.update { it.copy(busy = false) }
            fail("QueueOverflow：诊断操作队列已满")
            throw IllegalStateException("诊断操作队列已满")
        }
        return result.await()
    }
    private fun fail(message: String) {
        accepting.set(false)
        if (faultMessage.get() == null) pendingFault.set(message)
        mutableState.update { it.copy(error = message, message = "诊断异常，练习已请求暂停") }
        if (faultMessage.getAndSet(message) == null) failureListener?.invoke(message)
    }
    private fun requiredStore(): FieldSessionStore = store ?: error("本地诊断存储未就绪或初始化失败")
    private fun publish() {
        val disk = requiredStore()
        mutableState.update { it.copy(activeSession = disk.active(), sessions = disk.sessions(), totalBytes = disk.totalBytes(),
            clipCount = disk.totalClips(), storageWritable = writableAtLastCheck, freeSpaceBytes = disk.spaceBudgetBytes(), writerQueueOccupancy = queue.size) }
    }
    private fun persistPerformance() {
        if (active.get() == null) return
        val at = SystemClock.elapsedRealtimeNanos()
        val cpu = Process.getElapsedCpuTime()
        if (at > previousCpuTimeNanos) lastCpu = (cpu - previousCpuMillis) * 1e6 / (at - previousCpuTimeNanos) * 100
        previousCpuMillis = cpu; previousCpuTimeNanos = at
        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); lastPss = memory.totalPss
        val snapshot = engine
        val currentAudio = audio
        val signature = listOf(currentAudio.sampleRate, currentAudio.actualAudioSource, currentAudio.actualAudioSourceName,
            currentAudio.unprocessedSupported, currentAudio.actualInputRoute, currentAudio.routedDeviceType, currentAudio.capturedAtNanos != null)
        if (signature != lastDeviceSignature) { requiredStore().updateDevice(device()); lastDeviceSignature = signature }
        requiredStore().updatePerformance(mapOf("ModelLoadTime" to snapshot.modelLoadMs, "AudioLatency" to audio.audioLatencyMs,
            "AverageRecognitionLatency" to callbackAvailabilityTimes.average(), "P95RecognitionLatency" to callbackAvailabilityTimes.p95(), "MaxRecognitionLatency" to callbackAvailabilityTimes.maximum(),
            "recognitionLatencyDefinition" to "PCM read-completion sample-index estimate to emitted callback, including window/batch wait; not hardware acoustic latency. P95 upper bound from 1 ms bins.", "ModelCompute" to recognitionTimes.summary(),
            "readCompletionToCallback" to callbackAvailabilityTimes.summary(), "readCompletionDefinition" to "Monotonic PCM read-completion anchor plus 48 kHz sample-index extrapolation; not acoustic hardware latency",
            "ScoreFollowingLatency" to followingTimes.summary(), "AmbiguityResolutionLatency" to null,
            "CPU" to lastCpu, "cpuDefinition" to "Process.getElapsedCpuTime delta / elapsedRealtime delta x 100; one core = 100%",
            "Memory" to lastPss, "memoryUnit" to "PSS KiB", "DroppedAudioFrames" to synchronized(metricLock) { totalDroppedBlocks },
            "droppedFramesDefinition" to "Rejected/discarded PCM blocks", "droppedSamples" to synchronized(metricLock) { totalDroppedSamples },
            "QueueOverflowCount" to synchronized(metricLock) { totalOverflows }, "queueOccupancy" to snapshot.queueOccupancy, "queueCapacity" to snapshot.queueCapacity,
            "maxQueueOccupancy" to snapshot.maxQueueOccupancy, "rejectedBeforeReadyFrames" to snapshot.rejectedBeforeReadyFrames,
            "submittedBlocks" to snapshot.submittedBlocks, "processedBlocks" to snapshot.processedBlocks,
            "submittedSamples" to snapshot.submittedSamples, "processedSamples" to snapshot.processedSamples,
            "engineSessionId" to snapshot.sessionId, "audioStreamId" to stream.get(),
            "writerQueueOccupancy" to queue.size, "writerQueueCapacity" to 256,
            "writerMaxQueueOccupancy" to writerQueueHighWater.get(), "writerTaskTime" to writerTaskTimes.summary(),
            "writerCheckpointTime" to checkpointTimes.summary(), "storageMeasurements" to requiredStore().storageDiagnostics(),
            "accuracyValidated" to false, "fault" to faultMessage.get()))
    }
    /** Read only: never allocate bytes or request clearing another app's cache. */
    private fun availableStorageBytes(directory: File): Long = try {
        val manager = app.getSystemService(android.os.storage.StorageManager::class.java)
        if (manager == null) 0L else {
            val uuid = storageUuid ?: manager.getUuidForPath(directory).also { storageUuid = it }
            val allocatable = manager.getAllocatableBytes(uuid)
            val available = android.os.StatFs(directory.absolutePath).availableBytes
            minOf(allocatable, available).coerceAtLeast(0L)
        }
    } catch (_: Exception) { 0L }
    private fun identity(): Map<String, Any?> = mapOf("appVersion" to BuildConfig.VERSION_NAME, "buildNumber" to BuildConfig.VERSION_CODE,
        "gitCommit" to BuildConfig.GIT_COMMIT, "gitTag" to BuildConfig.FIELD_TAG, "gitTagAtBuild" to BuildConfig.GIT_TAG,
        "gitWorktreeState" to BuildConfig.GIT_WORKTREE_STATE, "buildTime" to BuildConfig.BUILD_TIME,
        "tagDefinition" to "gitTag is the planned delivery tag; gitTagAtBuild is actual build-time Git state")
    private fun device(): Map<String, Any?> {
        val current = audio
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return mapOf("brand" to Build.BRAND, "model" to Build.MODEL, "androidVersion" to Build.VERSION.RELEASE,
            "apiLevel" to Build.VERSION.SDK_INT, "cpuAbi" to Build.SUPPORTED_ABIS.toList(), "totalMemoryBytes" to memory.totalMem,
            "availableMemoryBytes" to memory.availMem, "sampleRate" to current.sampleRate,
            "audioSource" to current.actualAudioSource, "audioSourceName" to current.actualAudioSourceName,
            "unprocessedSupported" to current.unprocessedSupported, "actualInputRoute" to current.actualInputRoute,
            "routedDeviceType" to current.routedDeviceType, "actualSamplingKnown" to (current.capturedAtNanos != null), "inputLevelDbfs" to current.inputLevelDbfs, "noiseFloorDbfs" to current.noiseFloorDbfs)
    }
    private fun logRecord(level: String, module: String, message: String, detail: String) = mapOf("timestamp" to System.currentTimeMillis(),
        "monotonicNanos" to SystemClock.elapsedRealtimeNanos(), "level" to level, "module" to module, "message" to message, "detail" to detail)
    private class LatencyStats {
        private val histogram = LongArray(60_002)
        private var count = 0L
        private var total = 0.0
        private var max: Double? = null
        fun add(value: Double) { if (!value.isFinite() || value < 0) return; histogram[minOf(60_001, ceil(value).toInt())]++; count++; total += value; max = max?.let { maxOf(it, value) } ?: value }
        fun average(): Double? = if (count == 0L) null else total / count
        fun maximum(): Double? = max
        fun p95(): Double? { if (count == 0L) return null; val target = ceil(count * .95).toLong(); var totalCount = 0L; histogram.forEachIndexed { index, value -> totalCount += value; if (totalCount >= target) return if (index == 60_001) null else index.toDouble() }; return null }
        fun summary(): Map<String, Any?> = mapOf("averageMs" to average(), "p95UpperBoundMs" to p95(), "maximumMs" to max, "count" to count, "over60000Ms" to histogram[60_001])
        fun clear() { histogram.fill(0); count = 0; total = 0.0; max = null }
    }
}
