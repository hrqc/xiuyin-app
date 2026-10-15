package com.xiuyin.app.pitch

import com.xiuyin.app.pitch.clavier.tuning.KeyIdentifier
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** SYNTHETIC TEST DATA. Clock, streaming and unchanged-upstream checks, not piano accuracy. */
class ClavierSinglePitchReferenceTest {
    @Test fun warmupNullAndNoFutureOrExpiredEvidence() {
        val processor = ClavierSinglePitchReferenceProcessor()
        processor.accept(ShortArray(WINDOW - 1), firstInputSample = 0)
        assertEquals(0L, processor.windowCount)
        assertEquals(ClavierReferenceStatus.WARMING_UP, processor.forFrame((WINDOW - 1) / RATE).status)
        processor.accept(shortArrayOf(0), firstInputSample = (WINDOW - 1).toLong())
        assertEquals(1L, processor.windowCount)
        val zero = processor.forFrame(WINDOW / RATE)
        assertEquals(ClavierReferenceStatus.ALIGNED, zero.status)
        assertNull(zero.fits)
        assertNull(zero.best)
        assertNull(zero.error)
        assertEquals(0.0, zero.ageSeconds!!, 0.0)
        assertEquals(ClavierReferenceStatus.WARMING_UP, processor.forFrame((WINDOW - .01) / RATE).status)
        val stale = processor.forFrame((WINDOW + HOP + .01) / RATE)
        assertEquals(ClavierReferenceStatus.NO_ALIGNED_WINDOW, stale.status)
        assertNull(stale.fits)
        assertNull(stale.best)
        assertNull(stale.windowEndSampleExclusive)
    }

    @Test fun rawPcmPartitioningPreservesEveryFitAndOriginalBest() {
        val pcm = harmonicPcm(WINDOW + HOP * 3 + 127)
        val whole = ClavierSinglePitchReferenceProcessor().also { it.accept(pcm) }
        val split = ClavierSinglePitchReferenceProcessor()
        val chunks = intArrayOf(1, 37, 4096, 17, 2048, 913)
        var offset = 0
        var chunkIndex = 0
        while (offset < pcm.size) {
            val count = minOf(chunks[chunkIndex++ % chunks.size], pcm.size - offset)
            split.accept(pcm.copyOfRange(offset, offset + count), firstInputSample = offset.toLong())
            offset += count
        }
        assertEquals(4L, whole.windowCount)
        assertEquals(whole.windowCount, split.windowCount)
        val original = KeyIdentifier(48000, WINDOW)
        val floats = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        repeat(4) { index ->
            val end = WINDOW + index * HOP
            val a = whole.forFrame(end / RATE)
            val b = split.forFrame(end / RATE)
            assertEquals(a.copy(computeMs = null), b.copy(computeMs = null))
            val expectedFits = original.fits(floats, 440.0, index * HOP)
            val expectedBest = expectedFits?.let(original::best)
            assertEquals(expectedFits?.size, a.fits?.size)
            assertEquals(88, a.fits!!.size)
            expectedFits!!.zip(a.fits).forEach { (expected, actual) ->
                assertEquals(expected.startMidi, actual.startMidi)
                assertEquals(expected.midi, actual.midi)
                assertEquals(expected.f1, actual.f1Hz, 0.0)
                assertEquals(expected.f1 / sqrt(1 + expected.b), actual.f0HzDerived, 0.0)
                assertEquals(expected.b, actual.b, 0.0)
                assertEquals(expected.score, actual.score, 0.0)
                assertEquals(expected.explained, actual.explained, 0.0)
                assertEquals(expected.unexplained, actual.unexplained, 0.0)
                assertEquals(expected.gaps, actual.gaps, 0.0)
                assertEquals(expected.matched, actual.matchedPartials)
            }
            assertEquals(expectedBest?.midi, a.best?.midi)
            assertEquals(expectedBest?.startMidi, a.best?.startMidi)
            assertEquals(expectedBest?.score, a.best?.score)
        }
    }

    @Test fun nonzeroInputOriginUsesAbsoluteSampleClockAndLatestPastWindow() {
        val origin = 987_654L
        val processor = ClavierSinglePitchReferenceProcessor()
        processor.accept(ShortArray(WINDOW + HOP * 2), firstInputSample = origin)
        val at = (origin + WINDOW + HOP + 100) / RATE
        val record = processor.forFrame(at)
        assertEquals(ClavierReferenceStatus.ALIGNED, record.status)
        assertEquals(origin, record.inputOriginSample)
        assertEquals(origin + HOP, record.windowStartSample)
        assertEquals(origin + WINDOW + HOP, record.windowEndSampleExclusive)
        assertTrue(record.windowEndSeconds!! <= at)
        assertEquals(100 / RATE, record.ageSeconds!!, 1e-12)
        assertEquals(ClavierReferenceStatus.WARMING_UP, processor.forFrame((origin + WINDOW - 1) / RATE).status)
    }

    @Test fun windowsAndScratchStayBoundedAndIncompleteTailIsNotEvaluated() {
        var analyzed = 0
        var scratchIdentity: FloatArray? = null
        val processor = ClavierSinglePitchReferenceProcessor(ClavierWindowAnalyzer { samples ->
            assertEquals(WINDOW, samples.size)
            if (scratchIdentity == null) scratchIdentity = samples else assertSame(scratchIdentity, samples)
            analyzed++
            ClavierWindowResult(null, null)
        })
        processor.accept(ShortArray(WINDOW + HOP * 99 + HOP - 1))
        assertEquals(100, analyzed)
        assertEquals(100L, processor.windowCount)
        assertEquals(ClavierSinglePitchReferenceProcessor.MAX_HISTORY_WINDOWS, processor.retainedWindowCount)
        assertEquals(ClavierReferenceStatus.NO_ALIGNED_WINDOW, processor.forFrame(WINDOW / RATE).status)
    }

    @Test fun analyzerFailureIsExplicitErrorNeverOrdinaryNoPitch() {
        val processor = ClavierSinglePitchReferenceProcessor(ClavierWindowAnalyzer {
            throw IllegalStateException("SYNTHETIC injected detector failure")
        })
        processor.accept(ShortArray(WINDOW))
        val record = processor.forFrame(WINDOW / RATE)
        assertEquals(ClavierReferenceStatus.ERROR, record.status)
        assertTrue(record.error!!.contains("SYNTHETIC injected detector failure"))
        assertNull(record.fits)
        assertNull(record.best)
        assertTrue(record.computeMs!! >= 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun discontinuousInputIsRejectedInsteadOfChangingTheOrigin() {
        val processor = ClavierSinglePitchReferenceProcessor()
        processor.accept(ShortArray(10), firstInputSample = 123)
        processor.accept(ShortArray(10), firstInputSample = 134)
    }

    private fun harmonicPcm(size: Int) = ShortArray(size) { index ->
        val phase = 2.0 * PI * 261.625565 * index / RATE
        (900 * sin(phase) + 9000 * sin(phase * 2) + 1500 * sin(phase * 3)).toInt().toShort()
    }
    companion object {
        private const val WINDOW = 16384
        private const val HOP = 2048
        private const val RATE = 48000.0
    }
}
