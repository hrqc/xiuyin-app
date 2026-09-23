package com.xiuyin.app.practice

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.score.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** TEST DATA scores verify preparation safety and actionable diagnostics, not OMR accuracy. */
class PracticePreparationTest {
    @Test fun structuralErrorsRetainMeasureAndEventButCannotStartEvenWithReviewAcknowledged() = withModel { model, id ->
        val score = score().mapFirstEvent { it.copy(type = "half") }
        main { assertTrue(model.open(id, "TEST DATA structure", score, 7)) }
        awaitPrepared(model)
        val issue = model.state.value.preparationIssues.single { it.id.endsWith(":written") }
        assertEquals("m1", issue.measureId)
        assertEquals("right1", issue.eventId)
        assertEquals(ScoreIssueSeverity.ERROR, issue.severity)
        assertTrue(model.state.value.error.orEmpty().contains("1 处结构错误"))
        assertNull(model.state.value.timeline)
        main { model.start(PracticeConfig(reviewAcknowledged = true)); assertFalse(model.hasActiveSession()) }
        assertEquals(CompanionPhase.READY, model.state.value.phase)
        assertEquals(issue, model.state.value.preparationIssues.single { it.id.endsWith(":written") })
    }

    @Test fun compilerExceptionRetainsSpecificReasonAndPreviouslyLocatedValidationErrors() = withModel { model, id ->
        val score = score().mapFirstEvent { it.copy(duration = BeatFraction.of(2)) }
        main { assertTrue(model.open(id, "TEST DATA overfull", score, 0)) }
        awaitPrepared(model)
        assertNull(model.state.value.timeline)
        assertTrue(model.state.value.error.orEmpty().contains("超过拍号"))
        val errors = model.state.value.preparationIssues.filter { it.severity == ScoreIssueSeverity.ERROR }
        assertTrue(errors.any { it.measureId == "m1" && it.eventId == "right1" })
        assertTrue(errors.any { it.measureId == "m1" && it.message.contains("超过拍号") })
        main { model.start(PracticeConfig(reviewAcknowledged = true)); assertFalse(model.hasActiveSession()) }
    }

    @Test fun reopeningCorrectedScoreClearsBlockingIssuesAndRestoresExplicitHandPractice() = withModel { model, id ->
        main { assertTrue(model.open(id, "TEST DATA before", score().mapFirstEvent { it.copy(type = "half") }, 0)) }
        awaitPrepared(model)
        assertNull(model.state.value.timeline)
        assertTrue(model.state.value.preparationIssues.any { it.severity == ScoreIssueSeverity.ERROR })
        main { assertTrue(model.open(id, "TEST DATA corrected", score(), 1)) }
        awaitPrepared(model)
        assertNull(model.state.value.error)
        assertTrue(model.state.value.preparationIssues.isEmpty())
        assertTrue(supportsHandSelection(requireNotNull(model.state.value.timeline)))
        main { model.start(PracticeConfig(hand = PracticeHand.LEFT)) }
        assertEquals(CompanionPhase.RUNNING, model.state.value.phase)
        assertEquals(setOf(48), model.state.value.expectedMidi)
        main { model.finish(); assertFalse(model.hasActiveSession()) }
        main { model.start(PracticeConfig(hand = PracticeHand.RIGHT)) }
        assertEquals(setOf(60), model.state.value.expectedMidi)
    }

    @Test fun sourceWarningStillRequiresReviewAndNeverBecomesStructuralFailure() = withModel { model, id ->
        val warning = ScoreIssue("source:test", "TEST DATA 来源待核对", ScoreIssueSeverity.WARNING, "m1", "right1")
        main { assertTrue(model.open(id, "TEST DATA warning", score().copy(issues = listOf(warning)), 0)) }
        awaitPrepared(model)
        assertNotNull(model.state.value.timeline)
        assertNull(model.state.value.error)
        assertEquals(listOf(warning), model.state.value.preparationIssues)
        main { model.start(PracticeConfig()); assertFalse(model.hasActiveSession()) }
        assertTrue(model.state.value.error.orEmpty().contains("来源警告"))
        main { model.start(PracticeConfig(reviewAcknowledged = true)); assertTrue(model.hasActiveSession()) }
    }

    private fun DigitalScore.mapFirstEvent(transform: (ScoreEvent) -> ScoreEvent): DigitalScore = copy(parts = parts.map { part ->
        part.copy(measures = part.measures.map { measure -> measure.copy(events = measure.events.mapIndexed { i, event -> if (i == 0) transform(event) else event }) })
    })
    private fun score(): DigitalScore {
        val events = listOf(60, 48).mapIndexed { index, midi ->
            ScoreEvent(if (index == 0) "right1" else "left1", BeatFraction.ZERO, BeatFraction.of(1),
                ScoreEditing.pitchFromMidi(midi), index + 1, "1", false, "quarter", 0, emptySet(), index, 0)
        }
        val measure = ScoreMeasure("m1", "1", ScoreTime(1, 4), 0,
            listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)), events, false, declaredStaves = 2)
        return DigitalScore("test-preparation", "TEST DATA", listOf(ScorePart("p1", "Piano", listOf(measure))),
            emptyList(), ScoreSource("synthetic-preparation-test", "0".repeat(64), 1))
    }
    private fun awaitPrepared(model: PracticeViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (model.state.value.busy) {
            check(SystemClock.elapsedRealtime() < deadline) { "测试谱面准备超时" }
            Thread.sleep(10)
        }
    }
    private fun withModel(test: (PracticeViewModel, String) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val id = UUID.randomUUID().toString()
        lateinit var model: PracticeViewModel
        main { model = PracticeViewModel(application); store.put("test-preparation", model) }
        try { test(model, id) }
        finally {
            main { model.pause(); store.clear() }
            val root = File(application.filesDir, "practice-reports").canonicalFile
            val owned = File(root, id).canonicalFile
            require(owned.parentFile == root && owned.name == id)
            if (owned.exists()) check(owned.deleteRecursively())
        }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
