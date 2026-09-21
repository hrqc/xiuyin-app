package com.xiuyin.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEditingTest {
    @Test fun replacingPitchRetainsIdentityProvenanceAndDoesNotInventConfidence() {
        val original = ScoreFixtures.note("n1", duration = BeatFraction.of(4)).copy(confidence = 0.63, sourceBox = SourceBox(1, 2.0, 3.0, 4.0, 5.0))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(original)))
        val result = ScoreEditing.apply(score, ScorePatch.ReplaceEvent("n1", original.copy(pitch = ScorePitch("F", 1, 4), sourceLine = 999, confidence = 1.0)))
        val changed = result.score.parts.single().measures.single().events.single()
        assertEquals(66, changed.pitch?.midi)
        assertEquals(original.id, changed.id)
        assertEquals(original.sourceLine, changed.sourceLine)
        assertEquals(original.sourceBox, changed.sourceBox)
        assertEquals(original.confidence, changed.confidence)
        assertEquals(score.source, result.score.source)
        assertEquals(setOf("n1"), result.changedEventIds)
        assertEquals(60, score.parts.single().measures.single().events.single().pitch?.midi)
    }

    @Test fun insertAndDeleteUseStableIdsAndExposeOrphanChordInsteadOfShiftingTime() {
        val original = ScoreFixtures.note("lead", duration = BeatFraction.of(4))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(original)))
        val inserted = original.copy(id = "manual", pitch = ScorePitch("E", 0, 4), chord = true)
        val added = ScoreEditing.apply(score, ScorePatch.InsertEvent("m1", inserted)).score
        assertTrue(added.issues.isEmpty())
        val manual = added.parts.single().measures.single().events.last()
        assertEquals(-1, manual.sourceIndex)
        assertNull(manual.sourceBox)
        val removed = ScoreEditing.apply(added, ScorePatch.DeleteEvent("lead")).score
        assertEquals(BeatFraction.ZERO, removed.parts.single().measures.single().events.single().offset)
        assertTrue(removed.issues.any { "前导" in it.message })
    }

    @Test fun restAndWrittenDotChangesModifyCoreData() {
        val old = ScoreFixtures.note("n", duration = BeatFraction.of(4))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(old)))
        val duration = ScoreEditing.writtenDuration("half", 1)
        assertEquals(BeatFraction.of(3), duration)
        assertEquals(BeatFraction.of(7, 4), ScoreEditing.writtenDuration("quarter", 2))
        val edited = ScoreEditing.apply(score, ScorePatch.ReplaceEvent("n", old.copy(pitch = null, type = "half", dots = 1, duration = duration))).score
        val event = edited.parts.single().measures.single().events.single()
        assertTrue(event.isRest)
        assertEquals(duration, event.duration)
        assertTrue(edited.issues.any { "不足" in it.message })
    }

    @Test fun repairingStructureRemovesRecomputedIssuesButPreservesSourceWarnings() {
        val old = ScoreFixtures.note("n")
        val sourceWarning = ScoreIssue("issue-0", "元素 <direction> 尚未解释；需核对原文件", measureId = "m1")
        val structureWarning = ScoreIssue("issue-1", "谱表 1 / 声部 1 时值 1 不足拍号要求 4；未自动补休止符", measureId = "m1")
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(old))).copy(issues = listOf(sourceWarning, structureWarning))
        val first = ScoreEditing.apply(score, ScorePatch.ReplaceEvent("n", old.copy(duration = BeatFraction.of(2)))).score
        assertTrue(first.issues.any { it.id.startsWith("validation:") && "不足" in it.message })
        val repaired = ScoreEditing.apply(first, ScorePatch.ReplaceEvent("n", old.copy(duration = BeatFraction.of(4)))).score
        assertEquals(listOf(sourceWarning), repaired.issues)
    }

    @Test fun changingMeterAndStaffHeadersRevalidatesExistingEvents() {
        val event = ScoreFixtures.note("n", duration = BeatFraction.of(3), staff = 2)
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(event)))
        assertTrue(ScoreEditing.validate(score).any { "谱表" in it.message })
        val result = ScoreEditing.apply(score, ScorePatch.UpdateMeasure("m1", ScoreTime(3, 4), -1,
            listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)), 2)).score
        assertTrue(result.issues.isEmpty())
        assertEquals(-1, result.parts.single().measures.single().fifths)
        assertEquals(2, result.parts.single().measures.single().events.single().staff)
    }

    @Test fun invalidTargetsAndValuesAreRejectedWithoutChangingInput() {
        val original = ScoreFixtures.note("n", duration = BeatFraction.of(4))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(original)))
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.apply(score, ScorePatch.DeleteEvent("missing")) }
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.apply(score, ScorePatch.InsertEvent("m1", original)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.apply(score, ScorePatch.ReplaceEvent("n", original.copy(id = "changed"))) }
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.apply(score, ScorePatch.ReplaceEvent("n", original.copy(duration = BeatFraction.ZERO))) }
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.writtenDuration("quarter", 5) }
        assertEquals(listOf(original), score.parts.single().measures.single().events)
    }

    @Test fun transposeUpdatesEveryPitchAndKeyWhileKeepingIdsAndSourceLocations() {
        val events = listOf(ScoreFixtures.note("c", midi = 60, duration = BeatFraction.of(4)), ScoreFixtures.note("e", midi = 64, duration = BeatFraction.of(4), chord = true))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = events))
        val result = ScoreEditing.transpose(score, 2).score
        val measure = result.parts.single().measures.single()
        assertEquals(2, measure.fifths)
        assertEquals(listOf(62, 66), measure.events.map { it.pitch?.midi })
        assertEquals(listOf("D4", "F♯4"), measure.events.map { it.pitch?.displayName })
        assertEquals(events.map { it.id }, measure.events.map { it.id })
        assertEquals(events.map { it.sourceLine }, measure.events.map { it.sourceLine })
        assertEquals(score.id, result.id)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun explicitEnharmonicTargetAndMinorModeArePreserved() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("c", duration = BeatFraction.of(4)))).copy(keyMode = "minor"))
        val flat = ScoreEditing.transpose(score, 1).score.parts.single().measures.single()
        assertEquals(-5, flat.fifths)
        assertEquals("D♭4", flat.events.single().pitch?.displayName)
        val sharp = ScoreEditing.transpose(score, 1, 7).score.parts.single().measures.single()
        assertEquals("C♯4", sharp.events.single().pitch?.displayName)
        assertEquals("minor", sharp.keyMode)
        val sharpScore = score.copy(parts = listOf(score.parts.single().copy(measures = listOf(sharp))))
        assertEquals(7, ScoreEditing.transpose(sharpScore, 0).score.parts.single().measures.single().fifths)
        assertEquals("C♯4", ScoreEditing.transpose(sharpScore, 0).score.parts.single().measures.single().events.single().pitch?.displayName)
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.transpose(score, 1, 2) }
    }

    @Test fun modulationRemainsModulationAndOutOfRangeTranspositionIsAtomic() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", duration = BeatFraction.of(4)))),
            ScoreFixtures.measure("m2", listOf(ScoreFixtures.note("b", midi = 127, duration = BeatFraction.of(4)))).copy(fifths = 1))
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.transpose(score, 1) }
        assertEquals(127, score.parts.single().measures.last().events.single().pitch?.midi)
        val down = ScoreEditing.transpose(score, -2).score.parts.single().measures
        assertEquals(listOf(-2, -1), down.map { it.fifths })
    }

    @Test fun absentKeyIsNotInventedAndMidiSpellingHasCorrectPitch() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", duration = BeatFraction.of(4)))).copy(fifths = null))
        assertNull(ScoreEditing.transpose(score, 1).score.parts.single().measures.single().fifths)
        assertThrows(IllegalArgumentException::class.java) { ScoreEditing.transpose(score, 1, -5) }
        for (midi in 0..127) {
            assertEquals(midi, ScoreEditing.pitchFromMidi(midi).midi)
            assertEquals(midi, ScoreEditing.pitchFromMidi(midi, true).midi)
        }
        assertFalse(ScoreEditing.transpose(score, 0).score.issues.any { it.severity == ScoreIssueSeverity.ERROR })
    }
}

internal object ScoreFixtures {
    fun note(id: String, midi: Int = 60, offset: BeatFraction = BeatFraction.ZERO, duration: BeatFraction = BeatFraction.of(1),
        staff: Int = 1, voice: String = "1", chord: Boolean = false, ties: Set<String> = emptySet()) =
        ScoreEvent(id, offset, duration, ScoreEditing.pitchFromMidi(midi), staff, voice, chord, null, 0, ties, 0, 12)
    fun measure(id: String = "m1", events: List<ScoreEvent>, implicit: Boolean = false) =
        ScoreMeasure(id, id, ScoreTime(4, 4), 0, listOf(ScoreClef(1, "G", 2)), events, implicit)
    fun score(vararg measures: ScoreMeasure) = DigitalScore("fixture", "TEST DATA", listOf(ScorePart("p1", "钢琴", measures.toList())),
        emptyList(), ScoreSource("MusicXML", "0".repeat(64), 0))
}

