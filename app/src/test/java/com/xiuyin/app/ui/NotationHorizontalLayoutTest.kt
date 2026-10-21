package com.xiuyin.app.ui

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePitch
import com.xiuyin.app.score.ScoreTime
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: layout coordinates preserve musical time; these are not recognition tests. */
class NotationHorizontalLayoutTest {
    @Test fun sustainedBassDoesNotPinFollowToAnOldOnsetWhileEveryActiveHighlightIsRetained() {
        val bass = note("bass", BeatFraction.ZERO).copy(duration = BeatFraction.of(4), staff = 2)
        val earlier = note("earlier", BeatFraction.of(1, 32))
        val terminal = note("terminal", BeatFraction.of(15, 32))
        val terminalChord = terminal.copy(id = "terminal-chord", pitch = ScorePitch("E", 0, 4), chord = true)
        val events = listOf(bass, earlier, terminal, terminalChord)
        val highlights = setOf(bass.id, terminal.id, terminalChord.id)
        val followed = notationFollowEvents(events, highlights)
        assertEquals("Only the latest active onset anchors horizontal follow", setOf(terminal.id, terminalChord.id), followed.map { it.id }.toSet())
        assertEquals("All sounding notes remain highlighted", setOf("bass", "terminal", "terminal-chord"), highlights)
        assertEquals(listOf(bass), notationFollowEvents(events, setOf(bass.id)))
        assertTrue(notationFollowEvents(events, emptySet()).isEmpty())
    }

    @Test fun denseShortNotesKeepReadableSpacingWithoutChangingTheirExactTime() {
        val measure = fixture((0..7).map { note("n$it", BeatFraction.of(it.toLong(), 32)) })
        val original = measure.events.toList()
        val layout = notationHorizontalLayout(measure)
        val xs = measure.events.map { layout.x(it.offset) }
        xs.zipWithNext().forEach { (a, b) -> assertTrue("Short-note columns overlap: $a, $b", b - a >= 43f) }
        assertEquals(original, measure.events)
        assertEquals(BeatFraction.of(7, 32), measure.events.last().offset)
        assertEquals(BeatFraction.of(1, 32), measure.events.last().duration)
        assertTrue(layout.width >= xs.last() + 32f)
    }

    @Test fun sameOnsetAcrossStavesAndVoicesUsesOneSharedColumn() {
        val events = listOf(note("r0", BeatFraction.ZERO), note("l0", BeatFraction.ZERO).copy(staff = 2),
            note("r1", BeatFraction.of(1, 32)), note("l1", BeatFraction.of(1, 32)).copy(staff = 2, voice = "2"))
        val layout = notationHorizontalLayout(fixture(events))
        assertEquals(2, layout.positions.size)
        assertEquals(layout.x(events[0].offset), layout.x(events[1].offset), 0f)
        assertEquals(layout.x(events[2].offset), layout.x(events[3].offset), 0f)
        assertTrue(layout.x(events[2].offset) - layout.x(events[0].offset) >= 43f)
    }

    @Test fun adjacentColumnsReserveWideAccidentalsDotsAndTheWidestChordMember() {
        val first = note("dotted", BeatFraction.ZERO).copy(dots = 3)
        val last = note("wide-prefix", BeatFraction.of(1, 32))
        val lower = last.copy(id = "lower", staff = 2)
        val layout = notationHorizontalLayout(fixture(listOf(first, last, lower)), mapOf(
            first.id to NotationInkBounds(-16f, 45f), last.id to NotationInkBounds(-16f, 24f),
            lower.id to NotationInkBounds(-42f, 25f)))
        val previousRight = layout.x(first.offset) + 45f
        val nextLeft = layout.x(last.offset) - 42f
        assertTrue("Ink including chord accidentals needs a visible gap", nextLeft - previousRight >= 8f)
    }

    @Test fun sparseNotesKeepExistingProportionalPositionsAndMinimumCanvasWidth() {
        val layout = notationHorizontalLayout(fixture(listOf(note("a", BeatFraction.ZERO), note("b", BeatFraction.of(2)))))
        assertEquals(360f, layout.width, 0f)
        assertEquals(138f, layout.x(BeatFraction.ZERO), 0f)
        assertEquals(233f, layout.x(BeatFraction.of(2)), 0f)
    }

    @Test fun followBringsTheWholeTerminalGlyphInsideANarrowViewport() {
        val target = notationFollowScroll(920f, 966f, 240, 0, 760)
        assertTrue("Terminal note must enter the viewport", target > 0)
        assertTrue(920f >= target)
        assertTrue(966f <= target + 240)
        assertTrue(target <= 760)
    }

    @Test fun loopToFirstActiveColumnScrollsBackWithoutChangingSelection() {
        val target = notationFollowScroll(122f, 164f, 240, 700, 760)
        assertTrue(target < 700)
        assertTrue(target <= 122f && target + 240 >= 164f)
    }

    @Test fun visibleNotesDoNotCauseJitterAndAnUnmeasuredViewportDoesNotJump() {
        assertEquals(100, notationFollowScroll(150f, 196f, 240, 100, 760))
        assertEquals(100, notationFollowScroll(920f, 966f, 0, 100, 760))
        assertEquals(0, notationFollowScroll(122f, 164f, 240, 0, 0))
    }

    private fun note(id: String, offset: BeatFraction) = ScoreEvent(id, offset, BeatFraction.of(1, 32),
        ScorePitch("C", 0, 4), 1, "1", false, "128th", 0, emptySet(), 0, 0)
    private fun fixture(events: List<ScoreEvent>) = ScoreMeasure("horizontal", "1", ScoreTime(4, 4), 0,
        listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)), events, false, declaredStaves = 2)
}
