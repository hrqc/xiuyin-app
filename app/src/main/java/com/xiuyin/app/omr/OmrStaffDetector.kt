package com.xiuyin.app.omr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Measured staff geometry only: model lines plus optional original pixels, never score contents or an expected count. */
internal object OmrStaffDetector {
    data class Region(
        val lines: DoubleArray, val spacing: Double, val lineBox: IntArray, var box: IntArray,
        val detectionMethod: String,
        val trajectory: List<TrajectoryPoint> = emptyList(),
        val imageLineEvidence: ImageLineEvidence? = null,
    )
    data class ImageLineEvidence(
        val imageBandCoverage: List<Double>, val modelLineCoverage: List<Double>,
        val imagePixelCoverage: List<Double>, val longestImageGapPixels: List<Int>,
        val imageSpanPixels: Int, val minimumDarkRunPixels: Int, val maximumGapPixels: Int,
    )
    data class TrajectoryPoint(
        val bandLeft: Int, val bandWidth: Int, val x: Double, val centerY: Double,
        val matchedLines: List<Double?>,
    )
    private data class Peak(val y: Double, val support: Double)
    private data class Band(val x: Int, val width: Int, val peaks: List<Peak>)
    private data class Seed(val band: Int, val center: Double, val spacing: Double)
    private data class Match(val rows: List<Double?>, val support: Double) {
        val count: Int get() = rows.count { it != null }
    }
    private data class Track(
        val center: Double, val spacing: Double, val centers: DoubleArray, val matches: List<Match>,
        val minimumLineCoverage: Double, val coverage: Double, val quality: Double,
        val shortStaff: Boolean = false,
    )

    private data class LocalDetection(val regions: List<Region>, val candidates: List<Track>,
        val shortStaffs: List<Region> = emptyList())

    fun detect(labels: ByteArray, width: Int, height: Int): List<Region> = detect(labels, width, height, null)

    fun detect(labels: ByteArray, width: Int, height: Int, originalGray: IntArray?): List<Region> {
        require(width > 0 && height > 0 && width.toLong() * height == labels.size.toLong()) { "谱线分割尺寸无效" }
        require(originalGray == null || originalGray.size == labels.size && originalGray.all { it in 0..255 }) {
            "原图灰度与分割尺寸不一致"
        }
        val legacy = legacyRegions(labels, width, height)
        val local = localRegions(labels, width, height)
        // Keep all independently supported five-line candidates for agreement. A long
        // line below the staff can outscore the correct group when its top line is
        // partly obscured. Agreement must use evidence before competing groups are
        // deduplicated, while retaining the same center/spacing tolerances.
        val compatible = legacy.size == local.regions.size && legacy.all { old ->
            local.candidates.any { candidate ->
                abs(old.lines[2] - candidate.center) <= candidate.spacing * .5 &&
                    abs(old.spacing - candidate.spacing) <= candidate.spacing * .2
            }
        }
        val chosen = when {
            local.regions.isNotEmpty() && compatible -> legacy.map { it.copy(detectionMethod = "LEGACY_CROP_CONFIRMED_BY_LOCAL_TRACKS") }
            local.regions.isNotEmpty() -> {
                require(local.regions.size >= legacy.size) { "局部五线轨迹与全宽检测数量有分歧（${local.regions.size}/${legacy.size}），不能确认谱表完整；请按系统裁剪后重试" }
                local.regions
            }
            else -> legacy
        }
        // The final system may occupy only part of a page. Its five measured lines
        // must pass their own local support checks; do not reduce the page-wide
        // checks or replace previously established crops.
        pad(chosen, width, height)
        val chosenBoxes = chosen.map { it.box.copyOf() }
        val complete = (chosen + local.shortStaffs.filter { candidate ->
            chosen.none { existing -> abs(candidate.lines[2] - existing.lines[2]) <=
                min(candidate.spacing, existing.spacing) * 3 }
        }).sortedBy { it.lines[2] }
        pad(complete, width, height)
        chosen.forEachIndexed { index, region -> region.box = chosenBoxes[index] }
        val measured = originalGray?.let { imageSupportedRegions(labels, it, width, height, complete) }.orEmpty()
        val result = (complete + measured).sortedBy { it.lines[2] }
        require(result.isNotEmpty()) { "未找到可信五线谱区域；请使用清晰、近水平谱页，不能生成假音符" }
        require(result.size <= 64) { "单页谱表过多，请按区域分开导入" }
        // Only new regions receive new padding; do not resample or resize established crops.
        val establishedBoxes = complete.map { it.box.copyOf() }
        pad(result, width, height)
        complete.forEachIndexed { index, region -> region.box = establishedBoxes[index] }
        return result
    }

    /**
     * A segmentation miss does not license an inferred fifth line. Measure actual dark,
     * narrow horizontal runs in the immutable original image, then require five tracked
     * image lines AND at least four spatially agreeing model lines. Never draw pixels
     * into the original image or the model mask, and never use pitch/meter/note counts.
     */
    private fun imageSupportedRegions(labels: ByteArray, gray: IntArray, width: Int, height: Int,
        established: List<Region>): List<Region> {
        val linePixels = ByteArray(labels.size)
        val minimumRun = max(24, width / 50)
        for (y in 3 until height - 3) {
            var start = -1
            for (x in 0..width) {
                val p = y * width + min(x, width - 1)
                val measured = x < width && gray[p] <= 220 &&
                    min(gray[p - 3 * width], gray[p + 3 * width]) - gray[p] >= 20
                if (measured && start < 0) start = x
                if (!measured && start >= 0) {
                    if (x - start >= minimumRun) for (column in start until x) linePixels[y * width + column] = 4
                    start = -1
                }
            }
        }
        val image = localRegions(linePixels, width, height)
        val distinctImage = image.regions + image.shortStaffs.filter { short ->
            image.regions.none { abs(short.lines[2] - it.lines[2]) <= min(short.spacing, it.spacing) * 3 }
        }
        val candidates = distinctImage.filter { candidate ->
            established.none { abs(candidate.lines[2] - it.lines[2]) <= min(candidate.spacing, it.spacing) * 3 }
        }
        return candidates.mapNotNull { candidate ->
            val supported = candidate.trajectory.indices.filter { index ->
                candidate.trajectory[index].matchedLines.count { it != null } >= 3
            }
            if (supported.size < 3) return@mapNotNull null
            val span = supported.first()..supported.last()
            if (supported.size.toDouble() / span.count() < .85) return@mapNotNull null
            val bands = span.map { candidate.trajectory[it] }
            val imageCoverage = (0..4).map { line -> bands.count { it.matchedLines[line] != null }.toDouble() / bands.size }
            if (imageCoverage.any { it < .80 }) return@mapNotNull null
            // A peak in every band is not a continuous line: dashed underlines can
            // satisfy the band histogram. Count actual observed pixels once per x,
            // along the measured trajectory, and separately bound the longest gap.
            val continuity = pixelContinuity(linePixels, width, height, candidate)
            val maximumGap = max(minimumRun * 2, ceil(candidate.spacing * 6).toInt())
            if (continuity.coverage.any { it < .80 } || continuity.longestGaps.any { it > maximumGap })
                return@mapNotNull null
            val support = IntArray(5)
            var left = candidate.lineBox[0]; var right = candidate.lineBox[2]
            var top = candidate.lineBox[1]; var bottom = candidate.lineBox[3]
            val tolerance = max(.8, candidate.spacing * .18)
            bands.forEach { band ->
                for (line in 0..4) {
                    val expected = band.centerY + (line - 2) * candidate.spacing
                    val y0 = max(0, floor(expected - tolerance).toInt())
                    val y1 = min(height - 1, ceil(expected + tolerance).toInt())
                    var columns = 0
                    for (x in band.bandLeft until band.bandLeft + band.bandWidth) {
                        var found = false
                        for (y in y0..y1) if (labels[y * width + x].toInt() == 4) {
                            found = true
                            left = min(left, x); right = max(right, x + 1)
                            top = min(top, y); bottom = max(bottom, y + 1)
                        }
                        if (found) columns++
                    }
                    if (columns >= max(16.0, band.bandWidth * .25)) support[line]++
                }
            }
            val modelCoverage = support.map { it.toDouble() / bands.size }
            if (modelCoverage.count { it >= .65 } < 4) return@mapNotNull null
            val box = intArrayOf(left, top, right, bottom)
            candidate.copy(lineBox = box, box = box.copyOf(), detectionMethod = "IMAGE_LINES_WITH_FOUR_MODEL_ANCHORS",
                imageLineEvidence = ImageLineEvidence(imageCoverage, modelCoverage, continuity.coverage,
                    continuity.longestGaps, continuity.spanPixels, minimumRun, maximumGap))
        }
    }

    private data class PixelContinuity(val coverage: List<Double>, val longestGaps: List<Int>, val spanPixels: Int)
    private fun pixelContinuity(observed: ByteArray, width: Int, height: Int, candidate: Region): PixelContinuity {
        val left = candidate.lineBox[0]; val right = candidate.lineBox[2]
        val span = right - left
        val pixels = IntArray(5); val gaps = IntArray(5); val longest = IntArray(5)
        val trajectory = candidate.trajectory
        var band = 0
        val tolerance = max(.8, candidate.spacing * .18)
        for (x in left until right) {
            while (band + 1 < trajectory.size && trajectory[band + 1].x <= x) band++
            val a = trajectory[band]; val b = trajectory[min(band + 1, trajectory.lastIndex)]
            val weight = if (a.x == b.x) 0.0 else ((x - a.x) / (b.x - a.x)).coerceIn(0.0, 1.0)
            val center = a.centerY + (b.centerY - a.centerY) * weight
            for (line in 0..4) {
                val expected = center + (line - 2) * candidate.spacing
                val y0 = max(0, floor(expected - tolerance).toInt())
                val y1 = min(height - 1, ceil(expected + tolerance).toInt())
                val present = (y0..y1).any { y -> observed[y * width + x].toInt() == 4 }
                if (present) { pixels[line]++; gaps[line] = 0 }
                else { gaps[line]++; longest[line] = max(longest[line], gaps[line]) }
            }
        }
        return PixelContinuity(pixels.map { it.toDouble() / span }, longest.toList(), span)
    }

    /** Retained verbatim geometry calculations keep previously validated inference crops unchanged. */
    private fun legacyRegions(labels: ByteArray, width: Int, height: Int): List<Region> {
        val histogram = IntArray(height)
        for (y in 0 until height) for (x in 0 until width) if (labels[y * width + x].toInt() == 4) histogram[y]++
        val threshold = max(30.0, width * .16)
        val centers = mutableListOf<Double>()
        var y = 0
        while (y < height) {
            if (histogram[y] < threshold) { y++; continue }
            var total = 0L; var weighted = 0L
            while (y < height && histogram[y] >= threshold) { total += histogram[y]; weighted += histogram[y].toLong() * y; y++ }
            centers += weighted.toDouble() / total
        }
        val result = mutableListOf<Region>()
        var i = 0
        while (i + 4 < centers.size) {
            val lines = centers.subList(i, i + 5).toDoubleArray()
            val gaps = (0..3).map { lines[it + 1] - lines[it] }
            val sorted = gaps.sorted(); val space = (sorted[1] + sorted[2]) / 2
            if (space < 3 || space > height / 30.0 || gaps.any { abs(it - space) > max(2.0, space * .28) }) { i++; continue }
            val columns = mutableListOf<Int>()
            for (x in 0 until width) {
                var support = 0
                for (row in max(0, (lines[0] - 2).toInt()) until min(height, (lines[4] + 3).toInt()))
                    if (labels[row * width + x].toInt() == 4) support++
                if (support >= 2) columns += x
            }
            if (columns.size < width * .35) { i++; continue }
            val box = intArrayOf(columns.first(), lines[0].toInt(), columns.last() + 1, (lines[4] + 1).toInt())
            result += Region(lines, space, box, box.copyOf(), "LEGACY_PROJECTION_NEEDS_REVIEW"); i += 5
        }
        return result
    }

    /**
     * A whole-page row projection conflates sloping lines and short ledger lines. Seed five
     * locally parallel lines, then follow each of the five across overlapping narrow bands.
     * Every line needs its own spatial support; a long slur or a few ledger lines cannot
     * replace a missing staff. These support ratios are geometric evidence, not confidence.
     */
    private fun localRegions(labels: ByteArray, width: Int, height: Int): LocalDetection {
        if (width < 80) return LocalDetection(emptyList(), emptyList())
        val bandWidth = min(192, max(80, width / 6))
        val xs = (0..width - bandWidth step bandWidth / 2).toMutableList()
        if (xs.lastOrNull() != width - bandWidth) xs += width - bandWidth
        val bands = xs.map { x -> scanBand(labels, width, height, x, bandWidth) }
        val seeds = mutableListOf<Seed>()
        bands.forEachIndexed { index, band ->
            for (i in 0..band.peaks.size - 5) {
                val lines = band.peaks.subList(i, i + 5).map { it.y }
                val gaps = (0..3).map { lines[it + 1] - lines[it] }
                val spacing = median(gaps)
                if (spacing >= 3 && spacing <= height / 25.0 && gaps.all { abs(it - spacing) <= max(.75, spacing * .18) })
                    seeds += Seed(index, lines[2], spacing)
            }
        }
        require(seeds.size <= 2048) { "谱线候选过多，无法可靠追踪，请按系统裁剪" }
        val allTracks = seeds.mapNotNull { track(it, bands) }
            .sortedWith(compareByDescending<Track> { it.minimumLineCoverage }.thenByDescending { it.quality }.thenByDescending { it.coverage })
        val tracks = allTracks.filterNot { it.shortStaff }
        fun regionsFor(candidates: List<Track>): List<Region> {
            val distinct = mutableListOf<Track>()
            candidates.forEach { candidate ->
                if (distinct.all { abs(candidate.center - it.center) > min(candidate.spacing, it.spacing) * 3 }) distinct += candidate
            }
            return distinct.sortedBy { it.center }.mapNotNull { candidate ->
                var left = width; var right = -1; var top = height; var bottom = -1
                val tolerance = max(.8, candidate.spacing * .18)
                bands.forEachIndexed { index, band ->
                    if (candidate.matches[index].count >= 3) {
                        for (line in -2..2) {
                            val y = candidate.centers[index] + line * candidate.spacing
                            for (row in max(0, floor(y - tolerance).toInt())..min(height - 1, ceil(y + tolerance).toInt())) {
                                for (x in band.x until band.x + band.width) if (labels[row * width + x].toInt() == 4) {
                                    left = min(left, x); right = max(right, x); top = min(top, row); bottom = max(bottom, row)
                                }
                            }
                        }
                    }
                }
                val minimumWidth = if (candidate.shortStaff) max(160.0, candidate.spacing * 24) else width * .35
                if (right - left < minimumWidth || bottom < top) null else {
                    val box = intArrayOf(left, top, right + 1, bottom + 1)
                    val region = Region(DoubleArray(5) { candidate.center + (it - 2) * candidate.spacing }, candidate.spacing,
                        box, box.copyOf(), if (candidate.shortStaff) "LOCAL_SHORT_FIVE_LINE_TRACKS" else "LOCAL_FIVE_LINE_TRACKS",
                        bands.mapIndexed { index, band ->
                            TrajectoryPoint(band.x, band.width, band.x + (band.width - 1) / 2.0,
                                candidate.centers[index], candidate.matches[index].rows.toList())
                        })
                    if (candidate.shortStaff) {
                        // Band peaks alone also accept five dashed underlines. Require
                        // continuous class-4 model pixels, not filtered original-image
                        // runs: real short staffs can have image strokes occluded by notes.
                        val continuity = pixelContinuity(labels, width, height, region)
                        val maximumGap = max(max(24, width / 50) * 2, ceil(candidate.spacing * 6).toInt())
                        if (continuity.coverage.any { it < .80 } || continuity.longestGaps.any { it > maximumGap }) null
                        else region
                    } else region
                }
            }
        }
        return LocalDetection(regionsFor(tracks), tracks, regionsFor(allTracks.filter { it.shortStaff }))
    }

    private fun scanBand(labels: ByteArray, width: Int, height: Int, x: Int, bandWidth: Int): Band {
        val histogram = IntArray(height)
        for (row in 0 until height) for (column in x until x + bandWidth)
            if (labels[row * width + column].toInt() == 4) histogram[row]++
        val threshold = max(16.0, bandWidth * .25)
        val rows = (0 until height).filter { histogram[it] >= threshold }
        val peaks = mutableListOf<Peak>()
        var index = 0
        while (index < rows.size) {
            var last = rows[index]; var total = 0L; var weighted = 0L; var maximum = 0
            do {
                val row = rows[index]
                total += histogram[row]; weighted += row.toLong() * histogram[row]
                maximum = max(maximum, histogram[row]); last = row; index++
            } while (index < rows.size && rows[index] - last <= 2)
            peaks += Peak(weighted.toDouble() / total, maximum.toDouble() / bandWidth)
        }
        return Band(x, bandWidth, peaks)
    }

    private fun track(seed: Seed, bands: List<Band>): Track? {
        val centers = DoubleArray(bands.size) { seed.center }
        val matches = MutableList(bands.size) { Match(List(5) { null }, 0.0) }
        val tolerance = max(.8, seed.spacing * .18)
        matches[seed.band] = match(bands[seed.band], seed.center, seed.spacing, tolerance)
        for (direction in listOf(-1, 1)) {
            var center = seed.center
            var index = seed.band + direction
            while (index in bands.indices) {
                val offsets = linkedSetOf(0.0)
                bands[index].peaks.forEach { peak -> for (line in -2..2) {
                    val shift = peak.y - (center + line * seed.spacing)
                    if (abs(shift) <= seed.spacing * .45) offsets += shift
                } }
                var best = match(bands[index], center, seed.spacing, tolerance)
                var bestShift = 0.0
                for (shift in offsets) {
                    val candidate = match(bands[index], center + shift, seed.spacing, tolerance)
                    if (candidate.count > best.count || (candidate.count == best.count &&
                            candidate.support - abs(shift) * .02 > best.support - abs(bestShift) * .02)) {
                        best = candidate; bestShift = shift
                    }
                }
                if (best.count >= 3) { center += bestShift; matches[index] = best }
                else matches[index] = match(bands[index], center, seed.spacing, tolerance)
                centers[index] = center
                index += direction
            }
        }
        val coverage = matches.count { it.count >= 3 }.toDouble() / bands.size
        val minimumLineCoverage = (0..4).minOf { line -> matches.count { it.rows[line] != null }.toDouble() / bands.size }
        val pageSupported = coverage >= .65 && minimumLineCoverage >= .38
        if (!pageSupported) {
            // Short systems are measured against a contiguous supported span, not
            // against blank page margins. Every one of the five lines must really
            // occur in at least 80% of that span. Four lines never imply a fifth.
            val supported = matches.indices.filter { matches[it].count >= 4 }
            if (supported.size < 3) return null
            val span = supported.first()..supported.last()
            if (supported.size.toDouble() / span.count() < .85) return null
            val measuredWidth = bands[span.last].x + bands[span.last].width - bands[span.first].x
            if (measuredWidth < max(160.0, seed.spacing * 24)) return null
            if ((0..4).any { line -> span.count { matches[it].rows[line] != null }.toDouble() / span.count() < .80 }) return null
        }
        return Track(median(centers.toList()), seed.spacing, centers, matches, minimumLineCoverage,
            coverage, matches.sumOf { it.support } / bands.size, shortStaff = !pageSupported)
    }

    private fun match(band: Band, center: Double, spacing: Double, tolerance: Double): Match {
        var support = 0.0
        val rows = (-2..2).map { line ->
            val expected = center + line * spacing
            val nearest = band.peaks.minByOrNull { abs(it.y - expected) }
            if (nearest != null && abs(nearest.y - expected) <= tolerance) { support += nearest.support; nearest.y } else null
        }
        return Match(rows, support)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun pad(regions: List<Region>, width: Int, height: Int) {
        regions.forEachIndexed { index, staff ->
            val box = staff.lineBox; val spacing = staff.spacing
            val upper = if (index == 0) 0 else (regions[index - 1].lineBox[3] + box[1]) / 2
            val lower = if (index == regions.lastIndex) height else (box[3] + regions[index + 1].lineBox[1]) / 2
            staff.box = intArrayOf(max(0, (box[0] - 2 * spacing).toInt()), max(upper, (box[1] - 4 * spacing).toInt()),
                min(width, (box[2] + 2 * spacing).toInt()), min(lower, (box[3] + 4 * spacing).toInt()))
        }
    }
}
