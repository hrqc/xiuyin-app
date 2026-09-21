package com.xiuyin.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTimelineTest {
    @Test fun independentPartsAndStavesShareTheSameChordTime() {
        val right = ScoreFixtures.note("r", midi = 60, duration = BeatFraction.of(4))
        val left = ScoreFixtures.note("l", midi = 48, duration = BeatFraction.of(4), staff = 2, voice = "2")
        val first = ScoreFixtures.measure(events = listOf(right, left)).copy(declaredStaves = 2, clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)))
        val extra = ScorePart("p2", "第二声部", listOf(ScoreFixtures.measure("other", listOf(ScoreFixtures.note("extra", midi = 67, duration = BeatFraction.of(4))))))
        val base = ScoreFixtures.score(first)
        val timeline = ScoreTimelineCompiler.compile(base.copy(parts = base.parts + extra))
        assertEquals(1, timeline.chords.size)
        assertEquals(listOf(48, 60, 67), timeline.chords.single().notes.map { it.midi })
        assertEquals(BeatFraction.of(4), timeline.totalBeats)
        assertEquals(2, timeline.measures.single().measureIds.size)
    }

    @Test fun tieChainSpanningThreeMeasuresCreatesOneSustainedNote() {
        val score = ScoreFixtures.score(
            ScoreFixtures.measure("m1", listOf(ScoreFixtures.note("a", duration = BeatFraction.of(4), ties = setOf("start")))),
            ScoreFixtures.measure("m2", listOf(ScoreFixtures.note("b", duration = BeatFraction.of(4), ties = setOf("stop", "start")))),
            ScoreFixtures.measure("m3", listOf(ScoreFixtures.note("c", duration = BeatFraction.of(4), ties = setOf("stop")))),
        )
        val timeline = ScoreTimelineCompiler.compile(score)
        assertEquals(1, timeline.notes.size)
        assertEquals(BeatFraction.of(12), timeline.notes.single().duration)
        assertEquals(listOf("a", "b", "c"), timeline.notes.single().eventIds)
        assertEquals(1, timeline.chords.size)
        assertEquals(listOf("b"), timeline.eventsAt(BeatFraction.of(4)).map { it.eventId })
        assertEquals(1, timeline.measureAt(BeatFraction.of(4))?.index)
        assertNull(timeline.measureAt(BeatFraction.of(12)))
        assertTrue(timeline.issues.isEmpty())
    }

    @Test fun aRestKeepsSilenceAndMeasurePositionInsteadOfCompressingTheTimeline() {
        val rest = ScoreFixtures.note("rest", duration = BeatFraction.of(2)).copy(pitch = null)
        val note = ScoreFixtures.note("note", offset = BeatFraction.of(2), duration = BeatFraction.of(2))
        val timeline = ScoreTimelineCompiler.compile(ScoreFixtures.score(ScoreFixtures.measure(events = listOf(rest, note))))
        assertEquals(BeatFraction.of(2), timeline.notes.single().startBeat)
        assertEquals(BeatFraction.of(4), timeline.totalBeats)
        assertTrue(timeline.eventsAt(BeatFraction.of(1)).single().isRest)
    }

    @Test fun pickupAndFractionalDurationsKeepExactBoundaries() {
        val pickup = ScoreFixtures.measure("pickup", listOf(ScoreFixtures.note("a", duration = BeatFraction.of(1, 3))), implicit = true)
        val full = ScoreFixtures.measure("full", listOf(ScoreFixtures.note("b", duration = BeatFraction.of(4))))
        val timeline = ScoreTimelineCompiler.compile(ScoreFixtures.score(pickup, full))
        assertEquals(BeatFraction.of(1, 3), timeline.measures[1].startBeat)
        assertEquals(BeatFraction.of(13, 3), timeline.totalBeats)
        assertEquals(listOf("b"), timeline.eventsAt(BeatFraction.of(1, 3)).map { it.eventId })
    }

    @Test fun unmatchedOrGappedTiesAreVisibleAndNeverSilentlyJoined() {
        val start = ScoreFixtures.note("start", duration = BeatFraction.of(1), ties = setOf("start"))
        val stop = ScoreFixtures.note("stop", offset = BeatFraction.of(2), duration = BeatFraction.of(2), ties = setOf("stop"))
        val timeline = ScoreTimelineCompiler.compile(ScoreFixtures.score(ScoreFixtures.measure(events = listOf(start, stop))))
        assertEquals(2, timeline.notes.size)
        assertTrue(timeline.issues.any { it.id == "timeline:stop:stop" })
        assertTrue(timeline.issues.any { it.id == "timeline:start:start" })
    }

    @Test fun samePitchInAnotherVoiceDoesNotConsumeTheFirstVoicesTie() {
        val start = ScoreFixtures.note("start", duration = BeatFraction.of(2), ties = setOf("start"))
        val stop = ScoreFixtures.note("stop", offset = BeatFraction.of(2), duration = BeatFraction.of(2), voice = "2", ties = setOf("stop"))
        val timeline = ScoreTimelineCompiler.compile(ScoreFixtures.score(ScoreFixtures.measure(events = listOf(start, stop))))
        assertEquals(2, timeline.notes.size)
        assertTrue(timeline.issues.any { it.id == "timeline:stop:stop" })
    }

    @Test fun crossStaffTieWithinOneVoiceCanRemainOneSound() {
        val start = ScoreFixtures.note("start", duration = BeatFraction.of(2), ties = setOf("start"))
        val stop = ScoreFixtures.note("stop", offset = BeatFraction.of(2), duration = BeatFraction.of(2), staff = 2, ties = setOf("stop"))
        val measure = ScoreFixtures.measure(events = listOf(start, stop)).copy(declaredStaves = 2, clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)))
        val timeline = ScoreTimelineCompiler.compile(ScoreFixtures.score(measure))
        assertEquals(1, timeline.notes.size)
        assertEquals(BeatFraction.of(4), timeline.notes.single().duration)
    }

    @Test fun inconsistentMeasureLengthsAndInvalidDurationsAreRejected() {
        val base = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", duration = BeatFraction.of(4)))))
        val shortPart = ScorePart("p2", "short", listOf(ScoreFixtures.measure("other", listOf(ScoreFixtures.note("b", duration = BeatFraction.of(3)))).copy(time = ScoreTime(3, 4))))
        assertThrows(IllegalArgumentException::class.java) { ScoreTimelineCompiler.compile(base.copy(parts = base.parts + shortPart)) }
        val overfull = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", duration = BeatFraction.of(5)))))
        assertThrows(IllegalArgumentException::class.java) { ScoreTimelineCompiler.compile(overfull) }
    }

    @Test fun retainedSourceIssuesRemainVisibleInCompiledOutput() {
        val sourceIssue = ScoreIssue("source-unknown", "未支持的原始标记，需核对")
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", duration = BeatFraction.of(4))))).copy(issues = listOf(sourceIssue))
        assertTrue(ScoreTimelineCompiler.compile(score).issues.contains(sourceIssue))
    }
}
