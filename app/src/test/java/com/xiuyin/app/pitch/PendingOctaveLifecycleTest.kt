package com.xiuyin.app.pitch

import org.junit.Assert.*
import org.junit.Test

/**
 * SYNTHETIC TEST DATA: hand-assigned neural activations and band amplitudes.
 * Exercises the real tracker -> octave safety lifecycle, not PCM/model accuracy.
 * A pending candidate is never asserted to be a confirmed or physically played note.
 */
class PendingOctaveLifecycleTest {
    private val lowerNotes = listOf(21, 36, 48, 62, 74, 84, 96)
    private val profiles = listOf(PitchEvidenceConfig(), PitchEvidenceConfig(.85f, .75f))

    // The rejected pending-only ambiguity experiment is preserved, including its original test,
    // under reports/octave-pending-fix-20261015/rejected-broad-candidate. It is not a fixed D4 gap.

    @Test fun independentLowerAttackWitnessSurvivesItsUnconfirmedMiddleFrame() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        val before = mapOf(lower to .02, lower + 12 to .3)
        val after = mapOf(lower to .1, lower + 12 to .3)
        chain.frame(0, amplitudes = before)
        chain.frame(1, upperOnset = .1f, amplitudes = before)
        val upperConfirmed = chain.frame(2, upperOnset = .1f, amplitudes = before)
        assertEquals(chain.context, setOf(lower + 12), upperConfirmed.tracked.onsetMidi)
        chain.frame(3, upperOnset = .1f, amplitudes = before)

        // At frame 4, only the measured lower band rises: .02 -> .1, power x25.
        val started = chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = after)
        assertTrue(chain.context, started.tracked.onsetMidi.isEmpty())
        assertEquals(chain.context, setOf(lower + 12), started.tracked.activeMidi)
        assertRealWitness(chain, started)
        assertEquals(-.128, started.safe.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
        assertEquals(25.0, started.safe.octaveDiagnostics.single().lowerAttackEnergyRise!!, 1e-10)

        val pending = chain.frame(5, .1f, .95f, .1f, .95f, amplitudes = after)
        assertTrue(chain.context, pending.tracked.onsetMidi.isEmpty())
        assertEquals(chain.context, setOf(lower + 12), pending.tracked.activeMidi)
        assertRealWitness(chain, pending)

        val confirmed = chain.frame(6, .1f, .95f, .1f, .95f, amplitudes = after)
        assertEquals(chain.context, setOf(lower), confirmed.tracked.onsetMidi)
        assertEquals(chain.context, setOf(lower, lower + 12), confirmed.tracked.activeMidi)
        assertRealWitness(chain, confirmed)
    }

    @Test fun pendingWithoutIndependentEnergyCannotCreateARealOctaveWitness() = forCases { lower, profile ->
        val before = mapOf(lower to .02, lower + 12 to .3)
        for (after in listOf(before, mapOf(lower to .1, lower + 12 to 1.5), emptyMap())) {
            val chain = Chain(lower, profile)
            chain.frame(0, amplitudes = before)
            for (index in 1..3) chain.frame(index, upperOnset = .1f, amplitudes = before)
            val started = chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = after)
            assertAmbiguous(chain, started)
            val pending = chain.frame(5, .1f, .95f, .1f, .95f, amplitudes = after)
            assertEquals(chain.context, setOf(lower), pending.safe.pendingOnsetMidi)
            assertFalse(chain.context, lower in pending.safe.activeMidi)
            // Pending-only ambiguity remains outside this fix; it must never fabricate REAL.
            assertNoRealWitness(chain, pending)
            assertAmbiguous(chain, chain.frame(6, .1f, .95f, .1f, .95f, amplitudes = after))
        }
    }

    @Test fun invalidatedWitnessCannotReviveFromResidueOrANewUnsupportedAttack() = forCases { lower, profile ->
        for (reason in listOf("cancel", "reject-lower", "reject-upper", "clip", "gap", "reset")) {
            val chain = Chain(lower, profile)
            val before = mapOf(lower to .02, lower + 12 to .3)
            val after = mapOf(lower to .1, lower + 12 to .3)
            chain.frame(0, amplitudes = before)
            for (index in 1..3) chain.frame(index, upperOnset = .1f, amplitudes = before)
            assertRealWitness(chain, chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = after))
            val invalidated = when (reason) {
                "cancel" -> chain.frame(5, .4f, .4f, .1f, .95f, amplitudes = after)
                "reject-lower" -> chain.frame(5, .1f, .95f, .1f, .95f,
                    rejections = mapOf(lower to "SYNTHETIC rejection"), amplitudes = after)
                "reject-upper" -> chain.frame(5, .1f, .95f, .1f, .95f,
                    rejections = mapOf(lower + 12 to "SYNTHETIC rejection"), amplitudes = after)
                "clip" -> chain.frame(5, .1f, .95f, .1f, .95f, clipped = true, amplitudes = after)
                "gap" -> chain.frame(6, .1f, .95f, .1f, .95f, amplitudes = after)
                else -> {
                    chain.reset()
                    chain.frame(0, .1f, .95f, .1f, .95f, amplitudes = after)
                }
            }
            assertNoRealWitness(chain, invalidated, reason)
            val next = invalidated.safe.frameIndex.toInt() + 1
            assertNoRealWitness(chain, chain.frame(next, .1f, .98f, .1f, .95f, amplitudes = after), reason)
            // New attack identities, unchanged band amplitudes: the retired witness is unusable.
            assertAmbiguous(chain, chain.frame(next + 1, .99f, .95f, .99f, .95f, amplitudes = after))
            assertNoRealWitness(chain, chain.frame(next + 2, .1f, .95f, .1f, .95f, amplitudes = after), reason)
            assertAmbiguous(chain, chain.frame(next + 3, .1f, .95f, .1f, .95f, amplitudes = after))
        }
    }

    @Test fun stricterProfileCancellationRetiresWitnessEvenAboveRawSafetyThresholds() {
        for (lower in lowerNotes) for (middleOnset in listOf(.53f, .1f)) {
            val chain = Chain(lower, PitchEvidenceConfig(.85f, .75f))
            val before = mapOf(lower to .02, lower + 12 to .3)
            val after = mapOf(lower to .1, lower + 12 to .3)
            val canceledRise = mapOf(lower to .3, lower + 12 to .3)
            chain.frame(0, amplitudes = before)
            for (index in 1..3) chain.frame(index, upperOnset = .1f, amplitudes = before)
            assertRealWitness(chain, chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = after))
            val pending = chain.frame(5, middleOnset, .95f, .1f, .95f, amplitudes = after)
            assertEquals(chain.context, setOf(lower), pending.safe.pendingOnsetMidi)
            assertRealWitness(chain, pending)

            // .70/.60 cancels the actual .85/.75 tracker candidate despite passing raw .60/.35.
            // In the .1 middle-onset case, a new measured rise must not resurrect the canceled attack.
            val canceled = chain.frame(6, .70f, .60f, .1f, .95f, amplitudes = canceledRise)
            assertTrue(chain.context, canceled.safe.pendingOnsetMidi.isEmpty())
            assertTrue(chain.context, canceled.safe.onsetMidi.isEmpty())
            assertEquals(chain.context, setOf(lower + 12), canceled.safe.activeMidi)
            assertNoRealWitness(chain, canceled, "strict cancellation after onset=$middleOnset")
            assertNoRealWitness(chain, chain.frame(7, .70f, .60f, .1f, .95f, amplitudes = canceledRise))
            assertNoRealWitness(chain, chain.frame(8, .1f, .95f, .1f, .95f, amplitudes = canceledRise))
            // A subsequent legal candidate can confirm, but unchanged energy cannot reuse old REAL.
            assertAmbiguous(chain, chain.frame(9, .99f, .95f, .1f, .95f, amplitudes = canceledRise))
            assertNoRealWitness(chain, chain.frame(10, .53f, .95f, .1f, .95f, amplitudes = canceledRise))
            val confirmed = chain.frame(11, .1f, .95f, .1f, .95f, amplitudes = canceledRise)
            assertEquals(chain.context, setOf(lower), confirmed.safe.onsetMidi)
            assertAmbiguous(chain, confirmed)
        }
    }

    @Test fun insufficientInitialFrameSupportCannotLeaveAPendingLowerGhost() = forCases { lower, profile ->
        for (insufficient in listOf(.34f, profile.frameThreshold - .01f)) {
            val chain = Chain(lower, profile)
            chain.frame(0)
            chain.frame(1, .99f, insufficient)
            // The high lower onset never had enough simultaneous frame support to start.
            val upperConfirmed = chain.frame(2, .53f, .98f, .46f, .96f)
            assertUpperOnly(chain, upperConfirmed, newAttack = true)
            assertUpperOnly(chain, chain.frame(3, .1f, .98f, .1f, .96f), newAttack = false)
        }
    }

    @Test fun canceledLowerCandidateDoesNotReturnFromFrameOnlyResidue() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        // Both scores fail tracker continuation, while .4 is above the safety release floor.
        val canceled = chain.frame(2, .4f, .4f, .46f, .96f)
        assertUpperOnly(chain, canceled, newAttack = true)
        val residue = chain.frame(3, .1f, .98f, .1f, .96f)
        assertUpperOnly(chain, residue, newAttack = false)
    }

    @Test fun acousticRejectionCancelsLowerCandidateBeforeUpperConfirmation() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        val rejected = chain.frame(2, .53f, .98f, .46f, .96f,
            rejections = mapOf(lower to "SYNTHETIC: lower candidate lacks independent acoustic support"))
        assertUpperOnly(chain, rejected, newAttack = true)
        assertTrue(chain.context, lower in rejected.safe.uncertainMidi)
        assertUpperOnly(chain, chain.frame(3, .1f, .98f, .1f, .96f), newAttack = false)
    }

    @Test fun clippingClearsCandidatesBeforeANewIsolatedUpperAttack() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        val clipped = chain.frame(2, .53f, .98f, .46f, .96f, clipped = true)
        assertTrue(chain.context, clipped.tracked.clipped)
        assertTrue(chain.context, clipped.tracked.onsetMidi.isEmpty())
        assertTrue(chain.context, clipped.tracked.activeMidi.isEmpty())
        assertNoOctave(chain, clipped)
        // Low onset re-arms after clipping; high frame-only residue cannot restart lower.
        assertNoOctave(chain, chain.frame(3, .1f, .98f, .1f, .96f))
        assertNoOctave(chain, chain.frame(4, .1f, .98f))
        assertNoOctave(chain, chain.frame(5, .1f, .98f, .1f, .96f))
        assertUpperOnly(chain, chain.frame(6, .1f, .98f, .1f, .96f), newAttack = true)
    }

    @Test fun aFrameGapCannotContinueEitherPreGapCandidate() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        // Missing frame 2 invalidates the earlier candidates in both production components.
        val restarted = chain.frame(3, .53f, .98f)
        assertTrue(chain.context, restarted.tracked.onsetMidi.isEmpty())
        assertTrue(chain.context, restarted.tracked.activeMidi.isEmpty())
        assertNoOctave(chain, restarted)
        assertNoOctave(chain, chain.frame(4, .1f, .98f, .1f, .96f))
        assertUpperOnly(chain, chain.frame(5, .1f, .98f, .1f, .96f), newAttack = true)
    }

    @Test fun explicitResetDiscardsUnconfirmedLowerAndAllowsFreshFrameIndices() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        chain.reset()
        val restarted = chain.frame(0, .53f, .98f)
        assertTrue(chain.context, restarted.tracked.onsetMidi.isEmpty())
        assertTrue(chain.context, restarted.tracked.activeMidi.isEmpty())
        assertNoOctave(chain, restarted)
        assertNoOctave(chain, chain.frame(1, .1f, .98f, .1f, .96f))
        assertUpperOnly(chain, chain.frame(2, .1f, .98f, .1f, .96f), newAttack = true)
    }

    @Test fun stricterProfileCannotBeReconstructedUsingDefaultCandidateThresholds() {
        for (lower in lowerNotes) {
            for ((initialOnset, initialFrame) in listOf(.75f to .95f, .99f to .65f)) {
                val chain = Chain(lower, PitchEvidenceConfig(.85f, .75f))
                chain.frame(0)
                chain.frame(1, initialOnset, initialFrame)
                // Both starts would pass default tracker gates, but fail this actual profile.
                assertUpperOnly(chain, chain.frame(2, .53f, .98f, .46f, .96f), newAttack = true)
                assertUpperOnly(chain, chain.frame(3, .1f, .98f, .1f, .96f), newAttack = false)
            }
        }
    }

    @Test fun anIsolatedUpperConfirmsWithoutInventingAnyLowerNoteOrOctave() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        val first = chain.frame(0)
        assertTrue(chain.context, first.tracked.onsetMidi.isEmpty())
        assertNoOctave(chain, first)
        assertNoOctave(chain, chain.frame(1, upperOnset = .1f))
        assertUpperOnly(chain, chain.frame(2, upperOnset = .1f), newAttack = true)
        for (index in 3..6) assertUpperOnly(chain, chain.frame(index, upperOnset = .1f), newAttack = false)
    }

    @Test fun pendingSnapshotsRemainStableAfterConfirmationCancellationAndReset() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        val first = chain.frame(0)
        val both = chain.frame(1, .99f, .98f)
        val lowerOnly = chain.frame(2, .53f, .98f, .46f, .96f)
        val confirmed = chain.frame(3, .003f, .92f, .004f, .89f)
        chain.reset()
        val restarted = chain.frame(0)
        val beforeCancellation = chain.frame(1, .99f, .98f)
        val canceled = chain.frame(2, .4f, .4f, .46f, .96f)
        chain.reset()

        // Earlier returned frames are snapshots, not a shared set cleared by later accepts/reset.
        assertEquals(chain.context, setOf(lower + 12), first.safe.pendingOnsetMidi)
        assertEquals(chain.context, setOf(lower, lower + 12), both.safe.pendingOnsetMidi)
        assertEquals(chain.context, setOf(lower), lowerOnly.safe.pendingOnsetMidi)
        assertTrue(chain.context, confirmed.safe.pendingOnsetMidi.isEmpty())
        assertEquals(chain.context, setOf(lower + 12), restarted.safe.pendingOnsetMidi)
        assertEquals(chain.context, setOf(lower, lower + 12), beforeCancellation.safe.pendingOnsetMidi)
        assertTrue(chain.context, canceled.safe.pendingOnsetMidi.isEmpty())
    }

    @Test fun canceledWitnessAllowsAnImmediateSupportedRetryWithoutAnExtraRearmFrame() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        val before = mapOf(lower to .02, lower + 12 to .3)
        val firstRise = mapOf(lower to .1, lower + 12 to .3)
        val retryRise = mapOf(lower to .2, lower + 12 to .3)
        chain.frame(0, amplitudes = before)
        for (index in 1..3) chain.frame(index, upperOnset = .1f, amplitudes = before)
        assertRealWitness(chain, chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = firstRise))
        val strict = profile.onsetThreshold > .6f
        val canceled = chain.frame(5, if (strict) .70f else .1f, if (strict) .60f else .1f,
            .1f, .95f, amplitudes = firstRise)
        assertTrue(chain.context, canceled.safe.pendingOnsetMidi.isEmpty())
        assertNoRealWitness(chain, canceled)
        // No low-onset spacer: the very next frame begins a legal new tracker candidate.
        val retry = chain.frame(6, .99f, .95f, .1f, .95f, amplitudes = retryRise)
        assertEquals(chain.context, setOf(lower), retry.safe.pendingOnsetMidi)
        assertFalse(chain.context, lower in retry.safe.onsetMidi)
        assertRealWitness(chain, retry)
        assertRealWitness(chain, chain.frame(7, .1f, .95f, .1f, .95f, amplitudes = retryRise))
        val confirmed = chain.frame(8, .1f, .95f, .1f, .95f, amplitudes = retryRise)
        assertEquals(chain.context, setOf(lower), confirmed.safe.onsetMidi)
        assertRealWitness(chain, confirmed)
    }

    @Test fun firstTrackerPendingDoesNotRetimestampAnEarlierRawAttackIntoARealOctave() = forCases { lower, profile ->
        val chain = Chain(lower, profile)
        val before = mapOf(lower to .02, lower + 12 to .3)
        val rawRise = mapOf(lower to .1, lower + 12 to .3)
        val pendingRise = mapOf(lower to .2, lower + 12 to .3)
        chain.frame(0, amplitudes = before)
        chain.frame(1, upperOnset = .1f, amplitudes = before)
        val rawAttack = chain.frame(2, .99f, .4f, .1f, .95f, amplitudes = rawRise)
        assertTrue(chain.context, rawAttack.safe.pendingOnsetMidi.isEmpty())
        assertAmbiguous(chain, rawAttack)
        assertEquals(-.064, rawAttack.safe.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
        val pending = chain.frame(3, .99f, .95f, .1f, .95f, amplitudes = pendingRise)
        assertEquals(chain.context, setOf(lower), pending.safe.pendingOnsetMidi)
        assertAmbiguous(chain, pending)
        assertEquals(-.064, pending.safe.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
        assertNoRealWitness(chain, chain.frame(4, .1f, .95f, .1f, .95f, amplitudes = pendingRise))
        val confirmed = chain.frame(5, .1f, .95f, .1f, .95f, amplitudes = pendingRise)
        assertEquals(chain.context, setOf(lower), confirmed.safe.onsetMidi)
        assertAmbiguous(chain, confirmed)
        assertEquals(-.064, confirmed.safe.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
    }

    private fun forCases(action: (Int, PitchEvidenceConfig) -> Unit) {
        for (lower in lowerNotes) for (profile in profiles) action(lower, profile)
    }

    private fun assertAmbiguous(chain: Chain, result: Result) {
        assertEquals(chain.context, setOf(chain.lower, chain.lower + 12), result.safe.ambiguousOctaveMidi)
        assertEquals(chain.context, listOf(OctaveState.AMBIGUOUS_OCTAVE), result.safe.octaveDiagnostics.map { it.finalState })
        assertEquals(chain.context, RecognitionState.UNCERTAIN, result.safe.state)
    }

    private fun assertRealWitness(chain: Chain, result: Result) {
        assertEquals(chain.context, listOf(OctaveState.REAL_OCTAVE), result.safe.octaveDiagnostics.map { it.finalState })
        assertTrue(chain.context, result.safe.ambiguousOctaveMidi.isEmpty())
    }

    private fun assertNoRealWitness(chain: Chain, result: Result, reason: String = "no independent energy") {
        assertTrue("${chain.context} reason=$reason", result.safe.octaveDiagnostics.none { it.finalState == OctaveState.REAL_OCTAVE })
    }

    private fun assertNoOctave(chain: Chain, result: Result) {
        assertTrue(chain.context, result.safe.ambiguousOctaveMidi.isEmpty())
        assertTrue(chain.context, result.safe.octaveDiagnostics.isEmpty())
    }

    private fun assertUpperOnly(chain: Chain, result: Result, newAttack: Boolean) {
        assertEquals(chain.context, if (newAttack) setOf(chain.lower + 12) else emptySet<Int>(), result.tracked.onsetMidi)
        assertEquals(chain.context, setOf(chain.lower + 12), result.tracked.activeMidi)
        assertNoOctave(chain, result)
    }

    private data class Result(val tracked: PianoRecognitionFrame, val safe: PianoRecognitionFrame)

    private class Chain(val lower: Int, profile: PitchEvidenceConfig) {
        val context = "SYNTHETIC lower=$lower profile=$profile"
        private val tracker = PianoEvidenceTracker(profile)
        private val safety = OctaveSafetyLayer()

        fun reset() {
            tracker.reset()
            safety.reset()
        }

        fun frame(index: Int, lowerOnset: Float = 0f, lowerFrame: Float = 0f,
            upperOnset: Float = .99f, upperFrame: Float = .95f,
            clipped: Boolean = false, rejections: Map<Int, String> = emptyMap(),
            amplitudes: Map<Int, Double> = emptyMap()): Result {
            val onsets = FloatArray(88)
            val frames = FloatArray(88)
            onsets[lower - 21] = lowerOnset
            frames[lower - 21] = lowerFrame
            onsets[lower + 12 - 21] = upperOnset
            frames[lower + 12 - 21] = upperFrame
            val expectedOnsets = onsets.copyOf()
            val expectedFrames = frames.copyOf()
            val tracked = tracker.accept(index.toLong(), index * .032, index * .032 + 1.12,
                onsets, frames, clipped, 0.0, rejections)
            val safe = safety.observe(tracked, amplitudes)

            // Every lifecycle path preserves source activations and accepted-note evidence.
            assertArrayEquals(context, expectedOnsets, onsets, 0f)
            assertArrayEquals(context, expectedFrames, frames, 0f)
            assertArrayEquals(context, expectedOnsets, tracked.rawOnsetScores, 0f)
            assertArrayEquals(context, expectedFrames, tracked.rawFrameScores, 0f)
            assertSame(context, tracked.rawOnsetScores, safe.rawOnsetScores)
            assertSame(context, tracked.rawFrameScores, safe.rawFrameScores)
            assertEquals(context, tracked.onsetMidi, safe.onsetMidi)
            assertEquals(context, tracked.activeMidi, safe.activeMidi)
            assertEquals(context, tracked.notes, safe.notes)
            assertEquals(context, tracked.candidateRejections, safe.candidateRejections)
            assertEquals(context, tracked.pendingOnsetMidi, safe.pendingOnsetMidi)
            return Result(tracked, safe)
        }
    }
}
