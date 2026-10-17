package com.xiuyin.app.ui

import com.xiuyin.app.score.ScoreEvent
import kotlin.math.abs
import kotlin.math.max

/** Bounds in dp at the actual rendering font size; all y values are relative to the baseline. */
data class NumberedTextBounds(val advance: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

data class NumberedGlyphLayout(
    val digit: String,
    val accidental: String,
    val digitBounds: NumberedTextBounds,
    val accidentalX: Float,
    val octaveDotYs: List<Float>,
    val shortLineYs: List<Float>,
    val above: Float,
    val below: Float,
) {
    val centerX: Float get() = (digitBounds.left + digitBounds.right) / 2
    val centerY: Float get() = (digitBounds.top + digitBounds.bottom) / 2
    val rhythmDotX: Float get() = digitBounds.right + 5
}

data class NumberedEventLayout(val baseline: Float, val glyph: NumberedGlyphLayout)
data class NumberedStaffLayout(val events: Map<String, NumberedEventLayout>, val above: Float, val below: Float)

const val NUMBERED_OCTAVE_DOT_RADIUS = 1.5f
const val NUMBERED_HIGHLIGHT_RADIUS = 16f
private const val DOT_SPACING = 5.5f
private const val CHORD_GAP = 8f

/** Only placement changes here: degree/register still come from the real pitch and confirmed key. */
fun numberedGlyphLayout(event: ScoreEvent, fifths: Int, textBounds: (String) -> NumberedTextBounds): NumberedGlyphLayout {
    val pitch = event.pitch?.let { numberedPitch(it, fifths) }
    val digit = pitch?.degree?.toString() ?: "0"
    val accidental = pitch?.let { if (it.accidental > 0) "♯".repeat(it.accidental) else "♭".repeat(-it.accidental) }.orEmpty()
    val bounds = textBounds(digit)
    val accidentalBounds = if (accidental.isEmpty()) null else textBounds(accidental)
    val lineCount = when (event.type) {
        "eighth" -> 1; "16th" -> 2; "32nd" -> 3; "64th" -> 4; "128th" -> 5
        "256th" -> 6; "512th" -> 7; "1024th" -> 8; else -> 0
    }
    val shortLines = List(lineCount) { 3f + it * 4 }
    val octave = pitch?.octave ?: 0
    val dots = List(abs(octave)) { index ->
        if (octave > 0) bounds.top - 5 - index * DOT_SPACING
        else max(bounds.bottom, shortLines.lastOrNull() ?: bounds.bottom) + 6 + index * DOT_SPACING
    }
    val centerY = (bounds.top + bounds.bottom) / 2
    val top = minOf(bounds.top, accidentalBounds?.top ?: bounds.top, dots.minOrNull()?.minus(NUMBERED_OCTAVE_DOT_RADIUS) ?: bounds.top,
        centerY - NUMBERED_HIGHLIGHT_RADIUS)
    val bottom = maxOf(bounds.bottom, accidentalBounds?.bottom ?: bounds.bottom,
        dots.maxOrNull()?.plus(NUMBERED_OCTAVE_DOT_RADIUS) ?: bounds.bottom, (shortLines.lastOrNull() ?: bounds.bottom) + 0.5f,
        centerY + NUMBERED_HIGHLIGHT_RADIUS)
    return NumberedGlyphLayout(digit, accidental, bounds, -(accidentalBounds?.advance ?: 0f) - 2f,
        dots, shortLines, -top, bottom)
}

/** Stack simultaneous notes using their complete visible extents, including register dots and beams. */
fun numberedStaffLayout(events: List<ScoreEvent>, fifths: Int, textBounds: (String) -> NumberedTextBounds): NumberedStaffLayout {
    val placed = linkedMapOf<String, NumberedEventLayout>()
    for (group in events.groupBy { it.offset }.values) {
        var previous: NumberedEventLayout? = null
        for (event in group.sortedBy { it.pitch?.midi ?: -1 }) {
            val glyph = numberedGlyphLayout(event, fifths, textBounds)
            val baseline = previous?.let { it.baseline - it.glyph.above - CHORD_GAP - glyph.below } ?: 0f
            val current = NumberedEventLayout(baseline, glyph)
            placed[event.id] = current
            previous = current
        }
    }
    return NumberedStaffLayout(placed, placed.values.maxOfOrNull { it.glyph.above - it.baseline } ?: 0f,
        placed.values.maxOfOrNull { it.glyph.below + it.baseline } ?: 0f)
}
