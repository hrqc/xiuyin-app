package com.xiuyin.app.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** Conservative PCM support for neural candidates, never a replacement pitch predictor. */
class PianoAcousticEvidence {
    private val window=DoubleArray(2048) { .5-.5*cos(2*PI*it/2047) }
    private val normalization=2.0/window.sum()
    private val coefficients=Array(88) { key -> DoubleArray(3) { harmonic ->
        2*cos(2*PI*440.0*2.0.pow((key+21-69)/12.0)*(harmonic+1)/16000.0)
    } }
    data class Support(val rejected: Map<Int,String>, val fundamentalAmplitude: Map<Int,Double>)
    fun analyze(pcm16k: FloatArray, localFrame: Int, onset: FloatArray, frames: FloatArray): Support {
        require(pcm16k.size==PianoModel.INPUT_SAMPLES && localFrame in 0..31 && onset.size==88 && frames.size==88)
        val candidates=(0 until 88).filter { onset[it]>=.35f || frames[it]>=.35f }
        val amplitudes=HashMap<Int,DoubleArray>()
        for(key in candidates) amplitudes[key]=DoubleArray(3) { harmonic ->
            val frequency=440.0*2.0.pow((key+21-69)/12.0)*(harmonic+1)
            if(frequency>=7900) 0.0 else {
                val coefficient=coefficients[key][harmonic]
                var prior=0.0;var before=0.0
                for(i in window.indices) {
                    val value=pcm16k[localFrame*512+i]*window[i]+coefficient*prior-before
                    before=prior;prior=value
                }
                sqrt((prior*prior+before*before-coefficient*prior*before).coerceAtLeast(0.0))*normalization
            }
        }
        val strongest=amplitudes.values.maxOfOrNull { it[0] } ?: 0.0
        val rejected=linkedMapOf<Int,String>()
        for(key in candidates) {
            if(onset[key]<.35f) continue
            val level=amplitudes.getValue(key)
            if(level.max()<maxOf(MIN_AMPLITUDE,strongest*MIN_PARTIAL_RELATIVE_AMPLITUDE)) {
                rejected[key+21]="UNCERTAIN: model candidate lacks support in its first three PCM harmonics"
                continue
            }
            // Octave ambiguity cannot be settled from a target score. A weak higher candidate is
            // withheld, not called wrong. A softly played genuine octave may remain UNCERTAIN.
            for(lower in candidates) {
                if(lower>=key || ((key-lower)%12!=0 || key-lower>36) || frames[lower]<.5f) continue
                if(onset[lower]<.35f) continue
                val lowerLevel=amplitudes.getValue(lower)[0]
                if(level[0]<lowerLevel*WEAK_OCTAVE_RELATIVE_AMPLITUDE) {
                    rejected[key+21]="UNCERTAIN: weak octave candidate overlaps a stronger simultaneous lower-note harmonic"
                    break
                }
            }
        }
        return Support(rejected,amplitudes.mapKeys { it.key+21 }.mapValues { it.value[0] })
    }
    companion object {
        const val MIN_AMPLITUDE=0.00003
        const val MIN_PARTIAL_RELATIVE_AMPLITUDE=0.015
        const val WEAK_OCTAVE_RELATIVE_AMPLITUDE=0.32
    }
}