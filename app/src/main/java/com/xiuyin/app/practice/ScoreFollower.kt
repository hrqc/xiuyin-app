package com.xiuyin.app.practice

import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.ScoreIssueSeverity

enum class CompanionMode(val label: String) { FREE("自由跟弹"), STOP_ON_ERROR("错音即停"), LOOP("循环练习"), REINFORCE("强化易错片段") }
enum class PracticeHand(val label: String) { BOTH("双手"), RIGHT("右手（谱表1）"), LEFT("左手（谱表2）") }
enum class CompanionPhase { READY, RUNNING, PAUSED, LOCKED, COMPLETED }
enum class PracticeDecision { CORRECT, WRONG, MISSED, UNCERTAIN }
data class PracticeConfig(val mode: CompanionMode = CompanionMode.FREE, val startMeasure: Int = 0, val endMeasure: Int? = null, val repeatTarget: Int = 3, val reviewAcknowledged: Boolean = false, val hand: PracticeHand = PracticeHand.BOTH)

/** Model evidence, never a probability of musical correctness. No score enters this interface. */
data class ObservedNote(val midi: Int, val onsetScore: Double, val frameScore: Double)
data class RecognitionObservation(
    val frameIndex: Long, val timeSeconds: Double, val onsetNotes: List<ObservedNote>,
    val activeNotes: List<ObservedNote>, val usable: Boolean = true, val clipped: Boolean = false,
    val uncertainMidi: Set<Int> = emptySet(),
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

    init {
        require(config.startMeasure in timeline.measures.indices && (config.endMeasure ?: timeline.measures.lastIndex) in config.startMeasure..timeline.measures.lastIndex) { "练习小节范围无效" }
        require(config.hand == PracticeHand.BOTH || supportsHandSelection(timeline)) { "单谱表或谱表归属不完整，不能猜测左右手；请选择双手" }
        require(config.repeatTarget in 1..20) { "强化次数应为 1–20" }
        require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "乐谱含结构错误，请先修谱" }
        require(targets.isNotEmpty()) { "所选片段没有可跟随的起音" }
    }
    fun expectedMidi(): Set<Int> = targets.getOrNull(cursor)?.notes?.map { it.midi }?.toSet().orEmpty()
    fun measureIndex(): Int = targets.getOrNull(cursor)?.let { timeline.measureAt(it.startBeat)?.index }
        ?: timeline.measureAt(targets.last().startBeat)!!.index
    fun resetInputStream() { lastFrame = -1L; lastTime = -1.0; clearPending() }
    fun reset() { cursor = 0; locked = false; clearPending() }
    fun clearPending() { candidate = null; alignment = null; lastConfirmedTime = -10.0; lastConfirmedNotes = emptySet() }
    fun seekMeasure(index: Int) {
        val target = targets.indexOfFirst { (timeline.measureAt(it.startBeat)?.index ?: -1) >= index }
        require(index in config.startMeasure..(config.endMeasure ?: timeline.measures.lastIndex) && target >= 0) { "所选小节不在练习范围或没有起音" }
        cursor = target; locked = false; clearPending()
    }
    fun accept(frame: RecognitionObservation): FollowerUpdate {
        require(frame.frameIndex >= 0 && frame.timeSeconds.isFinite() && frame.timeSeconds >= 0) { "识别帧时间无效" }
        require((frame.onsetNotes + frame.activeNotes).all { it.midi in 0..127 && it.onsetScore.isFinite() && it.onsetScore in 0.0..1.0 && it.frameScore.isFinite() && it.frameScore in 0.0..1.0 }) { "识别音符证据无效" }
        if (frame.frameIndex <= lastFrame || frame.timeSeconds <= lastTime) return uncertain("忽略重复或时间回退的识别帧")
        if (lastTime >= 0 && frame.timeSeconds - lastTime > 0.25) clearPending()
        if (lastFrame >= 0 && frame.frameIndex != lastFrame + 1) candidate?.support?.clear()
        lastFrame = frame.frameIndex; lastTime = frame.timeSeconds
        if (!frame.usable || frame.clipped) { candidate = null; alignment = null; return uncertain("输入低证据、预热或削波，暂不判错") }
        if (cursor >= targets.size) return uncertain("本段已结束")
        require(frame.uncertainMidi.all { it in 0..127 }) { "不确定音高范围无效" }
        if (frame.uncertainMidi.any { it in expectedMidi() }) alignment = null
        val fresh = frame.onsetNotes.filter { it.onsetScore >= 0.60 && it.frameScore >= 0.50 }
        var pending = candidate
        if (pending == null && fresh.isNotEmpty()) {
            pending = Candidate(frame.timeSeconds, mutableMapOf())
            candidate = pending
        }
        if (pending == null) return uncertain("没有新起音；持续余音不推进游标")
        pending.uncertain.addAll(frame.uncertainMidi)
        val elapsed = frame.timeSeconds - pending.time
        if (elapsed > 0.21) { candidate = null; return uncertain("起音未获得连续帧确认") }
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
        if (elapsed < 0.079 || pending.notes.isEmpty() || pending.notes.keys.any { (pending.support[it]?.size ?: 0) < 2 }) return uncertain("汇总新起音并等待多帧确认")
        candidate = if (elapsed > 0.081 && fresh.isNotEmpty()) Candidate(frame.timeSeconds,
            fresh.associateBy { it.midi }.toMutableMap()).also { next ->
                fresh.forEach { next.support[it.midi] = ArrayDeque(listOf(it.frameScore)) }
                next.uncertain.addAll(frame.uncertainMidi)
            } else null
        val detected = pending.notes.keys.toSet()
        if (detected == lastConfirmedNotes && pending.time - lastConfirmedTime < 0.096) return uncertain("忽略同次击键的重复证据")
        lastConfirmedNotes = detected; lastConfirmedTime = pending.time
        // Preserve measured attack activation, but judge current support from the final two consecutive frames.
        val score = pending.notes.entries.minOf { (midi, note) -> minOf(note.onsetScore, pending.support.getValue(midi).min()) }
        return align(detected, pending.time, score, pending.uncertain)
    }
    private fun align(detected: Set<Int>, time: Double, score: Double, uncertainMidi: Set<Int>): FollowerUpdate {
        if (expectedMidi().any { it in uncertainMidi }) return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "目标音存在声学歧义，暂不判错或漏音")
        val current = cursor
        val position = targets[current].startBeat
        val optionalGrace = timeline.notes.filter { note ->
            note.grace && (config.hand == PracticeHand.BOTH || note.staff == if (config.hand == PracticeHand.RIGHT) 1 else 2) && timeline.measureAt(note.startBeat)?.index == measureIndex() &&
                note.startBeat <= position && (position - note.startBeat).toDouble() <= 0.25
        }.map { it.midi }.toSet() - expectedMidi()
        val musicalInput = detected - optionalGrace
        if (musicalInput.isEmpty()) return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "当前位置的可选装饰音，不要求推进")
        if (musicalInput == expectedMidi()) {
            alignment = null; locked = false; cursor++
            return FollowerUpdate(cursor, false, PracticeDecision.CORRECT, detected, true, listOf(evidence(current, PracticeDecision.CORRECT, detected, time, score, "目标起音集合得到连续帧支持")))
        }
        val previousAlignment = alignment
        if (previousAlignment != null && previousAlignment.next < targets.size && musicalInput == midiAt(previousAlignment.next)) {
            val evidence = (current until previousAlignment.matched).map { index ->
                evidence(index, PracticeDecision.MISSED, previousAlignment.detected, time, score, "连续两个后续起音支持前向定位；标记跳过位置")
            } + evidence(previousAlignment.matched, PracticeDecision.CORRECT, previousAlignment.detected, previousAlignment.time, previousAlignment.score, "后续起音已确认定位") +
                evidence(previousAlignment.next, PracticeDecision.CORRECT, detected, time, score, "第二个连续起音确认前向定位")
            alignment = null
            // Positional skip evidence is not Confirmed Wrong and must never cause a false stop.
            locked = false
            cursor = previousAlignment.next + 1
            return FollowerUpdate(cursor, locked, PracticeDecision.MISSED, detected, true, evidence, "后续证据确认跳过位置")
        }
        val future = (current + 1..minOf(current + 3, targets.lastIndex)).firstOrNull { midiAt(it) == musicalInput }
        if (!locked && future != null && future + 1 < targets.size) {
            alignment = Alignment(future, future + 1, detected, time, score)
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "可能已到后续位置，等待下一起音确认；暂不判漏音")
        }
        alignment = null
        if (future != null) return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "邻近位置匹配尚无后续确认，保持不确定")
        // A partial chord is not sufficient to accuse a missed note. Reappearing recent targets may be resonance.
        if (expectedMidi().containsAll(musicalInput) || (maxOf(0, current - 2) until current).any { midiAt(it) == musicalInput } || score < 0.80) {
            return FollowerUpdate(cursor, locked, PracticeDecision.UNCERTAIN, detected, true, reason = "缺音、近期余音或证据不足，保持当前位置")
        }
        locked = config.mode == CompanionMode.STOP_ON_ERROR || config.mode == CompanionMode.REINFORCE
        return FollowerUpdate(cursor, locked, PracticeDecision.WRONG, detected, true,
            listOf(evidence(current, PracticeDecision.WRONG, detected, time, score, "高激活的新起音与当前位置及邻近位置不符")), "错音证据已记录；可重弹目标音恢复")
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
    private data class Alignment(val matched: Int, val next: Int, val detected: Set<Int>, val time: Double, val score: Double)
}





/** Selection is based only on explicit score staff numbers, never on pitch range or detected audio. */
fun supportsHandSelection(timeline: ScoreTimeline): Boolean = timeline.notes.isNotEmpty() &&
    timeline.notes.groupBy { it.partId }.values.all { notes -> notes.any { it.staff == 1 } && notes.any { it.staff == 2 } && notes.all { it.staff in 1..2 } }

