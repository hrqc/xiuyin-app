package com.xiuyin.app.practice

import org.junit.Assert.*
import org.junit.Test

/** TEST DATA only: attack-group boundaries, not recognition or musical accuracy measurements. */
class ScoreFollowerAttackBoundaryTest {
    @Test fun aSupportedOldAttackIsConfirmedBeforeANewAmbiguousAttack() {
        for (root in roots) {
            val follower = follower(root, root + 2, root + 4)
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root))
            repeat(2) { frames.frame(active = setOf(root)) }
            // At 96 ms the new onset is outside the old 81 ms aggregation window.
            val old = frames.frame(onset = setOf(root + 2), active = setOf(root, root + 2),
                ambiguous = setOf(root + 2, root + 14))
            assertEquals(PracticeDecision.CORRECT, old.decision)
            assertEquals(1, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(0, old.evidence.single().targetIndex)
            assertEquals(setOf(root), old.evidence.single().detectedMidi)
            repeat(5) {
                val new = frames.frame(active = setOf(root + 2))
                assertEquals(PracticeDecision.UNCERTAIN, new.decision)
                assertTrue(new.evidence.isEmpty())
                assertEquals("Ambiguous new onset cannot inherit the old confirmation", 1, follower.cursor)
                assertFalse(follower.locked)
            }
        }
    }

    @Test fun anUnsupportedOldAttackCannotSwallowTheNextSupportedAttack() {
        for (root in roots) {
            val follower = follower(root, root + 2, root + 4, root + 7)
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root))
            frames.frame(active = setOf(root))
            val low = frames.frame(active = setOf(root), support = mapOf(root to .49))
            assertTrue(low.evidence.isEmpty())
            val boundary = frames.frame(onset = setOf(root + 2), active = setOf(root, root + 2),
                support = mapOf(root to .49))
            assertEquals(PracticeDecision.UNCERTAIN, boundary.decision)
            assertEquals(0, follower.cursor)
            assertTrue(boundary.evidence.isEmpty())
            assertFalse(follower.locked)
            repeat(2) { frames.frame(active = setOf(root + 2)) }
            val new = frames.frame(active = setOf(root + 2))
            assertTrue("The actual new onset must survive old support failure", new.confirmedInput)
            assertEquals(setOf(root + 2), new.detectedMidi)
            assertEquals(PracticeDecision.UNCERTAIN, new.decision)
            assertTrue(new.evidence.isEmpty())
            assertEquals(0, follower.cursor)
            assertRecoveredPastUnconfirmedOld(follower, frames.attack(root + 4))
        }
    }

    @Test fun anOldAttackWithItsOwnAmbiguityDoesNotEraseANewCleanGroup() {
        for (root in roots) {
            for (ambiguityAtBoundary in listOf(false, true)) {
                val follower = follower(root, root + 2, root + 4, root + 7)
                val frames = Inputs(follower)
                frames.frame(onset = setOf(root), active = setOf(root))
                // Cover both accumulated uncertainty and old-note octave ambiguity at the boundary.
                frames.frame(active = setOf(root), uncertain = if (ambiguityAtBoundary) emptySet() else setOf(root))
                frames.frame(active = setOf(root))
                val old = frames.frame(onset = setOf(root + 2), active = setOf(root, root + 2),
                    ambiguous = if (ambiguityAtBoundary) setOf(root, root + 12) else emptySet())
                assertEquals(PracticeDecision.UNCERTAIN, old.decision)
                assertTrue(old.evidence.isEmpty())
                assertEquals(0, follower.cursor)
                assertFalse(follower.locked)
                repeat(2) { frames.frame(active = setOf(root + 2)) }
                val new = frames.frame(active = setOf(root + 2))
                assertTrue("Old ambiguity must not discard an independently measured new onset", new.confirmedInput)
                assertEquals(setOf(root + 2), new.detectedMidi)
                assertTrue(new.evidence.isEmpty())
                assertEquals(0, follower.cursor)
                assertRecoveredPastUnconfirmedOld(follower, frames.attack(root + 4))
            }
        }
    }

    @Test fun ambiguityAtOrBefore81MillisecondsStillBlocksTheWholeAttackGroup() {
        for (root in roots) {
            for (boundarySeconds in listOf(.064, .080, .081)) {
                val follower = follower(root, root + 4)
                val frames = Inputs(follower)
                frames.frame(onset = setOf(root), active = setOf(root))
                frames.frame(active = setOf(root))
                val together = frames.frame(onset = setOf(root + 2), active = setOf(root, root + 2),
                    ambiguous = setOf(root + 2, root + 14), timeSeconds = boundarySeconds)
                assertEquals("Within-window ambiguity must not flush the old note early", PracticeDecision.UNCERTAIN, together.decision)
                assertTrue(together.evidence.isEmpty())
                assertEquals(0, follower.cursor)
                assertFalse(follower.locked)
                repeat(4) {
                    val sustained = frames.frame(active = setOf(root, root + 2), ambiguous = setOf(root + 2, root + 14))
                    assertEquals(PracticeDecision.UNCERTAIN, sustained.decision)
                    assertTrue(sustained.evidence.isEmpty())
                    assertEquals(0, follower.cursor)
                    assertFalse(follower.locked)
                }
                // A later clean independent attack may still correct the unique current target.
                val corrected = frames.attack(root)
                assertEquals(PracticeDecision.CORRECT, corrected.decision)
                assertEquals(1, follower.cursor)
                assertEquals(setOf(root), corrected.evidence.single().detectedMidi)
            }
        }
    }

    @Test fun settlingTheOldGroupCannotClearPositionUncertaintyFromTheNewGroup() {
        for (root in roots) {
            val repeated = root + 2
            val follower = follower(root, repeated, repeated - 1, repeated - 3,
                repeated + 5, repeated, repeated + 2, repeated + 4)
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root))
            repeat(2) { frames.frame(active = setOf(root)) }
            val old = frames.frame(onset = setOf(repeated), active = setOf(root, repeated),
                ambiguous = setOf(repeated, repeated + 12))
            assertEquals(PracticeDecision.CORRECT, old.decision)
            assertEquals(1, follower.cursor)
            assertEquals(0, old.evidence.single().targetIndex)
            // Deliberately no repeated ambiguity flag: only the boundary above establishes loss.
            repeat(3) {
                val sustained = frames.frame(active = setOf(repeated))
                assertTrue(sustained.evidence.isEmpty())
                assertEquals(1, follower.cursor)
            }
            val undecided = frames.attack(repeated)
            assertEquals("A repeated current pitch still needs a distinguishing next onset", 1, follower.cursor)
            assertEquals(PracticeDecision.UNCERTAIN, undecided.decision)
            assertTrue(undecided.confirmedInput)
            assertEquals(setOf(repeated), undecided.detectedMidi)
            assertTrue(undecided.evidence.isEmpty())
            assertFalse(follower.locked)
            val located = frames.attack(repeated + 2)
            assertEquals(7, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(listOf(5, 6), located.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
            assertEquals((1..4).toList(), located.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
            assertTrue(located.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
        }
    }

    @Test fun aReleasedShortOldNoteKeepsItsTwoSupportedFramesAtTheNewAttackBoundary() {
        for (root in roots) {
            val follower = follower(root, root + 2, root + 4)
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root))
            repeat(2) { frames.frame(active = setOf(root)) }
            // The last old support pair is at 32/64 ms; its release at 96 ms is not a revocation.
            val released = frames.frame(onset = setOf(root + 2), active = setOf(root + 2))
            assertEquals(PracticeDecision.CORRECT, released.decision)
            assertEquals(1, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(0, released.evidence.single().targetIndex)
            assertEquals(setOf(root), released.evidence.single().detectedMidi)
            repeat(2) { frames.frame(active = setOf(root + 2)) }
            val new = frames.frame(active = setOf(root + 2))
            assertEquals(PracticeDecision.CORRECT, new.decision)
            assertEquals(2, follower.cursor)
            assertEquals(1, new.evidence.single().targetIndex)
            assertEquals(setOf(root + 2), new.evidence.single().detectedMidi)
            repeat(3) { assertTrue(frames.frame(active = setOf(root + 2)).evidence.isEmpty()) }
            assertEquals(2, follower.cursor)
        }
    }

    @Test fun anOldSingleSupportedFrameCannotBorrowTheNewAttackFrameToReachTwo() {
        for (root in roots) {
            val follower = follower(root, root + 2, root + 4, root + 7)
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root))
            frames.frame(active = setOf(root), support = mapOf(root to .49))
            frames.frame(active = setOf(root))
            // At the boundary the old group has only its 64 ms support; do not add this 96 ms frame.
            val old = frames.frame(onset = setOf(root + 2), active = setOf(root, root + 2))
            assertEquals(PracticeDecision.UNCERTAIN, old.decision)
            assertEquals(0, follower.cursor)
            assertTrue(old.evidence.isEmpty())
            assertFalse(follower.locked)
            repeat(2) { frames.frame(active = setOf(root + 2)) }
            val new = frames.frame(active = setOf(root + 2))
            assertTrue(new.confirmedInput)
            assertEquals(setOf(root + 2), new.detectedMidi)
            assertTrue(new.evidence.isEmpty())
            assertEquals(0, follower.cursor)
            assertRecoveredPastUnconfirmedOld(follower, frames.attack(root + 4))
        }
    }
    private fun assertRecoveredPastUnconfirmedOld(follower: ScoreFollower, result: FollowerUpdate) {
        assertEquals(3, follower.cursor)
        assertFalse(follower.locked)
        assertEquals(listOf(0), result.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
        assertEquals(listOf(1, 2), result.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
        assertTrue(result.evidence.none { it.decision == PracticeDecision.WRONG || it.decision == PracticeDecision.MISSED })
    }

    private fun follower(vararg midi: Int) = ScoreFollower(ScoreFollowerTest.fixture(*midi),
        PracticeConfig(CompanionMode.STOP_ON_ERROR))

    private class Inputs(private val follower: ScoreFollower) {
        private var index = -1L
        fun frame(
            onset: Set<Int> = emptySet(), active: Set<Int> = emptySet(),
            support: Map<Int, Double> = emptyMap(), uncertain: Set<Int> = emptySet(),
            ambiguous: Set<Int> = emptySet(), timeSeconds: Double? = null,
        ): FollowerUpdate {
            index++
            return follower.accept(RecognitionObservation(index, timeSeconds ?: index * .032,
                onset.map { ObservedNote(it, .95, support[it] ?: .95) },
                active.map { ObservedNote(it, .0, support[it] ?: .95) },
                uncertainMidi = uncertain + ambiguous, ambiguousOctaveMidi = ambiguous))
        }
        fun attack(midi: Int): FollowerUpdate {
            repeat(4) { frame() }
            frame(onset = setOf(midi), active = setOf(midi))
            repeat(2) { frame(active = setOf(midi)) }
            return frame(active = setOf(midi))
        }
    }

    private val roots = listOf(24, 37, 50, 63, 76)
}