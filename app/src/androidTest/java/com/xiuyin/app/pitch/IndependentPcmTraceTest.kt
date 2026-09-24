package com.xiuyin.app.pitch

import android.os.SystemClock
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.practice.*
import com.xiuyin.app.score.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Complete diagnostic file replay only. A passing trace never means passing music acceptance. */
class IndependentPcmTraceTest {
    @Test fun frozenWebsitePcmProducesCompleteRawAndFollowerTrace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing explicit diagnostic argument: $name" }
        val runId = arg("traceRunId")
        require(runId.matches(Regex("[a-z0-9][a-z0-9-]{0,99}")))
        val root = File(requireNotNull(context.getExternalFilesDir(null)), "independent-pcm-traces/$runId")
        check(!root.exists() && root.mkdirs()) { "Refuse to overwrite an existing diagnostic run" }
        val metadataFile = File(root, "metadata.json")
        val metadata = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("startedAtMillis", System.currentTimeMillis()).put("traceComplete", false)
            .put("accuracyValidated", false).put("stage", "INPUT_VALIDATION")
            .put("scope", "Frozen website WAV -> production asset model and default config -> three production PracticeSessions; diagnostic, not musical acceptance, microphone or full original score UI")
            .put("referencePolicy", "Same simplified 1/4 single-staff group score and shifted correction reference as PracticePcmIntegrationTest; no score or truth is passed into recognition")
            .put("observationAdapter", "legacy-three-field-ObservedNote")
            .put("testCompileGitCommit", BuildConfig.GIT_COMMIT).put("testCompileGitCommitIsInstalledAppIdentity", false).put("pitchEvidenceConfig", PitchEvidenceConfig().toString())
            .put("scoresMeaning", "Unmodified sigmoid activation scores, not logits or calibrated probabilities; raw arrays index 0=MIDI21, index87=MIDI108")
            .put("modelConfirmedOnsetsMeaning", "notes.onset / onsetMidi are actual production tracker accepted onsets; no private tracker counters are inferred")
            .put("tailPolicy", "All actual PCM blocks processed; no zero-padding of incomplete last inference window")
            .put("limitations", JSONArray(listOf("No physical microphone/acoustics", "No reliable inferred pedal state or measured offsets", "Keyboard action timing is not acoustic ground truth or measured live latency", "Simplified pitch-group reference does not preserve original rhythm/hands/repeats/image", "Three sessions expose all decisions; completed sessions return a cached update marked sessionAccepted=false", "Diagnostic PASS only means complete and identified export; original musical gates remain separate")))
        metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
        var engine: PianoRecognitionEngine? = null
        var failure: Throwable? = null
        var frames = 0L
        var submitted = 0L
        var blocks = 0L
        var expectedFrames = 0L
        var lastAudio = -1.0
        var lastAvailable = -1.0
        val rawDigest = MessageDigest.getInstance("SHA-256")
        val rawBytes = ByteBuffer.allocate(8 + 88 * 8).order(ByteOrder.BIG_ENDIAN)
        val statusFailure = AtomicReference<Throwable?>(null)
        var statusPublications = 0L
        val frameFile = File(root, "frames.jsonl")
        val statusFile = File(root, "engine-status.jsonl")
        val started = SystemClock.elapsedRealtime()
        try {
            val wav = authorizedFile(context, arg("traceWav"))
            val timelineFile = authorizedFile(context, arg("traceTimeline"))
            val expectedWav = arg("traceWavSha256")
            val expectedTimeline = arg("traceTimelineSha256")
            val expectedApp = arg("traceAppSha256")
            val expectedModel = arg("traceModelSha256")
            listOf(expectedWav, expectedTimeline, expectedApp, expectedModel).forEach {
                require(it.matches(Regex("[a-f0-9]{64}"))) { "Explicit frozen SHA256 required" }
            }
            fun verify(file: File, expected: String): String = file.inputStream().use(::sha256).also {
                check(it == expected) { "Identity mismatch: ${file.name}: $it != $expected" }
            }
            metadata.put("appSha256", verify(File(context.applicationInfo.sourceDir), expectedApp))
                .put("testApkSha256", File(instrumentation.context.applicationInfo.sourceDir).inputStream().use(::sha256))
                .put("wavSha256", verify(wav, expectedWav)).put("timelineSha256", verify(timelineFile, expectedTimeline))
            val modelSha = context.assets.open(PianoModel.MODEL_ASSET).use(::sha256)
            check(modelSha == expectedModel && modelSha == PianoModel.MODEL_SHA256) { "Production model asset identity mismatch" }
            metadata.put("modelSha256", modelSha).put("modelAsset", PianoModel.MODEL_ASSET)
            val layout = readLayout(wav)
            check(layout.samples in 1..48_000L * 600)
            val converted = if (layout.samples < Streaming48kTo16k.TAP_COUNT) 0L else
                1L + (layout.samples - Streaming48kTo16k.TAP_COUNT) / Streaming48kTo16k.FACTOR
            val windows = if (converted < PianoModel.INPUT_SAMPLES) 0L else
                1L + (converted - PianoModel.INPUT_SAMPLES) / PianoRecognitionProcessor.STEP_SAMPLES
            expectedFrames = if (windows == 0L) 0L else PianoModel.FRAME_COUNT +
                (windows - 1) * (PianoRecognitionProcessor.STEP_SAMPLES / PianoModel.HOP_SAMPLES)
            check(expectedFrames > 0)
            val truth = JSONObject(timelineFile.readText(Charsets.UTF_8))
            check(truth.getString("sha256") == expectedWav)
            val groups = truth.getJSONArray("groups")
            val pitches = List(groups.length()) { index ->
                val notes = groups.getJSONObject(index).getJSONArray("expectedMidi")
                List(notes.length()) { notes.getInt(it).also { midi -> require(midi in 21..108) } }.toSet().also { require(it.isNotEmpty()) }
            }
            require(pitches.size > 1)
            val score = referenceScore(pitches, expectedWav)
            fun session(mode: CompanionMode, selected: List<Set<Int>>) = PracticeSession(
                UUID.randomUUID().toString(), score.title, 0,
                ScoreTimelineCompiler.compile(referenceScore(selected, expectedWav)), PracticeConfig(mode), 1000,
                "bundled production model; frozen website PCM")
            val good = session(CompanionMode.FREE, pitches)
            val stop = session(CompanionMode.STOP_ON_ERROR, pitches)
            val correction = session(CompanionMode.STOP_ON_ERROR, pitches.drop(1))
            val sessions = linkedMapOf("free" to good, "stop" to stop, "shiftedControl" to correction)
            val locks = sessions.keys.associateWith { 0 }.toMutableMap()
            val recoveries = sessions.keys.associateWith { 0 }.toMutableMap()
            val sparseEvents = JSONArray()
            val stopTransitions = JSONArray()
            metadata.put("samples", layout.samples).put("expectedFrames", expectedFrames).put("expectedWindows", windows)
                .put("expectedGroups", pitches.size).put("stage", "REPLAY_RUNNING")
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            frameFile.bufferedWriter(Charsets.UTF_8).use { frameWriter ->
                statusFile.bufferedWriter(Charsets.UTF_8).use { statusWriter ->
                    val running = PianoRecognitionEngine(context, onFrame = { frame ->
                        check(frame.frameIndex == frames) { "Non-contiguous frame: $frames -> ${frame.frameIndex}" }
                        check(frame.audioTimeSeconds.isFinite() && frame.audioTimeSeconds > lastAudio)
                        check(frame.availableAudioTimeSeconds.isFinite() && frame.availableAudioTimeSeconds >= frame.audioTimeSeconds && frame.availableAudioTimeSeconds >= lastAvailable)
                        check(frame.rawOnsetScores.size == 88 && frame.rawFrameScores.size == 88)
                        check(frame.rawOnsetScores.all { it.isFinite() && it in 0f..1f } && frame.rawFrameScores.all { it.isFinite() && it in 0f..1f })
                        rawBytes.clear(); rawBytes.putLong(frame.frameIndex)
                        frame.rawOnsetScores.forEach { rawBytes.putFloat(it) }; frame.rawFrameScores.forEach { rawBytes.putFloat(it) }
                        rawDigest.update(rawBytes.array())
                        val observation = observe(frame)
                        val updates = linkedMapOf<String, FollowerUpdate>()
                        val following = JSONObject()
                        sessions.forEach { (name, practice) ->
                            val beforePhase = practice.phase
                            val beforeCursor = practice.follower.cursor
                            val beforeWrong = practice.wrong
                            val expected = practice.follower.expectedMidi()
                            val accepts = beforePhase in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)
                            val update = practice.accept(observation)
                            updates[name] = update
                            if (beforePhase != CompanionPhase.LOCKED && practice.phase == CompanionPhase.LOCKED) locks[name] = locks.getValue(name) + 1
                            if (beforePhase == CompanionPhase.LOCKED && practice.phase != CompanionPhase.LOCKED && update.decision == PracticeDecision.CORRECT) recoveries[name] = recoveries.getValue(name) + 1
                            following.put(name, JSONObject().put("sessionAccepted", accepts).put("cursorBefore", beforeCursor)
                                .put("cursorAfter", practice.follower.cursor).put("phaseBefore", beforePhase.name).put("phaseAfter", practice.phase.name)
                                .put("expectedMidiBefore", JSONArray(expected.sorted())).put("expectedMidiAfter", JSONArray(practice.follower.expectedMidi().sorted()))
                                .put("decision", update.decision.name).put("detectedMidi", JSONArray(update.detectedMidi.sorted()))
                                .put("confirmedInput", update.confirmedInput).put("locked", practice.follower.locked).put("reason", update.reason)
                                .put("correct", practice.correct).put("wrong", practice.wrong).put("missed", practice.missed)
                                .put("evidence", if (accepts) JSONArray(update.evidence.map(::evidenceJson)) else JSONArray()))
                            if (name == "stop" && (beforePhase != practice.phase || beforeWrong != practice.wrong)) {
                                stopTransitions.put(JSONObject().put("frame", frame.frameIndex).put("audioSeconds", frame.audioTimeSeconds)
                                    .put("fromPhase", beforePhase.name).put("toPhase", practice.phase.name).put("decision", update.decision.name)
                                    .put("wrong", practice.wrong).put("missed", practice.missed).put("rawOnsetMidi", JSONArray(frame.onsetMidi.sorted())).put("reason", update.reason))
                            }
                        }
                        val update = updates.getValue("free"); val stopUpdate = updates.getValue("stop")
                        if (update.confirmedInput || frame.onsetMidi.isNotEmpty()) sparseEvents.put(JSONObject()
                            .put("frame", frame.frameIndex).put("audioSeconds", frame.audioTimeSeconds).put("rawOnsetMidi", JSONArray(frame.onsetMidi.sorted()))
                            .put("uncertainMidi", JSONArray(frame.uncertainMidi.sorted())).put("ambiguousOctaveMidi", JSONArray(frame.ambiguousOctaveMidi.sorted()))
                            .put("decision", update.decision.name).put("cursor", update.cursor).put("reason", update.reason)
                            .put("stopDecision", stopUpdate.decision.name).put("stopCursor", stopUpdate.cursor).put("stopPhase", stop.phase.name).put("stopReason", stopUpdate.reason))
                        frameWriter.write(canonicalJson(frameJson(frame).put("computeMs", frame.computeMs).put("following", following)))
                        frameWriter.write("\n")
                        frames++; lastAudio = frame.audioTimeSeconds; lastAvailable = frame.availableAudioTimeSeconds
                    }, onStatus = { snapshot ->
                        try {
                            statusWriter.write(canonicalJson(snapshotJson(snapshot).put("publicationIndex", statusPublications++)))
                            statusWriter.write("\n")
                        } catch (error: Throwable) { statusFailure.compareAndSet(null, error) }
                    })
                    engine = running
                    try {
                        running.start()
                        awaitEngine(running) { running.snapshot.status == RecognitionEngineStatus.READY }
                        metadata.put("readyObservedBeforeFirstPcm", true)
                        RandomAccessFile(wav, "r").use { input ->
                            input.seek(layout.dataOffset)
                            val bytes = ByteArray(4096 * 2)
                            val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            val samples = ShortArray(4096)
                            var target = minOf(60_000L, layout.samples)
                            while (submitted < layout.samples) {
                                while (submitted < target) {
                                    val count = minOf(samples.size.toLong(), target - submitted).toInt()
                                    input.readFully(bytes, 0, count * 2)
                                    repeat(count) { samples[it] = littleEndian.getShort(it * 2) }
                                    running.submitPcm(samples, count, submitted)
                                    submitted += count; blocks++
                                }
                                awaitEngine(running) { running.snapshot.processedSamples == submitted && running.snapshot.processedBlocks == blocks }
                                statusFailure.get()?.let { throw it }
                                target = minOf(submitted + 24_576L, layout.samples)
                            }
                        }
                        val before = running.snapshot
                        check(before.frameCount == expectedFrames && frames == expectedFrames)
                        check(before.processedSamples == layout.samples && before.processedBlocks == blocks)
                        check(before.submittedSamples == submitted && before.submittedBlocks == blocks)
                        check(before.queueOccupancy == 0 && before.droppedFrames == 0L && before.droppedSamples == 0L && before.overflowCount == 0L && before.rejectedBeforeReadyFrames == 0L)
                        metadata.put("engineBeforeStop", snapshotJson(before))
                    } finally {
                        running.stop(); check(running.snapshot.status == RecognitionEngineStatus.IDLE)
                        metadata.put("engineAfterStop", snapshotJson(running.snapshot))
                        running.close(); check(running.snapshot.status == RecognitionEngineStatus.CLOSED)
                        metadata.put("engineAfterClose", snapshotJson(running.snapshot))
                    }
                }
            }
            statusFailure.get()?.let { throw it }
            val store = PracticeReportStore(File(root, "persisted-reports"))
            val reports = JSONObject()
            sessions.forEach { (name, practice) ->
                val report = practice.report(2000)
                store.save(report)
                val equal = PracticeReportStore(File(root, "persisted-reports")).load(report.scoreId).single() == report
                check(equal) { "Practice report persistence changed real evidence" }
                reports.put(name, JSONObject().put("mode", practice.config.mode.name)
                    .put("correct", report.correct).put("wrong", report.wrong).put("missed", report.missed).put("completed", report.completed)
                    .put("confirmedInputs", report.confirmedInputs).put("uncertainFrames", report.uncertainFrames)
                    .put("finalPhase", practice.phase.name).put("finalCursor", practice.follower.cursor)
                    .put("lockedTransitions", locks.getValue(name)).put("correctionTransitions", recoveries.getValue(name))
                    .put("reportRoundTripEqual", equal).put("evidenceTruncated", report.evidenceTruncated)
                    .put("evidence", JSONArray(report.evidence.map(::evidenceJson))))
            }
            val free = reports.getJSONObject("free")
            val stopResult = reports.getJSONObject("stop").put("transitions", stopTransitions)
            val result = JSONObject().put("scope", "Complete diagnostic trace; simplified reference identical to original sparse harness")
                .put("sha256", expectedWav).put("samples", layout.samples).put("frames", frames).put("expectedGroups", pitches.size)
                .put("correct", free.getInt("correct")).put("wrong", free.getInt("wrong")).put("missed", free.getInt("missed"))
                .put("completed", free.getBoolean("completed")).put("reportRoundTripEqual", free.getBoolean("reportRoundTripEqual"))
                .put("lockedTransitions", locks.getValue("shiftedControl")).put("correctionTransitions", recoveries.getValue("shiftedControl"))
                .put("events", sparseEvents).put("correctReferenceStopOnError", stopResult).put("sessions", reports)
            File(root, "practice-replay-result.json").writeText(result.toString(2), Charsets.UTF_8)
            metadata.put("traceComplete", true).put("stage", "TRACE_COMPLETE").put("sessions", reports)
        } catch (error: Throwable) {
            failure = error
            metadata.put("traceComplete", false).put("stage", "FAILED")
        } finally {
            try { engine?.close() } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            metadata.put("traceComplete", failure == null && metadata.optBoolean("traceComplete"))
                .put("frames", frames).put("expectedFrames", expectedFrames).put("submittedSamples", submitted).put("submittedBlocks", blocks)
                .put("rawSequenceSha256", hex(rawDigest.digest())).put("statusPublications", statusPublications)
                .put("rawSequenceHashEncoding", "Per frame int64 index, float32 rawOnsetScores[88], float32 rawFrameScores[88], big endian")
                .put("elapsedWallMs", SystemClock.elapsedRealtime() - started).put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
            val artifacts = JSONObject()
            listOf(frameFile, statusFile, File(root, "practice-replay-result.json")).filter { it.isFile }.forEach {
                artifacts.put(it.name, JSONObject().put("bytes", it.length()).put("sha256", it.inputStream().use(::sha256)))
            }
            metadata.put("artifacts", artifacts)
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
        }
        failure?.let { throw it }
        check(metadata.getBoolean("traceComplete")) { "Diagnostic export incomplete" }
    }

    private fun observe(frame: PianoRecognitionFrame): RecognitionObservation {
        val notes = frame.notes.associateBy { it.midi }
        fun entry(midi: Int) = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
        return RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds, frame.onsetMidi.mapNotNull(::entry),
            frame.activeMidi.mapNotNull(::entry), clipped = frame.clipped,
            uncertainMidi = frame.uncertainMidi, ambiguousOctaveMidi = frame.ambiguousOctaveMidi)
    }

    private fun evidenceJson(item: PracticeEvidence) = JSONObject().put("decision", item.decision.name)
        .put("targetIndex", item.targetIndex).put("measureIndex", item.measureIndex).put("eventIds", JSONArray(item.eventIds))
        .put("audioSeconds", item.audioTimeSeconds).put("expectedMidi", JSONArray(item.expectedMidi.sorted()))
        .put("detectedMidi", JSONArray(item.detectedMidi.sorted())).put("evidenceScore", item.evidenceScore).put("reason", item.reason)

    private fun snapshotJson(s: RecognitionEngineSnapshot) = JSONObject().put("status", s.status.name).put("message", s.message)
        .put("sessionId", s.sessionId).put("frameCount", s.frameCount).put("modelSha256", s.modelSha256)
        .put("queueOccupancy", s.queueOccupancy).put("maxQueueOccupancy", s.maxQueueOccupancy).put("queueCapacity", s.queueCapacity)
        .put("submittedBlocks", s.submittedBlocks).put("submittedSamples", s.submittedSamples)
        .put("processedBlocks", s.processedBlocks).put("processedSamples", s.processedSamples)
        .put("droppedFrames", s.droppedFrames).put("droppedSamples", s.droppedSamples).put("overflowCount", s.overflowCount)
        .put("rejectedBeforeReadyFrames", s.rejectedBeforeReadyFrames).put("computeMs", s.computeMs ?: JSONObject.NULL)
        .put("modelLoadMs", s.modelLoadMs ?: JSONObject.NULL)

    private fun referenceScore(groups: List<Set<Int>>, source: String): DigitalScore {
        val measures = groups.mapIndexed { index, pitches ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), pitches.sorted().mapIndexed { noteIndex, midi ->
                ScoreEvent("e${index}_$noteIndex", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi), 1, "1", noteIndex > 0, "quarter", 0, emptySet(), index * 10 + noteIndex, 0)
            }, false)
        }
        return DigitalScore("test-reference", "TEST DATA 独立网站事件参考谱", listOf(ScorePart("p", "Piano", measures)), emptyList(), ScoreSource("independent-web-action-reference", source, 1))
    }

    private fun frameJson(frame: PianoRecognitionFrame): JSONObject {
        val rejections = JSONObject()
        frame.candidateRejections.toSortedMap().forEach { (midi, reason) -> rejections.put(midi.toString(), reason) }
        return JSONObject().put("frameIndex", frame.frameIndex).put("audioTimeSeconds", frame.audioTimeSeconds)
            .put("availableAudioTimeSeconds", frame.availableAudioTimeSeconds)
            .put("state", frame.state.name).put("clipped", frame.clipped)
            .put("onsetMidi", JSONArray(frame.onsetMidi.sorted())).put("activeMidi", JSONArray(frame.activeMidi.sorted()))
            .put("rawOnsetScores", JSONArray(frame.rawOnsetScores.toList()))
            .put("rawFrameScores", JSONArray(frame.rawFrameScores.toList()))
            .put("uncertainMidi", JSONArray(frame.uncertainMidi.sorted())).put("candidateRejections", rejections)
            .put("ambiguousOctaveMidi", JSONArray(frame.ambiguousOctaveMidi.sorted()))
            .put("octaveDiagnostics", JSONArray(frame.octaveDiagnostics.map { d ->
                JSONObject().put("lowerMidi", d.lowerMidi).put("upperMidi", d.upperMidi)
                    .put("lowerOnsetScore", d.lowerOnsetScore).put("upperOnsetScore", d.upperOnsetScore)
                    .put("lowerFrameScore", d.lowerFrameScore).put("upperFrameScore", d.upperFrameScore)
                    .put("temporalOffsetSeconds", d.temporalOffsetSeconds ?: JSONObject.NULL)
                    .put("lowerFundamentalAmplitude", d.lowerFundamentalAmplitude ?: JSONObject.NULL)
                    .put("upperFundamentalAmplitude", d.upperFundamentalAmplitude ?: JSONObject.NULL)
                    .put("upperAttackEnergyRise", d.upperAttackEnergyRise ?: JSONObject.NULL)
                    .put("lowerAttackEnergyRise", d.lowerAttackEnergyRise ?: JSONObject.NULL)
                    .put("harmonicPrior", d.harmonicPrior ?: JSONObject.NULL)
                    .put("independentOnsetScore", d.independentOnsetScore ?: JSONObject.NULL)
                    .put("scoreContextWeight", d.scoreContextWeight ?: JSONObject.NULL)
                    .put("finalConfidence", d.finalConfidence ?: JSONObject.NULL)
                    .put("finalState", d.finalState.name).put("reason", d.reason)
            }))
            .put("pedal", frame.pedalState).put("offset", JSONObject.NULL).put("offsetSupported", frame.offsetSupported)
            .put("modelWindowMs", frame.modelWindowMs)
            .put("notes", JSONArray(frame.notes.sortedBy { it.midi }.map { note ->
                JSONObject().put("midi", note.midi).put("onsetScore", note.onsetScore)
                    .put("frameScore", note.frameScore).put("onset", note.onset).put("active", note.active)
            }))
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        is String -> JSONObject.quote(value)
        else -> error("不可序列化的真实帧字段：${value.javaClass.name}")
    }

    private data class WavLayout(val dataOffset: Long, val samples: Long)

    private fun readLayout(file: File): WavLayout = RandomAccessFile(file, "r").use { input ->
        fun fourCc(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun unsignedInt(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun unsignedShort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        require(input.length() >= 44 && fourCc() == "RIFF") { "缺少有效 WAV RIFF" }
        require(unsignedInt() + 8 == input.length() && fourCc() == "WAVE") { "WAV 长度或类型无效" }
        var validFormat = false
        var result: WavLayout? = null
        while (input.filePointer + 8 <= input.length()) {
            val type = fourCc(); val length = unsignedInt(); val offset = input.filePointer
            require(length <= input.length() - offset) { "WAV 区块越界" }
            if (type == "fmt ") {
                require(length >= 16 && !validFormat)
                val pcm = unsignedShort(); val channels = unsignedShort(); val rate = unsignedInt()
                val bytesPerSecond = unsignedInt(); val alignment = unsignedShort(); val bits = unsignedShort()
                validFormat = pcm == 1 && channels == 1 && rate == 48_000L &&
                    bytesPerSecond == 96_000L && alignment == 2 && bits == 16
                require(validFormat) { "八度基线必须为 48kHz 单声道 16-bit PCM" }
            } else if (type == "data") {
                require(validFormat && length > 0 && length % 2 == 0L && result == null)
                result = WavLayout(offset, length / 2)
            }
            val next = offset + length + length % 2
            require(next <= input.length())
            input.seek(next)
        }
        require(input.filePointer == input.length()) { "WAV 存在不完整尾部区块" }
        requireNotNull(result) { "缺少真实 PCM data 区块" }
    }

    private fun authorizedFile(context: Context, path: String): File {
        val file = File(path).canonicalFile
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .map { it.canonicalPath + File.separator }
        require(roots.any { file.path.startsWith(it) } && file.isFile && file.length() in 1..64L * 1024 * 1024) {
            "基线输入必须位于此 App 私有测试目录且不超过 64MiB"
        }
        return file
    }

    private fun awaitEngine(engine: PianoRecognitionEngine, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(SystemClock.elapsedRealtime() < deadline) { "真实基线异步回放等待超时：${engine.snapshot}" }
            Thread.sleep(10)
        }
        check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

}
