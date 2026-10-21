package com.xiuyin.app.ui

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreMeasure
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class NotationInkBounds(val left: Float, val right: Float)
data class NotationHorizontalLayout(val width: Float, val positions: Map<BeatFraction, Float>) {
    fun x(offset: BeatFraction): Float = positions.getValue(offset)
}

/** Anchor to the newest sounding onset; older sustained notes keep their separate highlight state. */
fun notationFollowEvents(events: List<ScoreEvent>, activeIds: Set<String>): List<ScoreEvent> {
    val active = events.filter { it.id in activeIds }
    val latest = active.maxOfOrNull { it.offset } ?: return emptyList()
    return active.filter { it.offset == latest }
}

/** One time axis for all staves/voices. Extra space changes drawing only, never score time. */
fun notationHorizontalLayout(measure: ScoreMeasure, eventBounds: Map<String, NotationInkBounds> = emptyMap()): NotationHorizontalLayout {
    val offsets = measure.events.map { it.offset }.distinct().sorted()
    val originalWidth = max(360f, 138f + offsets.size * 43f + 35f)
    val duration = max(measure.time?.duration?.toDouble() ?: 0.0,
        measure.events.maxOfOrNull { (it.offset + it.duration).toDouble() } ?: 1.0).coerceAtLeast(1.0)
    val ink = measure.events.groupBy { it.offset }.mapValues { (_, events) ->
        val bounds = events.map { eventBounds[it.id] ?: NotationInkBounds(-16f, 24f) }
        NotationInkBounds(bounds.minOf { it.left }, bounds.maxOf { it.right })
    }
    val positions = linkedMapOf<BeatFraction, Float>()
    var previous: BeatFraction? = null
    for (offset in offsets) {
        val proportional = 138f + (offset.toDouble() / duration * (originalWidth - 138f - 32f)).toFloat()
        val x = previous?.let { prior ->
            max(proportional, positions.getValue(prior) + max(43f, ink.getValue(prior).right - ink.getValue(offset).left + 8f))
        } ?: proportional
        positions[offset] = x
        previous = offset
    }
    val right = positions.maxOfOrNull { (offset, x) -> x + max(32f, ink.getValue(offset).right + 12f) } ?: 0f
    return NotationHorizontalLayout(max(originalWidth, right), positions)
}

/** Complete numbered ink: octave dots/underlines, accidental prefixes, rhythm dots, dashes and highlights. */
fun numberedHorizontalInk(event: ScoreEvent, glyph: NumberedGlyphLayout, accidental: NumberedTextBounds?, dashWidth: Float): NotationInkBounds {
    val dashes = (floor(event.duration.toDouble()).toInt() - 1).coerceIn(0, 7)
    val left = minOf(glyph.digitBounds.left - 2f, glyph.centerX - 18f,
        accidental?.let { glyph.accidentalX + it.left } ?: glyph.digitBounds.left)
    val right = maxOf(glyph.digitBounds.right + 2f, glyph.centerX + 18f,
        if (event.dots > 0) glyph.rhythmDotX + (event.dots - 1) * 5f + 1.4f else glyph.digitBounds.right,
        if (dashes > 0) glyph.digitBounds.right + 8 + (dashes - 1) * 13 + dashWidth else glyph.digitBounds.right)
    return NotationInkBounds(left, right)
}

/** Pixel coordinates. Visible notes stay still; terminal and looped notes enter the clipped viewport. */
fun notationFollowScroll(activeLeft: Float, activeRight: Float, viewportWidth: Int, current: Int, maximum: Int): Int {
    val position = current.coerceIn(0, maximum.coerceAtLeast(0))
    if (viewportWidth <= 0 || maximum <= 0 || !activeLeft.isFinite() || !activeRight.isFinite()) return position
    val padding = minOf(24f, viewportWidth * .1f)
    val target = when {
        activeRight - activeLeft + padding * 2 > viewportWidth -> floor(activeLeft - padding).toInt()
        activeLeft < position + padding -> floor(activeLeft - padding).toInt()
        activeRight > position + viewportWidth - padding -> ceil(activeRight + padding - viewportWidth).toInt()
        else -> position
    }
    return target.coerceIn(0, maximum)
}
