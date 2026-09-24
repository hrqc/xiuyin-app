package com.xiuyin.app.practice

import org.junit.Assert.*
import org.junit.Test

/** Explicit TEST DATA observations for recovery safety; these are not audio/music accuracy results. */
class ScoreFollowerAlignmentSafetyTest {
    @Test fun eitherUncertainFutureAttackCannotBeAwardedCorrectByForwardAlignment() {
        for (uncertainAttack in listOf(62, 64)) {
            val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64, 65), PracticeConfig())
            val frames = Inputs(follower)
            val first = frames.attack(62, uncertain = if (uncertainAttack == 62) setOf(62) else emptySet())
            val second = frames.attack(64, uncertain = if (uncertainAttack == 64) setOf(64) else emptySet())
            assertTrue("不能用歧义起音确认后续位置：$uncertainAttack", (first.evidence + second.evidence).none { it.decision == PracticeDecision.CORRECT })
            assertEquals(0, follower.cursor)
            assertFalse(follower.locked)
        }
    }

    @Test fun uncertainCurrentTargetIsUnknownRatherThanMissedWhenLaterNotesLocateTheScore() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64, 65), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val frames = Inputs(follower)
        frames.attack(62, uncertain = setOf(60))
        val located = frames.attack(64, uncertain = setOf(60))
        assertEquals(3, follower.cursor)
        assertFalse(follower.locked)
        assertEquals(PracticeDecision.UNCERTAIN, located.evidence.single { it.targetIndex == 0 }.decision)
        assertEquals(listOf(1, 2), located.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
        assertTrue(located.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
    }

    @Test fun repeatedTwoNoteMotifsStayUnlocatedUntilADistinguishingFollowingAttack() {
        // The pair 62 -> 64 occurs at indices 2/3 and 6/7; nearest is not independent location evidence.
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 61, 62, 64, 65, 66, 62, 64, 67, 69), PracticeConfig())
        val frames = Inputs(follower)
        frames.frame(onset = setOf(60), active = setOf(60), ambiguous = setOf(60, 72))
        assertEquals(0, follower.cursor)
        assertTrue(frames.attack(62).evidence.isEmpty())
        val duplicatePair = frames.attack(64)
        assertEquals("两个相同候选片段不能武断选第一个", 0, follower.cursor)
        assertEquals(PracticeDecision.UNCERTAIN, duplicatePair.decision)
        assertTrue(duplicatePair.evidence.isEmpty())
        val distinguished = frames.attack(67)
        assertEquals(9, follower.cursor)
        assertEquals(setOf(69), follower.expectedMidi())
        assertEquals(listOf(6, 7, 8), distinguished.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
        assertTrue(distinguished.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
    }

    @Test fun anInterveningUncertainAttackBreaksTheTwoAttackAlignment() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64, 65), PracticeConfig())
        val frames = Inputs(follower)
        frames.attack(62)
        val interruption = frames.attack(60, uncertain = setOf(60))
        assertEquals(PracticeDecision.UNCERTAIN, interruption.decision)
        assertTrue(interruption.evidence.isEmpty())
        val after = frames.attack(64)
        assertEquals("62 与 64 中间已有另一实际起音，不能拼成连续两音", 0, follower.cursor)
        assertTrue(after.evidence.isEmpty())
        assertEquals(PracticeDecision.UNCERTAIN, after.decision)
    }

    @Test fun anExpiredCandidateCannotLetUnrelatedSustainedAmbiguityEraseANewAttack() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(65), PracticeConfig())
        val frames = Inputs(follower)
        frames.frame(onset = setOf(60), active = setOf(60))
        repeat(6) { frames.frame() }
        // Frame 7 is at 0.224s: the previous candidate is already outside its 0.21s confirmation window.
        frames.frame(onset = setOf(65), active = setOf(48, 60, 65), ambiguous = setOf(48, 60))
        repeat(2) { frames.frame(active = setOf(48, 60, 65), ambiguous = setOf(48, 60)) }
        val result = frames.frame(active = setOf(48, 60, 65), ambiguous = setOf(48, 60))
        assertEquals(PracticeDecision.CORRECT, result.decision)
        assertEquals(setOf(65), result.evidence.single().detectedMidi)
        assertEquals(1, follower.cursor)
    }

    @Test fun aRealWrongLockSurvivesUncertaintyExpiryAndFuturePairsUntilItsOriginalTarget() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64, 65, 67, 69, 71, 72), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val frames = Inputs(follower)
        assertEquals(PracticeDecision.WRONG, frames.attack(66).decision)
        assertTrue(follower.locked)
        val ambiguous = frames.frame(onset = setOf(60), active = setOf(60), ambiguous = setOf(60, 72))
        assertEquals(PracticeDecision.UNCERTAIN, ambiguous.decision)
        assertTrue(ambiguous.locked)
        frames.frame(onset = setOf(60), active = setOf(60))
        repeat(8) { frames.frame() }
        for (midi in listOf(69, 71, 62, 64)) {
            val future = frames.attack(midi)
            assertEquals("后续音不能代替纠正锁定目标", 0, follower.cursor)
            assertTrue(follower.locked)
            assertTrue(future.evidence.none { it.decision == PracticeDecision.CORRECT })
        }
        val corrected = frames.attack(60)
        assertEquals(PracticeDecision.CORRECT, corrected.decision)
        assertFalse(follower.locked)
        assertEquals(1, follower.cursor)
        assertEquals(setOf(62), follower.expectedMidi())
    }

    @Test fun anUncertainCurrentPitchRepeatedAheadNeedsTheNextAttackToChooseItsPosition() {
        for (root in listOf(24, 37, 50, 63, 76)) {
            for (mode in listOf(CompanionMode.STOP_ON_ERROR, CompanionMode.REINFORCE)) {
                for (actuallyLater in listOf(false, true)) {
                    val follower = ScoreFollower(ScoreFollowerTest.fixture(root, root - 1, root - 3,
                        root + 5, root, root + 2, root + 4, root + 7), PracticeConfig(mode))
                    val frames = Inputs(follower)
                    frames.frame(onset = setOf(root), active = setOf(root), ambiguous = setOf(root, root + 12))
                    val repeatedPitch = frames.attack(root)
                    assertEquals("A matching pitch cannot select between current and later positions", 0, follower.cursor)
                    assertEquals(PracticeDecision.UNCERTAIN, repeatedPitch.decision)
                    assertTrue(repeatedPitch.confirmedInput)
                    assertEquals(setOf(root), repeatedPitch.detectedMidi)
                    assertFalse(follower.locked)
                    assertTrue(repeatedPitch.evidence.isEmpty())

                    val distinguished = frames.attack(if (actuallyLater) root + 2 else root - 1)
                    val correctIndices = if (actuallyLater) listOf(4, 5) else listOf(0, 1)
                    val unknownIndices = if (actuallyLater) (0..3).toList() else emptyList()
                    assertEquals(if (actuallyLater) 6 else 2, follower.cursor)
                    assertFalse(follower.locked)
                    assertEquals(correctIndices, distinguished.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
                    assertEquals(unknownIndices, distinguished.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
                    assertEquals(setOf(root), distinguished.evidence.first { it.decision == PracticeDecision.CORRECT }.detectedMidi)
                    assertTrue(distinguished.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
                }
            }
        }
    }

    @Test fun anUncertainCurrentPitchWithoutAnotherMatchingPositionCanBeCorrectedImmediately() {
        for (root in listOf(24, 37, 50, 63, 76)) {
            val follower = ScoreFollower(ScoreFollowerTest.fixture(root, root + 2, root + 4, root + 7),
                PracticeConfig(CompanionMode.STOP_ON_ERROR))
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root), ambiguous = setOf(root, root + 12))
            val corrected = frames.attack(root)
            assertEquals(PracticeDecision.CORRECT, corrected.decision)
            assertEquals(1, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(setOf(root + 2), follower.expectedMidi())
            assertEquals(0, corrected.evidence.single().targetIndex)
            assertEquals(setOf(root), corrected.evidence.single().detectedMidi)
        }
    }

    @Test fun aRealLockStillCorrectsItsOriginalPitchEvenWhenThatPitchOccursAgainLater() {
        for (root in listOf(24, 37, 50, 63, 76)) {
            for (mode in listOf(CompanionMode.STOP_ON_ERROR, CompanionMode.REINFORCE)) {
                val follower = ScoreFollower(ScoreFollowerTest.fixture(root, root - 1, root - 3,
                    root + 5, root, root + 2, root + 4), PracticeConfig(mode))
                val frames = Inputs(follower)
                assertEquals(PracticeDecision.WRONG, frames.attack(root + 1).decision)
                assertTrue(follower.locked)
                val uncertainty = frames.frame(onset = setOf(root), active = setOf(root), ambiguous = setOf(root, root + 12))
                assertEquals(PracticeDecision.UNCERTAIN, uncertainty.decision)
                assertTrue(uncertainty.locked)

                val corrected = frames.attack(root)
                assertEquals(PracticeDecision.CORRECT, corrected.decision)
                assertEquals(1, follower.cursor)
                assertFalse(follower.locked)
                assertEquals(setOf(root - 1), follower.expectedMidi())
                assertEquals(0, corrected.evidence.single().targetIndex)
                assertEquals(setOf(root), corrected.evidence.single().detectedMidi)
            }
        }
    }

    @Test fun anExistingConsecutiveAlignmentIsResolvedBeforeARepeatedCurrentPitchShortcut() {
        for (root in listOf(24, 37, 50, 63, 76)) {
            val follower = ScoreFollower(ScoreFollowerTest.fixture(root, root + 2, root, root + 5),
                PracticeConfig(CompanionMode.STOP_ON_ERROR))
            val frames = Inputs(follower)
            frames.frame(onset = setOf(root), active = setOf(root), ambiguous = setOf(root, root + 12))
            val first = frames.attack(root + 2)
            assertEquals(0, follower.cursor)
            assertTrue(first.evidence.isEmpty())
            val resolved = frames.attack(root)
            assertEquals("The preceding distinct attack identifies this as the later repeated pitch", 3, follower.cursor)
            assertFalse(follower.locked)
            assertEquals(setOf(root + 5), follower.expectedMidi())
            assertEquals(listOf(1, 2), resolved.evidence.filter { it.decision == PracticeDecision.CORRECT }.map { it.targetIndex })
            assertEquals(listOf(0), resolved.evidence.filter { it.decision == PracticeDecision.UNCERTAIN }.map { it.targetIndex })
            assertTrue(resolved.evidence.none { it.decision == PracticeDecision.MISSED || it.decision == PracticeDecision.WRONG })
        }
    }

    private class Inputs(private val follower: ScoreFollower) {
        private var index = -1L
        fun frame(onset: Set<Int> = emptySet(), active: Set<Int> = emptySet(), uncertain: Set<Int> = emptySet(), ambiguous: Set<Int> = emptySet()): FollowerUpdate {
            index++
            return follower.accept(RecognitionObservation(index, index * .032,
                onset.map { ScoreFollowerTest.note(it) }, active.map { ScoreFollowerTest.note(it) },
                uncertainMidi = uncertain + ambiguous, ambiguousOctaveMidi = ambiguous))
        }
        fun attack(midi: Int, uncertain: Set<Int> = emptySet()): FollowerUpdate {
            repeat(4) { frame() }
            frame(setOf(midi), setOf(midi), uncertain)
            repeat(2) { frame(active = setOf(midi), uncertain = uncertain) }
            return frame(active = setOf(midi), uncertain = uncertain)
        }
    }
}