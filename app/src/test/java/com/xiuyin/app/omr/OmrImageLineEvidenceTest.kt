package com.xiuyin.app.omr

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** TEST DATA geometry counterexamples; real original-image derivatives are separately hashed. */
class OmrImageLineEvidenceTest {
    @Test fun aRealFifthImageLineAndFourModelLinesRecoverWithoutChangingEitherInput() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..4)
        val originalMask = mask.copyOf(); val originalGray = gray.copyOf()
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height) }
        val region = OmrStaffDetector.detect(mask, width, height, gray).single()
        assertEquals(220.0, region.lines[2], .5)
        assertEquals("IMAGE_LINES_WITH_FOUR_MODEL_ANCHORS", region.detectionMethod)
        val evidence = requireNotNull(region.imageLineEvidence)
        assertEquals(4, evidence.modelLineCoverage.count { it >= .65 })
        assertEquals(0.0, evidence.modelLineCoverage[4], 0.0)
        assertTrue(evidence.imageBandCoverage.all { it >= .8 })
        assertTrue(evidence.imagePixelCoverage.all { it >= .8 })
        assertTrue(evidence.longestImageGapPixels.all { it <= evidence.maximumGapPixels })
        assertArrayEquals(originalMask, mask); assertArrayEquals(originalGray, gray)
    }

    @Test fun fourLinesInBothSourcesNeverImplyAFifthLine() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..3)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun fiveImageLinesWithoutEnoughModelAgreementDoNotCreateAStaff() {
        val (mask, gray) = fixture(modelLines = 0..2, imageLines = 0..4)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun ModelLinesElsewhereCannotValidateAnUnrelatedImageGrid() {
        val (mask, _) = fixture(modelLines = 0..3, imageLines = 0..4)
        val gray = IntArray(width * height) { 255 }
        draw(gray, 350, 0..4, 80, 940)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun ADarkPageBorderHasNoNarrowLineContrast() {
        val (mask, _) = fixture(modelLines = 0..3, imageLines = 0..4)
        assertThrows(IllegalArgumentException::class.java) {
            OmrStaffDetector.detect(mask, width, height, IntArray(mask.size))
        }
    }

    @Test fun aTinyFifthImageFragmentDoesNotRepairFourModelLines() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..3)
        draw(gray, 200, 4..4, 80, 120)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun aPartlyMissingFifthImageLineNeedsContinuousSupportAcrossTheStaff() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..3)
        draw(gray, 200, 4..4, 80, 500)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun repeatedFortyPixelDashesCannotMasqueradeAsContinuousImageStaffLines() {
        val mask = ByteArray(width * height); val gray = IntArray(width * height) { 255 }
        for (line in 0..4) for (x in 80 until 940) if ((x - 80) % 80 < 40) {
            for (y in 199 + line * 10..201 + line * 10) {
                gray[y * width + x] = 50
                if (line < 4) mask[y * width + x] = 4
            }
        }
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun aLongGapInOneImageLineIsRejectedEvenWithHighOverallPixelCoverage() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..4)
        for (x in 420 until 500) for (y in 239..241) gray[y * width + x] = 255
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun StaggeredTextUnderlinesDoNotSupplyACommonFiveLineSpan() {
        val (mask, _) = fixture(modelLines = 0..3, imageLines = 0..3)
        val gray = IntArray(width * height) { 255 }
        for (line in 0..4) draw(gray, 200, line..line, 80 + line * 100, 540 + line * 100)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    @Test fun actualS02ImageRecoversAllEightRowsAndPreservesEveryOldCrop() {
        val mask = staged("s02.labels.bin", "509e7187ed8fc7eff8f4ea644b82d32cde5eed5f1bde9296cfa8d36bdc971b9e")
        val gray = gray("s02.gray.bin", "a7d45b526e921e529a51ee4f95274ca510adfa6a23793cf557fbcabdf29b77ea")
        val old = OmrStaffDetector.detect(mask, 1080, 1620)
        assertEquals(5, old.size)
        val recovered = OmrStaffDetector.detect(mask, 1080, 1620, gray)
        assertEquals(8, recovered.size)
        assertSameCrops(old, recovered)
        val added = recovered.filter { it.imageLineEvidence != null }
        assertEquals(3, added.size)
        listOf(497.5, 639.0, 780.5).zip(added).forEach { (expected, region) -> assertEquals(expected, region.lines[2], 1.0) }
        assertTrue(added.all { requireNotNull(it.imageLineEvidence).modelLineCoverage.count { n -> n >= .65 } >= 4 })
        // The added crops retain the observed model-line left extent so the clef is not cropped off.
        assertTrue(added.all { it.box[0] <= 50 && it.box[2] >= 1030 })
    }

    @Test fun actualCanonHostImagesRecoverTheMissingBassStaffAndKeepTheShortFinalSystem() {
        val cases = listOf(
            Triple("s03p1", "14c1af45ee7ab6912420470ed7456d57c7b02474838abc4c1cd5bc0a432d4a12", "7d7306f8fca90e586c7f381e7f0c221191a72d85b51b41679cb77ee042ed47e0"),
            Triple("s03p2", "3311dfed7e8b1f6297bc02472bf8231636fe270dc5cd8052a2c92eef1db62d3a", "9a3b59b88dc45605839c03bda0dd6940260046a436b05b9cce47fbcf9bcc4bd7"),
        )
        cases.forEachIndexed { page, (name, maskSha, graySha) ->
            val mask = staged("$name.labels.bin", maskSha)
            val old = OmrStaffDetector.detect(mask, 1785, 2523)
            val result = OmrStaffDetector.detect(mask, 1785, 2523, gray("$name.gray.bin", graySha))
            assertEquals(if (page == 0) 12 else 8, result.size)
            assertSameCrops(old, result)
            assertEquals(if (page == 0) 1 else 0, result.count { it.imageLineEvidence != null })
        }
    }

    @Test fun actualS01AndTwentyLegacyCropsRemainUnchangedWithOriginalPixelEvidenceEnabled() {
        val cases = listOf(
            Case("labels.bin", "1995ab0a0c46caac9b7603cff27800e7f37b8d926a121ea7cdd8458ed61dadf1", "s01.gray.bin", "3690d06e897e78abf90d363001cf90e6a8d6a66e2ba26001a7e8009a49b91980", 1440, 1080, 6),
            Case("legacy-page-58.labels.bin", "1744d756763ff8794c954815c029fcfff43c8556eafe17dad95f96919ab00209", "legacy58.gray.bin", "46f9645ab58fe3f9fa14a44d65e1fc88c33e8d67d2d1507f03ceaa06a50ea8b0", 1079, 1462, 10),
            Case("legacy-page-59.labels.bin", "3c0a988daeb97d6a591f7e124d3ea852eb9540cd91b7ddb7daf9d9920c5e4255", "legacy59.gray.bin", "af921a1434f440179785d0a921062e76a3d153da79b54f15f162d4365134a851", 1079, 1507, 10),
        )
        cases.forEach { case ->
            val mask = read("reports/fieldtest2-development/segmentation-baseline/${case.mask}", case.maskSha)
            val old = OmrStaffDetector.detect(mask, case.width, case.height)
            val current = OmrStaffDetector.detect(mask, case.width, case.height, gray(case.gray, case.graySha))
            assertEquals(case.count, current.size)
            assertSameCrops(old, current)
            assertTrue(current.none { it.imageLineEvidence != null })
        }
    }

    @Test fun invalidOriginalPixelArraysFailVisibly() {
        val (mask, gray) = fixture(modelLines = 0..3, imageLines = 0..4)
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray.copyOf(1)) }
        gray[0] = -1
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDetector.detect(mask, width, height, gray) }
    }

    private fun assertSameCrops(old: List<OmrStaffDetector.Region>, current: List<OmrStaffDetector.Region>) {
        old.forEach { before ->
            val after = current.single { kotlin.math.abs(it.lines[2] - before.lines[2]) < .01 }
            assertArrayEquals(before.box, after.box)
            assertEquals(before.detectionMethod, after.detectionMethod)
        }
    }
    private fun fixture(modelLines: IntRange, imageLines: IntRange): Pair<ByteArray, IntArray> {
        val mask = ByteArray(width * height); val gray = IntArray(width * height) { 255 }
        for (line in modelLines) for (x in 80 until 940) for (y in 199 + line * 10..201 + line * 10) mask[y * width + x] = 4
        draw(gray, 200, imageLines, 80, 940)
        return mask to gray
    }
    private fun draw(gray: IntArray, top: Int, lines: IntRange, left: Int, right: Int) {
        for (line in lines) for (x in left until right) for (y in top + line * 10 - 1..top + line * 10 + 1) gray[y * width + x] = 50
    }
    private fun staged(name: String, sha: String) = read("reports/fieldtest4-development/omr-stage/$name", sha)
    private fun gray(name: String, sha: String) = staged(name, sha).map { it.toInt() and 255 }.toIntArray()
    private fun read(path: String, sha: String): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "PRODUCT.md").isFile }
        val file = root?.let { File(it, path) }
        assumeTrue("未提供来源哈希匹配的真实图像证据；本项未验证", file?.isFile == true)
        val bytes = requireNotNull(file).readBytes()
        assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        return bytes
    }
    private data class Case(val mask: String, val maskSha: String, val gray: String, val graySha: String,
        val width: Int, val height: Int, val count: Int)
    private val width = 1000
    private val height = 500
}
