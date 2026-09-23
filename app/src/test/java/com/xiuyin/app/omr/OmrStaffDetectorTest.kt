package com.xiuyin.app.omr

import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic class masks test geometry only; explicitly staged real masks have separate provenance. */
class OmrStaffDetectorTest {
    @Test fun aStraightStaffKeepsTheEstablishedCropExactly() {
        val mask = draw(1000, 500, listOf(200)) { 0 }
        val region = OmrStaffDetector.detect(mask, 1000, 500).single()
        assertEquals("LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS", region.detectionMethod)
        assertArrayEquals(intArrayOf(40, 160, 990, 281), region.box)
        assertEquals(10.0, region.spacing, 0.0)
    }

    @Test fun slopedFiveLineTrajectoriesRecoverSparseStaffsWithoutExpectedStaffCountInput() {
        val starts = listOf(90, 220, 370, 500, 650, 790)
        val mask = draw(1000, 900, starts) { x -> ((x - 60) * 12.0 / 910).roundToInt() }
        val regions = OmrStaffDetector.detect(mask, 1000, 900)
        assertEquals(6, regions.size)
        assertTrue(regions.all { it.spacing in 9.0..11.0 })
        regions.zip(starts).forEach { (region, top) -> assertEquals(top + 26.0, region.lines[2], 3.0) }
        assertTrue(regions.all { it.box[0] >= 0 && it.box[1] >= 0 && it.box[2] <= 1000 && it.box[3] <= 900 })
    }

    @Test fun curvedLinesStayOneStaffInsteadOfMultiplyingProjectionPeaks() {
        val mask = draw(1000, 500, listOf(200)) { x -> (7 * sin((x - 60) * Math.PI / 910)).roundToInt() }
        val regions = OmrStaffDetector.detect(mask, 1000, 500)
        assertEquals(1, regions.size)
        assertEquals(10.0, regions.single().spacing, 1.0)
    }

    @Test fun shortLedgerLinesCannotReplaceTheFiveLongStaffLines() {
        val mask = draw(1000, 500, listOf(220)) { 0 }
        for (line in listOf(180, 190, 200, 210)) for (x in 150 until 260)
            for (y in line - 1..line + 1) mask[y * 1000 + x] = 4
        val region = OmrStaffDetector.detect(mask, 1000, 500).single()
        assertEquals(240.0, region.lines[2], 1.0)
    }

    @Test fun aLongLineBelowTheStaffCannotDisplaceAnIndependentlySupportedTopLine() {
        val mask = draw(1000, 500, listOf(200)) { 0 }
        // Explicit geometry fixture: the true top line is half obscured, while a
        // sixth long line below the staff gives the shifted candidate more support.
        for (x in 60 until 500) for (y in 199..201) mask[y * 1000 + x] = 0
        for (x in 60 until 970) for (y in 249..251) mask[y * 1000 + x] = 4
        val region = OmrStaffDetector.detect(mask, 1000, 500).single()
        assertEquals("LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS", region.detectionMethod)
        assertEquals(220.0, region.lines[2], 0.0)
        assertArrayEquals(intArrayOf(40, 160, 990, 281), region.box)
    }

    @Test fun fourLinesAndBlankMasksNeverInventAFifthLine() {
        val mask = ByteArray(1000 * 500)
        for (line in 0..3) for (x in 60 until 970) mask[(200 + line * 10) * 1000 + x] = 4
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, 1000, 500) }
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(ByteArray(mask.size), 1000, 500) }
    }

    @Test fun realProblemMaskRecoversAllSixVisibleStaffsAndRejectsHalfSpacing() {
        val mask = realMask("labels.bin", "1995ab0a0c46caac9b7603cff27800e7f37b8d926a121ea7cdd8458ed61dadf1")
        val regions = OmrStaffDetector.detect(mask, 1440, 1080)
        assertEquals(6, regions.size)
        assertTrue(regions.all { it.detectionMethod == "LOCAL_FIVE_LINE_TRACKS" })
        assertTrue(regions.all { it.spacing in 9.5..11.5 })
        regions.zip(listOf(230.0, 360.0, 539.0, 670.0, 838.0, 967.0)).forEach { (region, center) ->
            assertEquals(center, region.lines[2], 3.0)
        }
        assertTrue("首系统稀疏下谱必须保留", regions[1].lineBox[1] <= 333 && regions[1].lineBox[3] >= 384)
        assertTrue("第二系统上谱不能把加线误当真实五线", regions[2].lines[2] > 535)
    }

    @Test fun realPreviouslyUsedMasksKeepAllTwentyPriorCropsExactly() {
        val fixtures = listOf(
            Triple("legacy-page-58.labels.bin", 1462, "1744d756763ff8794c954815c029fcfff43c8556eafe17dad95f96919ab00209"),
            Triple("legacy-page-59.labels.bin", 1507, "3c0a988daeb97d6a591f7e124d3ea852eb9540cd91b7ddb7daf9d9920c5e4255"),
        )
        // Frozen output of the previous detector on these exact real segmentation bytes.
        // The older page-58 mask differs by one right-edge pixel from a later integrated run.
        val boxes = listOf(
            listOf(intArrayOf(141,98,1076,208), intArrayOf(142,210,1077,318), intArrayOf(75,370,1077,471), intArrayOf(80,471,1077,573),
                intArrayOf(75,624,1077,726), intArrayOf(80,726,1077,829), intArrayOf(78,874,1077,978), intArrayOf(78,989,1077,1097),
                intArrayOf(74,1156,1078,1260), intArrayOf(78,1260,1077,1364)),
            listOf(intArrayOf(30,89,1033,193), intArrayOf(29,193,1033,298), intArrayOf(27,334,1034,444), intArrayOf(27,445,1034,554),
                intArrayOf(30,616,1034,722), intArrayOf(30,722,1034,828), intArrayOf(30,891,1034,997), intArrayOf(29,997,1034,1103),
                intArrayOf(26,1168,1036,1277), intArrayOf(31,1277,1035,1386)),
        )
        fixtures.forEachIndexed { index, (name, height, hash) ->
            val regions = OmrStaffDetector.detect(realMask(name, hash), 1079, height)
            assertEquals(10, regions.size)
            assertTrue("$name: " + regions.joinToString { "center=${it.lines[2]} spacing=${it.spacing} method=${it.detectionMethod}" },
                regions.all { it.detectionMethod == "LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS" })
            regions.forEachIndexed { staff, region -> assertArrayEquals("$name staff $staff", boxes[index][staff], region.box) }
        }
    }

    private fun draw(width: Int, height: Int, starts: List<Int>, drift: (Int) -> Int): ByteArray {
        val mask = ByteArray(width * height)
        starts.forEach { top -> for (line in 0..4) for (x in 60 until width - 30) {
            val center = top + line * 10 + drift(x)
            for (y in center - 1..center + 1) if (y in 0 until height) mask[y * width + x] = 4
        } }
        return mask
    }

    private fun realMask(name: String, expectedHash: String): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "PRODUCT.md").isFile }
        val directory = root?.let { File(it, "reports/fieldtest2-development/segmentation-baseline") }
        val source = directory?.let { File(it, name) }
        assumeTrue("本机未提供带来源哈希的真实分割证据；本项未验证", source?.isFile == true)
        val bytes = requireNotNull(source).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expectedHash, hash)
        return bytes
    }
}
