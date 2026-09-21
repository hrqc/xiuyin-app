package com.xiuyin.app.pitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Independent PCM support for NN candidates; no score, target, or website input. */
class ResidualHarmonicEvidence {
    private val window = DoubleArray(WINDOW_SIZE) { .5 - .5 * cos(2 * PI * it / (WINDOW_SIZE - 1)) }
    private val normalization = 2.0 / window.sum()
    private val frequencies = DoubleArray(88) { 440.0 * 2.0.pow((it + 21 - 69) / 12.0) }
    private val detune = doubleArrayOf(-20.0, -10.0, 0.0, 10.0, 20.0).map { 2.0.pow(it / 1200.0) }
    private val reversal = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - 13) }
    private val twiddleReal = DoubleArray(FFT_SIZE / 2) { cos(-2 * PI * it / FFT_SIZE) }
    private val twiddleImag = DoubleArray(FFT_SIZE / 2) { sin(-2 * PI * it / FFT_SIZE) }
    // Per-stream, non-concurrent analyzer. Scratch arrays remain bounded and are reused.
    private val real = DoubleArray(FFT_SIZE)
    private val imaginary = DoubleArray(FFT_SIZE)
    private val magnitude = DoubleArray(FFT_SIZE / 2 + 1)
    data class Partial(val harmonic: Int, val frequency: Double, val amplitude: Double, val prominence: Double)
    data class Result(val rejected: Map<Int, String>, val spectralFlatness: Double)

    fun analyze(pcm16k: FloatArray, offset: Int, onset: FloatArray, frames: FloatArray): Result {
        require(offset >= 0 && offset + WINDOW_SIZE <= pcm16k.size && onset.size == 88 && frames.size == 88)
        require(onset.all { it.isFinite() && it in 0f..1f } && frames.all { it.isFinite() && it in 0f..1f })
        val candidates = (0 until 88).filter { onset[it] >= .35f || frames[it] >= .35f }
        if (candidates.isEmpty()) return Result(emptyMap(), 0.0)
        require((offset until offset + WINDOW_SIZE).all { pcm16k[it].isFinite() })
        spectrum(pcm16k, offset)
        val first = (20.0 * FFT_SIZE / SAMPLE_RATE).toInt()
        val last = (7900.0 * FFT_SIZE / SAMPLE_RATE).toInt()
        var powerSum = 0.0
        var logPowerSum = 0.0
        for (i in first until last) {
            val power = magnitude[i] * magnitude[i]
            powerSum += power
            logPowerSum += ln(maxOf(power, 1e-30))
        }
        val flatness = exp(logPowerSum / (last - first)) / maxOf(powerSum / (last - first), 1e-30)
        val partials = candidates.associateWith { key -> (1..6).map { harmonic ->
            val nominal = frequencies[key] * harmonic
            var at = nominal * detune[0]
            var level = amplitude(at)
            for (factor in detune.drop(1)) {
                val frequency = nominal * factor
                val probe = amplitude(frequency)
                if (probe > level) { at = frequency; level = probe }
            }
            val guard = 2.0 * SAMPLE_RATE / WINDOW_SIZE
            val side = maxOf(amplitude(at - guard), amplitude(at + guard))
            Partial(harmonic, at, level, level / maxOf(side, 1e-12))
        } }
        val strongest = partials.values.maxOf { it[0].amplitude }
        val minimum = maxOf(.00003, strongest * .015)
        fun tonal(partial: Partial) = partial.amplitude >= minimum && partial.prominence >= 2.0
        // Only candidates supported by their own tonal fundamental may explain another partial.
        // A phantom low NN candidate therefore cannot circularly erase its higher source notes.
        val owners = candidates.filter { frames[it] >= .50f && tonal(partials.getValue(it)[0]) }
        val rejected = linkedMapOf<Int, String>()
        for (key in candidates) {
            if (onset[key] < .35f) continue
            val own = partials.getValue(key)
            if (flatness > .30) {
                rejected[key + 21] = "UNCERTAIN: broadband PCM lacks tonal support"
                continue
            }
            // Genuine fundamental wins even if a lower note could also explain this frequency.
            // Existing nominal-amplitude weak-octave protection is applied separately, unchanged.
            if (tonal(own[0])) continue
            val independent = own.any { partial -> tonal(partial) && owners.none { owner ->
                owner != key && (1..8).any { harmonic ->
                    val target = partials.getValue(owner)[0].frequency * harmonic
                    val tolerance = maxOf(SAMPLE_RATE.toDouble() / WINDOW_SIZE, target * (2.0.pow(35.0 / 1200) - 1))
                    abs(partial.frequency - target) <= tolerance
                }
            } }
            if (!independent) rejected[key + 21] = "UNCERTAIN: no independent narrowband partial; shared harmonics or transient"
        }
        return Result(rejected, flatness)
    }

    private fun amplitude(frequency: Double): Double {
        if (frequency < 0 || frequency > SAMPLE_RATE / 2) return 0.0
        val bin = frequency * FFT_SIZE / SAMPLE_RATE
        val lower = floor(bin).toInt()
        if (lower >= magnitude.lastIndex) return magnitude.last()
        return magnitude[lower] + (magnitude[lower + 1] - magnitude[lower]) * (bin - lower)
    }

    private fun spectrum(pcm: FloatArray, offset: Int) {
        real.fill(0.0); imaginary.fill(0.0)
        for (i in window.indices) real[reversal[i]] = pcm[offset + i] * window[i]
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val stride = FFT_SIZE / size
            for (start in 0 until FFT_SIZE step size) for (j in 0 until half) {
                val twiddle = j * stride
                val right = start + j + half
                val tr = twiddleReal[twiddle] * real[right] - twiddleImag[twiddle] * imaginary[right]
                val ti = twiddleReal[twiddle] * imaginary[right] + twiddleImag[twiddle] * real[right]
                val left = start + j
                real[right] = real[left] - tr; imaginary[right] = imaginary[left] - ti
                real[left] += tr; imaginary[left] += ti
            }
            size *= 2
        }
        for (i in magnitude.indices) magnitude[i] = sqrt(real[i] * real[i] + imaginary[i] * imaginary[i]) * normalization
    }
    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 2048
        const val FFT_SIZE = 8192
    }
}