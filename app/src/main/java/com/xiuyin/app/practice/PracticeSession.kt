package com.xiuyin.app.practice

import com.xiuyin.app.score.ScoreTimeline
import java.util.UUID

data class PracticeReport(
    val id: String, val scoreId: String, val title: String, val revisionGeneration: Long,
    val modelDescription: String, val startedAtMillis: Long, val endedAtMillis: Long,
    val config: PracticeConfig, val confirmedInputs: Int, val correct: Int, val wrong: Int, val missed: Int,
    val uncertainFrames: Int, val completedPasses: Int, val consecutivePasses: Int,
    val completed: Boolean, val evidence: List<PracticeEvidence>, val evidenceTruncated: Boolean,
)

/** One session uses an immutable score timeline. Edits take effect only after reopening practice. */
class PracticeSession(
    val scoreId: String, val title: String, val revisionGeneration: Long, timeline: ScoreTimeline,
    val config: PracticeConfig, val startedAtMillis: Long, val modelDescription: String,
) {
    val follower = ScoreFollower(timeline, config)
    var phase = CompanionPhase.RUNNING; private set
    var lastUpdate = FollowerUpdate(0); private set
    var confirmedInputs = 0; private set
    var completedPasses = 0; private set
    var consecutivePasses = 0; private set
    var correct = 0; private set
    var wrong = 0; private set
    var missed = 0; private set
    var uncertainFrames = 0; private set
    private var passClean = true
    private var truncated = false
    private val evidence = ArrayDeque<PracticeEvidence>()
    private val id = UUID.randomUUID().toString()
    init { require(startedAtMillis >= 0 && revisionGeneration >= 0) }
    fun accept(frame: RecognitionObservation): FollowerUpdate {
        if (phase != CompanionPhase.RUNNING && phase != CompanionPhase.LOCKED) return lastUpdate
        val update = follower.accept(frame)
        lastUpdate = update
        if (update.confirmedInput) confirmedInputs++
        if (update.decision == PracticeDecision.UNCERTAIN) uncertainFrames++
        update.evidence.forEach { item ->
            when (item.decision) {
                PracticeDecision.CORRECT -> correct++
                PracticeDecision.WRONG -> { wrong++; passClean = false; consecutivePasses = 0 }
                PracticeDecision.MISSED -> { missed++; passClean = false; consecutivePasses = 0 }
                PracticeDecision.UNCERTAIN -> { passClean = false; consecutivePasses = 0 }
            }
            if (evidence.size == 2000) { evidence.removeFirst(); truncated = true }
            evidence.addLast(item)
        }
        phase = if (update.locked) CompanionPhase.LOCKED else CompanionPhase.RUNNING
        if (follower.cursor == follower.targets.size) {
            completedPasses++
            consecutivePasses = if (passClean) consecutivePasses + 1 else 0
            if (config.mode == CompanionMode.LOOP || (config.mode == CompanionMode.REINFORCE && consecutivePasses < config.repeatTarget)) {
                follower.reset(); passClean = true
                lastUpdate = update.copy(cursor = 0, reason = "片段完成，开始下一轮")
            } else phase = CompanionPhase.COMPLETED
        }
        return lastUpdate
    }
    fun pause() { if (phase == CompanionPhase.RUNNING || phase == CompanionPhase.LOCKED) { phase = CompanionPhase.PAUSED; follower.clearPending() } }
    fun resume() { if (phase == CompanionPhase.PAUSED) { follower.resetInputStream(); phase = if (follower.locked) CompanionPhase.LOCKED else CompanionPhase.RUNNING } }
    fun seekMeasure(index: Int) {
        follower.seekMeasure(index); passClean = false; consecutivePasses = 0
        lastUpdate = FollowerUpdate(follower.cursor, reason = "已手动定位，当前轮不计完整无错轮次")
        if (phase != CompanionPhase.PAUSED) phase = CompanionPhase.RUNNING
    }
    fun recentEvidence(): List<PracticeEvidence> = evidence.toList().takeLast(30).reversed()
    fun report(endedAtMillis: Long): PracticeReport {
        require(endedAtMillis >= startedAtMillis) { "报告结束时间早于开始时间" }
        return PracticeReport(id, scoreId, title, revisionGeneration, modelDescription, startedAtMillis, endedAtMillis, config,
            confirmedInputs, correct, wrong, missed, uncertainFrames, completedPasses, consecutivePasses,
            phase == CompanionPhase.COMPLETED, evidence.toList(), truncated)
    }
}

