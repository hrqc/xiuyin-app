package com.xiuyin.app.omr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreIssueSeverity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit token protocol fixtures only; these never stand in for real model accuracy tests. */
@RunWith(AndroidJUnit4::class)
class OmrSemanticBoundaryTest {
    @Test fun aChordFollowingGraceUsesTheGraceOffsetNotAnOlderOrdinaryNote() {
        val score = OmrScoreAssembler.assemble("a".repeat(64), 1, pages(staff(
            token("timeSignature/8"), token("note_4"), token("note_8G"), token("chord"),
            token("note_8G"), token("note_4"), token("barline"))))
        val events = score.parts.single().measures.single().events
        assertEquals(listOf(BeatFraction.ZERO, BeatFraction.of(1), BeatFraction.of(1), BeatFraction.of(1)), events.map { it.offset })
        assertTrue(events[2].chord && events[2].grace)
        assertTrue(score.issues.none { it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun slurIsVisibleButNeverInventsATie() {
        val symbol = token("note_4").put("slur", "slurStart")
        val score = OmrScoreAssembler.assemble("b".repeat(64), 1, pages(staff(token("timeSignature/4"), symbol, token("barline"))))
        val event = score.parts.single().measures.single().events.single()
        assertTrue(event.ties.isEmpty())
        assertTrue(score.issues.any { it.id.startsWith("omr:slur:") && it.eventId == event.id })
    }

    @Test fun changingMeterIsBlockingUntilUserSetsTheActualMeasures() {
        val score = OmrScoreAssembler.assemble("c".repeat(64), 1, pages(staff(
            token("timeSignature/8"), token("note_4"), token("barline"),
            token("timeSignature/4"), token("note_4"), token("barline"))))
        assertTrue(score.issues.any { it.id == "omr:meter-changes" })
        assertTrue(score.parts.flatMap { it.measures }.all { it.time == null })
        assertTrue(score.issues.any { it.id.startsWith("validation:") && it.severity == ScoreIssueSeverity.ERROR })
        val repaired = score.copy(parts = score.parts.map { part -> part.copy(measures = part.measures.map { it.copy(time = com.xiuyin.app.score.ScoreTime(1, 4)) }) })
        assertTrue(com.xiuyin.app.score.ScoreEditing.validate(repaired).none { it.severity == ScoreIssueSeverity.ERROR })
    }

    private fun token(rhythm: String) = JSONObject().put("rhythm", rhythm).put("pitch", "C4").put("lift", "_")
        .put("position", "upper").put("slur", "_").put("articulation", "_")
        .put("modelScores", JSONObject().put("rhythm", 1.0).put("pitch", 1.0).put("lift", 1.0))
    private fun staff(vararg tokens: JSONObject) = JSONObject().put("eos", true).put("tokens", JSONArray(tokens.toList()))
        .put("sourceBox", JSONArray(listOf(10, 20, 900, 140))).put("lines", JSONArray(listOf(50, 60, 70, 80, 90)))
    private fun pages(staff: JSONObject) = JSONArray().put(JSONObject().put("page", 0).put("staffs", JSONArray().put(staff)))
}
