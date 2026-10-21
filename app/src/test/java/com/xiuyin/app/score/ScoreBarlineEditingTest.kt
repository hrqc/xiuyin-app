package com.xiuyin.app.score

import org.junit.Assert.*
import org.junit.Test

/** Synthetic TEST DATA. User photographs and their recognition results are never checked into Git. */
class ScoreBarlineEditingTest {
    @Test fun removesOnlySelectedFalseBoundaryAndPairsEverySystemWithoutChangingMusic() {
        val original = fixture()
        val preview = ScoreBarlineEditing.previewMerge(original, original, request())
        val score = preview.candidate
        assertEquals(original.source, score.source)
        assertEquals(original.id, score.id)
        assertEquals(1, score.parts.size)
        assertEquals(3, score.parts.single().measures.size)
        assertEquals(BeatFraction.of(1, 2), preview.joinBeat)
        assertEquals(0, preview.page)
        assertEquals(4, preview.sourceLine)
        assertEquals("r3a", preview.leftMeasure.id)
        assertEquals("r3b", preview.rightMeasure.id)
        assertEquals(setOf("r3b", "l1", "l2", "l3"), preview.retiredMeasureIds)
        assertEquals(setOf(1, 2), score.parts.single().measures.flatMap { it.events }.map { it.staff }.toSet())
        val before = original.parts.flatMap { it.measures }.flatMap { it.events }.associateBy { it.id }
        val after = score.parts.single().measures.flatMap { it.events }.associateBy { it.id }
        assertEquals(before.keys, after.keys)
        before.forEach { (id, event) ->
            val expected = event.copy(staff = if (event.sourceLine % 2 == 0) 1 else 2,
                offset = if (id in original.parts[0].measures.last().events.map { it.id }) event.offset + BeatFraction.of(1, 2) else event.offset)
            assertEquals("payload of $id", expected, after[id])
        }
        assertTrue(score.parts.single().measures.all { it.declaredStaves == 2 && it.clefs.map { c -> c.staff } == listOf(1, 2) })
        assertEquals(listOf("r1", "r2", "r3a"), score.parts.single().measures.map { it.id })
        assertEquals(listOf("1", "2", "3"), score.parts.single().measures.map { it.number })
        assertFalse(score.issues.any { it.id == "omr:alignment" || it.id.startsWith("omr:system-alignment:") })
        assertTrue(score.issues.any { it.id == "omr:review" })
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertEquals(3, ScoreTimelineCompiler.compile(score).measures.size)
        assertEquals(4, original.parts[0].measures.size)
        assertEquals(2, original.issues.count { it.severity == ScoreIssueSeverity.ERROR })
    }

    @Test fun previewCarriesStableBindingAndRejectsAnotherScoreGenerationOrSource() {
        val score = fixture()
        val req = request()
        val preview = ScoreBarlineEditing.previewMerge(score, score, req)
        req.requireCurrent(SCORE_ID, 7)
        preview.requireCurrent(SCORE_ID, 7, score)
        assertEquals(SCORE_ID, preview.scoreId)
        assertEquals(7L, preview.revisionGeneration)
        assertEquals(SOURCE, preview.sourceId)
        assertThrows(IllegalArgumentException::class.java) { req.requireCurrent("another", 7) }
        assertThrows(IllegalArgumentException::class.java) { req.requireCurrent(SCORE_ID, 8) }
        assertThrows(IllegalArgumentException::class.java) { preview.requireCurrent(SCORE_ID, 8, score) }
        assertThrows(IllegalArgumentException::class.java) { preview.requireCurrent(SCORE_ID, 7, score.copy(source = score.source.copy(sha256 = "1".repeat(64)))) }
    }

    @Test fun currentManualPitchDurationAndInsertedNotesSurviveAndAreNotNewlyCertified() {
        val original = fixture()
        val right = original.parts[0].measures.last()
        val edited = right.events.first().copy(pitch = ScorePitch("F", 1, 5), duration = BeatFraction.of(1, 4), type = "16th")
        val inserted = edited.copy(id = "user-extra", chord = true, sourceIndex = -1, sourceLine = -1, sourceBox = null, confidence = null)
        val current = replace(original, "r3b", right.copy(events = listOf(edited, inserted) + right.events.drop(1)))
        val preview = ScoreBarlineEditing.previewMerge(original, current, request())
        val events = preview.candidate.parts.single().measures.last().events
        assertEquals(edited.copy(offset = BeatFraction.of(1, 2)), events.first { it.id == edited.id })
        assertEquals(inserted.copy(offset = BeatFraction.of(1, 2)), events.first { it.id == inserted.id })
        assertTrue(preview.changedEventIds.containsAll(right.events.map { it.id } + inserted.id))
        assertFalse(preview.changedEventIds.contains(original.parts[0].measures[2].events.single().id))
        assertEquals(current.parts[0].measures.last(), preview.rightMeasure)
    }

    @Test fun unrelatedSourceErrorsAndWarningsRemainExactlyVisibleAfterAlignmentRepair() {
        val original = fixture().let { it.copy(issues = it.issues + listOf(
            ScoreIssue("omr:unsupported:0:9:99", "TEST DATA 未解决符号", ScoreIssueSeverity.ERROR),
            ScoreIssue("omr:slur:test", "TEST DATA 连奏线未解释"))) }
        val preview = ScoreBarlineEditing.previewMerge(original, original, request())
        original.issues.filterNot { alignment(it) }.forEach { assertTrue(preview.candidate.issues.contains(it)) }
        assertEquals(1, preview.after.errorCount)
        assertEquals(ScoreStatus.NEEDS_REVIEW, preview.candidate.status)
    }

    @Test fun underfullLengthsObservedInArchivedPhotoKeepCommonMeterAndRemainReviewWarnings() {
        // Synthetic notes carry the exact occupied lengths seen in the local old JSON;
        // no photograph, melody, title or private JSON is included in this fixture.
        var score = fixture()
        val first = score.parts[0].measures[0]
        val second = score.parts[0].measures[1]
        val lowerThird = score.parts[1].measures.last()
        fun underfull(measure: ScoreMeasure, numerator: Long, actual: Int, normal: Int): ScoreMeasure =
            measure.copy(events = listOf(measure.events.single().copy(duration = BeatFraction.of(numerator, 6),
                type = "half", dots = 0, tupletActual = actual, tupletNormal = normal)))
        score = replace(score, first.id, underfull(first, 14, 6, 7)) // 7/3, versus common 3/4 meter.
        score = replace(score, second.id, underfull(second, 13, 12, 13)) // 13/6.
        score = replace(score, lowerThird.id, underfull(lowerThird, 13, 12, 13))
        val preview = ScoreBarlineEditing.previewMerge(score, score, request())
        assertEquals(1, preview.candidate.parts.size)
        val actual = preview.candidate.parts.single().measures
        assertEquals(BeatFraction.of(7, 3), actual[0].events.first { it.staff == 1 }.duration)
        assertEquals(BeatFraction.of(13, 6), actual[1].events.first { it.staff == 1 }.duration)
        assertEquals(BeatFraction.of(13, 6), actual[2].events.first { it.staff == 2 }.duration)
        assertEquals(9.0, ScoreTimelineCompiler.compile(preview.candidate).totalBeats.toDouble(), 0.0)
        assertTrue(preview.candidate.issues.count { "未自动补休止符" in it.message } >= 3)
        assertEquals(0, preview.after.errorCount)
        assertEquals(ScoreStatus.NEEDS_REVIEW, preview.candidate.status)
    }

    @Test fun rejectsCrossPartNonAdjacentReverseOrMissingTargets() {
        val score = fixture()
        listOf(request().copy(rightMeasureId = "l3"), request().copy(leftMeasureId = "r1"),
            request().copy(leftMeasureId = "r3b", rightMeasureId = "r3a"),
            request().copy(partId = "missing"), request().copy(rightMeasureId = "missing")).forEach {
            assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, score, it) }
        }
    }

    @Test fun rejectsCrossSystemAndCrossPageBoundary() {
        val score = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            ScoreBarlineEditing.previewMerge(score, score, request().copy(leftMeasureId = "r2", rightMeasureId = "r3a"))
        }
        val right = score.parts[0].measures.last()
        val moved = replace(score, "r3b", right.copy(events = right.events.map { it.copy(sourceBox = it.sourceBox!!.copy(page = 1)) }))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(moved, moved, request()) }
    }

    @Test fun rejectsMissingOrForgedSourceMetadataInsteadOfGuessingStaffPairs() {
        val score = fixture()
        val right = score.parts[0].measures.last()
        val withoutBoxes = replace(score, "r3b", right.copy(events = right.events.map { it.copy(sourceBox = null) }))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(withoutBoxes, withoutBoxes, request()) }
        val forged = replace(score, "r3b", right.copy(events = right.events.map { it.copy(sourceLine = 8) }))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, forged, request()) }
        val onlyNew = replace(score, "r3b", right.copy(events = right.events.map { it.copy(id = "manual-${it.id}", sourceLine = -1, sourceIndex = -1, sourceBox = null, confidence = null) }))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, onlyNew, request()) }
        val falselySourcedNew = replace(score, "r3b", right.copy(events = right.events + right.events.first().copy(id = "fake-source")))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, falselySourcedNew, request()) }
    }

    @Test fun rejectsConflictingMeterKeyClefAndUnknownLayoutWithoutDiscardingThem() {
        val score = fixture()
        val right = score.parts[0].measures.last()
        listOf(right.copy(time = null), right.copy(time = ScoreTime(6, 8)), right.copy(fifths = 2),
            right.copy(keyMode = "minor"), right.copy(clefs = listOf(ScoreClef(1, "F", 4))),
            right.copy(declaredStaves = 2)).forEach { changed ->
            val current = replace(score, "r3b", changed)
            assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, current, request()) }
        }
        val unknown = score.copy(issues = score.issues.filterNot { it.id == "omr:layout" })
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(unknown, unknown, request()) }
    }

    @Test fun rejectsBoundaryWithRepeatSemanticsThatCannotBeProvenSafeFromScoreAlone() {
        val score = fixture().let { it.copy(issues = it.issues + ScoreIssue("omr:repeat:0:4:3", "TEST DATA 反复边界", ScoreIssueSeverity.ERROR)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, score, request()) }
    }

    @Test fun preservesOtherSystemsAlignmentErrorsAndDoesNotPairOnPageTotals() {
        val score = fixture(twoBrokenSystems = true)
        val preview = ScoreBarlineEditing.previewMerge(score, score, request())
        assertEquals(2, preview.candidate.parts.size)
        assertTrue(preview.candidate.issues.any { it.id == "omr:system-alignment:0:0" && it.severity == ScoreIssueSeverity.ERROR })
        assertTrue(preview.candidate.issues.any { it.id == "omr:alignment" && it.severity == ScoreIssueSeverity.ERROR })
        assertFalse(preview.candidate.issues.any { it.id == "omr:system-alignment:0:2" })
    }

    @Test fun oppositeSystemMismatchesWithEqualTotalsStillBlockPairing() {
        val score = fixture(twoBrokenSystems = true)
        val lower = score.parts[1]
        val third = lower.measures.last()
        val firstEvent = third.events.first().copy(duration = BeatFraction.of(1), type = "quarter", dots = 0)
        val secondEvent = third.events.first().copy(id = "lower-second", sourceIndex = 4,
            duration = BeatFraction.of(2), type = "half", dots = 0)
        val split = listOf(third.copy(id = "l3a", events = listOf(firstEvent)),
            third.copy(id = "l3b", events = listOf(secondEvent)))
        val original = score.copy(parts = listOf(score.parts[0], lower.copy(measures = lower.measures.dropLast(1) + split)))
        val preview = ScoreBarlineEditing.previewMerge(original, original, request())
        assertEquals(preview.candidate.parts[0].measures.size, preview.candidate.parts[1].measures.size)
        assertEquals(2, preview.candidate.parts.size)
        assertTrue(preview.candidate.issues.any { it.id == "omr:system-alignment:0:0" })
        assertTrue(preview.candidate.issues.any { it.id == "omr:system-alignment:0:2" })
    }

    @Test fun incompatiblePairedHeadersOrReorderedSystemPositionsAreRejected() {
        val score = fixture()
        val lower = score.parts[1]
        val altered = score.copy(parts = listOf(score.parts[0], lower.copy(measures = lower.measures.mapIndexed { index, m -> if (index == 1) m.copy(fifths = 3) else m })))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, altered, request()) }
        val reordered = score.copy(parts = listOf(score.parts[0], lower.copy(measures = listOf(lower.measures[1], lower.measures[0], lower.measures[2]))))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, reordered, request()) }
    }

    @Test fun nonOmrDifferentSourcesDuplicateIdsAndNegativeGenerationAreRejected() {
        val score = fixture()
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score.copy(source = score.source.copy(format = "MusicXML")), score, request()) }
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, score.copy(source = score.source.copy(byteCount = 2)), request()) }
        val right = score.parts[0].measures.last()
        val duplicate = replace(score, "r3b", right.copy(events = right.events + right.events.first()))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, duplicate, request()) }
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(score, score, request().copy(revisionGeneration = -1)) }
    }

    @Test fun unequalVoiceEndsCannotMakeMaxOccupiedTimeAnInventedJoinBeat() {
        val base = fixture()
        val left = base.parts[0].measures[2]
        val right = base.parts[0].measures[3]
        val held = left.events.single().copy(id = "held-other-voice", sourceIndex = 3,
            duration = BeatFraction.of(1), type = "quarter", voice = "2")
        var original = replace(base, left.id, left.copy(events = left.events + held))
        original = replace(original, right.id, right.copy(events = right.events.dropLast(1)))
        // max(left end)=1 and right length=2 would fit 3/4, but the source cursor
        // may instead have advanced by the short 1/2-beat voice. Fitting is insufficient.
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(original, original, request()) }
    }

    @Test fun unequalDurationsAtOneChordOnsetCannotInventTheSeam() {
        val base = fixture()
        val left = base.parts[0].measures[2]
        val right = base.parts[0].measures[3]
        val held = left.events.single().copy(id = "held-chord-member", sourceIndex = 3,
            duration = BeatFraction.of(1), type = "quarter", chord = true)
        var original = replace(base, left.id, left.copy(events = left.events + held))
        original = replace(original, right.id, right.copy(events = right.events.dropLast(1)))
        assertThrows(IllegalArgumentException::class.java) { ScoreBarlineEditing.previewMerge(original, original, request()) }
    }

    @Test fun equalDurationChordKeepsOneUnambiguousJoinBeat() {
        val base = fixture()
        val left = base.parts[0].measures[2]
        val chord = left.events.single().copy(id = "equal-chord-member", sourceIndex = 3,
            pitch = ScorePitch("D", 0, 5), chord = true)
        val original = replace(base, left.id, left.copy(events = left.events + chord))
        val preview = ScoreBarlineEditing.previewMerge(original, original, request())
        assertEquals(BeatFraction.of(1, 2), preview.joinBeat)
        assertTrue(preview.candidate.parts.single().measures.last().events.contains(chord))
    }

    private fun alignment(issue: ScoreIssue) = issue.id == "omr:alignment" || issue.id.startsWith("omr:system-alignment:")
    private fun request() = ScoreBarlineMergeRequest(SCORE_ID, 7, "P1", "r3a", "r3b")
    private fun replace(score: DigitalScore, id: String, replacement: ScoreMeasure) = score.copy(parts = score.parts.map { part ->
        part.copy(measures = part.measures.map { if (it.id == id) replacement else it })
    })

    private fun fixture(twoBrokenSystems: Boolean = false): DigitalScore {
        fun event(line: Int, token: Int, duration: BeatFraction = BeatFraction.of(3), offset: BeatFraction = BeatFraction.ZERO) =
            ScoreEvent("$SOURCE:p0:s$line:t$token", offset, duration, ScorePitch("B", -1, if (line % 2 == 0) 4 else 3),
                1, "1", false, if (duration == BeatFraction.of(3)) "half" else "eighth", if (duration == BeatFraction.of(3)) 1 else 0,
                emptySet(), token, line, .83, SourceBox(0, 100.0, line * 100.0, 900.0, 80.0))
        fun measure(id: String, line: Int, events: List<ScoreEvent> = listOf(event(line, 2))) =
            ScoreMeasure(id, id, ScoreTime(3, 4), -1, listOf(ScoreClef(1, if (line % 2 == 0) "G" else "F", if (line % 2 == 0) 2 else 4)), events, false)
        val right = mutableListOf(measure("r1", 0), measure("r2", 2),
            measure("r3a", 4, listOf(event(4, 2, BeatFraction.of(1, 2)))),
            measure("r3b", 4, (0..4).map { event(4, 4 + it, BeatFraction.of(1, 2), BeatFraction.of(it.toLong(), 2)) }))
        if (twoBrokenSystems) {
            right[0] = measure("r1", 0, listOf(event(0, 2, BeatFraction.of(1, 2))))
            right.add(1, measure("r1extra", 0, listOf(event(0, 4, BeatFraction.of(1, 2)))))
        }
        val issues = mutableListOf(ScoreIssue("omr:review", "TEST DATA 图像识谱仍需人工复核"),
            ScoreIssue("omr:layout", "TEST DATA 双谱表间距配对待复核"),
            ScoreIssue("omr:system-alignment:0:2", "TEST DATA 第三组 2/1", ScoreIssueSeverity.ERROR),
            ScoreIssue("omr:alignment", "TEST DATA 总小节数不齐", ScoreIssueSeverity.ERROR))
        if (twoBrokenSystems) issues += ScoreIssue("omr:system-alignment:0:0", "TEST DATA 第一组 2/1", ScoreIssueSeverity.ERROR)
        return DigitalScore(SOURCE, "TEST DATA 小节线修复", listOf(ScorePart("P1", "上谱", right),
            ScorePart("P2", "下谱", listOf(measure("l1", 1), measure("l2", 3), measure("l3", 5)))),
            issues, ScoreSource("OMR_IMAGE", SOURCE, 1))
    }

    companion object {
        private const val SCORE_ID = "00000000-0000-4000-8000-000000000001"
        private val SOURCE = "0".repeat(64)
    }
}
