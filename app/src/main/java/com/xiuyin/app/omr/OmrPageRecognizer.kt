package com.xiuyin.app.omr

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap -> real segmentation -> five-line regions -> genuine model tokens. Never uploads. */
internal class OmrPageRecognizer(private val models: NativeOmrModels) {
    fun recognize(bitmap: Bitmap, page: Int, progress: (String) -> Unit): JSONObject {
        val width = bitmap.width
        val height = bitmap.height
        require(width >= 160 && height >= 160 && width.toLong() * height <= 8_000_000L) {
            "谱页需要至少 160×160，且不超过 800 万像素；请按页或谱表裁剪，原件未更改"
        }
        val gray = grayscale(bitmap)
        val enhanced = clahe(gray, width, height)
        val labels = segment(enhanced, width, height, progress)
        val regions = detectStaffs(labels, width, height, gray)
        val staffs = JSONArray()
        regions.forEachIndexed { index, region ->
            NativeOmrModels.checkCancelled()
            progress("第 ${page + 1} 页：识别谱表 ${index + 1}/${regions.size}")
            val dewarp = OmrStaffDewarp.planFor(region)
            val first = models.recognizeStaff(canvasForRegion(gray, width, region, dewarp))
            val firstScore = sequenceScore(first)
            val needsSecond = !first.getBoolean("eos") || lowConfidence(first)
            var selected = first
            var second: JSONObject? = null
            if (needsSecond) {
                progress("第 ${page + 1} 页：复核低置信谱表 ${index + 1}/${regions.size}")
                // Independent inference with local contrast enhancement, not a copy of first tokens.
                second = models.recognizeStaff(canvasForRegion(enhanced, width, region, dewarp))
                if (second.getBoolean("eos") && (!first.getBoolean("eos") || sequenceScore(second) > firstScore)) selected = second
            }
            val disagreement = second != null && tokenSignature(first) != tokenSignature(second)
            val staff = JSONObject()
                .put("index", index).put("page", page)
                .put("sourceBox", JSONArray(region.box.toList()))
                .put("sourceBoxPrecision", "STAFF_REGION_NOT_SYMBOL")
                .put("lines", JSONArray(region.lines.toList()))
                .put("spacing", region.spacing)
                .put("staffDetectionMethod", region.detectionMethod)
                .put("imageLineEvidence", region.imageLineEvidence?.let { evidence ->
                    JSONObject().put("source", "IMMUTABLE_ORIGINAL_GRAYSCALE_HORIZONTAL_RUNS")
                        .put("modelMaskModified", false).put("sourceImageModified", false)
                        .put("minimumDarkRunPixels", evidence.minimumDarkRunPixels)
                        .put("maximumGray", 220).put("minimumVerticalContrast", 20).put("verticalProbeDistance", 3)
                        .put("minimumRequiredModelLines", 4).put("minimumModelLineCoverage", .65)
                        .put("minimumImageBandCoverage", .80).put("minimumBandSpanCoverage", .85)
                        .put("minimumImagePixelCoverage", .80).put("maximumGapRule", "max(2*minimumDarkRunPixels,ceil(6*spacing))")
                        .put("imageBandCoverage", JSONArray(evidence.imageBandCoverage))
                        .put("imagePixelCoverage", JSONArray(evidence.imagePixelCoverage))
                        .put("longestImageGapPixels", JSONArray(evidence.longestImageGapPixels))
                        .put("imageSpanPixels", evidence.imageSpanPixels).put("maximumGapPixels", evidence.maximumGapPixels)
                        .put("modelLineCoverage", JSONArray(evidence.modelLineCoverage))
                } ?: JSONObject.NULL)
                .put("geometryTransform", geometryDiagnostics(region, dewarp))
                .put("tokens", selected.getJSONArray("tokens"))
                .put("eos", selected.getBoolean("eos"))
                .put("sequenceTruncated", selected.getBoolean("sequenceTruncated"))
                .put("firstPass", first).put("secondPass", second ?: JSONObject.NULL)
                .put("secondPassDisagreement", disagreement)
                .put("selectedPass", if (selected === first) 1 else 2)
                .put("reviewStatus", "NEEDS_REVIEW")
            staffs.put(staff)
        }
        return JSONObject().put("page", page).put("imageWidth", width).put("imageHeight", height)
            .put("staffs", staffs).put("model", "Andromr-segnet308-transformer414-int8")
            .put("confidenceCalibrated", false).put("sourceBoxPrecision", "STAFF_REGION_NOT_SYMBOL")
            .put("status", if ((0 until staffs.length()).all { staffs.getJSONObject(it).getBoolean("eos") }) "INFERENCE_FINISHED" else "INFERENCE_INCOMPLETE")
    }

    private fun segment(gray: IntArray, width: Int, height: Int, progress: (String) -> Unit): ByteArray {
        val votes = Array(6) { ByteArray(width * height) }
        fun starts(length: Int): List<Int> = (0..max(0, length - 320) + 159 step 160)
            .map { min(it, max(0, length - 320)) }.distinct()
        val xs = starts(width)
        val ys = starts(height)
        var index = 0
        val patch = FloatArray(320 * 320 * 3)
        ys.forEach { y -> xs.forEach { x ->
            NativeOmrModels.checkCancelled()
            patch.fill(255f)
            val dh = min(320, height - y)
            val dw = min(320, width - x)
            for (py in 0 until dh) for (px in 0 until dw) {
                val value = gray[(y + py) * width + x + px].toFloat()
                val p = py * 320 + px
                patch[p] = value; patch[320 * 320 + p] = value; patch[2 * 320 * 320 + p] = value
            }
            val result = models.segment(patch)
            for (py in 0 until dh) for (px in 0 until dw) {
                val label = result[py * 320 + px].toInt()
                check(label in 0..5) { "分割模型返回未知类别 $label" }
                val p = (y + py) * width + x + px
                votes[label][p] = (votes[label][p] + 1).toByte()
            }
            index++
            if (index == 1 || index % 10 == 0) progress("按原图分块识别：$index/${xs.size * ys.size}")
        } }
        return ByteArray(width * height) { p ->
            var label = 0
            for (c in 1..5) if (votes[c][p] > votes[label][p]) label = c
            label.toByte()
        }
    }

    private fun detectStaffs(labels: ByteArray, width: Int, height: Int, originalGray: IntArray): List<OmrStaffDetector.Region> =
        OmrStaffDetector.detect(labels, width, height, originalGray)

    private fun canvasForRegion(gray: IntArray, width: Int, region: OmrStaffDetector.Region,
        dewarp: OmrStaffDewarp.Plan?): FloatArray =
        OmrStaffDewarp.canvasOrLegacy(gray, width, region.box, dewarp) { canvas(gray, width, region.box) }

    private fun geometryDiagnostics(region: OmrStaffDetector.Region, dewarp: OmrStaffDewarp.Plan?): JSONObject =
        JSONObject().put("method", when {
            dewarp == null -> "LEGACY_CROP_RESIZE_UNCHANGED"
            dewarp.isIdentity -> "IDENTITY_TRAJECTORY_NO_RESAMPLE"
            else -> "MEASURED_STAFF_VERTICAL_DEWARP_V1"
        }).put("coordinateSystem", "ORIGINAL_PAGE_PIXEL_CENTERS")
            .put("sourceImageModified", false)
            .put("sourceBox", JSONArray(region.box.toList()))
            .put("sourceBoxPrecision", "ORIGINAL_STAFF_REGION_NOT_SYMBOL")
            .put("sameGeometryForBothPasses", true)
            .put("targetCenterY", dewarp?.targetCenterY ?: JSONObject.NULL)
            .put("inverseMap", if (dewarp != null) "sourceX=outputX;sourceY=outputY+interpolateMeasuredCenter(outputX)-targetCenterY" else JSONObject.NULL)
            .put("interpolation", if (dewarp != null) "LINEAR_TRAJECTORY_BILINEAR_PIXEL" else JSONObject.NULL)
            .put("trajectoryEnds", if (dewarp != null) "NEAREST_MEASURED_ENDPOINT" else JSONObject.NULL)
            .put("outsideSourceBox", if (dewarp != null && !dewarp.isIdentity) "WHITE_255" else "LEGACY_CLAMP")
            .put("trajectorySource", if (dewarp != null) "SEGMENTATION_CLASS_4_FIVE_LINE_TRACKS" else JSONObject.NULL)
            .put("observations", JSONArray(region.trajectory.map { point ->
                JSONObject().put("bandLeft", point.bandLeft).put("bandWidth", point.bandWidth)
                    .put("x", point.x).put("centerY", point.centerY)
                    .put("matchedLines", JSONArray(point.matchedLines.map { it ?: JSONObject.NULL }))
                    .put("usedForInterpolation", point.matchedLines.count { it != null } >= 3)
            })).put("interpolationPoints", JSONArray(dewarp?.points?.map { point ->
                JSONObject().put("x", point.x).put("centerY", point.centerY)
            } ?: emptyList<JSONObject>()))

    private fun canvas(gray: IntArray, width: Int, box: IntArray): FloatArray {
        val cropWidth = box[2] - box[0]; val cropHeight = box[3] - box[1]
        require(cropWidth > 0 && cropHeight > 0) { "无效谱表范围" }
        val scale = min(1280.0 / cropWidth, 256.0 / cropHeight)
        val newWidth = max(1, (cropWidth * scale).toInt()); val newHeight = max(1, (cropHeight * scale).toInt())
        val offset = (256 - newHeight) / 2
        val output = FloatArray(256 * 1280) { ((1 - .7931) / .1738).toFloat() }
        for (y in 0 until newHeight) for (x in 0 until newWidth) {
            val fx = (x + .5) * cropWidth / newWidth - .5
            val fy = (y + .5) * cropHeight / newHeight - .5
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            val ax = fx - x0; val ay = fy - y0
            fun pixel(dx: Int, dy: Int): Int = gray[(box[1] + (y0 + dy).coerceIn(0, cropHeight - 1)) * width + box[0] + (x0 + dx).coerceIn(0, cropWidth - 1)]
            val value = ((1 - ay) * ((1 - ax) * pixel(0, 0) + ax * pixel(1, 0)) + ay * ((1 - ax) * pixel(0, 1) + ax * pixel(1, 1))).roundToInt()
            output[(offset + y) * 1280 + x] = ((value / 255.0 - .7931) / .1738).toFloat()
        }
        return output
    }

    private fun grayscale(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val color = pixels[i]; val alpha = Color.alpha(color) / 255.0
            pixels[i] = (alpha * (.299 * Color.red(color) + .587 * Color.green(color) + .114 * Color.blue(color)) + (1 - alpha) * 255).roundToInt().coerceIn(0, 255)
        }
        return pixels
    }

    /** CLAHE clipLimit=1, 8x8 tiles, reflected boundary and interpolated lookup tables. */
    private fun clahe(gray: IntArray, width: Int, height: Int): IntArray {
        val tileW = ceil(width / 8.0).toInt(); val tileH = ceil(height / 8.0).toInt()
        val tileArea = tileW * tileH; val clip = max(1, tileArea / 256)
        fun reflect(p: Int, limit: Int): Int = if (p < limit) p else (2 * limit - 2 - p).coerceAtLeast(0)
        val tables = Array(64) { IntArray(256) }
        for (ty in 0..7) for (tx in 0..7) {
            val hist = IntArray(256)
            for (y in 0 until tileH) for (x in 0 until tileW) hist[gray[reflect(ty * tileH + y, height) * width + reflect(tx * tileW + x, width)]]++
            var clipped = 0
            for (v in 0..255) if (hist[v] > clip) { clipped += hist[v] - clip; hist[v] = clip }
            val batch = clipped / 256; var residual = clipped - batch * 256
            for (v in 0..255) hist[v] += batch
            if (residual > 0) {
                val step = max(1, 256 / residual); var v = 0
                while (v < 256 && residual > 0) { hist[v]++; residual--; v += step }
            }
            var cumulative = 0
            for (v in 0..255) { cumulative += hist[v]; tables[ty * 8 + tx][v] = (cumulative * 255.0 / tileArea).roundToInt().coerceIn(0, 255) }
        }
        return IntArray(gray.size) { index ->
            val x = index % width; val y = index / width
            val fx = x.toDouble() / tileW - .5; val fy = y.toDouble() / tileH - .5
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt(); val ax = fx - x0; val ay = fy - y0
            fun lookup(dx: Int, dy: Int) = tables[(y0 + dy).coerceIn(0, 7) * 8 + (x0 + dx).coerceIn(0, 7)][gray[index]]
            ((1 - ay) * ((1 - ax) * lookup(0, 0) + ax * lookup(1, 0)) + ay * ((1 - ax) * lookup(0, 1) + ax * lookup(1, 1))).roundToInt().coerceIn(0, 255)
        }
    }

    private fun lowConfidence(result: JSONObject): Boolean {
        val tokens = result.getJSONArray("tokens")
        if (tokens.length() == 0) return true
        return (0 until tokens.length()).any {
            val token = tokens.getJSONObject(it); val scores = token.getJSONObject("modelScores")
            scores.getDouble("rhythm") < .80 || (token.getString("rhythm").startsWith("note_") &&
                (scores.getDouble("pitch") < .80 || scores.getDouble("lift") < .70))
        }
    }
    private fun sequenceScore(result: JSONObject): Double {
        val tokens = result.getJSONArray("tokens")
        if (tokens.length() == 0) return Double.NEGATIVE_INFINITY
        var sum = 0.0; var count = 0
        for (i in 0 until tokens.length()) {
            val token = tokens.getJSONObject(i); val scores = token.getJSONObject("modelScores")
            for (head in listOf("rhythm", "pitch", "lift")) { sum += ln(max(1e-12, scores.getDouble(head))); count++ }
        }
        return sum / count
    }
    private fun tokenSignature(result: JSONObject): String = result.getJSONArray("tokens").let { tokens ->
        (0 until tokens.length()).joinToString("|") { index -> tokens.getJSONObject(index).let { "${it.getString("rhythm")}:${it.getString("pitch")}:${it.getString("lift")}" } }
    }
}
