package com.xiuyin.app.pitch

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stateful 48kHz -> 16kHz FIR decimator. No gain, fabricated leading audio, or per-block phase reset. */
class Streaming48kTo16k {
    private val taps = FloatArray(TAP_COUNT) { index ->
        val x = index - GROUP_DELAY
        val ideal = if (x == 0L) 2.0 * CUTOFF / SOURCE_RATE else sin(2.0 * PI * CUTOFF * x / SOURCE_RATE) / (PI * x)
        val window = 0.54 - 0.46 * cos(2.0 * PI * index / (TAP_COUNT - 1))
        (ideal * window).toFloat()
    }.also { coefficients -> val sum = coefficients.sum(); coefficients.indices.forEach { coefficients[it] /= sum } }
    private val history = FloatArray(TAP_COUNT)
    private var nextWrite = 0
    private var samplesSeen = 0L

    data class Output(val firstCenterInputSample: Long?, val samples: FloatArray)

    fun accept(pcm: ShortArray, count: Int = pcm.size): Output {
        require(count in 0..pcm.size)
        val result = ArrayList<Float>((count + 2) / 3)
        var firstCenter: Long? = null
        repeat(count) { index ->
            history[nextWrite] = pcm[index] / 32768.0f
            nextWrite = (nextWrite + 1) % TAP_COUNT
            val latest = samplesSeen++
            if (samplesSeen >= TAP_COUNT && (latest - GROUP_DELAY) % FACTOR == 0L) {
                var sum = 0.0
                repeat(TAP_COUNT) { tap ->
                    val offset = (nextWrite - 1 - tap + TAP_COUNT * 2) % TAP_COUNT
                    sum += history[offset] * taps[tap]
                }
                if (firstCenter == null) firstCenter = latest - GROUP_DELAY
                // FIR overshoot is not clipped away; caller must detect it and expose overload.
                result.add(sum.toFloat())
            }
        }
        return Output(firstCenter, result.toFloatArray())
    }

    fun reset() { history.fill(0f); nextWrite = 0; samplesSeen = 0 }

    companion object {
        const val SOURCE_RATE = 48_000
        const val TARGET_RATE = 16_000
        const val FACTOR = 3
        const val TAP_COUNT = 127
        const val GROUP_DELAY = 63L
        const val CUTOFF = 7_200.0
    }
}