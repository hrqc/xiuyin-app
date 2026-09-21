package com.xiuyin.app.score

import org.junit.Assert.*
import org.junit.Test

class GraceTupletTest {
    private fun score(events: List<ScoreEvent>) = DigitalScore("a".repeat(64), "TEST DATA", listOf(ScorePart("p", "piano", listOf(ScoreMeasure("m", "1", ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), events, false)))), emptyList(), ScoreSource("OMR", "a".repeat(64), 120))
    private fun event(id: String, start: BeatFraction, duration: BeatFraction) = ScoreEvent(id, start, duration, ScorePitch("C", 0, 4), 1, "1", false, "eighth", 0, emptySet(), 0, 0)
    @Test fun exactTripletDurationIsNotMisreportedAsWrittenMismatch() {
        val events = (0..2).map { event("n$it", BeatFraction.of(it.toLong(), 3), BeatFraction.of(1, 3)).copy(tupletActual = 3, tupletNormal = 2) }
        assertTrue(ScoreEditing.validate(score(events)).isEmpty())
        assertEquals(BeatFraction.of(1), ScoreTimelineCompiler.compile(score(events)).totalBeats)
    }
    @Test fun graceRetainsZeroDurationAndIsNotRequiredChordTone() {
        val grace = event("g", BeatFraction.ZERO, BeatFraction.ZERO).copy(grace = true, pitch = ScorePitch("B", 0, 3))
        val main = event("n", BeatFraction.ZERO, BeatFraction.of(1)).copy(type = "quarter")
        val timeline = ScoreTimelineCompiler.compile(score(listOf(grace, main)))
        assertTrue(timeline.issues.isEmpty())
        assertEquals(BeatFraction.ZERO, timeline.notes.first { it.grace }.duration)
        assertEquals(listOf(60), timeline.chords.single().notes.map { it.midi })
        assertEquals(BeatFraction.of(1), timeline.totalBeats)
    }
    @Test fun zeroDurationOrdinaryNoteAndIncompleteTupletRemainInvalid() {
        assertTrue(ScoreEditing.validate(score(listOf(event("bad", BeatFraction.ZERO, BeatFraction.ZERO)))).any { it.severity == ScoreIssueSeverity.ERROR })
        val invalid = event("bad", BeatFraction.ZERO, BeatFraction.of(1, 3)).copy(tupletActual = 3)
        assertTrue(ScoreEditing.validate(score(listOf(invalid))).any { it.severity == ScoreIssueSeverity.ERROR })
    }
}
