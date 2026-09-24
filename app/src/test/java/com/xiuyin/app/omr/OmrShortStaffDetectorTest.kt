package com.xiuyin.app.omr

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Geometry-only fixtures and a separately hashed host-model diagnostic; never note accuracy. */
class OmrShortStaffDetectorTest {
    @Test fun aShortFinalSystemRetainsBothMeasuredStaffsWithoutAnExpectedCountInput() {
        val mask = ByteArray(1785 * 900)
        draw(mask, 1785, 160, 0..4, 80, 1660)
        draw(mask, 1785, 340, 0..4, 80, 1660)
        draw(mask, 1785, 600, 0..4, 80, 640)
        draw(mask, 1785, 760, 0..4, 80, 640)
        val regions = OmrStaffDetector.detect(mask, 1785, 900)
        assertEquals(4, regions.size)
        assertTrue(regions.take(2).all { it.detectionMethod == "LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS" })
        assertTrue(regions.takeLast(2).all { it.detectionMethod == "LOCAL_SHORT_FIVE_LINE_TRACKS" })
        assertEquals(listOf(180.0, 360.0, 620.0, 780.0), regions.map { it.lines[2] })
        assertTrue(regions.takeLast(2).all { it.lineBox[2] <= 640 })
    }

    @Test fun aNearbyShortStaffNeverTrimsAnEstablishedLegacyCrop() {
        val width = 1785; val height = 500
        val oldMask = ByteArray(width * height)
        draw(oldMask, width, 200, 0..4, 80, 1660)
        val old = OmrStaffDetector.detect(oldMask, width, height).single()
        // Frozen original detector crop, before any short staff is added.
        assertArrayEquals(intArrayOf(60, 160, 1680, 281), old.box)
        val combined = oldMask.copyOf()
        draw(combined, width, 260, 0..4, 80, 640)
        val result = OmrStaffDetector.detect(combined, width, height)
        assertEquals(2, result.size)
        assertArrayEquals(old.box, result.first().box)
        assertEquals("LOCAL_SHORT_FIVE_LINE_TRACKS", result.last().detectionMethod)
    }

    @Test fun fiveDashedModelLinesCannotCreateAShortStaffEvenWhenOriginalPixelsAgree() {
        // TEST DATA: every supported band has five peaks, but each line is only
        // 280/520 observed columns. Both public detection inputs must reject it.
        val width = 1785; val height = 500
        val mask = ByteArray(width * height)
        draw(mask, width, 200, 0..4, 80, 640)
        for (x in 80 until 640) if ((x - 80) % 80 >= 40) for (y in 0 until height) mask[y * width + x] = 0
        val gray = IntArray(mask.size) { if (mask[it].toInt() == 4) 50 else 255 }
        val originalMask = mask.copyOf(); val originalGray = gray.copyOf()
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height) }
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
        assertArrayEquals(originalMask, mask); assertArrayEquals(originalGray, gray)
    }

    @Test fun fiveContinuousModelLinesKeepTheSameShortCropWithOrWithoutOriginalPixels() {
        // TEST DATA positive control for the same short width and line spacing.
        val width = 1785; val height = 500
        val mask = ByteArray(width * height)
        draw(mask, width, 200, 0..4, 80, 640)
        val gray = IntArray(mask.size) { if (mask[it].toInt() == 4) 50 else 255 }
        val modelOnly = OmrStaffDetector.detect(mask, width, height).single()
        val withImage = OmrStaffDetector.detect(mask, width, height, gray).single()
        assertEquals("LOCAL_SHORT_FIVE_LINE_TRACKS", modelOnly.detectionMethod)
        assertEquals(modelOnly.detectionMethod, withImage.detectionMethod)
        assertArrayEquals(intArrayOf(80, 199, 640, 242), modelOnly.lineBox)
        assertArrayEquals(intArrayOf(60, 159, 660, 282), modelOnly.box)
        assertArrayEquals(modelOnly.box, withImage.box)
    }

    @Test fun fourBroadLinesAndATinyFifthFragmentDoNotCreateAShortStaff() {
        val mask = ByteArray(1785 * 500)
        draw(mask, 1785, 200, 0..3, 80, 640)
        draw(mask, 1785, 200, 4..4, 80, 180)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, 1785, 500) }
    }

    @Test fun fiveShortLedgerLinesCannotBecomeASecondStaff() {
        val mask = ByteArray(1785 * 500)
        draw(mask, 1785, 280, 0..4, 80, 1660)
        draw(mask, 1785, 160, 0..4, 350, 490)
        val region = OmrStaffDetector.detect(mask, 1785, 500).single()
        assertEquals(300.0, region.lines[2], 0.0)
        assertEquals("LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS", region.detectionMethod)
    }

    @Test fun actualCanonHostMaskRestoresTwoShortFinalStaffsAndKeepsAllSixOlderCrops() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "PRODUCT.md").isFile }
        val file = root?.let { File(it, "reports/fieldtest4-development/omr-stage/s03p2.labels.bin") }
        assumeTrue("未提供带来源哈希的真实主机模型掩模；短谱行验证未运行", file?.isFile == true)
        val mask = requireNotNull(file).readBytes()
        assertEquals("3311dfed7e8b1f6297bc02472bf8231636fe270dc5cd8052a2c92eef1db62d3a",
            MessageDigest.getInstance("SHA-256").digest(mask).joinToString("") { "%02x".format(it.toInt() and 255) })
        // Actual PDFium raster + unchanged real segnet: provenance is in the adjacent manifests.
        // These are frozen previous detector crops, not musical reference or synthetic line data.
        val previous = listOf(intArrayOf(92,311,1686,470), intArrayOf(93,474,1687,633),
            intArrayOf(92,664,1687,822), intArrayOf(92,826,1687,985),
            intArrayOf(92,1016,1686,1174), intArrayOf(92,1179,1686,1337))
        val regions = OmrStaffDetector.detect(mask, 1785, 2523)
        assertEquals(8, regions.size)
        previous.forEachIndexed { index, box -> assertArrayEquals("old staff $index", box, regions[index].box) }
        assertTrue(regions.takeLast(2).all { it.detectionMethod == "LOCAL_SHORT_FIVE_LINE_TRACKS" })
        assertEquals(1458.0, regions[6].lines[2], 1.0)
        assertEquals(1621.0, regions[7].lines[2], 1.0)
        assertTrue(regions.takeLast(2).all { it.box[2] < 700 })
        // Frozen real short crops survive the model-pixel continuity gate. The
        // original-image run filter would incorrectly reject the upper short staff.
        assertArrayEquals(intArrayOf(86, 1377, 666, 1540), regions[6].box)
        assertArrayEquals(intArrayOf(92, 1540, 666, 1702), regions[7].box)
    }

    private fun draw(mask: ByteArray, width: Int, top: Int, lines: IntRange, left: Int, right: Int) {
        for (line in lines) for (x in left until right) for (y in top + line * 10 - 1..top + line * 10 + 1)
            mask[y * width + x] = 4
    }
}
