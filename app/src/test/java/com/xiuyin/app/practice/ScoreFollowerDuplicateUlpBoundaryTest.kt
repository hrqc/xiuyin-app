package com.xiuyin.app.practice

import org.junit.Assert.*
import org.junit.Test

/** TEST DATA observations on the production 63/48000 origin and 32 ms time grid.
 * Not physical re-strike ground truth; exercises timestamp arithmetic only.
 */
class ScoreFollowerDuplicateUlpBoundaryTest {
    @Test fun exactlyThreeFramesApartMustNotBecomeADuplicateThroughDoubleSubtraction() {
        for (startFrame in listOf(8L, 9L, 12L, 31L, 100L, 370L)) {
            val (follower, updates) = repeatedPair(startFrame, 3)
            assertEquals("Exactly 96ms at startFrame=$startFrame", 2, follower.cursor)
            assertEquals(listOf(0, 1), updates.flatMap { it.evidence }.map { it.targetIndex })
            assertTrue(updates.flatMap { it.evidence }.all { it.decision == PracticeDecision.CORRECT })
            assertFalse(follower.locked)
        }
    }

    @Test fun twoFramesApartStayInOneGroupAndCannotConfirmTwoScoreTargets() {
        val (follower, updates) = repeatedPair(8, 2)
        assertEquals(1, follower.cursor)
        assertEquals(listOf(0), updates.flatMap { it.evidence }.map { it.targetIndex })
        assertFalse(follower.locked)
    }

    @Test fun genuinelyBelow96msIsStillRejectedByTheUnchangedDuplicatePolicy() {
        for (startFrame in listOf(8L, 31L, 370L)) {
            // 95.999ms is 1 microsecond below the policy boundary, far more than ULP error.
            val (follower, updates) = repeatedPair(startFrame, 3, secondAttackAdvanceSeconds = .000001)
            assertEquals(1, follower.cursor)
            assertEquals(listOf(0), updates.flatMap { it.evidence }.map { it.targetIndex })
            assertFalse(follower.locked)
        }
    }

    private fun repeatedPair(startFrame: Long, gapFrames: Int,
        secondAttackAdvanceSeconds: Double = 0.0): Pair<ScoreFollower, List<FollowerUpdate>> {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 60, 62),
            PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val updates = mutableListOf<FollowerUpdate>()
        for (frame in startFrame..startFrame + gapFrames + 6) {
            val second = frame == startFrame + gapFrames
            val at = 63.0 / 48000.0 + frame * .032 - if (second) secondAttackAdvanceSeconds else 0.0
            updates += follower.accept(RecognitionObservation(frame, at,
                if (frame == startFrame || second) listOf(ObservedNote(60, .95, .95)) else emptyList(),
                listOf(ObservedNote(60, .0, .95))))
        }
        return follower to updates
    }
}
