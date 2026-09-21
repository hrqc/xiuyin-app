package com.xiuyin.app.calibration

import org.junit.Assert.*
import org.junit.Test

class PcmRingBufferTest {
    @Test fun keepsOnlyTheLatestFiveSecondsInOrderWithoutPadding() {
        val ring = PcmRingBuffer(8000)
        val input = ShortArray(64000) { (it % 30000).toShort() }
        ring.append(input, 30000, 0)
        ring.append(input.copyOfRange(30000, 64000), 34000, 30000)
        val clip = requireNotNull(ring.snapshot())
        assertEquals(24000L, clip.firstSample); assertEquals(40000, clip.samples.size)
        assertArrayEquals(input.copyOfRange(24000, 64000), clip.samples)
        assertEquals(5.0, clip.durationSeconds, 0.0)
    }
    @Test fun aGapOrRestartDiscardsOldAudioRatherThanFabricatingContinuity() {
        val ring = PcmRingBuffer(8000)
        ring.append(shortArrayOf(1, 2, 3), 3, 0)
        ring.append(shortArrayOf(5, 6), 2, 100)
        assertArrayEquals(shortArrayOf(5, 6), ring.snapshot()!!.samples)
        assertEquals(100L, ring.snapshot()!!.firstSample)
        ring.append(shortArrayOf(8), 1, 0)
        assertArrayEquals(shortArrayOf(8), ring.snapshot()!!.samples)
    }
    @Test fun snapshotsAndInputArraysCannotMutateRingAndClearErasesIt() {
        val ring = PcmRingBuffer(8000)
        val input = shortArrayOf(1, 2); ring.append(input, 2, 0); input[0] = 9
        val first = ring.snapshot()!!; first.samples[0] = 8
        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot()!!.samples)
        ring.clear(); assertNull(ring.snapshot())
    }
    @Test fun eventTimeMustRemainInsideActualRetainedSamples() {
        val ring = PcmRingBuffer(8000)
        val input = ShortArray(48000) { it.toShort() }
        ring.append(input, input.size, 0)
        assertNull(ring.snapshotAt(0.99))
        val first = requireNotNull(ring.snapshotAt(1.0))
        assertEquals(8000L, first.firstSample)
        assertArrayEquals(input.copyOfRange(8000, 48000), first.samples)
        assertNotNull(ring.snapshotAt(5.999))
        assertNull(ring.snapshotAt(6.0))
        ring.append(ShortArray(8000), 8000, 48000)
        assertNull(ring.snapshotAt(1.5))
        assertNotNull(ring.snapshotAt(2.0))
    }
    @Test fun missingInvalidFutureAndClearedEventAudioCannotCreateACaseClip() {
        val ring = PcmRingBuffer(8000)
        ring.append(ShortArray(8000), 8000, 0)
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.0, Double.MAX_VALUE)
            .forEach { assertNull(ring.snapshotAt(it)) }
        assertNotNull(ring.snapshotAt(0.5))
        ring.clear()
        assertNull(ring.snapshotAt(0.5))
    }
    @Test fun eventLookupUsesActualStreamSampleOriginAndReturnsAnIndependentCopy() {
        val ring = PcmRingBuffer(8000)
        ring.append(ShortArray(8000) { 7 }, 8000, 80000)
        assertNull(ring.snapshotAt(0.5))
        assertNull(ring.snapshotAt(9.999))
        val clip = requireNotNull(ring.snapshotAt(10.5))
        assertEquals(80000L, clip.firstSample)
        clip.samples[0] = 9
        assertEquals(7.toShort(), ring.snapshotAt(10.5)!!.samples[0])
        assertNull(ring.snapshotAt(11.0))
    }
    @Test fun oversizedSingleFramePreservesItsLastSamples() {
        val ring = PcmRingBuffer(8000, 1)
        val input = ShortArray(10000) { it.toShort() }; ring.append(input, input.size, 50)
        assertArrayEquals(input.takeLast(8000).toShortArray(), ring.snapshot()!!.samples)
        assertEquals(2050L, ring.snapshot()!!.firstSample)
        assertThrows(IllegalArgumentException::class.java) { ring.append(input, 10001, 0) }
        assertThrows(IllegalArgumentException::class.java) { ring.append(input, 1, Long.MAX_VALUE) }
    }
}
