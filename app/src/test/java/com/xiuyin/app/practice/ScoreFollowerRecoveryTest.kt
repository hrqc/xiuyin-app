package com.xiuyin.app.practice

import org.junit.Assert.*
import org.junit.Test

/** TEST DATA for positional uncertainty, not piano recognition accuracy. */
class ScoreFollowerRecoveryTest {
    @Test fun anUnrelatedSustainedOctaveDoesNotEraseASeparateFreshAttack() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(65), PracticeConfig())
        var result = FollowerUpdate(0)
        repeat(4) { index ->
            result = follower.accept(RecognitionObservation(index.toLong(), index * .032,
                if (index == 0) listOf(ScoreFollowerTest.note(65)) else emptyList(),
                listOf(48, 60, 65).map { ScoreFollowerTest.note(it) },
                ambiguousOctaveMidi = setOf(48, 60)))
        }
        assertEquals(PracticeDecision.CORRECT, result.decision)
        assertEquals(setOf(65), result.evidence.single().detectedMidi)
        assertEquals(1, follower.cursor)
    }

    @Test fun unresolvedFreshOctaveIsStillNotAConfirmedChord() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(65), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        repeat(4) { index ->
            val result = follower.accept(RecognitionObservation(index.toLong(), index * .032,
                if (index == 0) listOf(48, 60, 65).map { ScoreFollowerTest.note(it) } else emptyList(),
                listOf(48, 60, 65).map { ScoreFollowerTest.note(it) }, ambiguousOctaveMidi = setOf(48, 60)))
            assertEquals(PracticeDecision.UNCERTAIN, result.decision)
            assertFalse(result.locked)
            assertEquals(0, follower.cursor)
        }
    }

    @Test fun anExpiredAttackMakesThePositionUncertainInsteadOfAccusingLaterNotes() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64, 65, 67, 69, 71, 72), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val frames = ScoreFollowerTest.Frames(follower::accept)
        frames.note(60)
        frames.frame(setOf(62), setOf(62))
        repeat(8) { frames.frame() }
        val lost = frames.note(69)
        assertEquals(PracticeDecision.UNCERTAIN, lost.decision)
        assertFalse(follower.locked)
        assertEquals(1, follower.cursor)
        val aligned = frames.note(71)
        assertEquals(7, follower.cursor)
        assertFalse(follower.locked)
        assertEquals(4, aligned.evidence.count { it.decision == PracticeDecision.UNCERTAIN })
        assertEquals(2, aligned.evidence.count { it.decision == PracticeDecision.CORRECT })
        assertTrue(aligned.evidence.none { it.decision == PracticeDecision.WRONG || it.decision == PracticeDecision.MISSED })
    }

    @Test fun unknownPositionsCannotCountAsACleanReinforcementPass() {
        val session = PracticeSession("s", "TEST DATA", 0, ScoreFollowerTest.fixture(60, 62, 64, 65, 67),
            PracticeConfig(CompanionMode.REINFORCE, repeatTarget = 1), 0, "TEST DATA")
        val frames = ScoreFollowerTest.Frames(session::accept)
        frames.note(60)
        frames.frame(setOf(62), setOf(62))
        repeat(8) { frames.frame() }
        frames.note(65)
        frames.note(67)
        assertEquals(0, session.consecutivePasses)
        assertEquals(CompanionPhase.RUNNING, session.phase)
        assertEquals(0, session.follower.cursor)
        assertTrue(session.recentEvidence().any { it.decision == PracticeDecision.UNCERTAIN })
    }
}
