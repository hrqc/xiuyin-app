package com.xiuyin.app.omr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic vertical correction from the five-line segmentation trajectory only.
 * The geometric principle matches local Andromr homr/staff_dewarping.py:287-317:
 * measured staff-center displacement is mapped to a horizontal line. This is an
 * inverse vertical resampler, not its full piecewise-affine implementation. No
 * rhythm, pitch, meter, expected score or recognition scores enter this transform.
 */
internal object OmrStaffDewarp {
    data class Point(val x: Double, val centerY: Double)

    data class Plan(val targetCenterY: Double, val points: List<Point>) {
        init {
            require(targetCenterY.isFinite() && points.size >= 2) { "谱线纠偏缺少真实轨迹" }
            require(points.all { it.x.isFinite() && it.centerY.isFinite() } &&
                points.zipWithNext().all { (a, b) -> a.x < b.x }) { "谱线纠偏轨迹坐标无效" }
        }

        val isIdentity: Boolean get() = points.all { it.centerY == targetCenterY }

        /** Original page pixel coordinates; outside measured x coverage use the nearest endpoint. */
        fun sourceY(x: Double, outputY: Double): Double = outputY + centerAt(x) - targetCenterY

        fun centerAt(x: Double): Double {
            if (x <= points.first().x) return points.first().centerY
            if (x >= points.last().x) return points.last().centerY
            val right = points.indexOfFirst { it.x >= x }
            val a = points[right - 1]; val b = points[right]
            val fraction = (x - a.x) / (b.x - a.x)
            return a.centerY + fraction * (b.centerY - a.centerY)
        }
    }

    fun planFor(region: OmrStaffDetector.Region): Plan? {
        // Keep the established legacy crop and canvas path entirely unchanged.
        if (region.detectionMethod != "LOCAL_FIVE_LINE_TRACKS") return null
        val points = region.trajectory.filter { it.matchedLines.count { row -> row != null } >= 3 }
            .map { Point(it.x, it.centerY) }
        return Plan(region.lines[2], points)
    }

    /** Return the original legacy result directly, including its exact float bits. */
    fun canvasOrLegacy(gray: IntArray, width: Int, box: IntArray, plan: Plan?, legacy: () -> FloatArray): FloatArray =
        if (plan == null || plan.isIdentity) legacy() else canvas(gray, width, box, plan)

    /**
     * Bilinear lookup in original image coordinates. Out-of-crop pixels are white,
     * never replicated staff strokes or pixels from a neighboring staff. The
     * immutable page and its sourceBox remain the provenance for every sample.
     */
    fun sample(gray: IntArray, width: Int, box: IntArray, x: Double, y: Double): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val ax = x - x0; val ay = y - y0
        fun pixel(dx: Int, dy: Int): Int {
            val sx = x0 + dx; val sy = y0 + dy
            return if (sx >= box[0] && sx < box[2] && sy >= box[1] && sy < box[3]) gray[sy * width + sx] else 255
        }
        return (1 - ay) * ((1 - ax) * pixel(0, 0) + ax * pixel(1, 0)) +
            ay * ((1 - ax) * pixel(0, 1) + ax * pixel(1, 1))
    }

    fun canvas(gray: IntArray, width: Int, box: IntArray, plan: Plan): FloatArray {
        require(width > 0 && gray.size % width == 0 && box.size == 4 &&
            box[0] >= 0 && box[1] >= 0 && box[2] <= width && box[3] <= gray.size / width) { "谱线纠偏原图范围无效" }
        val cropWidth = box[2] - box[0]; val cropHeight = box[3] - box[1]
        require(cropWidth > 0 && cropHeight > 0) { "无效谱表范围" }
        val scale = min(1280.0 / cropWidth, 256.0 / cropHeight)
        val newWidth = max(1, (cropWidth * scale).toInt()); val newHeight = max(1, (cropHeight * scale).toInt())
        val offset = (256 - newHeight) / 2
        val output = FloatArray(256 * 1280) { ((1 - .7931) / .1738).toFloat() }
        for (x in 0 until newWidth) {
            val sx = box[0] + (x + .5) * cropWidth / newWidth - .5
            val displacement = plan.centerAt(sx) - plan.targetCenterY
            for (y in 0 until newHeight) {
                val sy = box[1] + (y + .5) * cropHeight / newHeight - .5 + displacement
                val value = sample(gray, width, box, sx, sy).roundToInt().coerceIn(0, 255)
                output[(offset + y) * 1280 + x] = ((value / 255.0 - .7931) / .1738).toFloat()
            }
        }
        return output
    }
}
