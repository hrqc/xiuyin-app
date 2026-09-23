package com.xiuyin.app.omr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.practice.supportsHandSelection
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreTimelineCompiler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit protocol fixtures verify conversion, never model accuracy or phone piano-room use. */
@RunWith(AndroidJUnit4::class)
class OmrGrandStaffTest {
    @Test fun pairedStaffsKeepTheSameEventsInOnePianoPartAndExposeIndependentHands() {
        val upper = staff(50, "G2", token("note_4", "C5"), token("rest_4"), token("barline"))
        val lower = staff(150, "F4", token("note_4", "C3"), token("note_4", "D3"), token("barline"))
        val score = assemble(upper, lower)
        val measure = score.parts.single().measures.single()
        assertEquals(2, measure.declaredStaves)
        assertEquals(listOf(1 to "G", 2 to "F"), measure.clefs.map { it.staff to it.sign })
        assertEquals(listOf(1, 1, 2, 2), measure.events.map { it.staff })
        assertEquals(listOf("$source:p0:s0:t3", "$source:p0:s0:t4", "$source:p0:s1:t3", "$source:p0:s1:t4"), measure.events.map { it.id })
        assertEquals(listOf(BeatFraction.ZERO, BeatFraction.of(1), BeatFraction.ZERO, BeatFraction.of(1)), measure.events.map { it.offset })
        assertEquals(1, measure.events.count { it.isRest })
        assertEquals("$source:p0:s0:t4", measure.events.single { it.isRest }.id)
        assertEquals(140.0, measure.events.last().sourceBox!!.y, 0.0)
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        val timeline = ScoreTimelineCompiler.compile(score)
        assertEquals(BeatFraction.of(2), timeline.totalBeats)
        assertTrue(supportsHandSelection(timeline))
        assertEquals(setOf(72), ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.RIGHT)).expectedMidi())
        val left = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.LEFT))
        assertEquals(setOf(48), left.expectedMidi())
        assertEquals(listOf(setOf(48), setOf(50)), left.targets.map { chord -> chord.notes.map { it.midi }.toSet() })
        assertEquals(setOf(48, 72), ScoreFollower(timeline, PracticeConfig()).expectedMidi())
    }

    @Test fun absentRestsAreNeverSynthesizedToFillShorterStaffs() {
        val score = assemble(
            staff(50, "G2", token("note_4", "C5"), token("note_4", "D5"), token("barline")),
            staff(150, "F4", token("note_4", "C3"), token("barline")),
        )
        val events = score.parts.single().measures.single().events
        assertEquals(3, events.size)
        assertFalse(events.any { it.isRest })
        assertTrue(score.issues.any { it.id.contains("underfull") })
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun equalPageTotalsCannotHideBarlineMismatchBetweenSystems() {
        val score = assemble(
            staff(50, "G2", token("note_4", "C5"), token("barline"), token("note_4", "D5"), token("barline")),
            staff(150, "F4", token("note_4", "C3"), token("barline")),
            staff(350, "G2", token("note_4", "E5"), token("barline")),
            staff(450, "F4", token("note_4", "D3"), token("barline"), token("note_4", "E3"), token("barline")),
        )
        assertEquals(listOf(3, 3), score.parts.map { it.measures.size })
        assertEquals(6, score.parts.flatMap { it.measures }.sumOf { it.events.size })
        assertEquals(2, score.issues.count { it.id.startsWith("omr:system-alignment:") && it.severity == ScoreIssueSeverity.ERROR })
        val timeline = ScoreTimelineCompiler.compile(score)
        assertFalse(supportsHandSelection(timeline))
        assertThrows(IllegalArgumentException::class.java) { ScoreFollower(timeline, PracticeConfig()) }
    }

    @Test fun differingStaffKeysRemainSeparateWithoutDiscardingEitherKey() {
        val upper = staff(50, "G2", token("note_4", "F5"), token("barline"))
        val lower = staff(150, "F4", token("note_4", "F3"), token("barline"))
        lower.getJSONArray("tokens").put(2, token("keySignature_1"))
        val score = assemble(upper, lower)
        assertEquals(listOf(0, 1), score.parts.map { it.measures.single().fifths })
        assertEquals(listOf(77, 54), score.parts.map { it.measures.single().events.single().pitch!!.midi })
        assertTrue(score.issues.any { it.id == "omr:staff-keys" && it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun clefChangesRemainAttachedToTheirOwnStaffInLaterSystems() {
        val score = assemble(
            staff(50, "G2", token("note_4", "C5"), token("barline")),
            staff(150, "F4", token("note_4", "C3"), token("barline")),
            staff(350, "G2", token("note_4", "D5"), token("barline")),
            staff(450, "G2", token("note_4", "D4"), token("barline")),
        )
        assertEquals(listOf("F", "G"), score.parts.single().measures.map { measure -> measure.clefs.single { it.staff == 2 }.sign })
        assertEquals(listOf(48, 62), score.parts.single().measures.map { measure -> measure.events.single { it.staff == 2 }.pitch!!.midi })
    }

    @Test fun aSingleStaffNeverInventsALeftHand() {
        val score = assemble(staff(50, "G2", token("note_4"), token("barline")))
        assertEquals(1, score.parts.single().measures.single().declaredStaves)
        assertFalse(supportsHandSelection(ScoreTimelineCompiler.compile(score)))
    }

    @Test fun unsupportedTokensStillBlockPracticeAfterSafePairing() {
        val score = assemble(
            staff(50, "G2", token("unrecognized-blank"), token("note_4", "C5"), token("barline")),
            staff(150, "F4", token("note_4", "C3"), token("barline")),
        )
        assertEquals(1, score.parts.size)
        assertFalse(score.parts.single().measures.single().events.any { it.isRest })
        assertTrue(score.issues.any { it.id.startsWith("omr:unsupported:") && it.severity == ScoreIssueSeverity.ERROR })
        assertThrows(IllegalArgumentException::class.java) { ScoreFollower(ScoreTimelineCompiler.compile(score), PracticeConfig()) }
    }

    @Test fun aLongChordToneAndShortMovingNotesUseConcurrentVoicesWithoutMovingOnsets() {
        val score = assemble(staff(50, "G2", token("note_2", "C4"), token("chord"),
            token("note_4", "E4"), token("note_4", "F4"), token("barline")))
        val events = score.parts.single().measures.single().events
        assertEquals(listOf(BeatFraction.ZERO, BeatFraction.ZERO, BeatFraction.of(1)), events.map { it.offset })
        assertEquals(listOf(BeatFraction.of(2), BeatFraction.of(1), BeatFraction.of(1)), events.map { it.duration })
        assertEquals(listOf("2", "1", "1"), events.map { it.voice })
        assertTrue(events.none { it.chord })
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        val timeline = ScoreTimelineCompiler.compile(score)
        assertEquals(listOf(setOf(60, 64), setOf(65)), timeline.chords.map { chord -> chord.notes.map { it.midi }.toSet() })
        assertEquals(BeatFraction.of(2), timeline.notes.single { it.midi == 60 }.duration)
    }

    @Test fun concurrentRestIsRetainedInItsOwnVoiceAndNeverBecomesANoteChord() {
        val score = assemble(staff(50, "G2", token("note_2", "C4"), token("chord"),
            token("rest_4"), token("note_4", "D4"), token("barline")))
        val events = score.parts.single().measures.single().events
        val rest = events.single { it.isRest }
        assertEquals("$source:p0:s0:t5", rest.id)
        assertEquals(BeatFraction.ZERO, rest.offset)
        assertEquals(BeatFraction.of(1), rest.duration)
        assertFalse(rest.chord)
        assertEquals(rest.voice, events.last().voice)
        assertNotEquals(rest.voice, events.first().voice)
        assertEquals(BeatFraction.of(1), events.last().offset)
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        assertEquals(2, ScoreTimelineCompiler.compile(score).notes.size)
    }

    @Test fun equalLengthChordStillHasOneLeaderAndOneVoice() {
        val score = assemble(staff(50, "G2", token("note_4", "C4"), token("chord"),
            token("note_4", "E4"), token("note_4", "D4"), token("barline")))
        val events = score.parts.single().measures.single().events
        assertEquals(listOf(false, true, false), events.map { it.chord })
        assertEquals(listOf("1", "1", "1"), events.map { it.voice })
        assertEquals(listOf(BeatFraction.ZERO, BeatFraction.ZERO, BeatFraction.of(1)), events.map { it.offset })
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun anOrdinaryRestIsNotHiddenOrGivenAnExtraVoice() {
        val score = assemble(staff(50, "G2", token("rest_4"), token("note_4", "C4"), token("barline")))
        val events = score.parts.single().measures.single().events
        assertEquals(2, events.size)
        assertTrue(events.first().isRest)
        assertEquals(listOf("1", "1"), events.map { it.voice })
        assertEquals(listOf(BeatFraction.ZERO, BeatFraction.of(1)), events.map { it.offset })
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun voiceAssignmentNeverClearsAnOrphanChordError() {
        val score = assemble(staff(50, "G2", token("chord"), token("note_4"), token("barline")))
        assertTrue(score.issues.any { it.id.startsWith("omr:orphan-chord:") && it.severity == ScoreIssueSeverity.ERROR })
        assertThrows(IllegalArgumentException::class.java) { ScoreFollower(ScoreTimelineCompiler.compile(score), PracticeConfig()) }
    }

    @Test fun geometryPairingStillRequiresReviewAndInvalidGraceRestsStayVisible() {
        val score = assemble(staff(50, "G2", token("note_4"), token("barline")),
            staff(150, "F4", token("rest_8G"), token("note_4", "C3"), token("barline")))
        assertTrue(score.issues.any { it.id == "omr:layout" })
        assertEquals(com.xiuyin.app.score.ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.parts.flatMap { it.measures }.flatMap { it.events }.any { it.isRest && it.grace })
        assertTrue(score.issues.any { it.severity == ScoreIssueSeverity.ERROR })
    }

    private fun assemble(vararg staffs: JSONObject) = OmrScoreAssembler.assemble(source, 10,
        JSONArray().put(JSONObject().put("page", 0).put("staffs", JSONArray(staffs.toList()))))
    private fun token(rhythm: String, pitch: String = "C4") = JSONObject()
        .put("rhythm", rhythm).put("pitch", pitch).put("lift", "_")
        .put("position", "upper").put("slur", "_").put("articulation", "_")
        .put("modelScores", JSONObject().put("rhythm", 1.0).put("pitch", 1.0).put("lift", 1.0))
    private fun staff(top: Int, clef: String, vararg tokens: JSONObject) = JSONObject()
        .put("eos", true).put("tokens", JSONArray(listOf(token("clef_$clef"), token("timeSignature/4"), token("keySignature_0")) + tokens))
        .put("sourceBox", JSONArray(listOf(10, top - 10, 900, top + 50)))
        .put("lines", JSONArray((0..4).map { top + it * 10 }))

    private val source = "a".repeat(64)
}
