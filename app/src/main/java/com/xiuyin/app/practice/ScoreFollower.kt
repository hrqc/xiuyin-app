package com.xiuyin.app.practice

import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.ScoreIssueSeverity

enum class CompanionMode(val label: String) { FREE("自由跟弹"), STOP_ON_ERROR("错音即停"), LOOP("循环练习"), REINFORCE("强化易错片段") }
enum class PracticeHand(val label: String) { BOTH("双手"), RIGHT("右手（谱表1）"), LEFT("左手（谱表2）") }
enum class CompanionPhase { READY, RUNNING, PAUSED, LOCKED, COMPLETED }
enum class PracticeDecision { CORRECT, WRONG, MISSED, UNCERTAIN }
/** Describes the follower's evidence, not whether the score or performance is objectively correct. */
enum class FollowerPositionState { INITIAL_UNLOCATED, RELIABLE, AMBIGUOUS, LOCKED, COMPLETED }
data class FollowerContextSnapshot(
    val targetIndex: Int, val positionState: FollowerPositionState,
    val expectedMidi: Set<Int>, val eventIds: List<String>, val measureIndex: Int?, val startBeat: Double?,
    val lastInputFrameIndex: Long? = null, val lastInputTimeSeconds: Double? = null,
)
data class PracticeConfig(val mode: CompanionMode = CompanionMode.FREE, val startMeasure: Int = 0, val endMeasure: Int? = null, val repeatTarget: Int = 3, val reviewAcknowledged: Boolean = false, val hand: PracticeHand = PracticeHand.BOTH)

/** Model evidence, never a probability of musical correctness. No score enters this interface. */
data class ObservedNote(val midi: Int, val onsetScore: Double, val frameScore: Double)
data class RecognitionObservation(
    val frameIndex: Long, val timeSeconds: Double, val onsetNotes: List<ObservedNote>,
    val activeNotes: List<ObservedNote>, val usable: Boolean = true, val clipped: Boolean = false,
    val uncertainMidi: Set<Int> = emptySet(),
    val ambiguousOctaveMidi: Set<Int> = emptySet(),
)
data class PracticeEvidence(
    val decision: PracticeDecision, val targetIndex: Int, val measureIndex: Int,
    val eventIds: List<String>, val expectedMidi: Set<Int>, val detectedMidi: Set<Int>,
    val audioTimeSeconds: Double, val evidenceScore: Double, val reason: String,
)
data class FollowerUpdate(
    val cursor: Int, val locked: Boolean = false, val decision: PracticeDecision = PracticeDecision.UNCERTAIN,
    val detectedMidi: Set<Int> = emptySet(), val confirmedInput: Boolean = false,
    val evidence: List<PracticeEvidence> = emptyList(), val reason: String = "等待新的击键证据",
)

/** Bounded positional follower. It never changes the recognizer's input or candidate notes. */
class ScoreFollower(val timeline: ScoreTimeline, val config: PracticeConfig) {
    val targets = timeline.chords.map { chord ->
        chord.copy(notes = chord.notes.filter { config.hand == PracticeHand.BOTH || it.staff == if (config.hand == PracticeHand.RIGHT) 1 else 2 })
    }.filter { chord ->
        val measure = timeline.measureAt(chord.startBeat)?.index ?: -1
        chord.notes.isNotEmpty() && measure >= config.startMeasure && measure <= (config.endMeasure ?: timeline.measures.lastIndex)
    }
    var cursor: Int = 0; private set
    var locked: Boolean = false; private set
    private var lastFrame = -1L
    private var lastTime = -1.0
    private var candidate: Candidate? = null
    private var alignment: Alignment? = null
    private var lastConfirmedTime = -10.0
    private var lastConfirmedNotes: Set<Int> = emptySet()
    private var positionUncertain = false
    // Diagnostic-only state. Never read by the matching, recovery or locking policy.
    private var positionObserved = false
    private var positionEvidenceInterrupted = false

    init {
        require(config.startMeasure in timeline.measures.indices && (config.endMeasure ?: timeline.measures.lastIndex) in config.startMeasure..timeline.measures.lastIndex) { "练习小节范围无效" }
        require(config.hand == PracticeHand.BOTH || supportsHandSelection(timeline)) { "单谱表或谱表归属不完整，不能猜测左右手；请选择双手" }
        require(config.repeatTarget in 1..20) { "强化次数应为 1–20" }
        require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "乐谱含结构错误，请先修谱" }
        require(targets.isNotEmpty()) { "所选片段没有可跟随的起音" }
    }
    fun expectedMidi(): Set<Int> = targets.getOrNull(cursor)?.notes?.map { it.midi }?.toSet().orEmpty()
    fun contextSnapshot(): FollowerContextSnapshot {
        val target = targets.getOrNull(cursor)
        val state = when {
            target == null -> FollowerPositionState.COMPLETED
            locked -> FollowerPositionState.LOCKED
            positionUncertain || alignment != null || positionEvidenceInterrupted -> FollowerPositionState.AMBIGUOUS
            positionObserved -> FollowerPositionState.RELIABLE
            else -> FollowerPositionState.INITIAL_UNLOCATED
        }
        return FollowerContextSnapshot(cursor, state, expectedMidi(), target?.notes?.flatMap { it.eventIds }.orEmpty(),
            target?.let { timeline.measureAt(it.startBeat)?.index }, target?.startBeat?.toDouble(),
            lastFrame.takeIf { it >= 0 }, lastTime.takeIf { it >= 0 })
    }
    fun measureIndex(): Int = targets.getOrNull(cursor)?.let { timeline.measureAt(it.startBeat)?.index }
        ?: timeline.measureAt(targets.last().startBeat)!!.index
    fun resetInputStream() { lastFrame = -1L; lastTime = -1.0; clearPending(); positionEvidenceInterrupted = false }
    fun reset() { cursor = 0; locked = false; positionUncertain = false; clearPending(); positionEvidenceInterrupted = false }
    fun clearPending() { candidate = null; alignment = null; lastConfirmedTime = -10.0; lastConfirmedNotes = emptySet(); positionObserved = false }
    fun seekMeasure(index: Int) {
        val target = targets.indexOfFirst { (timeline.measureAt(it.startBeat)?.index ?: -1) >= index }
        require(index in config.startMeasure..(config.endMeasure ?: timeline.measures.lastIndex) && target >= 0) { "所选小节不在练习范围或没有起音" }
        cursor = target; locked = false; positionUncertain = false; clearPending(); positionEvidenceInterrupted = false
    }
    fun accept(frame: RecognitionObservation): FollowerUpdate {
        require(frame.frameIndex >= 0 && frame.timeSeconds.isFinite() && frame.timeSeconds >= 0) { "识别帧时间无效" }
        require((frame.onsetNotes + frame.activeNotes).all { it.midi in 0..127 && it.onsetScore.isFinite() && it.onsetScore in 0.0..1.0 && it.frameScore.isFinite() && it.frameScore in 0.0..1.0 }) { "识别音符证据无效" }
        if (frame.frameIndex <= lastFrame || frame.timeSeconds <= lastTime) return uncertain("忽略重复或时间回退的识别帧")
        if (lastTime >= 0 && frame.timeSeconds - lastTime > 0.25) { clearPending(); positionEvidenceInterrupted = true }
        if (lastFrame >= 0 && frame.frameIndex != lastFrame + 1) { candidate?.support?.clear(); positionEvidenceInterrupted = true }
        lastFrame = frame.frameIndex; lastTime = frame.timeSeconds
        if (!frame.usable || frame.clipped) { candidate = null; alignment = null; positionEvidenceInterrupted = true; return uncertain("输入低证据、预热或削波，暂不判错") }
        require(frame.ambiguousOctaveMidi.all { it in 0..127 }) { "八度歧义音高范围无效" }
        if (cursor >= targets.size) return uncertain("本段已结束")
        require(frame.uncertainMidi.all { it in 0..127 }) { "不确定音高范围无效" }
        val fresh = frame.onsetNotes.filter { it.onsetScore >= 0.60 && it.frameScore >= 0.50 }
        if (candidate?.let { frame.timeSeconds - it.time > 0.21 } == true) {
            candidate = null
            alignment = null
            positionUncertain = true
        }
        val previous = candidate
        val separateAttack = previous != null && frame.timeSeconds - previous.time > 0.081 && fresh.isNotEmpty()
        val newAttackAmbiguous = fresh.any { it.midi in frame.ambiguousOctaveMidi }
        // A later attack belongs to its own bounded group. Settle the older group against
        // its own evidence; a new ambiguous note cannot revoke an already supported attack.
        val next = if (separateAttack && !newAttackAmbiguous) Candidate(frame.timeSeconds,
            fresh.associateBy { it.midi }.toMutableMap()).also { pending ->
                fresh.forEach { pending.support[it.midi] = ArrayDeque(listOf(it.frameScore)) }
                pending.uncertain.addAll(frame.uncertainMidi)
            } else null
        val attackMidi = previous?.notes.orEmpty().keys + if (separateAttack) emptySet() else fresh.map { it.midi }.toSet()
        if (frame.ambiguousOctaveMidi.any { it in attackMidi }) {
            candidate = next
            alignment = null
            positionUncertain = true
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN,
                (frame.onsetNotes + frame.activeNotes).map { it.midi }.toSet() + frame.ambiguousOctaveMidi,
                reason = if (next != null) "前组起音含未决八度；下一独立起音已保留，等待多帧确认"
                else "AMBIGUOUS_OCTAVE：本次起音含未决八度，保留候选并等待；不判对、不判错、不新增锁定")
        }
        var pending = previous
        if (pending == null && fresh.isNotEmpty()) {
            pending = Candidate(frame.timeSeconds, mutableMapOf())
            candidate = pending
        }
        if (pending == null) return uncertain(if (frame.ambiguousOctaveMidi.isNotEmpty())
            "AMBIGUOUS_OCTAVE：持续候选未决；没有新的独立起音" else "没有新起音；持续余音不推进游标")
        val elapsed = frame.timeSeconds - pending.time
        if (!separateAttack) {
            pending.uncertain.addAll(frame.uncertainMidi)
            if (elapsed <= 0.081) fresh.forEach { note -> pending.notes.putIfAbsent(note.midi, note) }
            val active = (frame.activeNotes + frame.onsetNotes).associateBy { it.midi }
            pending.notes.keys.forEach { midi ->
                val evidence = active[midi]
                if (evidence == null || evidence.frameScore < 0.50) pending.support.remove(midi)
                else pending.support.getOrPut(midi) { ArrayDeque() }.apply {
                    addLast(evidence.frameScore)
                    if (size > 2) removeFirst()
                }
            }
        }
        // At a new group boundary use the older group's own consecutive support. The new
        // frame must neither erase a released short note nor supply missing support to it.
        if (elapsed < 0.079 || pending.notes.isEmpty() || pending.notes.keys.any { (pending.support[it]?.size ?: 0) < 2 }) {
            if (separateAttack) {
                // The older attack has not earned confirmation. Do not mark it correct or
                // retain it at the expense of losing the measured onset of the next group.
                candidate = next
                alignment = null
                positionUncertain = true
            }
            return uncertain(if (separateAttack) "前组起音支持不足，未判定；下一独立起音单独等待确认"
                else "汇总新起音并等待多帧确认")
        }
        candidate = next
        fun retainNextUncertainty(update: FollowerUpdate): FollowerUpdate {
            if (separateAttack && newAttackAmbiguous) {
                alignment = null
                positionUncertain = true
            }
            return update
        }
        val detected = pending.notes.keys.toSet()
        // Preserve the 96 ms policy; tolerate only arithmetic rounding in source timestamps,
        // far below one audio sample. An exact three-frame interval must not become a duplicate.
        val repeatRounding = 2.0 * (Math.ulp(pending.time) + Math.ulp(lastConfirmedTime) + Math.ulp(0.096))
        if (detected == lastConfirmedNotes && 0.096 - (pending.time - lastConfirmedTime) > repeatRounding)
            return retainNextUncertainty(uncertain("忽略同次击键的重复证据"))
        lastConfirmedNotes = detected; lastConfirmedTime = pending.time
        // Preserve measured attack activation, but judge current support from the final two consecutive frames.
        val noteScores = pending.notes.mapValues { (midi, note) -> minOf(note.onsetScore, pending.support.getValue(midi).min()) }
        val score = noteScores.values.min()
        return retainNextUncertainty(align(detected, pending.time, score, pending.uncertain, noteScores))
    }
    private fun align(detected: Set<Int>, time: Double, score: Double, uncertainMidi: Set<Int>, noteScores: Map<Int, Double>): FollowerUpdate {
        val targetUncertain = expectedMidi().any { it in uncertainMidi }
        if (targetUncertain) positionUncertain = true
        if (detected.any { it in uncertainMidi }) {
            alignment = null
            positionUncertain = true
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true,
                reason = "本次起音自身仍有声学歧义，不能用于判对或连续定位")
        }
        val current = cursor
        val position = targets[current].startBeat
        val optionalGrace = timeline.notes.filter { note ->
            note.grace && (config.hand == PracticeHand.BOTH || note.staff == if (config.hand == PracticeHand.RIGHT) 1 else 2) && timeline.measureAt(note.startBeat)?.index == measureIndex() &&
                note.startBeat <= position && (position - note.startBeat).toDouble() <= 0.25
        }.map { it.midi }.toSet() - expectedMidi()
        val musicalInput = detected - optionalGrace
        if (musicalInput.isEmpty()) return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "当前位置的可选装饰音，不要求推进")
        val repeatedCurrentWhileLost = !locked && positionUncertain &&
            (alignment != null || (current + 1..minOf(current + 32, targets.lastIndex)).any { midiAt(it) == musicalInput })
        if (musicalInput == expectedMidi() && !repeatedCurrentWhileLost) {
            if (targetUncertain) {
                positionUncertain = true
                return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "目标起音存在声学歧义，暂不判对或判错")
            }
            alignment = null; locked = false; positionUncertain = false; cursor++
            positionObserved = true; positionEvidenceInterrupted = false
            return FollowerUpdate(cursor, false, PracticeDecision.CORRECT, detected, true, listOf(evidence(current, PracticeDecision.CORRECT, detected, time, score, "目标起音集合得到连续帧支持")))
        }
        val previousAlignment = alignment
        if (!locked && previousAlignment != null) {
            val offset = previousAlignment.attacks.size
            val consistent = previousAlignment.starts.filter { start ->
                start + offset < targets.size && midiAt(start + offset) == musicalInput
            }
            if (consistent.isNotEmpty()) {
                val attacks = previousAlignment.attacks + AlignedAttack(detected, time, score)
                if (consistent.size == 1) {
                    val start = consistent.single()
                    val skippedDecision = if (positionUncertain) PracticeDecision.UNCERTAIN else PracticeDecision.MISSED
                    val evidence = (current until start).map { index ->
                        evidence(index, skippedDecision, attacks.first().detected, time, score,
                            if (positionUncertain) "此前起音或位置无法确认，不能断言用户漏弹；仅恢复后续定位"
                            else "连续后续起音支持唯一前向定位；标记跳过位置")
                    } + attacks.mapIndexed { index, attack ->
                        evidence(start + index, PracticeDecision.CORRECT, attack.detected, attack.time, attack.score,
                            "连续独立起音确认唯一后续位置")
                    }
                    alignment = null
                    cursor = start + attacks.size
                    positionUncertain = false
                    positionObserved = true; positionEvidenceInterrupted = false
                    return FollowerUpdate(cursor, false, if (start == current) PracticeDecision.CORRECT else skippedDecision, detected, true, evidence,
                        "连续起音恢复唯一定位；未确认的位置单独保留")
                }
                // Repeated motifs require more evidence; never pick the first matching location.
                alignment = if (attacks.size < 8) Alignment(consistent, attacks) else null
                positionUncertain = true
                return FollowerUpdate(cursor, false, PracticeDecision.UNCERTAIN, detected, true,
                    reason = "多个片段含相同起音序列，继续等待唯一位置；不猜测游标")
            }
        }
        // Wider search only after a concrete input/position failure, with consecutive attacks.
        // A locked wrong note still requires the original target to recover.
        val searchAhead = if (positionUncertain && !locked) 32 else 3
        val firstSearch = if (!locked && positionUncertain && musicalInput == expectedMidi()) current else current + 1
        val future = (firstSearch..minOf(current + searchAhead, targets.lastIndex)).filter { midiAt(it) == musicalInput }
        val extendable = future.filter { it + 1 < targets.size }
        if (!locked && extendable.isNotEmpty()) {
            alignment = Alignment(extendable, listOf(AlignedAttack(detected, time, score)))
            return FollowerUpdate(cursor, false, PracticeDecision.UNCERTAIN, detected, true,
                reason = "可能已到后续位置，等待连续起音确认唯一位置；暂不判漏音")
        }
        alignment = null
        if (future.isNotEmpty()) return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true,
            reason = "邻近位置匹配尚无后续确认，保持不确定")
        val partialTarget = (current..minOf(current + 3, targets.lastIndex)).any { index ->
            val notes = midiAt(index)
            notes.size > musicalInput.size && notes.containsAll(musicalInput)
        }
        if (partialTarget) {
            if (!locked) positionUncertain = true
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true,
                reason = "只确认了当前或邻近和弦的部分音，位置与缺音尚不能判定；不推进、不判错")
        }
        if (!locked && (positionUncertain || targetUncertain)) {
            positionUncertain = true
            return FollowerUpdate(cursor, false, PracticeDecision.UNCERTAIN, detected, true,
                reason = "跟谱位置尚未恢复，不能用陈旧目标判错；等待连续起音重新定位")
        }
        // A partial chord is not sufficient to accuse a missed note. Reappearing recent targets may be resonance.
        // Only an extra key's own accepted onset and recent consecutive support may establish
        // a wrong note. Expected/recent keys cannot lend their strength to a weak extra, and
        // another weak chord member cannot veto an independently supported wrong key.
        val recentMidi = (maxOf(0, current - 2) until current).flatMap { midiAt(it) }.toSet()
        val extraMidi = musicalInput - expectedMidi() - recentMidi
        val wrongScore = extraMidi.maxOfOrNull { noteScores.getValue(it) } ?: 0.0
        if (expectedMidi().containsAll(musicalInput) || (maxOf(0, current - 2) until current).any { midiAt(it) == musicalInput } || wrongScore < 0.80) {
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "缺音、近期余音或证据不足，保持当前位置")
        }
        locked = config.mode == CompanionMode.STOP_ON_ERROR || config.mode == CompanionMode.REINFORCE
        return FollowerUpdate(cursor, locked, PracticeDecision.WRONG, detected, true,
            listOf(evidence(current, PracticeDecision.WRONG, detected, time, wrongScore, "高激活的新起音与当前位置及邻近位置不符")), "错音证据已记录；可重弹目标音恢复")
    }
    private fun midiAt(index: Int) = targets[index].notes.map { it.midi }.toSet()
    private fun evidence(index: Int, decision: PracticeDecision, detected: Set<Int>, time: Double, score: Double, reason: String): PracticeEvidence {
        val target = targets[index]
        return PracticeEvidence(decision, index, timeline.measureAt(target.startBeat)!!.index, target.notes.flatMap { it.eventIds }, midiAt(index), detected, time, score, reason)
    }
    private fun uncertain(reason: String) = FollowerUpdate(cursor, locked, reason = reason)
    private data class Candidate(
        val time: Double, val notes: MutableMap<Int, ObservedNote>,
        val support: MutableMap<Int, ArrayDeque<Double>> = mutableMapOf(), val uncertain: MutableSet<Int> = mutableSetOf(),
    )
    private data class AlignedAttack(val detected: Set<Int>, val time: Double, val score: Double)
    private data class Alignment(val starts: List<Int>, val attacks: List<AlignedAttack>)
}





/** Selection is based only on explicit score staff numbers, never on pitch range or detected audio. */
fun supportsHandSelection(timeline: ScoreTimeline): Boolean = timeline.notes.isNotEmpty() &&
    timeline.notes.groupBy { it.partId }.values.all { notes -> notes.any { it.staff == 1 } && notes.any { it.staff == 2 } && notes.all { it.staff in 1..2 } }

