package com.xiuyin.app.score

import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: explicit manual corrections, never an OMR accuracy test. */
class ScoreRangeEditingTest {
    @Test fun writtenDurationRetainsTupletRatioAcrossTypeDotsAndGraceChanges() {
        assertEquals(BeatFraction.of(1, 3), ScoreEditing.notatedDuration("eighth", 0, 3, 2))
        assertEquals(BeatFraction.of(1, 6), ScoreEditing.notatedDuration("16th", 0, 3, 2))
        assertEquals(BeatFraction.of(1, 4), ScoreEditing.notatedDuration("16th", 1, 3, 2))
        assertEquals(BeatFraction.of(3, 20), ScoreEditing.notatedDuration("16th", 1, 5, 2))
        assertEquals(BeatFraction.ZERO, ScoreEditing.notatedDuration("16th", 1, 3, 2, grace = true))
        assertEquals(BeatFraction.of(3, 8), ScoreEditing.notatedDuration("16th", 1))
    }

    @Test fun incompleteAndInvalidTupletRatiosAreNotSilentlyDiscarded() {
        for ((actual, normal) in listOf(3 to null, null to 2, 0 to 2, 3 to 33)) {
            assertThrows(IllegalArgumentException::class.java) { ScoreEditing.notatedDuration("eighth", 0, actual, normal) }
        }
    }

    @Test fun rangeAcrossMeasuresIncludesBoundaryChordsButNotOtherHandsVoicesOrParts() {
        val original = fixture()
        val preview = ScoreRangeEditing.preview(original, "a", "c", 1)
        assertEquals(setOf("a", "chord", "b", "c"), preview.changedEventIds)
        assertEquals(1, preview.skippedRests)
        val result = ScoreRangeEditing.shiftOctave(original, "a", "c", 1)
        val before = ScoreRangeEditing.endpoints(original).associate { it.eventId to it.event }
        val after = ScoreRangeEditing.endpoints(result.score).associate { it.eventId to it.event }
        assertEquals(before.keys, after.keys)
        before.forEach { (id, old) ->
            val expected = if (id in preview.changedEventIds) {
                val pitch = requireNotNull(old.pitch)
                old.copy(pitch = pitch.copy(octave = pitch.octave + 1))
            } else old
            assertEquals("All non-pitch fields must remain intact: $id", expected, after[id])
        }
        assertEquals(original.source, result.score.source)
        assertEquals(original.parts.map { it.measures.map { m -> m.time to m.fifths } }, result.score.parts.map { it.measures.map { m -> m.time to m.fifths } })
        assertTrue(result.score.issues.contains(original.issues.single()))
        assertEquals(60, before.getValue("a").pitch!!.midi)
    }

    @Test fun downOneOctaveRetainsEnharmonicSpellingAndCanBeReversed() {
        val score = fixture()
        val down = ScoreRangeEditing.shiftOctave(score, "a", "c", -1).score
        val restored = ScoreRangeEditing.shiftOctave(down, "a", "c", 1).score
        assertEquals(score.parts, restored.parts)
        assertEquals(ScoreRangeEditing.endpoints(score).first { it.eventId == "chord" }.event.pitch!!.alter,
            ScoreRangeEditing.endpoints(down).first { it.eventId == "chord" }.event.pitch!!.alter)
    }

    @Test fun outOfRangePitchRejectsTheWholeOperation() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(
            ScoreFixtures.note("ok", midi = 60), ScoreFixtures.note("high", midi = 127, offset = BeatFraction.of(1)))))
        assertThrows(IllegalArgumentException::class.java) { ScoreRangeEditing.shiftOctave(score, "ok", "high", 1) }
        assertEquals(listOf(60, 127), score.parts.single().measures.single().events.map { it.pitch!!.midi })
        val low = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("low", midi = 0))))
        assertThrows(IllegalArgumentException::class.java) { ScoreRangeEditing.shiftOctave(low, "low", "low", -1) }
    }

    @Test fun backwardsRestMissingAndCrossLaneEndpointsAreRejected() {
        val score = fixture()
        for ((start, end) in listOf("c" to "a", "rest" to "c", "a" to "missing", "a" to "left", "a" to "voice2", "a" to "other")) {
            assertThrows("$start..$end", IllegalArgumentException::class.java) { ScoreRangeEditing.preview(score, start, end, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { ScoreRangeEditing.preview(score, "a", "c", 2) }
    }

    @Test fun duplicateEventIdsAndStaleRequestsAreRejected() {
        val score = fixture()
        val duplicate = score.copy(parts = score.parts + score.parts.first().copy(id = "another"))
        assertThrows(IllegalArgumentException::class.java) { ScoreRangeEditing.preview(duplicate, "a", "c", 1) }
        val request = ScoreOctaveRangeRequest("library-id", 4, "a", "c", 1)
        request.requireCurrent("library-id", 4)
        assertThrows(IllegalArgumentException::class.java) { request.requireCurrent("other-library-id", 4) }
        assertThrows(IllegalArgumentException::class.java) { request.requireCurrent("library-id", 5) }
    }

    private fun fixture(): DigitalScore {
        val box = SourceBox(0, 10.0, 20.0, 100.0, 50.0)
        val a = ScoreFixtures.note("a").copy(sourceBox = box, confidence = 0.53, type = "quarter", ties = setOf("start"))
        val chord = ScoreFixtures.note("chord", midi = 61, chord = true).copy(sourceBox = box, confidence = 0.72)
        val rest = ScoreFixtures.note("rest", offset = BeatFraction.of(1)).copy(pitch = null)
        val b = ScoreFixtures.note("b", offset = BeatFraction.of(2))
        val left = ScoreFixtures.note("left", midi = 48, staff = 2)
        val otherVoice = ScoreFixtures.note("voice2", midi = 67, voice = "2")
        val m1 = ScoreFixtures.measure(events = listOf(a, chord, rest, b, left, otherVoice)).copy(
            declaredStaves = 2, clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)))
        val m2 = ScoreFixtures.measure("m2", listOf(ScoreFixtures.note("c")))
        val p2 = ScorePart("p2", "other part", listOf(ScoreFixtures.measure("other-m", listOf(ScoreFixtures.note("other")))))
        return ScoreFixtures.score(m1, m2).let { it.copy(parts = it.parts + p2,
            issues = listOf(ScoreIssue("omr:source", "TEST DATA unverified source"))) }
    }
}
