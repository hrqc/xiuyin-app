package com.xiuyin.app.omr

import org.junit.Assert.*
import org.junit.Test

class OmrImageSizingTest {
    @Test fun existingPagesAndExactBudgetNeedNoSampling() {
        listOf(1079 to 1507, 1079 to 1462, 1440 to 1080, 4000 to 2000).forEach { (w, h) ->
            val plan = OmrImageSizing.plan(w, h)
            assertEquals(1, plan.sampleSize)
            assertEquals(w, plan.maximumDecodedWidth)
            assertEquals(h, plan.maximumDecodedHeight)
        }
    }

    @Test fun ordinaryTwelveMegapixelPhonePhotoIsAdmittedWithoutFullDecode() {
        val plan = OmrImageSizing.plan(4032, 3024)
        assertEquals(2, plan.sampleSize)
        assertEquals(2016, plan.maximumDecodedWidth)
        assertEquals(1512, plan.maximumDecodedHeight)
    }

    @Test fun fortyEightMegapixelPhotoUsesBoundedDecode() {
        val plan = OmrImageSizing.plan(8000, 6000)
        assertEquals(4, plan.sampleSize)
        assertEquals(2000, plan.maximumDecodedWidth)
        assertEquals(1500, plan.maximumDecodedHeight)
    }

    @Test fun oddDimensionsUseCeilingToAvoidUnderestimatingDecodedMemory() {
        val plan = OmrImageSizing.plan(4001, 2001)
        assertEquals(2, plan.sampleSize)
        assertEquals(2001, plan.maximumDecodedWidth)
        assertEquals(1001, plan.maximumDecodedHeight)
    }

    @Test fun extremeHeaderDimensionsCannotOverflowPixelCalculation() {
        val plan = OmrImageSizing.plan(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(plan.maximumDecodedWidth.toLong() * plan.maximumDecodedHeight <= 8_000_000L)
        assertTrue(plan.sampleSize > 1)
    }

    @Test fun tinyAndNarrowPagesFailWithoutInventingPixels() {
        listOf(0 to 1000, 159 to 160, 160 to Int.MAX_VALUE).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { OmrImageSizing.plan(w, h) }
        }
    }
}
