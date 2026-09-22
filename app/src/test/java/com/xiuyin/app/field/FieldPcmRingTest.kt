package com.xiuyin.app.field

import org.junit.Assert.*
import org.junit.Test

class FieldPcmRingTest {
    @Test fun retainsOnlyLatestFiveSecondsAndCopiesSamples() {
        val ring = FieldPcmRing(10)
        val samples = ShortArray(70) { it.toShort() }
        ring.append(samples, samples.size, 0)
        samples.fill(-1)
        assertEquals(20L, ring.retainedStart())
        assertNull(ring.snapshot(19, 30))
        assertArrayEquals(ShortArray(50) { (20 + it).toShort() }, ring.snapshot(20, 70)!!.samples)
    }
    @Test fun discontinuityErasesOldStreamAndClearRemovesAudio() {
        val ring = FieldPcmRing(10)
        ring.append(ShortArray(30) { 12 }, 30, 0)
        assertFalse(ring.append(ShortArray(10) { 24 }, 10, 0))
        assertNull(ring.snapshot(0, 30))
        assertArrayEquals(ShortArray(10) { 24 }, ring.snapshot(0, 10)!!.samples)
        ring.clear(); assertNull(ring.snapshot(0, 10)); assertNull(ring.endSample())
    }
    @Test fun futureAndInvalidInputCannotProduceFabricatedClip() {
        val ring = FieldPcmRing(10)
        assertThrows(IllegalArgumentException::class.java) { ring.append(shortArrayOf(1), 2, 0) }
        ring.append(shortArrayOf(1, 2), 2, 0)
        assertNull(ring.snapshot(0, 3)); assertNull(ring.snapshot(1, 1))
    }
    @Test fun threeSecondsBeforeAndOneAndHalfAfterFitWithoutSavingWholeStream() {
        val ring = FieldPcmRing(10)
        ring.append(ShortArray(100) { it.toShort() }, 100, 0)
        val event = 80L
        val clip = ring.snapshot(event - 30, event + 15)!!
        assertEquals(45, clip.samples.size); assertEquals(50L, clip.firstSample)
    }
}