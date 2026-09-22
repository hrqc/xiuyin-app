package com.xiuyin.app.pitch

import com.xiuyin.app.practice.ObservedNote
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeDecision
import com.xiuyin.app.practice.RecognitionObservation
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.practice.ScoreFollowerTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** TEST DATA: algorithm safety properties, not measured piano accuracy. */
class OctaveSafetyLayerTest {
    private fun frame(index: Long, lower: Int, lowerOnset: Float = .95f, upperOnset: Float = .95f,
        active: Set<Int> = setOf(lower, lower + 12), clipped: Boolean = false,
        rejections: Map<Int, String> = emptyMap()): PianoRecognitionFrame {
        val onset = FloatArray(88)
        val sustain = FloatArray(88)
        onset[lower - 21] = lowerOnset; onset[lower + 12 - 21] = upperOnset
        active.forEach { sustain[it - 21] = .95f }
        val attacks = listOf(lower, lower + 12).filter { onset[it - 21] >= .6f }.toSet()
        return PianoRecognitionFrame(index, index * .032, index * .032 + 1.12, attacks, active,
            active.map { PianoNoteEvidence(it, onset[it - 21], sustain[it - 21], it in attacks, true) },
            RecognitionState.ATTACK, clipped, 1.0, onset, sustain, candidateRejections = rejections)
    }

    @Test fun everySimultaneousOctaveKeepsBothCandidatesAndCannotPretendCorrect() {
        for (lower in 21..96) {
            val original = frame(0, lower)
            val actual = OctaveSafetyLayer().observe(original, mapOf(lower to .1, lower + 12 to .2))
            assertEquals(setOf(lower, lower + 12), actual.onsetMidi)
            assertEquals(original.activeMidi, actual.activeMidi)
            assertSame(original.rawOnsetScores, actual.rawOnsetScores)
            assertSame(original.rawFrameScores, actual.rawFrameScores)
            assertEquals(RecognitionState.UNCERTAIN, actual.state)
            assertEquals(setOf(lower, lower + 12), actual.ambiguousOctaveMidi)
            assertEquals(OctaveState.AMBIGUOUS_OCTAVE, actual.octaveDiagnostics.single().finalState)
            assertNull(actual.octaveDiagnostics.single().harmonicPrior)
            assertNull(actual.octaveDiagnostics.single().independentOnsetScore)
            assertNull(actual.octaveDiagnostics.single().scoreContextWeight)
            assertNull(actual.octaveDiagnostics.single().finalConfidence)
        }
    }

    @Test fun genuineIsolatedUpperNotesAreNotSuppressedByPitchOrRange() {
        for (lower in 21..96) {
            val original = frame(0, lower, lowerOnset = 0f, active = setOf(lower + 12))
            val actual = OctaveSafetyLayer().observe(original, mapOf(lower + 12 to .2))
            assertSame(original, actual)
            assertTrue(actual.ambiguousOctaveMidi.isEmpty())
            assertEquals(setOf(lower + 12), actual.onsetMidi)
        }
    }

    @Test fun laterUpperAttackNeedsIndependentMeasuredPowerRiseAndRetainsItsWitness() {
        for (lower in listOf(24, 36, 48, 60, 72, 84)) {
            val layer = OctaveSafetyLayer()
            layer.observe(frame(0, lower, upperOnset = .1f), mapOf(lower to .3, lower + 12 to .02))
            for (index in 1L..3L) layer.observe(frame(index, lower, .1f, .1f), mapOf(lower to .3, lower + 12 to .02))
            val attack = layer.observe(frame(4, lower, .1f, .95f), mapOf(lower to .3, lower + 12 to .1))
            assertEquals(OctaveState.REAL_OCTAVE, attack.octaveDiagnostics.single().finalState)
            assertEquals(.128, attack.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
            assertEquals(25.0, attack.octaveDiagnostics.single().upperAttackEnergyRise!!, 1e-10)
            assertTrue(attack.ambiguousOctaveMidi.isEmpty())
            val sustained = layer.observe(frame(5, lower, .1f, .1f), mapOf(lower to .3, lower + 12 to .1))
            assertEquals(OctaveState.REAL_OCTAVE, sustained.octaveDiagnostics.single().finalState)
            // A new simultaneous attack cannot reuse the earlier independent witness.
            val next = layer.observe(frame(6, lower), mapOf(lower to .4, lower + 12 to .15))
            assertEquals(OctaveState.AMBIGUOUS_OCTAVE, next.octaveDiagnostics.single().finalState)
        }
    }

    @Test fun delayedNeuralScoreAloneDoesNotProveIndependentAttack() {
        val layer = OctaveSafetyLayer()
        for (index in 0L..3L) layer.observe(frame(index, 48, if (index == 0L) .95f else .1f, .1f),
            mapOf(48 to .1, 60 to .02))
        val actual = layer.observe(frame(4, 48, .1f, .95f), mapOf(48 to .4, 60 to .08))
        assertEquals(OctaveState.AMBIGUOUS_OCTAVE, actual.octaveDiagnostics.single().finalState)
        assertEquals(16.0, actual.octaveDiagnostics.single().upperAttackEnergyRise!!, 1e-10)
        assertEquals(16.0, actual.octaveDiagnostics.single().lowerAttackEnergyRise!!, 1e-10)
    }

    @Test fun unsupportedFeaturesOrFrameGapCannotManufactureRealOctave() {
        val layer = OctaveSafetyLayer()
        layer.observe(frame(0, 48, upperOnset = .1f), mapOf(48 to .3, 60 to .02))
        val missing = layer.observe(frame(4, 48, .1f, .95f), emptyMap())
        assertEquals(OctaveState.AMBIGUOUS_OCTAVE, missing.octaveDiagnostics.single().finalState)
        assertNull(missing.octaveDiagnostics.single().temporalOffsetSeconds)
        assertNull(missing.octaveDiagnostics.single().upperAttackEnergyRise)
        assertThrows(IllegalArgumentException::class.java) { layer.observe(frame(5, 48), mapOf(48 to Double.NaN)) }
    }

    @Test fun harmonicLikelyStillPreservesAnUpperCandidateAndNeverBecomesCorrectByDeletion() {
        val original = frame(0, 48, rejections = mapOf(60 to "TEST DATA measured weak harmonic"))
        val actual = OctaveSafetyLayer().observe(original, mapOf(48 to .3, 60 to .02))
        assertEquals(OctaveState.HARMONIC_LIKELY, actual.octaveDiagnostics.single().finalState)
        assertEquals(original.onsetMidi, actual.onsetMidi)
        assertEquals(setOf(48, 60), actual.ambiguousOctaveMidi)
        assertEquals(original.candidateRejections, actual.candidateRejections)
    }

    @Test fun lowerAttackAfterSustainedUpperAlsoRequiresMeasuredIndependentRise() {
        val layer = OctaveSafetyLayer()
        layer.observe(frame(0, 48, lowerOnset = .1f), mapOf(48 to .02, 60 to .3))
        for (index in 1L..3L) layer.observe(frame(index, 48, .1f, .1f), mapOf(48 to .02, 60 to .3))
        val actual = layer.observe(frame(4, 48, .95f, .1f), mapOf(48 to .1, 60 to .3))
        assertEquals(OctaveState.REAL_OCTAVE, actual.octaveDiagnostics.single().finalState)
        assertEquals(-.128, actual.octaveDiagnostics.single().temporalOffsetSeconds!!, 1e-10)
        assertEquals(25.0, actual.octaveDiagnostics.single().lowerAttackEnergyRise!!, 1e-10)
        assertTrue(actual.ambiguousOctaveMidi.isEmpty())
    }

    @Test fun rejectedLowerGhostCannotTurnAnAcceptedUpperNoteIntoAnUnresolvedOctave() {
        val original = frame(0, 48, rejections = mapOf(48 to "TEST DATA no actual lower spectral support"))
        val actual = OctaveSafetyLayer().observe(original, mapOf(48 to .000001, 60 to .3))
        assertSame(original, actual)
        assertTrue(actual.ambiguousOctaveMidi.isEmpty())
    }

    @Test fun frameOnlyLowerActivationCannotAccuseAnIsolatedUpperAttack() {
        val original = frame(0, 48, lowerOnset = .1f, active = setOf(60))
        original.rawFrameScores[48 - 21] = .4f
        val actual = OctaveSafetyLayer().observe(original, mapOf(48 to .000001, 60 to .3))
        assertSame(original, actual)
    }

    @Test fun clippingInvalidatesAnOtherwiseIndependentWitness() {
        val layer = OctaveSafetyLayer()
        for (index in 0L..3L) layer.observe(frame(index, 48, if (index == 0L) .95f else .1f, .1f),
            mapOf(48 to .3, 60 to .02))
        val actual = layer.observe(frame(4, 48, .1f, .95f, clipped = true), mapOf(48 to .3, 60 to .1))
        assertEquals(OctaveState.AMBIGUOUS_OCTAVE, actual.octaveDiagnostics.single().finalState)
        assertEquals(setOf(48, 60), actual.ambiguousOctaveMidi)
    }
    @Test fun canceledAcousticAttackCannotReappearAsAnOctaveFromFrameOnlyResidue() {
        // TEST DATA lifecycle regression, using rounded raw activations actually observed at
        // Autopiano frames 260..265. No WAV truth or score enters the safety layer.
        val observed = listOf(
            floatArrayOf(.9097f, .7604f, .9935f, .4904f),
            floatArrayOf(.9988f, .9619f, .9942f, .9674f),
            floatArrayOf(.0937f, .0252f, .9637f, .8370f),
            floatArrayOf(.0009f, .0006f, .8694f, .6927f),
            floatArrayOf(.0003f, .0004f, .9349f, .6389f),
            floatArrayOf(.0002f, .0003f, .9653f, .5575f),
        )
        for (lower in listOf(36, 48, 69, 81, 84)) {
            for (cancelUpper in listOf(true, false)) {
                val tracker = PianoEvidenceTracker()
                val layer = OctaveSafetyLayer()
                val follower = ScoreFollower(ScoreFollowerTest.fixture(lower), PracticeConfig())
                val decisions = mutableListOf<PracticeDecision>()
                observed.forEachIndexed { index, values ->
                    val onset = FloatArray(88); val sustain = FloatArray(88)
                    onset[lower - 21] = values[0]; onset[lower + 12 - 21] = values[1]
                    sustain[lower - 21] = values[2]; sustain[lower + 12 - 21] = values[3]
                    val rejected = if (cancelUpper && index <= 1)
                        mapOf(lower + 12 to "TEST DATA actual acoustic rejection lifecycle") else emptyMap()
                    val tracked = tracker.accept(index.toLong(), index * .032, 1.12, onset, sustain, false, 0.0, rejected)
                    val safe = layer.observe(tracked, emptyMap())
                    assertEquals(tracked.onsetMidi, safe.onsetMidi)
                    assertEquals(tracked.activeMidi, safe.activeMidi)
                    assertSame(tracked.rawOnsetScores, safe.rawOnsetScores)
                    assertSame(tracked.rawFrameScores, safe.rawFrameScores)
                    if (cancelUpper && index >= 2) {
                        assertTrue("Canceled upper attack is not a live candidate", safe.ambiguousOctaveMidi.isEmpty())
                        assertSame(tracked, safe)
                    } else {
                        assertEquals(setOf(lower, lower + 12), safe.ambiguousOctaveMidi)
                    }
                    val notes = safe.notes.associateBy { it.midi }
                    fun observedNote(midi: Int) = notes.getValue(midi).let {
                        ObservedNote(it.midi, it.onsetScore.toDouble(), it.frameScore.toDouble())
                    }
                    decisions += follower.accept(RecognitionObservation(safe.frameIndex, safe.audioTimeSeconds,
                        safe.onsetMidi.map(::observedNote), safe.activeMidi.map(::observedNote),
                        uncertainMidi = safe.uncertainMidi, ambiguousOctaveMidi = safe.ambiguousOctaveMidi)).decision
                }
                if (cancelUpper) {
                    assertEquals(PracticeDecision.CORRECT, decisions.last())
                    assertEquals(1, follower.cursor)
                } else {
                    assertTrue("A still-live upper candidate must never be deleted to match the lower score",
                        decisions.all { it == PracticeDecision.UNCERTAIN })
                    assertEquals(0, follower.cursor)
                }
            }
        }
    }

    @Test fun aReleasedUpperAttackCannotTurnLaterBackgroundActivationIntoANewKey() {
        for (lower in listOf(36, 48, 60, 81, 84)) {
            val layer = OctaveSafetyLayer()
            layer.observe(frame(0, lower, 0f, .95f, active = setOf(lower + 12)), emptyMap())
            for (index in 1L..100L)
                layer.observe(frame(index, lower, 0f, 0f, active = emptySet()), emptyMap())
            val current = frame(101, lower, .95f, .02f, active = setOf(lower))
            current.rawFrameScores[lower + 12 - 21] = .8f
            assertSame(current, layer.observe(current, emptyMap()))
        }
    }

    @Test fun aStillActiveUpperNoteNeverExpiresJustBecauseItsAttackIsOld() {
        val layer = OctaveSafetyLayer()
        layer.observe(frame(0, 48, 0f, .95f, active = setOf(60)), emptyMap())
        for (index in 1L..100L)
            layer.observe(frame(index, 48, 0f, .01f, active = setOf(60)), emptyMap())
        val current = frame(101, 48, .95f, .01f)
        val actual = layer.observe(current, emptyMap())
        assertEquals(setOf(48, 60), actual.ambiguousOctaveMidi)
        assertEquals(current.onsetMidi, actual.onsetMidi)
        assertEquals(current.activeMidi, actual.activeMidi)
        assertEquals(OctaveState.AMBIGUOUS_OCTAVE, actual.octaveDiagnostics.single().finalState)
    }
    @Test fun sharedRealPcmSpectrumCannotAloneSettleSimultaneousOctave() {
        for (lower in listOf(36, 48, 60, 72)) {
            val frequency = 440.0 * 2.0.pow((lower - 69) / 12.0)
            val pcm = FloatArray(PianoModel.INPUT_SAMPLES) { sample ->
                (.15 * sin(2 * PI * frequency * sample / 16000) +
                    .1 * sin(2 * PI * frequency * 2 * sample / 16000)).toFloat()
            }
            val original = frame(0, lower)
            val support = PianoAcousticEvidence().analyze(pcm, 0, original.rawOnsetScores, original.rawFrameScores)
            val actual = OctaveSafetyLayer().observe(original, support.fundamentalAmplitude)
            assertEquals(OctaveState.AMBIGUOUS_OCTAVE, actual.octaveDiagnostics.single().finalState)
            assertTrue(actual.octaveDiagnostics.single().lowerFundamentalAmplitude!! > 0)
            assertTrue(actual.octaveDiagnostics.single().upperFundamentalAmplitude!! > 0)
        }
    }
}
