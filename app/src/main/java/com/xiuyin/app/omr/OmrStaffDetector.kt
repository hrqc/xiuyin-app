package com.xiuyin.app.omr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Geometry from the real segmentation mask only: no pitches, meter, score or expected staff count. */
internal object OmrStaffDetector {
    data class Region(
        val lines: DoubleArray, val spacing: Double, val lineBox: IntArray, var box: IntArray,
        val detectionMethod: String,
        val trajectory: List<TrajectoryPoint> = emptyList(),
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
    )

    private data class LocalDetection(val regions: List<Region>, val candidates: List<Track>)

    fun detect(labels: ByteArray, width: Int, height: Int): List<Region> {
        require(width > 0 && height > 0 && width.toLong() * height == labels.size.toLong()) { "谱线分割尺寸无效" }
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
        require(chosen.isNotEmpty()) { "未找到可信五线谱区域；请使用清晰、近水平谱页，不能生成假音符" }
        require(chosen.size <= 64) { "单页谱表过多，请按区域分开导入" }
        pad(chosen, width, height)
        return chosen
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
        val tracks = seeds.mapNotNull { track(it, bands) }
            .sortedWith(compareByDescending<Track> { it.minimumLineCoverage }.thenByDescending { it.quality }.thenByDescending { it.coverage })
        val distinct = mutableListOf<Track>()
        tracks.forEach { candidate ->
            if (distinct.all { abs(candidate.center - it.center) > min(candidate.spacing, it.spacing) * 3 }) distinct += candidate
        }
        val regions = distinct.sortedBy { it.center }.mapNotNull { candidate ->
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
            if (right - left < width * .35 || bottom < top) null else {
                val box = intArrayOf(left, top, right + 1, bottom + 1)
                Region(DoubleArray(5) { candidate.center + (it - 2) * candidate.spacing }, candidate.spacing,
                    box, box.copyOf(), "LOCAL_FIVE_LINE_TRACKS",
                    bands.mapIndexed { index, band ->
                        TrajectoryPoint(band.x, band.width, band.x + (band.width - 1) / 2.0,
                            candidate.centers[index], candidate.matches[index].rows.toList())
                    })
            }
        }
        return LocalDetection(regions, tracks)
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
        if (coverage < .65 || minimumLineCoverage < .38) return null
        return Track(median(centers.toList()), seed.spacing, centers, matches, minimumLineCoverage,
            coverage, matches.sumOf { it.support } / bands.size)
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
