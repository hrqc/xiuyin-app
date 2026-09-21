package com.xiuyin.app.pitch

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Synthetic acoustic-support boundary checks, not trained-model accuracy measurements. */
class PianoAcousticEvidenceTest {
    private fun pcm(vararg notes: Pair<Int, Double>): FloatArray = FloatArray(17920) { sample ->
        notes.sumOf { (midi, amplitude) -> amplitude*sin(2*PI*440*2.0.pow((midi-69)/12.0)*sample/16000) }.toFloat()
    }
    private fun support(samples: FloatArray, vararg candidates: Int): PianoAcousticEvidence.Support {
        val scores=FloatArray(88); candidates.forEach { scores[it-21]=.9f }
        return PianoAcousticEvidence().analyze(samples,8,scores,scores)
    }
    @Test fun unsupportedBassCandidateIsUncertainWithoutCreatingAnyNewNote() {
        val result=support(pcm(79 to .2),36,79)
        assertTrue(result.rejected.containsKey(36)); assertFalse(result.rejected.containsKey(79))
    }
    @Test fun realLowFundamentalSurvivesAlongsideLouderUpperNote() {
        val result=support(pcm(36 to .05,79 to .2),36,79)
        assertTrue(result.rejected.isEmpty())
    }
    @Test fun weakSimultaneousOctaveIsExplicitlyAmbiguous() {
        val result=support(pcm(84 to .2,96 to .02),84,96)
        assertTrue(result.rejected.containsKey(96)); assertFalse(result.rejected.containsKey(84))
    }
    @Test fun strongRealOctaveEvidenceIsNotRemovedByTargetOrFixedPitchList() {
        assertTrue(support(pcm(84 to .2,96 to .2),84,96).rejected.isEmpty())
    }
    @Test fun noNeuralCandidateDoesNotInventAcousticNotes() {
        val result=support(pcm(60 to .2))
        assertTrue(result.rejected.isEmpty());assertTrue(result.fundamentalAmplitude.isEmpty())
    }
    @Test fun profileCannotWeakenDefaultThresholdsOrUseNonfiniteValues() {
        assertThrows(IllegalArgumentException::class.java) { PitchEvidenceConfig(onsetThreshold=.59f) }
        assertThrows(IllegalArgumentException::class.java) { PitchEvidenceConfig(frameThreshold=.49f) }
        assertThrows(IllegalArgumentException::class.java) { PitchEvidenceConfig(onsetThreshold=Float.NaN) }
        assertEquals(.85f,PitchEvidenceConfig(.85f,.75f).onsetThreshold,0f)
    }
}