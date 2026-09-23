package com.xiuyin.app.omr

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Deterministic geometry fixtures, not OMR/music-accuracy acceptance or simulated AI results. */
class OmrStaffDewarpTest {
    @Test fun straightTrajectoryIsIdentityAndPreservesEveryLegacyFloatBit() {
        val plan = OmrStaffDewarp.planFor(region(listOf(point(0.0, 5.0), point(7.0, 5.0))))
        assertNotNull(plan)
        assertTrue(requireNotNull(plan).isIdentity)
        for (x in listOf(-20.0, 0.0, 3.25, 7.0, 50.0))
            assertEquals(2.75, plan.sourceY(x, 2.75), 0.0)
        val frozen = floatArrayOf(Float.fromBits(0x80000000.toInt()), Float.fromBits(0x7fc00001), .125f)
        var calls = 0
        val output = OmrStaffDewarp.canvasOrLegacy(IntArray(64), 8, intArrayOf(0, 0, 8, 8), plan) { calls++; frozen }
        assertEquals(1, calls)
        assertSame("Identity must return the unchanged old canvas, including NaN and signed-zero bits", frozen, output)
    }

    @Test fun legacyRegionsNeverEnterDewarpEvenIfTheyCarryOtherTrajectoryData() {
        val distorted = region(listOf(point(0.0, 3.0), point(7.0, 7.0)))
        for (method in listOf("LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS", "LEGACY_PROJECTION_NEEDS_REVIEW")) {
            val plan = OmrStaffDewarp.planFor(distorted.copy(detectionMethod = method))
            assertNull(plan)
            val frozen = floatArrayOf(.25f, -.75f)
            var calls = 0
            val actual = OmrStaffDewarp.canvasOrLegacy(IntArray(64), 8, distorted.box, plan) { calls++; frozen }
            assertSame(frozen, actual)
            assertEquals(1, calls)
        }
    }

    @Test fun slopeInverseSamplesTheMeasuredLineAndDoesNotReverseTheShift() {
        val plan = OmrStaffDewarp.Plan(5.0, listOf(OmrStaffDewarp.Point(0.0, 3.0), OmrStaffDewarp.Point(4.0, 7.0)))
        val pixels = IntArray(10 * 10) { p -> p / 10 * 10 + p % 10 }
        for (x in 0..4) {
            val sy = plan.sourceY(x.toDouble(), 5.0)
            assertEquals(3.0 + x, sy, 0.0)
            assertEquals((3 + x) * 10.0 + x,
                OmrStaffDewarp.sample(pixels, 10, intArrayOf(0, 0, 10, 10), x.toDouble(), sy), 0.0)
        }
        assertEquals(4.5, plan.sourceY(1.5, 5.0), 0.0)
        assertEquals(46.5, OmrStaffDewarp.sample(pixels, 10, intArrayOf(0, 0, 10, 10), 1.5, 4.5), 0.0)
    }

    @Test fun curvedTrajectoryInterpolatesActualPointsAndUsesNearestMeasuredEndpoints() {
        val plan = OmrStaffDewarp.Plan(5.0, listOf(
            OmrStaffDewarp.Point(1.0, 4.0), OmrStaffDewarp.Point(3.0, 7.0), OmrStaffDewarp.Point(5.0, 4.0)))
        for ((x, center) in listOf(-8.0 to 4.0, 1.0 to 4.0, 2.0 to 5.5, 3.0 to 7.0,
            4.0 to 5.5, 5.0 to 4.0, 30.0 to 4.0))
            assertEquals(center, plan.sourceY(x, 5.0), 0.0)
        // Moving the target row by one moves source sampling by one, preserving spacing.
        assertEquals(8.0, plan.sourceY(3.0, 6.0), 0.0)
    }

    @Test fun unsupportedBandsRemainDiagnosticsButCannotDriveInterpolation() {
        val source = region(listOf(point(0.0, 3.0), point(2.0, 999.0, 2), point(4.0, 7.0)))
        val plan = requireNotNull(OmrStaffDewarp.planFor(source))
        assertEquals(3, source.trajectory.size)
        assertEquals(2, plan.points.size)
        assertEquals(5.0, plan.sourceY(2.0, 5.0), 0.0)
        assertEquals(999.0, source.trajectory[1].centerY, 0.0)
    }

    @Test fun outsideCropSamplesAreWhiteAndEdgesBlendWithoutRepeatingStrokes() {
        val pixels = IntArray(10 * 10)
        val box = intArrayOf(2, 2, 8, 8)
        assertEquals(0.0, OmrStaffDewarp.sample(pixels, 10, box, 2.0, 2.0), 0.0)
        assertEquals(255.0, OmrStaffDewarp.sample(pixels, 10, box, 1.0, 3.0), 0.0)
        assertEquals(255.0, OmrStaffDewarp.sample(pixels, 10, box, 8.0, 3.0), 0.0)
        assertEquals(127.5, OmrStaffDewarp.sample(pixels, 10, box, 1.5, 3.0), 0.0)
        assertEquals(127.5, OmrStaffDewarp.sample(pixels, 10, box, 7.5, 3.0), 0.0)
        assertEquals(191.25, OmrStaffDewarp.sample(pixels, 10, box, 1.5, 1.5), 0.0)
        val plan = OmrStaffDewarp.Plan(5.0, listOf(OmrStaffDewarp.Point(2.0, 3.0), OmrStaffDewarp.Point(7.0, 7.0)))
        assertEquals(255.0, OmrStaffDewarp.sample(pixels, 10, box, 2.0, plan.sourceY(2.0, 2.0)), 0.0)
    }

    @Test fun canvasUsesOneDeterministicGeometryAndLeavesBothSourceArraysUntouched() {
        // Width 1280 and height 256 make output samples exact original pixel centers.
        val box = intArrayOf(0, 0, 1280, 256)
        val gray = IntArray(1280 * 256) { it / 1280 }
        val enhanced = IntArray(gray.size) { 255 - gray[it] }
        val before = gray.copyOf(); val enhancedBefore = enhanced.copyOf()
        val plan = OmrStaffDewarp.Plan(100.0, listOf(OmrStaffDewarp.Point(0.0, 98.0), OmrStaffDewarp.Point(1279.0, 102.0)))
        var legacyCalls = 0
        val actual = OmrStaffDewarp.canvasOrLegacy(gray, 1280, box, plan) { legacyCalls++; floatArrayOf() }
        val second = OmrStaffDewarp.canvasOrLegacy(enhanced, 1280, box, plan) { legacyCalls++; floatArrayOf() }
        assertEquals(0, legacyCalls)
        assertEquals(((98 / 255.0 - .7931) / .1738).toFloat(), actual[100 * 1280], 0f)
        assertEquals(((102 / 255.0 - .7931) / .1738).toFloat(), actual[100 * 1280 + 1279], 0f)
        assertEquals((((255 - 98) / 255.0 - .7931) / .1738).toFloat(), second[100 * 1280], 0f)
        assertEquals(((1 - .7931) / .1738).toFloat(), actual[0], 0f)
        assertArrayEquals(before, gray)
        assertArrayEquals(enhancedBefore, enhanced)
        assertArrayEquals(actual, OmrStaffDewarp.canvas(gray, 1280, box, plan), 0f)
    }

    @Test fun invalidOrMissingMeasuredTrajectoryFailsVisibly() {
        assertThrows(IllegalArgumentException::class.java) { OmrStaffDewarp.planFor(region(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            OmrStaffDewarp.Plan(5.0, listOf(OmrStaffDewarp.Point(1.0, 5.0), OmrStaffDewarp.Point(1.0, 6.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OmrStaffDewarp.Plan(Double.NaN, listOf(OmrStaffDewarp.Point(0.0, 5.0), OmrStaffDewarp.Point(1.0, 6.0)))
        }
    }

    @Test fun trueProblemSegmentationCarriesMeasuredTracksThroughTheInverseTransform() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "PRODUCT.md").isFile }
        val source = root?.let { File(it, "reports/fieldtest2-development/segmentation-baseline/labels.bin") }
        assumeTrue("Real mask absent: measured geometry regression is unverified", source?.isFile == true)
        val bytes = requireNotNull(source).readBytes()
        assertEquals("1995ab0a0c46caac9b7603cff27800e7f37b8d926a121ea7cdd8458ed61dadf1",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        val regions = OmrStaffDetector.detect(bytes, 1440, 1080)
        assertEquals(6, regions.size)
        regions.forEach { region ->
            val plan = requireNotNull(OmrStaffDewarp.planFor(region))
            assertFalse(plan.isIdentity)
            assertTrue(region.trajectory.size >= plan.points.size)
            region.trajectory.filter { it.matchedLines.count { y -> y != null } >= 3 }.forEach { point ->
                assertEquals(point.bandLeft + (point.bandWidth - 1) / 2.0, point.x, 0.0)
                assertEquals(point.centerY, plan.sourceY(point.x, plan.targetCenterY), 1e-10)
                assertTrue(point.matchedLines.filterNotNull().all { it.isFinite() })
            }
        }
    }

    private fun region(points: List<OmrStaffDetector.TrajectoryPoint>) = OmrStaffDetector.Region(
        doubleArrayOf(1.0, 3.0, 5.0, 7.0, 9.0), 2.0, intArrayOf(0, 0, 8, 10),
        intArrayOf(0, 0, 8, 10), "LOCAL_FIVE_LINE_TRACKS", points)

    private fun point(x: Double, center: Double, matches: Int = 5) =
        OmrStaffDetector.TrajectoryPoint(x.toInt(), 1, x, center,
            List(5) { if (it < matches) center + (it - 2) * 2 else null })
}
