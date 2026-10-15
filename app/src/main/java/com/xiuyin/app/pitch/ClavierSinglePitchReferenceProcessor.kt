package com.xiuyin.app.pitch

import com.xiuyin.app.pitch.clavier.tuning.KeyIdentifier
import java.util.ArrayDeque
import kotlin.math.sqrt

internal data class ClavierWindowResult(val fits: List<ClavierPitchFit>?, val best: ClavierPitchFit?)

/** This seam is for explicit synthetic failure/clock tests, never selected by an App setting. */
internal fun interface ClavierWindowAnalyzer {
    fun analyze(samples: FloatArray): ClavierWindowResult
}

private class OriginalClavierAnalyzer : ClavierWindowAnalyzer {
    private val identifier = KeyIdentifier(ClavierSinglePitchReferenceProcessor.SAMPLE_RATE,
        ClavierSinglePitchReferenceProcessor.WINDOW_SAMPLES)

    override fun analyze(samples: FloatArray): ClavierWindowResult {
        // Exactly the frozen offline run: all 88 starting keys, A440, off=0, default backgroundEnd=-1.
        val fits = identifier.fits(samples, ClavierSinglePitchReferenceProcessor.A4_HZ, 0)
        val best = fits?.let(identifier::best)
        return ClavierWindowResult(fits?.map { it.referenceFit() }, best?.referenceFit())
    }

    private fun KeyIdentifier.Fit.referenceFit() = ClavierPitchFit(startMidi, midi, f1,
        f1 / sqrt(1.0 + b), b, score, explained, unexplained, gaps, matched)
}

/**
 * Bounded, single-worker raw PCM side channel. No capture callback, score, filter, gain, model
 * activation or note decision enters this class. The final incomplete window is never padded.
 */
class ClavierSinglePitchReferenceProcessor internal constructor(
    private val analyzer: ClavierWindowAnalyzer,
) {
    constructor() : this(OriginalClavierAnalyzer())

    private val ring = FloatArray(WINDOW_SAMPLES)
    private val scratch = FloatArray(WINDOW_SAMPLES)
    private val history = ArrayDeque<Window>()
    private var nextWrite = 0
    private var origin: Long? = null
    private var expectedInput = 0L
    private var received = 0L
    private var nextWindowEnd = WINDOW_SAMPLES.toLong()
    var windowCount: Long = 0; private set
    internal val retainedWindowCount: Int get() = history.size

    fun accept(samples: ShortArray, count: Int = samples.size, firstInputSample: Long = expectedInput) {
        require(count in 1..samples.size && firstInputSample >= 0)
        if (origin == null) { origin = firstInputSample; expectedInput = firstInputSample }
        require(firstInputSample == expectedInput) { "Clavier旁证PCM时钟不连续：需要$expectedInput，收到$firstInputSample" }
        require(firstInputSample <= Long.MAX_VALUE - count)
        expectedInput += count
        repeat(count) { index ->
            ring[nextWrite] = samples[index] / 32768.0f
            nextWrite = (nextWrite + 1) % WINDOW_SAMPLES
            received++
            if (received == nextWindowEnd) {
                analyzeWindow()
                nextWindowEnd += HOP_SAMPLES
            }
        }
    }

    /** Latest complete window ending at or before this model frame; never attach a future/stale fit. */
    fun forFrame(frameAudioTimeSeconds: Double): ClavierSinglePitchReference {
        require(frameAudioTimeSeconds.isFinite() && frameAudioTimeSeconds >= 0)
        val inputOrigin = checkNotNull(origin) { "旁证尚未收到真实PCM" }
        val latest = history.descendingIterator().asSequence().firstOrNull {
            it.endSample / SAMPLE_RATE.toDouble() <= frameAudioTimeSeconds
        }
        if (latest == null) {
            val status = if (frameAudioTimeSeconds < (inputOrigin + WINDOW_SAMPLES) / SAMPLE_RATE.toDouble())
                ClavierReferenceStatus.WARMING_UP else ClavierReferenceStatus.NO_ALIGNED_WINDOW
            return ClavierSinglePitchReference(status, inputOrigin, frameAudioTimeSeconds)
        }
        val endSeconds = latest.endSample / SAMPLE_RATE.toDouble()
        val age = frameAudioTimeSeconds - endSeconds
        if (age > HOP_SAMPLES / SAMPLE_RATE.toDouble()) {
            return ClavierSinglePitchReference(ClavierReferenceStatus.NO_ALIGNED_WINDOW, inputOrigin, frameAudioTimeSeconds)
        }
        return ClavierSinglePitchReference(
            if (latest.error == null) ClavierReferenceStatus.ALIGNED else ClavierReferenceStatus.ERROR,
            inputOrigin, frameAudioTimeSeconds, latest.endSample - WINDOW_SAMPLES, latest.endSample,
            (latest.endSample - WINDOW_SAMPLES) / SAMPLE_RATE.toDouble(), endSeconds, age,
            latest.computeMs, latest.result?.fits, latest.result?.best, latest.error,
        )
    }

    private fun analyzeWindow() {
        repeat(WINDOW_SAMPLES) { scratch[it] = ring[(nextWrite + it) % WINDOW_SAMPLES] }
        val started = System.nanoTime()
        var result: ClavierWindowResult? = null
        var error: String? = null
        try {
            result = analyzer.analyze(scratch)
        } catch (failure: Exception) {
            error = failure.stackTraceToString().take(MAX_ERROR_CHARS)
        } catch (failure: LinkageError) {
            error = failure.stackTraceToString().take(MAX_ERROR_CHARS)
        }
        history.addLast(Window(checkNotNull(origin) + received, result,
            (System.nanoTime() - started) / 1_000_000.0, error))
        while (history.size > MAX_HISTORY_WINDOWS) history.removeFirst()
        windowCount++
    }

    private data class Window(val endSample: Long, val result: ClavierWindowResult?,
        val computeMs: Double, val error: String?)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val WINDOW_SAMPLES = 16_384
        const val HOP_SAMPLES = 2_048
        const val A4_HZ = 440.0
        // 2.73 seconds covers the model's 1.12-second initial lookback plus chunk scheduling.
        internal const val MAX_HISTORY_WINDOWS = 64
        private const val MAX_ERROR_CHARS = 4096
    }
}
