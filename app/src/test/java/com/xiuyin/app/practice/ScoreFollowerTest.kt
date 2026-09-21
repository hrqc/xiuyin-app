package com.xiuyin.app.practice

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.MidiNoteSpan
import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.TimedScoreChord
import com.xiuyin.app.score.TimedScoreMeasure
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic recognizer evidence; these tests do not measure real audio accuracy. */
class ScoreFollowerTest {
    @Test fun sustainDoesNotAdvanceAndEveryNewOnsetRequiresMultipleFrames() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig())
        val driver = Frames(follower::accept)
        val first = driver.note(60)
        assertEquals(PracticeDecision.CORRECT, first.decision)
        repeat(20) { driver.frame(active = setOf(60)) }
        assertEquals(1, follower.cursor)
        assertEquals(PracticeDecision.CORRECT, driver.note(62).decision)
        assertEquals(2, follower.cursor)
    }
    @Test fun lowEvidenceAndClippingNeverLock() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Frames(follower::accept)
        assertEquals(PracticeDecision.UNCERTAIN, driver.note(66, score = 0.7).decision)
        repeat(5) { driver.frame(onset = setOf(66), active = setOf(66), clipped = true) }
        assertEquals(0, follower.cursor); assertFalse(follower.locked)
    }
    @Test fun wrongStopsAndCorrectNewOnsetRecovers() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Frames(follower::accept)
        assertEquals(PracticeDecision.WRONG, driver.note(66).decision)
        assertTrue(follower.locked); assertEquals(0, follower.cursor)
        assertEquals(PracticeDecision.CORRECT, driver.note(60).decision)
        assertFalse(follower.locked); assertEquals(1, follower.cursor)
    }
    @Test fun freeModeRecordsWrongWithoutLockingOrInventingPosition() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig())
        val result = Frames(follower::accept).note(66)
        assertEquals(PracticeDecision.WRONG, result.decision)
        assertFalse(result.locked); assertEquals(0, result.cursor)
    }
    @Test fun twoFollowingMatchesAreRequiredBeforeCallingAnythingMissed() {
        val follower = ScoreFollower(fixture(60, 62, 64, 65), PracticeConfig())
        val driver = Frames(follower::accept)
        val pending = driver.note(62)
        assertEquals(PracticeDecision.UNCERTAIN, pending.decision); assertTrue(pending.evidence.isEmpty())
        assertEquals(0, follower.cursor)
        val aligned = driver.note(64)
        assertEquals(PracticeDecision.MISSED, aligned.decision)
        assertEquals(listOf(PracticeDecision.MISSED, PracticeDecision.CORRECT, PracticeDecision.CORRECT), aligned.evidence.map { it.decision })
        assertEquals(3, follower.cursor)
    }
    @Test fun finalNearbyMatchWithoutSuccessorStaysUncertain() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig())
        assertEquals(PracticeDecision.UNCERTAIN, Frames(follower::accept).note(62).decision)
        assertEquals(0, follower.cursor)
    }
    @Test fun partialChordDoesNotAccuseMissingNotes() {
        val original = fixture(60)
        val base = original.notes.single()
        val chord = listOf(base, base.copy(midi = 64, eventIds = listOf("e2")), base.copy(midi = 67, eventIds = listOf("e3")))
        val follower = ScoreFollower(original.copy(notes = chord, chords = listOf(TimedScoreChord(BeatFraction.ZERO, chord))), PracticeConfig())
        val driver = Frames(follower::accept)
        assertEquals(PracticeDecision.UNCERTAIN, driver.note(60).decision)
        assertEquals(PracticeDecision.CORRECT, driver.chord(setOf(60, 64, 67)).decision)
    }
    @Test fun previousNoteResonanceDoesNotCauseAFalseStop() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Frames(follower::accept)
        driver.note(60)
        assertEquals(PracticeDecision.UNCERTAIN, driver.note(60).decision)
        assertFalse(follower.locked); assertEquals(1, follower.cursor)
    }
    @Test fun onlyNearbyGraceIsOptionalAndRawEvidenceRemainsVisible() {
        val base = fixture(60, 62)
        val grace = base.notes.first().copy(midi = 61, duration = BeatFraction.ZERO, grace = true)
        val follower = ScoreFollower(base.copy(notes = base.notes + grace), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Frames(follower::accept)
        assertEquals(PracticeDecision.UNCERTAIN, driver.note(61).decision)
        val chord = driver.chord(setOf(60, 61))
        assertEquals(PracticeDecision.CORRECT, chord.decision)
        assertEquals(setOf(60, 61), chord.evidence.single().detectedMidi)
        assertEquals(PracticeDecision.WRONG, driver.note(61).decision)
    }
    @Test fun aGapDoesNotSpliceAnOnsetCandidateAcrossMissingAudio() {
        val follower = ScoreFollower(fixture(60), PracticeConfig())
        follower.accept(RecognitionObservation(0, 0.0, listOf(note(60)), listOf(note(60))))
        val update = follower.accept(RecognitionObservation(20, 0.64, emptyList(), listOf(note(60))))
        assertEquals(PracticeDecision.UNCERTAIN, update.decision); assertEquals(0, follower.cursor)
    }
    @Test fun duplicateAndBackwardFramesAreIgnoredAndInvalidEvidenceRejected() {
        val follower = ScoreFollower(fixture(60), PracticeConfig())
        val frame = RecognitionObservation(0, 0.0, listOf(note(60)), listOf(note(60)))
        follower.accept(frame); follower.accept(frame)
        assertEquals(0, follower.cursor)
        assertThrows(IllegalArgumentException::class.java) { follower.accept(frame.copy(frameIndex = 1, timeSeconds = 0.032, onsetNotes = listOf(note(200)))) }
        assertThrows(IllegalArgumentException::class.java) { follower.accept(frame.copy(timeSeconds = Double.NaN)) }
    }
    @Test fun repeatedNotesNeedSeparateAttacksEvenAtSamePitch() {
        val follower = ScoreFollower(fixture(60, 60), PracticeConfig())
        val driver = Frames(follower::accept)
        driver.note(60); assertEquals(1, follower.cursor)
        driver.note(60); assertEquals(2, follower.cursor)
    }
    @Test fun rangeAndManualSeekUseActualMeasurePositions() {
        val follower = ScoreFollower(fixture(60, 62, 64), PracticeConfig(startMeasure = 1, endMeasure = 2))
        assertEquals(setOf(62), follower.expectedMidi())
        follower.seekMeasure(2); assertEquals(setOf(64), follower.expectedMidi())
        assertThrows(IllegalArgumentException::class.java) { follower.seekMeasure(0) }
    }
    @Test fun acousticAmbiguityInTargetPreventsWrongAndMissedAccusations() {
        val follower = ScoreFollower(fixture(60, 62, 64), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        var result = FollowerUpdate(0)
        repeat(4) { index ->
            result = follower.accept(RecognitionObservation(index.toLong(), index * 0.032,
                if (index == 0) listOf(note(66)) else emptyList(), listOf(note(66)), uncertainMidi = setOf(60)))
        }
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertTrue(result.evidence.isEmpty()); assertFalse(follower.locked); assertEquals(0, follower.cursor)
    }
    @Test fun explicitStaffSelectionFiltersTargetsButNeverFiltersDetectedAudio() {
        val base = fixture(60)
        val lower = base.notes.single().copy(midi = 48, staff = 2, eventIds = listOf("left"))
        val notes = base.notes + lower
        val score = base.copy(notes = notes, chords = listOf(TimedScoreChord(BeatFraction.ZERO, notes)))
        val right = ScoreFollower(score, PracticeConfig(hand = PracticeHand.RIGHT))
        val left = ScoreFollower(score, PracticeConfig(hand = PracticeHand.LEFT))
        assertEquals(setOf(60), right.expectedMidi()); assertEquals(setOf(48), left.expectedMidi())
        val wrong = Frames(right::accept).note(48)
        assertEquals(PracticeDecision.WRONG, wrong.decision)
        assertEquals(setOf(48), wrong.evidence.single().detectedMidi)
        assertThrows(IllegalArgumentException::class.java) { ScoreFollower(base, PracticeConfig(hand = PracticeHand.RIGHT)) }
    }
    @Test fun positionalMissedEvidenceDoesNotLockStopOnErrorMode() {
        val follower = ScoreFollower(fixture(60, 62, 64, 65), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Frames(follower::accept)
        driver.note(62)
        val result = driver.note(64)
        assertEquals(PracticeDecision.MISSED, result.decision)
        assertFalse(follower.locked); assertFalse(result.locked); assertEquals(3, follower.cursor)
    }
    @Test fun decayedRecentEvidenceCannotReuseAnInitiallyStrongFrameToLock() {
        listOf(0.70, 0.95).forEach { finalScore ->
            val follower = ScoreFollower(fixture(60), PracticeConfig(CompanionMode.STOP_ON_ERROR))
            follower.accept(observation(0, 66, onsetScore = 0.96))
            follower.accept(observation(1, 66))
            follower.accept(observation(2, 66, frameScore = 0.65))
            val result = follower.accept(observation(3, 66, frameScore = finalScore))
            assertEquals(PracticeDecision.UNCERTAIN, result.decision)
            assertTrue(result.confirmedInput); assertTrue(result.evidence.isEmpty())
            assertEquals(setOf(66), result.detectedMidi)
            assertEquals(0, follower.cursor); assertFalse(follower.locked)
        }
    }
    @Test fun missingOrWeakSupportResetsTheStreakAndTwoFreshFramesCanConfirmAgain() {
        listOf<Double?>(null, 0.49).forEach { interruption ->
            val follower = ScoreFollower(fixture(60), PracticeConfig(CompanionMode.STOP_ON_ERROR))
            follower.accept(observation(0, 66, onsetScore = 0.96))
            follower.accept(observation(1, 66))
            follower.accept(observation(2, 66, frameScore = interruption))
            val firstRecovery = follower.accept(observation(3, 66, frameScore = 0.92))
            assertEquals(PracticeDecision.UNCERTAIN, firstRecovery.decision)
            assertFalse(firstRecovery.confirmedInput); assertFalse(follower.locked)
            val confirmed = follower.accept(observation(4, 66, frameScore = 0.89))
            assertEquals(PracticeDecision.WRONG, confirmed.decision)
            assertTrue(follower.locked)
            assertEquals(0.89, confirmed.evidence.single().evidenceScore, 0.0)
            assertEquals(0, follower.cursor)
        }
    }
    @Test fun aMissingFrameIndexBreaksSupportEvenInsideTheCandidateTimeWindow() {
        val follower = ScoreFollower(fixture(60), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        follower.accept(observation(0, 66, onsetScore = 0.96))
        follower.accept(observation(1, 66))
        val gap = follower.accept(observation(3, 66))
        assertEquals(PracticeDecision.UNCERTAIN, gap.decision)
        assertFalse(gap.confirmedInput); assertFalse(follower.locked)
        val recovered = follower.accept(observation(4, 66))
        assertEquals(PracticeDecision.WRONG, recovered.decision)
        assertTrue(follower.locked)
    }
    @Test fun aCorrectNoteAlsoWaitsForTwoCurrentFramesAfterAnInterruption() {
        val follower = ScoreFollower(fixture(60, 62), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        follower.accept(observation(0, 60, onsetScore = 0.96))
        follower.accept(observation(1, 60))
        follower.accept(observation(2, 60, frameScore = null))
        assertEquals(PracticeDecision.UNCERTAIN, follower.accept(observation(3, 60)).decision)
        assertEquals(0, follower.cursor)
        val recovered = follower.accept(observation(4, 60))
        assertEquals(PracticeDecision.CORRECT, recovered.decision)
        assertEquals(1, follower.cursor); assertFalse(follower.locked)
    }
    @Test fun attackActivationIsRetainedInsteadOfReplacingItWithLaterSustainValues() {
        listOf(0.65, 0.88).forEach { attack ->
            val follower = ScoreFollower(fixture(60), PracticeConfig(CompanionMode.STOP_ON_ERROR))
            follower.accept(observation(0, 66, onsetScore = attack))
            follower.accept(observation(1, 66))
            follower.accept(observation(2, 66))
            val result = follower.accept(observation(3, 66))
            if (attack < 0.80) {
                assertEquals(PracticeDecision.UNCERTAIN, result.decision)
                assertFalse(follower.locked)
            } else {
                assertEquals(PracticeDecision.WRONG, result.decision)
                assertEquals(attack, result.evidence.single().evidenceScore, 0.0)
                assertTrue(follower.locked)
            }
        }
    }
    private fun observation(index: Int, midi: Int, onsetScore: Double? = null, frameScore: Double? = 0.95): RecognitionObservation {
        val current = frameScore?.let { ObservedNote(midi, onsetScore ?: 0.0, it) }
        return RecognitionObservation(index.toLong(), index * 0.032,
            if (onsetScore != null && current != null) listOf(current) else emptyList(), listOfNotNull(current))
    }
    companion object {
        fun note(midi: Int, score: Double = 0.95) = ObservedNote(midi, score, score)
        fun fixture(vararg midi: Int): ScoreTimeline {
            val notes = midi.mapIndexed { index, value -> MidiNoteSpan(value, BeatFraction.of(index.toLong()), BeatFraction.of(1), listOf("e$index"), "p", 1, "1") }
            return ScoreTimeline(BeatFraction.of(midi.size.toLong()), notes, notes.map { TimedScoreChord(it.startBeat, listOf(it)) }, emptyList(),
                midi.indices.map { TimedScoreMeasure(it, BeatFraction.of(it.toLong()), BeatFraction.of(1), listOf("m$it")) }, emptyList())
        }
    }
    class Frames(private val consume: (RecognitionObservation) -> FollowerUpdate) {
        private var index = -1L
        fun frame(onset: Set<Int> = emptySet(), active: Set<Int> = emptySet(), score: Double = 0.95, clipped: Boolean = false): FollowerUpdate {
            index++
            return consume(RecognitionObservation(index, index * 0.032, onset.map { ScoreFollowerTest.note(it, score) }, active.map { ScoreFollowerTest.note(it, score) }, clipped = clipped))
        }
        fun chord(midi: Set<Int>, score: Double = 0.95): FollowerUpdate {
            repeat(4) { frame() }
            frame(midi, midi, score)
            frame(active = midi, score = score); frame(active = midi, score = score)
            return frame(active = midi, score = score)
        }
        fun note(midi: Int, score: Double = 0.95): FollowerUpdate = chord(setOf(midi), score)
    }
}




