package com.xiuyin.app.practice

import android.app.Application
import android.os.SystemClock
import android.os.Looper
import androidx.annotation.MainThread
import com.xiuyin.app.field.FieldDiagnostics
import com.xiuyin.app.field.FieldFollowingTrace
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreMetadataDatabase
import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.ScoreTimelineCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class CompanionState(
    val busy: Boolean = false, val scoreId: String? = null, val title: String = "实时陪练",
    val score: DigitalScore? = null, val timeline: ScoreTimeline? = null, val revisionGeneration: Long = 0,
    val phase: CompanionPhase = CompanionPhase.READY, val config: PracticeConfig = PracticeConfig(),
    val locked: Boolean = false,
    val cursor: Int = 0, val measureIndex: Int = 0, val expectedMidi: Set<Int> = emptySet(),
    val detectedMidi: Set<Int> = emptySet(), val activeEventIds: Set<String> = emptySet(),
    val decision: PracticeDecision = PracticeDecision.UNCERTAIN, val reason: String = "准备就绪后开始监听",
    val confirmedInputs: Int = 0, val correct: Int = 0, val wrong: Int = 0, val missed: Int = 0,
    val completedPasses: Int = 0, val consecutivePasses: Int = 0,
    val evidence: List<PracticeEvidence> = emptyList(), val reports: List<PracticeReport> = emptyList(),
    val latestReport: PracticeReport? = null,
    val lastDecisionFrame: PianoRecognitionFrame? = null, val lastWrongFrame: PianoRecognitionFrame? = null,
    val error: String? = null, val message: String? = null,
    val preparationIssues: List<ScoreIssue> = emptyList(),
)

/** UI controller only. Root owns microphone permissions/lifecycle and supplies independent model frames. */
class PracticeViewModel(application: Application) : AndroidViewModel(application) {
    private val fieldDiagnostics = FieldDiagnostics.get(application)
    private val mutableState = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = mutableState.asStateFlow()
    private val reports = PracticeReportStore(File(application.filesDir, "practice-reports"))
    private var session: PracticeSession? = null
    private var pendingReport: PracticeReport? = null
    private var modelDescription = "未提供模型标识"
    private var openGeneration = 0L
    private val frameHistory = linkedMapOf<Long, PianoRecognitionFrame>()
    private val inputGeneration = AtomicLong()
    private data class PendingInputFrame(val generation: Long, val frame: PianoRecognitionFrame)
    private val pendingInputFrames = ArrayBlockingQueue<PendingInputFrame>(MAX_PENDING_INPUT_FRAMES)
    private val inputDrainScheduled = AtomicBoolean()
    private val pendingQueueFailure = AtomicReference<String?>(null)
    private var drainingInputFrames = false
    private var completingInput = false
    private var completionDeliveredFor: PracticeSession? = null
    private var notifyingInputFailure = false
    private var inputFaultedFieldSessionId: String? = null
    private var inputQueueFailureListener: ((String) -> Unit)? = null
    private var inputCompletedListener: (() -> Unit)? = null

    companion object {
        internal const val MAX_PENDING_INPUT_FRAMES = 256
    }

    @MainThread
    fun setInputQueueFailureListener(listener: ((String) -> Unit)?) { inputQueueFailureListener = listener }
    @MainThread
    fun setInputCompletedListener(listener: (() -> Unit)?) { inputCompletedListener = listener }

    /** Main-thread navigation query; paused sessions still require an explicit finish. */
    fun hasActiveSession(): Boolean = session != null
    fun open(scoreId: String, title: String, score: DigitalScore, revisionGeneration: Long, modelDescription: String = "未提供模型标识"): Boolean {
        if (mutableState.value.busy || session != null) { mutableState.value = mutableState.value.copy(error = "请先结束当前练习"); return false }
        val token = ++openGeneration
        this.modelDescription = modelDescription
        mutableState.value = CompanionState(busy = true, scoreId = scoreId, title = title, score = score, revisionGeneration = revisionGeneration)
        viewModelScope.launch {
            var preparationIssues = score.issues
            try {
                val loaded = withContext(Dispatchers.IO) {
                    // Keep locatable validation results even if timeline compilation itself throws.
                    preparationIssues = ScoreEditing.validate(score)
                    val timeline = ScoreTimelineCompiler.compile(score)
                    preparationIssues = timeline.issues
                    if (timeline.issues.any { it.severity == ScoreIssueSeverity.ERROR }) null to emptyList()
                    else timeline to reports.load(scoreId)
                }
                if (token == openGeneration) {
                    val blockers = preparationIssues.filter { it.severity == ScoreIssueSeverity.ERROR }
                    mutableState.value = mutableState.value.copy(busy = false, timeline = loaded.first,
                        reports = loaded.second, preparationIssues = preparationIssues,
                        error = if (blockers.isEmpty()) null else "练习准备失败：谱面有 ${blockers.size} 处结构错误，请按下方位置返回修谱")
                    if (blockers.isNotEmpty()) logPreparationFailure(scoreId, preparationIssues)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (token == openGeneration) {
                    val reason = error.message ?: error.javaClass.simpleName
                    mutableState.value = mutableState.value.copy(busy = false, timeline = null,
                        preparationIssues = preparationIssues, error = "练习准备失败：$reason")
                    logPreparationFailure(scoreId, preparationIssues, error)
                }
            }
        }
        return true
    }
    fun start(config: PracticeConfig) {
        val current = mutableState.value
        if (current.busy || session != null) return
        try {
            val timeline = requireNotNull(current.timeline) { "谱面尚未准备成功，请先处理准备错误" }
            require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "谱面仍有结构错误，不能开始陪练" }
            require(timeline.issues.isEmpty() || config.reviewAcknowledged) { "该谱仍有来源警告，请先校对并确认当前谱面" }
            session = PracticeSession(requireNotNull(current.scoreId), current.title, current.revisionGeneration,
                timeline, config, System.currentTimeMillis(), modelDescription)
            invalidateInputFrames()
            publish()
            fieldDiagnostics.recordLog("INFO", "Practice", "Practice Start", "scoreId=${current.scoreId}; revision=${current.revisionGeneration}; mode=${config.mode}")
        } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = current.copy(error = error.message) }
    }
    /** Worker delivery is retained synchronously; only Main consumes and mutates the practice session. */
    fun accept(frame: PianoRecognitionFrame) {
        val pending = PendingInputFrame(inputGeneration.get(), frame)
        if (!pendingInputFrames.offer(pending)) {
            pendingQueueFailure.compareAndSet(null, "跟谱输入队列已满；已请求停止，不能将未处理帧当作判分结果")
            recordNotEvaluated(frame, "INPUT_QUEUE_OVERFLOW")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) drainPendingInputFrames()
        else scheduleInputDrain()
    }

    private fun scheduleInputDrain() {
        if (inputDrainScheduled.compareAndSet(false, true)) viewModelScope.launch {
            inputDrainScheduled.set(false)
            drainPendingInputFrames()
        }
    }

    /** Close the producer first. This drains real accepted frames before pause/stream rollover. */
    @MainThread
    fun drainPendingInputFrames() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "drainPendingInputFrames must run on Main" }
        if (drainingInputFrames) return
        drainingInputFrames = true
        var failure = pendingQueueFailure.getAndSet(null)
        try {
            failure?.let(::inputUnavailable)
            while (true) {
                // Queue.poll releases its lock before follower work or lifecycle callbacks.
                val pending = pendingInputFrames.poll() ?: break
                val error = consumePendingInputFrame(pending)
                if (failure == null) failure = error
            }
        } finally {
            drainingInputFrames = false
        }
        if (failure != null && !notifyingInputFailure) {
            notifyingInputFailure = true
            try { inputQueueFailureListener?.invoke(requireNotNull(failure)) }
            finally { notifyingInputFailure = false }
        }
        val completed = session?.takeIf { it.phase == CompanionPhase.COMPLETED }
        if (failure == null && completed != null && completed !== completionDeliveredFor && !completingInput) {
            completingInput = true
            completionDeliveredFor = completed
            try {
                // Root closes input and may re-enter drain for a final queued worker batch.
                // The nested drain must not finish or notify completion a second time.
                inputCompletedListener?.invoke()
                if (session === completed && completed.phase == CompanionPhase.COMPLETED && !mutableState.value.busy) finish()
            } finally {
                completingInput = false
            }
        }
    }

    private fun consumePendingInputFrame(pending: PendingInputFrame): String? {
        val frame = pending.frame
        val active = session
        currentFieldInputFailure(frame)?.let { message ->
            pauseForFieldInputFailure(frame, message)
            recordNotEvaluated(frame, "FIELD_DIAGNOSTICS_FAILED", active)
            return message
        }
        val disposition = when {
            pending.generation != inputGeneration.get() -> "INPUT_GENERATION_INVALIDATED"
            active == null -> "NO_ACTIVE_PRACTICE"
            mutableState.value.busy -> "PRACTICE_BUSY"
            active.phase == CompanionPhase.COMPLETED -> "PRACTICE_COMPLETED"
            active.phase != CompanionPhase.RUNNING && active.phase != CompanionPhase.LOCKED -> "PRACTICE_INACTIVE"
            else -> null
        }
        if (disposition != null) {
            recordNotEvaluated(frame, disposition, active)
            return currentFieldInputFailure(frame)?.also { pauseForFieldInputFailure(frame, it) }
        }
        requireNotNull(active)
        var followingRecorded = false
        try {
            val notes = frame.notes.associateBy { it.midi }
            fun evidence(midi: Int): ObservedNote? = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
            frameHistory[frame.frameIndex] = frame
            while (frameHistory.size > 64) frameHistory.remove(frameHistory.keys.first())
            val previousPosition = active.follower.cursor
            val previousLocked = active.follower.locked
            val target = active.follower.targets.getOrNull(previousPosition)
            val expectedBefore = active.follower.expectedMidi()
            val measureBefore = active.follower.measureIndex()
            val followingStart = SystemClock.elapsedRealtimeNanos()
            val update = active.accept(RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds,
                frame.onsetMidi.mapNotNull(::evidence), frame.activeMidi.mapNotNull(::evidence),
                frame.state != RecognitionState.WARMING_UP, frame.clipped, frame.uncertainMidi, frame.ambiguousOctaveMidi))
            val followingMs = (SystemClock.elapsedRealtimeNanos() - followingStart) / 1_000_000.0
            fieldDiagnostics.recordFollowing(FieldFollowingTrace(frame.frameIndex, frame.audioTimeSeconds,
                expectedMeasure = (measureBefore + 1).toString(), expectedBeat = target?.startBeat?.toDouble(),
                expectedNotes = expectedBefore, detectedNotes = update.detectedMidi,
                previousScorePosition = previousPosition.toString(), currentScorePosition = active.follower.cursor.toString(),
                decision = update.decision.name, practiceState = active.phase.name, locked = active.follower.locked,
                reason = update.reason, recoveryEvent = if (previousLocked && !active.follower.locked) "UNLOCK_AFTER_RECOGNIZED_CORRECTION" else null,
                followingLatencyMs = followingMs, fieldSessionId = frame.fieldSessionId,
                audioStreamId = frame.audioStreamId, engineSessionId = frame.engineSessionId))
            followingRecorded = true
            update.evidence.forEach { item ->
                fieldDiagnostics.recordFollowing(FieldFollowingTrace(frame.frameIndex, frame.audioTimeSeconds,
                    expectedMeasure = (item.measureIndex + 1).toString(),
                    expectedBeat = active.follower.targets.getOrNull(item.targetIndex)?.startBeat?.toDouble(),
                    expectedNotes = item.expectedMidi, detectedNotes = item.detectedMidi,
                    previousScorePosition = previousPosition.toString(), currentScorePosition = active.follower.cursor.toString(),
                    decision = item.decision.name, practiceState = active.phase.name, locked = active.follower.locked,
                    reason = item.reason, evidenceAudioTimeSeconds = item.audioTimeSeconds,
                    decisionEventId = "${active.scoreId}:${active.revisionGeneration}:${frame.frameIndex}:${item.targetIndex}:${item.decision}:${item.audioTimeSeconds}",
                    confirmedDecision = true, fieldSessionId = frame.fieldSessionId,
                    audioStreamId = frame.audioStreamId, engineSessionId = frame.engineSessionId))
            }
            publish()
            if (update.evidence.isNotEmpty()) {
                val decisionTime = update.evidence.last().audioTimeSeconds
                val source = frameHistory.values.minByOrNull { kotlin.math.abs(it.audioTimeSeconds - decisionTime) } ?: frame
                val wrong = update.evidence.lastOrNull { it.decision == PracticeDecision.WRONG }
                val wrongFrame = wrong?.let { evidence -> frameHistory.values.minByOrNull { kotlin.math.abs(it.audioTimeSeconds - evidence.audioTimeSeconds) } ?: frame }
                mutableState.value = mutableState.value.copy(lastDecisionFrame = source, lastWrongFrame = wrongFrame ?: mutableState.value.lastWrongFrame)
            }
        } catch (error: Exception) {
            if (!followingRecorded) recordNotEvaluated(frame, "FOLLOWING_EXCEPTION", active)
            val message = "识别帧无法用于陪练：${error.message ?: error.javaClass.simpleName}"
            inputUnavailable(message)
            fieldDiagnostics.recordLog("ERROR", "Practice", "FollowingProcessingFailure", error.stackTraceToString())
            return message
        }
        return null
    }

    private fun currentFieldInputFailure(frame: PianoRecognitionFrame): String? {
        val field = fieldDiagnostics.state.value
        return field.error?.takeIf { frame.fieldSessionId != null && field.activeSession?.id == frame.fieldSessionId }
    }

    private fun pauseForFieldInputFailure(frame: PianoRecognitionFrame, message: String) {
        if (inputFaultedFieldSessionId != frame.fieldSessionId) {
            inputFaultedFieldSessionId = frame.fieldSessionId
            inputUnavailable(message)
        }
    }

    private fun recordNotEvaluated(frame: PianoRecognitionFrame, reason: String, active: PracticeSession? = null) {
        val current = mutableState.value
        fieldDiagnostics.recordNotEvaluated(frame, reason,
            actualPracticeState = active?.phase?.name ?: current.phase.name,
            actualPosition = active?.follower?.cursor?.toString(),
            locked = active?.follower?.locked ?: current.locked)
    }
    /** May be called from an audio worker; old queued callbacks become invalid immediately. */
    fun invalidateInputFrames() {
        val generation = inputGeneration.incrementAndGet()
        viewModelScope.launch {
            if (generation != inputGeneration.get()) return@launch
            frameHistory.clear()
            mutableState.value = mutableState.value.copy(lastDecisionFrame = null, lastWrongFrame = null)
        }
    }
    fun pause() { invalidateInputFrames(); session?.pause(); publish(); fieldDiagnostics.recordLog("INFO", "Practice", "Practice Stop", "PAUSED") }
    fun resume() { if (!mutableState.value.busy && pendingReport == null) { invalidateInputFrames(); session?.resume(); publish() } }
    fun seekMeasure(index: Int) {
        try { session?.seekMeasure(index); publish() }
        catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = error.message) }
    }
    fun inputUnavailable(message: String) {
        invalidateInputFrames(); session?.pause(); publish(); mutableState.value = mutableState.value.copy(error = message, reason = "输入不可用，已暂停判分")
        fieldDiagnostics.recordLog("ERROR", "Practice", "Practice Stop", message)
    }
    fun finish() {
        val active = session ?: return
        if (mutableState.value.busy) return
        fieldDiagnostics.recordLog("INFO", "Practice", "Practice Stop", "USER_FINISHED")
        val report = pendingReport ?: active.report(System.currentTimeMillis().coerceAtLeast(active.startedAtMillis)).also { pendingReport = it }
        invalidateInputFrames()
        active.pause()
        publish()
        if (report.confirmedInputs == 0) {
            session = null; pendingReport = null
            mutableState.value = mutableState.value.copy(phase = CompanionPhase.READY, locked = false, error = null, message = "没有确认的实际击键，未更新最近练习，也未生成报告")
            return
        }
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        // Queue the save so finish() returns with busy=true even when IO completes immediately.
        // Main.immediate could publish a fast success/failure inside the caller's current UI action.
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val all = withContext(Dispatchers.IO) {
                    ScoreMetadataDatabase(getApplication()).use { database ->
                        require(database.get(report.scoreId) != null) { "此乐谱已删除，练习报告未写入" }
                        reports.save(report)
                        database.markPracticed(report.scoreId, report.endedAtMillis)
                    }
                    reports.load(report.scoreId)
                }
                session = null; pendingReport = null
                mutableState.value = mutableState.value.copy(busy = false, phase = CompanionPhase.COMPLETED, locked = false, latestReport = report,
                    reports = all, message = "练习报告已保存到本机；准确率与误停率仍待有真值的琴房验证")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(busy = false, error = "报告保存失败，可重试结束保存：${error.message}")
            }
        }
    }
    private fun logPreparationFailure(scoreId: String, issues: List<ScoreIssue>, error: Exception? = null) {
        val detail = buildString {
            append("scoreId=$scoreId; revision=${mutableState.value.revisionGeneration}")
            issues.forEach { issue ->
                append("\n${issue.severity}; issueId=${issue.id}; measureId=${issue.measureId}; eventId=${issue.eventId}; ${issue.message}")
            }
            if (error != null) append("\n${error.stackTraceToString()}")
        }
        fieldDiagnostics.recordLog("ERROR", "Practice", "PracticePreparationFailed", detail)
    }
    private fun publish() {
        val active = session ?: return
        val follower = active.follower
        val target = follower.targets.getOrNull(follower.cursor)
        val update = active.lastUpdate
        mutableState.value = mutableState.value.copy(phase = active.phase, locked = follower.locked, config = active.config, cursor = follower.cursor,
            measureIndex = follower.measureIndex(), expectedMidi = follower.expectedMidi(), detectedMidi = update.detectedMidi,
            activeEventIds = target?.notes?.flatMap { it.eventIds }?.toSet().orEmpty(), decision = update.decision, reason = update.reason,
            confirmedInputs = active.confirmedInputs, correct = active.correct, wrong = active.wrong, missed = active.missed,
            completedPasses = active.completedPasses, consecutivePasses = active.consecutivePasses, evidence = active.recentEvidence(),
            error = null, message = null)
    }
}





