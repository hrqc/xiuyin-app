package com.xiuyin.app.ui

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScorePitch
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: explicit written notes and synthetic font bounds test placement, not music recognition. */
class NumberedNotationLayoutTest {
    private val bounds: (String) -> NumberedTextBounds = { text ->
        if (text.first() in '0'..'9') NumberedTextBounds(10f, 1f, -13f, 9f, 0f)
        else NumberedTextBounds(text.length * 12f, 0f, -15f, text.length * 11f, 1f)
    }

    @Test fun registerDotsRetainAllZeroOneAndTwoOctaveInformation() {
        for (octave in 2..6) {
            val glyph = numberedGlyphLayout(note(octave = octave), 0, bounds)
            assertEquals(kotlin.math.abs(octave - 4), glyph.octaveDotYs.size)
            assertEquals("1", glyph.digit)
            assertEquals(5f, glyph.centerX, 0f)
            assertTrue(glyph.octaveDotYs.all { if (octave > 4) it + NUMBERED_OCTAVE_DOT_RADIUS < -13 else it - NUMBERED_OCTAVE_DOT_RADIUS > 0 })
        }
    }

    @Test fun accidentalsDoNotShiftDotsOrDigitAndRhythmDotsStayOnTheRight() {
        val natural = numberedGlyphLayout(note(octave = 5), 0, bounds)
        for (alter in listOf(-1, 1)) {
            val glyph = numberedGlyphLayout(note(octave = 5, alter = alter), 0, bounds)
            assertEquals(natural.centerX, glyph.centerX, 0f)
            assertEquals(natural.octaveDotYs, glyph.octaveDotYs)
            assertEquals(if (alter < 0) "♭" else "♯", glyph.accidental)
            assertTrue(glyph.accidentalX + bounds(glyph.accidental).right < glyph.digitBounds.left)
            assertTrue(glyph.rhythmDotX > glyph.digitBounds.right)
        }
    }

    @Test fun lowDotsClearEveryDurationUnderlineIncluding128thAndBeyond() {
        val types = listOf("eighth", "16th", "32nd", "64th", "128th", "256th", "512th", "1024th")
        for ((index, type) in types.withIndex()) {
            val glyph = numberedGlyphLayout(note(octave = 2, type = type), 0, bounds)
            assertEquals(index + 1, glyph.shortLineYs.size)
            assertEquals(2, glyph.octaveDotYs.size)
            assertTrue("$type low dot touches a duration line", glyph.octaveDotYs.first() - NUMBERED_OCTAVE_DOT_RADIUS > glyph.shortLineYs.last() + 1)
            assertTrue(glyph.below >= glyph.octaveDotYs.last() + NUMBERED_OCTAVE_DOT_RADIUS)
        }
    }

    @Test fun simultaneousNotesReserveTheirCompleteInkAndHighlightBounds() {
        val notes = listOf(note("low", octave = 2, type = "128th"), note("mid", octave = 4), note("high", octave = 6))
        val layout = numberedStaffLayout(notes, 0, bounds)
        val placed = notes.map { layout.events.getValue(it.id) }
        for ((lower, higher) in placed.zipWithNext()) {
            assertTrue(higher.baseline + higher.glyph.below + 7.9f < lower.baseline - lower.glyph.above)
            assertTrue(lower.baseline + lower.glyph.centerY - (higher.baseline + higher.glyph.centerY) > NUMBERED_HIGHLIGHT_RADIUS * 2)
        }
        for (entry in placed) {
            assertTrue(entry.baseline - entry.glyph.above >= -layout.above)
            assertTrue(entry.baseline + entry.glyph.below <= layout.below)
        }
    }

    @Test fun pianoExtremesKeepFourRegisterDotsWithoutClippingOrArtificialCap() {
        for (pitch in listOf(ScorePitch("A", 0, 0), ScorePitch("C", 0, 8))) {
            assertTrue(pitch.midi in 21..108)
            val glyph = numberedGlyphLayout(note().copy(pitch = pitch), 0, bounds)
            assertEquals(4, glyph.octaveDotYs.size)
            assertTrue(glyph.octaveDotYs.all { it - NUMBERED_OCTAVE_DOT_RADIUS >= -glyph.above && it + NUMBERED_OCTAVE_DOT_RADIUS <= glyph.below })
        }
    }

    @Test fun restsHaveNoRegisterDotsAndSeparateOnsetsShareTheSameRow() {
        val rest = note("rest", type = "16th").copy(pitch = null)
        val later = note("later", octave = 6).copy(offset = BeatFraction.of(1))
        val layout = numberedStaffLayout(listOf(rest, later), 0, bounds)
        assertEquals("0", layout.events.getValue("rest").glyph.digit)
        assertTrue(layout.events.getValue("rest").glyph.octaveDotYs.isEmpty())
        assertEquals(0f, layout.events.getValue("rest").baseline, 0f)
        assertEquals(0f, layout.events.getValue("later").baseline, 0f)
    }

    private fun note(id: String = "note", octave: Int = 4, alter: Int = 0, type: String = "quarter") = ScoreEvent(
        id, BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("C", alter, octave), 1, "1", false, type,
        0, emptySet(), 0, 0)
}
