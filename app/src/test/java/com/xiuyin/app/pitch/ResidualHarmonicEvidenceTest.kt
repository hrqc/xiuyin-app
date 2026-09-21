package com.xiuyin.app.pitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class ResidualHarmonicEvidenceTest {
    private fun frequency(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun pcm(parts: List<Triple<Double, Double, Double>>) = FloatArray(17920) { i ->
        if (i >= 2048) 0f else parts.sumOf { (f, a, phase) -> a * sin(2 * PI * f * i / 16000 + phase) }.toFloat()
    }
    private fun reject(pcm: FloatArray, midis: List<Int>): Set<Int> {
        val on = FloatArray(88); val frames = FloatArray(88)
        midis.forEach { on[it - 21] = .9f; frames[it - 21] = .95f }
        return (PianoAcousticEvidence().analyze(pcm, 0, on, frames).rejected +
            ResidualHarmonicEvidence().analyze(pcm, 0, on, frames).rejected).keys
    }
    @Test fun genuineBassOctaveFifthAcross52NotesAndThreePhasesRemainSupported() {
        for (bass in 21..72) for (phase in listOf(0.0, .7, 2.0)) {
            val parts = listOf(Triple(frequency(bass), .10, phase), Triple(frequency(bass + 12), .15, phase + .3), Triple(frequency(bass + 19), .15, phase + .8))
            assertEquals("bass=$bass phase=$phase", emptySet<Int>(), reject(pcm(parts), listOf(bass, bass + 12, bass + 19)))
        }
    }
    @Test fun weakFundamentalWithNonSharedFifthHarmonicPreservesBassAcross40Notes() {
        for (bass in 33..72) {
            val parts = listOf(Triple(frequency(bass), .0001, 0.0), Triple(5 * frequency(bass), .07, .6), Triple(frequency(bass + 12), .18, .4), Triple(frequency(bass + 19), .18, .8))
            assertFalse("bass=$bass", bass in reject(pcm(parts), listOf(bass, bass + 12, bass + 19)))
        }
    }
    @Test fun higherOctaveAndFifthDoNotExplainAbsentBass() {
        val parts = listOf(Triple(frequency(62), .18, .3), Triple(frequency(69), .18, .8))
        assertEquals(setOf(50), reject(pcm(parts), listOf(50, 62, 69)))
    }
    @Test fun pureHigherNoteCannotSupplyLowerOctaves() {
        assertEquals(setOf(38, 50), reject(pcm(listOf(Triple(frequency(62), .2, .3))), listOf(38, 50, 62)))
    }
    @Test fun lowFifthRetainsBothNotes() {
        assertTrue(reject(pcm(listOf(Triple(55.0, .1, .2), Triple(frequency(40), .1, .9))), listOf(33, 40)).isEmpty())
    }
    @Test fun moderateDetuningRetainsRealBassAndOctave() {
        val ratio = 2.0.pow(18.0 / 1200)
        assertTrue(reject(pcm(listOf(Triple(frequency(50) * ratio, .1, .1), Triple(frequency(62) * ratio, .14, .8))), listOf(50, 62)).isEmpty())
    }
    @Test fun quietRealBassAboveExistingFloorSurvives() {
        assertTrue(reject(pcm(listOf(Triple(frequency(50), .0001, 0.0))), listOf(50)).isEmpty())
    }
    @Test fun absentNeuralCandidateCannotBeInventedFromSpectrum() {
        assertTrue(reject(pcm(listOf(Triple(frequency(50), .1, 0.0))), emptyList()).isEmpty())
    }
    @Test fun broadbandImpulseIsUncertain() {
        val input = FloatArray(17920); input[1024] = .9f
        assertEquals(setOf(50, 62, 69), reject(input, listOf(50, 62, 69)))
    }
    @Test fun hundredBroadbandTestNoiseInputsAreUncertain() {
        for (seed in 0L until 100L) {
            val random = Random(seed)
            val input = FloatArray(17920) { if (it < 2048) (random.nextGaussian() * .05).toFloat() else 0f }
            assertEquals("TEST DATA seed=$seed", setOf(36, 50, 62, 69, 84), reject(input, listOf(36, 50, 62, 69, 84)))
        }
    }
    @Test fun rawNeuralArraysRemainUnmodified() {
        val on = FloatArray(88) { .1f }; val frames = FloatArray(88) { .2f }; on[29] = .9f
        val a = on.copyOf(); val b = frames.copyOf()
        ResidualHarmonicEvidence().analyze(FloatArray(2048), 0, on, frames)
        assertTrue(on.contentEquals(a)); assertTrue(frames.contentEquals(b))
    }
    @Test(expected = IllegalArgumentException::class) fun nonFinitePcmIsRejectedExplicitly() {
        val on = FloatArray(88); on[29] = .9f
        ResidualHarmonicEvidence().analyze(FloatArray(2048) { Float.NaN }, 0, on, FloatArray(88))
    }
}