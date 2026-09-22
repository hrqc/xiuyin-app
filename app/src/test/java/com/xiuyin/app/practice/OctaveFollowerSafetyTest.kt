package com.xiuyin.app.practice

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.TimedScoreChord
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: an unresolved octave must never manufacture correctness or an accusation. */
class OctaveFollowerSafetyTest {
    @Test fun unresolvedOctaveDoesNotWrongLockCorrectOrAdvanceInAnyMode() {
        for (mode in CompanionMode.entries) {
            for (target in listOf(48, 60, 65)) {
                val follower = ScoreFollower(ScoreFollowerTest.fixture(target), PracticeConfig(mode))
                repeat(10) { index ->
                    val result = follower.accept(observation(index, setOf(48, 60), setOf(48, 60)))
                    assertEquals(PracticeDecision.UNCERTAIN, result.decision)
                    assertFalse(result.locked); assertFalse(follower.locked)
                    assertEquals(0, follower.cursor); assertTrue(result.evidence.isEmpty())
                    assertEquals(setOf(48, 60), result.detectedMidi)
                    assertTrue(result.reason.contains("AMBIGUOUS_OCTAVE"))
                }
            }
        }
    }

    @Test fun removingUpperFromAcceptedNotesCannotTurnAmbiguityIntoCorrectLowerNote() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(48), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        repeat(6) { index ->
            val result = follower.accept(observation(index, setOf(48), setOf(48, 60)))
            assertEquals(PracticeDecision.UNCERTAIN, result.decision)
            assertEquals(setOf(48, 60), result.detectedMidi)
            assertFalse(result.confirmedInput); assertEquals(0, follower.cursor)
        }
    }

    @Test fun exactScoreOctaveDoesNotResolveAcousticAmbiguityAndConfirmedOctaveCanAdvance() {
        val base = ScoreFollowerTest.fixture(48)
        val notes = base.notes + base.notes.single().copy(midi = 60, eventIds = listOf("upper"))
        val timeline = base.copy(notes = notes, chords = listOf(TimedScoreChord(BeatFraction.ZERO, notes)))
        val follower = ScoreFollower(timeline, PracticeConfig(CompanionMode.STOP_ON_ERROR))
        repeat(6) { follower.accept(observation(it, setOf(48, 60), setOf(48, 60))) }
        assertEquals(0, follower.cursor)
        var last = FollowerUpdate(0)
        for (index in 6..9) last = follower.accept(observation(index, setOf(48, 60), emptySet()))
        assertEquals(PracticeDecision.CORRECT, last.decision)
        assertEquals(1, follower.cursor); assertFalse(follower.locked)
    }

    @Test fun ambiguityDoesNotUnlockAnEarlierIndependentWrongNote() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(48), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        for (index in 0..3) follower.accept(observation(index, setOf(65), emptySet()))
        assertTrue(follower.locked)
        val uncertain = follower.accept(observation(4, setOf(48, 60), setOf(48, 60)))
        assertEquals(PracticeDecision.UNCERTAIN, uncertain.decision)
        assertTrue(uncertain.locked); assertEquals(0, follower.cursor)
        assertTrue(uncertain.evidence.isEmpty())
    }

    private fun observation(index: Int, notes: Set<Int>, ambiguous: Set<Int>) =
        RecognitionObservation(index.toLong(), index * .032, notes.map { ObservedNote(it, .95, .95) },
            notes.map { ObservedNote(it, .95, .95) }, ambiguousOctaveMidi = ambiguous)
}
