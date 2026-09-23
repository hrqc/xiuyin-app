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

    @Test fun fillingMissingMetersPreservesLegalChangesAndEveryEventPayload() {
        val note = ScoreFixtures.note("n", duration = BeatFraction.of(1)).copy(confidence = 0.4,
            sourceBox = SourceBox(0, 0.1, 0.2, 0.03, 0.04))
        val rest = ScoreFixtures.note("rest", offset = BeatFraction.of(1)).copy(pitch = null)
        val measures = listOf(
            ScoreFixtures.measure("absent", listOf(note, rest)).copy(time = null),
            ScoreFixtures.measure("invalid", listOf(ScoreFixtures.note("n2"))).copy(time = ScoreTime(4, 3)),
            ScoreFixtures.measure("three", listOf(ScoreFixtures.note("n3"))).copy(time = ScoreTime(3, 4)),
            ScoreFixtures.measure("four", listOf(ScoreFixtures.note("n4"))).copy(time = ScoreTime(4, 4)),
            ScoreFixtures.measure("six", listOf(ScoreFixtures.note("n5"))).copy(time = ScoreTime(6, 8)),
        )
        val original = ScoreFixtures.score(*measures.toTypedArray())
        val repaired = ScoreStructureTools.fillMissingTime(original, ScoreTime(2, 4))
        val actual = repaired.parts.single().measures
        assertEquals(listOf(ScoreTime(2, 4), ScoreTime(2, 4), ScoreTime(3, 4), ScoreTime(4, 4), ScoreTime(6, 8)), actual.map { it.time })
        measures.zip(actual).forEach { (before, after) -> assertEquals(before.copy(time = after.time), after) }
        assertEquals(measures.drop(2), actual.drop(2))
        assertEquals(original.source, repaired.source)
        assertNull(original.parts.single().measures.first().time)
        assertEquals(listOf("absent", "invalid"), ScoreStructureTools.missingTimeMeasures(original).map { it.id })
        assertTrue(ScoreStructureTools.missingTimeMeasures(repaired).isEmpty())
    }

    @Test fun elevenPreparationProblemsGroupNineMetersAndTwoRepeatsWithoutDroppingOtherErrors() {
        val measures = (1..9).map { index -> ScoreFixtures.measure("m$index", listOf(ScoreFixtures.note("n$index"))).copy(time = null) }
        val repeats = (1..2).map { ScoreIssue("omr:repeat:test:$it", "TEST DATA 全局反复 $it", ScoreIssueSeverity.ERROR) }
        val score = ScoreFixtures.score(*measures.toTypedArray()).copy(source = ScoreSource("OMR_IMAGE", "0".repeat(64), 1), issues = repeats)
        val needs = ScoreStructureTools.preparationNeeds(score)
        assertEquals(11, needs.errors.size)
        assertEquals(9, needs.missingTimeIssues.size)
        assertEquals(repeats, needs.repeatIssues)
        assertTrue(needs.otherErrors.isEmpty())
        val untouched = ScoreIssue("omr:unsupported-tuplet", "TEST DATA 未修复连音时值", ScoreIssueSeverity.ERROR, "m1", "n1")
        val changed = score.copy(issues = repeats + untouched)
        val repaired = ScoreStructureTools.fillMissingTime(changed, ScoreTime(2, 4))
        val after = ScoreStructureTools.preparationNeeds(repaired)
        assertEquals(3, after.errors.size)
        assertEquals(listOf(untouched), after.otherErrors)
        assertEquals(repeats, after.repeatIssues)
    }

    @Test fun fillMissingRequiresExplicitValidMeterAndNeverTouchesAlreadyCompleteScore() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))).copy(time = null))
        listOf(ScoreTime(0, 4), ScoreTime(4, 0), ScoreTime(4, 3), ScoreTime(129, 4)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.fillMissingTime(score, invalid) }
        }
        val complete = ScoreStructureTools.fillMissingTime(score, ScoreTime(3, 4))
        assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.fillMissingTime(complete, ScoreTime(4, 4)) }
        assertEquals(ScoreTime(3, 4), complete.parts.single().measures.single().time)
    }
    @Test fun invalidMeterIsRejectedInsteadOfCoerced() {
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("n"))))
        listOf(ScoreTime(0, 4), ScoreTime(4, 0), ScoreTime(4, 3), ScoreTime(129, 4)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ScoreStructureTools.setWholeScoreTime(score, it) }
        }
    }
}
