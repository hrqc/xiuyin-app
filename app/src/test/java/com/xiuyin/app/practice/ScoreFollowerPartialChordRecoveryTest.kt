package com.xiuyin.app.practice

import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.TimedScoreChord
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: transposed synthetic recognizer evidence, never a claim of measured piano accuracy. */
class ScoreFollowerPartialChordRecoveryTest {
    private val roots = listOf(24, 37, 50, 63, 76)
    private val lockingModes = listOf(CompanionMode.STOP_ON_ERROR, CompanionMode.REINFORCE)

    @Test fun partialCurrentChordPreservesAllDetectedNotesUntilACompleteNewAttack() {
        for (root in roots) for (mode in lockingModes) {
            val current = setOf(root, root + 4, root + 7)
            for (partial in listOf(setOf(root), setOf(root, root + 7))) {
                val follower = ScoreFollower(chords(current, setOf(root + 9)), PracticeConfig(mode))
                val frames = ScoreFollowerTest.Frames(follower::accept)
                assertUnlocated(follower, frames.chord(partial), partial)
                assertEquals(current, follower.expectedMidi())

                val complete = frames.chord(current)
                assertEquals(PracticeDecision.CORRECT, complete.decision)
                assertEquals(1, follower.cursor)
                assertFalse(follower.locked)
                assertEquals(current, complete.detectedMidi)
                assertEquals(current, complete.evidence.single().detectedMidi)
                assertEquals(0, complete.evidence.single().targetIndex)
            }
        }
    }

    @Test fun recoveryAfterPartialCurrentChordDoesNotConvertUnheardChordNotesIntoMisses() {
        for (root in roots) for (mode in lockingModes) {
            val current = setOf(root, root + 4, root + 7)
            val next = setOf(root + 9, root + 16)
            val following = setOf(root + 5, root + 11)
            val follower = ScoreFollower(chords(current, next, following, setOf(root + 19)), PracticeConfig(mode))
            val frames = ScoreFollowerTest.Frames(follower::accept)
            assertUnlocated(follower, frames.chord(setOf(root, root + 7)), setOf(root, root + 7))
            assertUnlocated(follower, frames.chord(next), next)

            val recovered = frames.chord(following)
            assertEquals(3, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(PracticeDecision.UNCERTAIN, recovered.decision)
            assertEquals(listOf(0), recovered.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
            assertEquals(listOf(1, 2), recovered.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
            assertEquals(listOf(next, following), recovered.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.detectedMidi })
            assertTrue(recovered.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
        }
    }

    @Test fun partialNearbyChordNeedsTwoCompleteUniqueGroupsBeforeRecoveringPastUnknownPositions() {
        for (root in roots) for (mode in lockingModes) {
            val partialTarget = setOf(root, root + 4, root + 7, root + 12)
            val partial = setOf(root, root + 4, root + 7)
            val next = setOf(root + 9, root + 16)
            val following = setOf(root + 5, root + 11)
            // The partial chord lies at the edge of the ordinary three-target lookahead.
            // Its two successors require positional recovery; no particular piece or pitch is special.
            val follower = ScoreFollower(chords(setOf(root + 14), setOf(root + 17), setOf(root + 14),
                partialTarget, next, following, setOf(root + 19)), PracticeConfig(mode))
            val frames = ScoreFollowerTest.Frames(follower::accept)
            assertUnlocated(follower, frames.chord(partial), partial)
            assertEquals(setOf(root + 14), follower.expectedMidi())
            assertUnlocated(follower, frames.chord(next), next)

            val recovered = frames.chord(following)
            assertEquals(6, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(setOf(root + 19), follower.expectedMidi())
            assertEquals(PracticeDecision.UNCERTAIN, recovered.decision)
            assertEquals((0..3).toList(), recovered.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
            assertEquals(listOf(4, 5), recovered.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
            assertEquals(listOf(next, following), recovered.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.detectedMidi })
            assertEquals(partialTarget, recovered.evidence.single { it.targetIndex == 3 }.expectedMidi)
            assertTrue("The partial target was never completely heard", recovered.evidence.none {
                it.targetIndex == 3 && it.decision == PracticeDecision.CORRECT
            })
            assertTrue(recovered.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
        }
    }

    @Test fun anExtraForeignNoteCannotBeDeletedToMakeAPartialChordAndAvoidARealLock() {
        for (root in roots) for (mode in lockingModes) {
            val current = setOf(root, root + 4, root + 7)
            val future = setOf(root + 9, root + 12, root + 16)
            for (detected in listOf(setOf(root, root + 4, root + 1), setOf(root + 9, root + 12, root + 1))) {
                val follower = ScoreFollower(chords(current, future, setOf(root + 19)), PracticeConfig(mode))
                val wrong = ScoreFollowerTest.Frames(follower::accept).chord(detected)
                assertEquals(PracticeDecision.WRONG, wrong.decision)
                assertTrue(wrong.confirmedInput)
                assertTrue(wrong.locked)
                assertTrue(follower.locked)
                assertEquals(0, follower.cursor)
                assertEquals(detected, wrong.detectedMidi)
                assertEquals(detected, wrong.evidence.single().detectedMidi)
                assertEquals(current, wrong.evidence.single().expectedMidi)
            }
        }
    }

    @Test fun aPreviousRealLockCannotBeReleasedByPartialChordsOrLaterCompleteGroups() {
        for (root in roots) for (mode in lockingModes) {
            val current = setOf(root, root + 4, root + 7)
            val future = setOf(root + 12, root + 16, root + 19)
            val next = setOf(root + 9)
            val follower = ScoreFollower(chords(current, future, next, setOf(root + 11)), PracticeConfig(mode))
            val frames = ScoreFollowerTest.Frames(follower::accept)
            assertEquals(PracticeDecision.WRONG, frames.note(root + 1).decision)
            assertTrue(follower.locked)

            for (detected in listOf(setOf(root, root + 4), setOf(root + 12), future, next)) {
                val attempted = frames.chord(detected)
                assertEquals(0, follower.cursor)
                assertTrue("Only the original locked chord can release the lock", follower.locked)
                assertTrue(attempted.locked)
                assertEquals(detected, attempted.detectedMidi)
                assertTrue(attempted.evidence.none { it.decision == PracticeDecision.CORRECT })
            }
            val corrected = frames.chord(current)
            assertEquals(PracticeDecision.CORRECT, corrected.decision)
            assertFalse(follower.locked)
            assertEquals(1, follower.cursor)
            assertEquals(current, corrected.evidence.single().detectedMidi)
        }
    }

    @Test fun anExactCurrentChordIsNotDowngradedBecauseItIsAlsoPartOfAFutureChord() {
        for (root in roots) {
            val current = setOf(root, root + 4)
            val follower = ScoreFollower(chords(current, current + (root + 7), setOf(root + 12)),
                PracticeConfig(CompanionMode.STOP_ON_ERROR))
            val result = ScoreFollowerTest.Frames(follower::accept).chord(current)
            assertEquals(PracticeDecision.CORRECT, result.decision)
            assertEquals(1, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(current, result.evidence.single().detectedMidi)
        }
    }

    private fun assertUnlocated(follower: ScoreFollower, update: FollowerUpdate, detected: Set<Int>) {
        assertEquals(PracticeDecision.UNCERTAIN, update.decision)
        assertTrue("The actual attack was confirmed, only its score position is unresolved", update.confirmedInput)
        assertEquals(detected, update.detectedMidi)
        assertEquals(0, update.cursor)
        assertEquals(0, follower.cursor)
        assertFalse(update.locked)
        assertFalse(follower.locked)
        assertTrue("Partial evidence must not manufacture a correct or missed score event", update.evidence.isEmpty())
    }

    private fun chords(vararg groups: Set<Int>): ScoreTimeline {
        require(groups.all { it.isNotEmpty() })
        val base = ScoreFollowerTest.fixture(*groups.map { it.first() }.toIntArray())
        val notes = groups.mapIndexed { index, group ->
            group.sorted().map { midi -> base.notes[index].copy(midi = midi,
                eventIds = listOf("TEST_DATA_group_${index}_$midi")) }
        }
        return base.copy(notes = notes.flatten(), chords = notes.map { TimedScoreChord(it.first().startBeat, it) })
    }
}