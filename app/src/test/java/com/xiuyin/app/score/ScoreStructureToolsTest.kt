package com.xiuyin.app.score

import org.junit.Assert.*
import org.junit.Test

class ScoreStructureToolsTest {
    @Test fun explicitMeterRepairsContinuationPageWithoutChangingNotesOrInventingRests() {
        val event = ScoreFixtures.note("note", duration = BeatFraction.of(1))
        val measure = ScoreFixtures.measure(events = listOf(event)).copy(time = null)
        val score = ScoreFixtures.score(measure)
        assertEquals(1, ScoreStructureTools.summarize(score).errorCount)
        val repaired = ScoreStructureTools.setWholeScoreTime(score, ScoreTime(12, 8))
        assertEquals(listOf(event), repaired.parts.single().measures.single().events)
        assertEquals(0, ScoreStructureTools.summarize(repaired).errorCount)
        assertEquals(ScoreTime(12, 8), repaired.parts.single().measures.single().time)
        assertTrue(repaired.issues.any { "未自动补休止符" in it.message })
        assertNull(score.parts.single().measures.single().time)
    }

    @Test fun overfullNotesRemainBlockingAfterAnIncorrectUserMeter() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(
            ScoreFixtures.note("long", duration = BeatFraction.of(4)))))
        val changed = ScoreStructureTools.setWholeScoreTime(score, ScoreTime(3, 4))
        assertEquals(score.parts.single().measures.single().events, changed.parts.single().measures.single().events)
        assertTrue(ScoreStructureTools.summarize(changed).errorCount > 0)
        assertNotNull(ScoreStructureTools.summarize(changed).timelineProblem)
    }

    @Test fun structuralErrorsPrecedeHundredsOfSourceWarningsAndKeepMeasureLocations() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))).copy(time = null))
            .copy(issues = (1..207).map { ScoreIssue("source:$it", "TEST DATA 来源提示 $it") })
        val issues = ScoreStructureTools.reviewIssues(score)
        assertEquals(ScoreIssueSeverity.ERROR, issues.first().severity)
        assertEquals(score.parts.single().measures.single().id, issues.first().measureId)
        assertTrue(issues.count { it.id.startsWith("source:") } == 207)
    }

    @Test fun restoringOldSeparatedOmrMeasuresCannotDiscardTheOtherHandAfterMerge() {
        val right = ScoreFixtures.measure("r", listOf(ScoreFixtures.note("rn")))
        val left = ScoreFixtures.measure("l", listOf(ScoreFixtures.note("ln", midi = 48)))
        val original = ScoreFixtures.score(right).copy(
            parts = listOf(ScorePart("right", "上谱", listOf(right)), ScorePart("left", "下谱", listOf(left))),
            source = ScoreSource("OMR_IMAGE", "0".repeat(64), 1))
        val merged = original.copy(parts = listOf(ScorePart("right", "双谱表", listOf(
            right.copy(declaredStaves = 2, events = right.events + left.events.map { it.copy(staff = 2) })))))
        assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.requireOriginalRestoreCompatible(original, merged) }
        val movedStaff = original.copy(parts = original.parts.map { part -> part.copy(measures = part.measures.map { measure ->
            measure.copy(events = measure.events.map { it.copy(staff = 2) })
        }) })
        assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.requireOriginalRestoreCompatible(original, movedStaff) }
        val changedPitch = original.copy(parts = original.parts.map { part -> part.copy(measures = part.measures.map { measure ->
            measure.copy(events = measure.events.map { it.copy(pitch = ScorePitch("D", 0, 4)) })
        }) })
        ScoreStructureTools.requireOriginalRestoreCompatible(original, changedPitch)
    }

    @Test fun explicitLinearChoiceOnlyChangesRepeatErrorsAndPreservesOtherBlockers() {
        val measure = ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))).copy(time = null)
        val repeat = ScoreIssue("omr:repeat:0:1:22", "原始反复提示", ScoreIssueSeverity.ERROR, measure.id, "n")
        val overlap = ScoreIssue("omr:unsupported-overlap", "未解决重叠", ScoreIssueSeverity.ERROR, measure.id)
        val score = ScoreFixtures.score(measure).copy(source = ScoreSource("OMR_IMAGE", "0".repeat(64), 1),
            issues = listOf(repeat, overlap, ScoreIssue("omr:layout", "谱表需复核")))
        assertEquals(listOf(repeat), ScoreStructureTools.pendingRepeatChoices(score))
        assertFalse(ScoreStructureTools.hasConfirmedLinearRepeat(score))
        val confirmed = ScoreStructureTools.confirmLinearRepeat(score)
        assertEquals(score.parts, confirmed.parts)
        assertEquals(score.issues.drop(1), confirmed.issues.drop(1))
        val kept = confirmed.issues.first()
        assertEquals(repeat.id, kept.id)
        assertEquals(repeat.measureId, kept.measureId)
        assertEquals(repeat.eventId, kept.eventId)
        assertEquals(ScoreIssueSeverity.WARNING, kept.severity)
        assertTrue(kept.message.contains(repeat.message))
        assertTrue(ScoreStructureTools.reviewIssues(confirmed).any { it.severity == ScoreIssueSeverity.ERROR && "拍号" in it.message })
        assertTrue(ScoreStructureTools.reviewIssues(confirmed).contains(overlap))
        assertTrue(ScoreStructureTools.hasConfirmedLinearRepeat(confirmed))
        assertEquals(ScoreIssueSeverity.ERROR, score.issues.first().severity)
    }

    @Test fun linearChoiceDoesNotApplyToNonOmrScoresOrClearGenericErrors() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))))
            .copy(issues = listOf(ScoreIssue("omr:repeat:0:0:1", "TEST DATA", ScoreIssueSeverity.ERROR)))
        assertTrue(ScoreStructureTools.pendingRepeatChoices(score).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.confirmLinearRepeat(score) }
        val noRepeat = score.copy(source = ScoreSource("OMR_IMAGE", "0".repeat(64), 1),
            issues = listOf(ScoreIssue("omr:unknown", "未知记号", ScoreIssueSeverity.ERROR)))
        assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.confirmLinearRepeat(noRepeat) }
        assertEquals(ScoreIssueSeverity.ERROR, noRepeat.issues.single().severity)
    }

    @Test fun invalidMeterIsRejectedInsteadOfCoerced() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))))
        listOf(ScoreTime(0, 4), ScoreTime(4, 0), ScoreTime(4, 3), ScoreTime(129, 4)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.setWholeScoreTime(score, it) }
        }
    }
}
