package com.xiuyin.app.practice

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.pitch.PianoNoteEvidence
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreImportMetadata
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScoreMetadataDatabase
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: synthetic evidence tests scheduling only; real PCM coverage is in FieldFrameBoundaryTest. */
class PracticePendingFrameQueueTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun workerBurstDrainsBeforePauseAndScheduledJobCannotScoreItAgain() = withModel { model ->
        main {
            queueFromWorker(model, burst(66))
            assertEquals("Main 仍被当前操作占用，worker 不得直接修改练习", 0, model.state.value.confirmedInputs)
            model.drainPendingInputFrames()
            assertEquals(1, model.state.value.confirmedInputs)
            assertEquals(1, model.state.value.wrong)
            assertEquals(CompanionPhase.LOCKED, model.state.value.phase)
            assertNotNull(model.state.value.lastWrongFrame)
            model.pause()
            assertEquals(CompanionPhase.PAUSED, model.state.value.phase)
        }
        instrumentation.waitForIdleSync()
        assertEquals("已安排的旧 Main 工作不能重复消费同批帧", 1, model.state.value.confirmedInputs)
        assertEquals(1, model.state.value.wrong)
        assertTrue(model.state.value.locked)
        assertNull(model.state.value.lastWrongFrame)
    }

    @Test fun explicitInvalidationStillRejectsQueuedOldGenerationBeforeAnyJudgment() = withModel { model ->
        main {
            queueFromWorker(model, burst(66))
            model.invalidateInputFrames()
            model.drainPendingInputFrames()
            assertEquals(0, model.state.value.confirmedInputs)
            assertEquals(0, model.state.value.wrong)
            assertNull(model.state.value.lastDecisionFrame)
            assertNull(model.state.value.lastWrongFrame)
            queueFromWorker(model, burst(66, firstIndex = 10))
            model.drainPendingInputFrames()
            assertEquals("失效保护不能误伤后续新 generation 的实际判断", 1, model.state.value.wrong)
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, model.state.value.confirmedInputs)
        assertEquals(1, model.state.value.wrong)
    }

    @Test fun resumedStreamWithResetFrameIndexKeepsLockUntilActualTargetCorrection() = withModel { model ->
        main {
            queueFromWorker(model, burst(66))
            model.drainPendingInputFrames()
            model.pause()
            model.resume()
            assertEquals(CompanionPhase.LOCKED, model.state.value.phase)
            queueFromWorker(model, burst(60))
            model.drainPendingInputFrames()
            assertFalse(model.state.value.locked)
            assertEquals(1, model.state.value.cursor)
            assertEquals(1, model.state.value.wrong)
            assertEquals(1, model.state.value.correct)
            assertEquals(2, model.state.value.confirmedInputs)
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, model.state.value.correct)
        assertEquals(2, model.state.value.confirmedInputs)
    }

    @Test fun completedBatchAndReentrantStopTailDoNotRepeatDecisionOrCompletion() = withModel(listOf(60)) { model ->
        val notifications = AtomicInteger()
        main {
            model.setInputCompletedListener {
                assertEquals(1, notifications.incrementAndGet())
                assertEquals(CompanionPhase.COMPLETED, model.state.value.phase)
                assertEquals(1, model.state.value.correct)
                assertEquals(1, model.state.value.confirmedInputs)
                assertFalse("停止回调发生在保存前", model.state.value.busy)
                // Simulates the final already-accepted worker batch seen by Root's stop/drain callback.
                queueFromWorker(model, burst(60, firstIndex = 8, size = 4, attack = false))
                model.drainPendingInputFrames()
                assertFalse("嵌套 drain 不能抢先 finish", model.state.value.busy)
                assertEquals(1, notifications.get())
                assertEquals(1, model.state.value.correct)
                assertEquals(1, model.state.value.confirmedInputs)
            }
            queueFromWorker(model, burst(60, size = 8))
            model.drainPendingInputFrames()
        }
        awaitNotBusy(model)
        instrumentation.waitForIdleSync()
        val report = requireNotNull(model.state.value.latestReport)
        assertTrue(report.completed)
        assertEquals(1, report.correct)
        assertEquals(1, report.confirmedInputs)
        assertEquals(1, report.evidence.count { it.decision == PracticeDecision.CORRECT })
        assertEquals(0, report.wrong)
        assertEquals(1, notifications.get())
        main { model.drainPendingInputFrames() }
        assertEquals("空队列和旧 scheduled job 不得再次通知同一完成会话", 1, notifications.get())
    }

    @Test fun boundedQueueOverflowIsVisibleAndNeverTurnsUnprocessedFramesIntoJudgments() = withModel { model ->
        val failures = AtomicInteger()
        main {
            model.setInputQueueFailureListener { message ->
                assertTrue(message.contains("队列已满"))
                assertEquals(CompanionPhase.PAUSED, model.state.value.phase)
                failures.incrementAndGet()
                model.drainPendingInputFrames()
            }
            queueFromWorker(model, burst(66, size = PracticeViewModel.MAX_PENDING_INPUT_FRAMES + 1))
            model.drainPendingInputFrames()
            assertEquals(1, failures.get())
            assertEquals(0, model.state.value.confirmedInputs)
            assertEquals(0, model.state.value.wrong)
            assertTrue(model.state.value.error.orEmpty().contains("队列已满"))
            assertEquals(CompanionPhase.PAUSED, model.state.value.phase)
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, failures.get())
        assertEquals(0, model.state.value.confirmedInputs)
    }

    @Test fun drainRejectsWorkerThreadInsteadOfMutatingTheSessionOffMain() = withModel { model ->
        val error = AtomicReference<Throwable?>()
        val worker = Thread { try { model.drainPendingInputFrames() } catch (caught: Throwable) { error.set(caught) } }
        worker.start(); worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(error.get() is IllegalStateException)
        assertTrue(error.get()?.message.orEmpty().contains("must run on Main"))
        assertEquals(0, model.state.value.confirmedInputs)
        assertEquals(CompanionPhase.RUNNING, model.state.value.phase)
    }

    private fun queueFromWorker(model: PracticeViewModel, frames: List<PianoRecognitionFrame>) {
        val error = AtomicReference<Throwable?>()
        val worker = Thread { try { frames.forEach(model::accept) } catch (caught: Throwable) { error.set(caught) } }
        worker.start(); worker.join(3000)
        assertFalse("worker 必须只入队，不能等待正被本操作占用的 Main", worker.isAlive)
        error.get()?.let { throw AssertionError("worker 入队失败", it) }
    }

    private fun burst(midi: Int, firstIndex: Int = 0, size: Int = 4, attack: Boolean = true) =
        (0 until size).map { offset ->
            val onset = attack && offset == 0
            val index = firstIndex + offset
            val scores = FloatArray(88).also { it[midi - 21] = 0.95f }
            PianoRecognitionFrame(index.toLong(), index * 0.032, (firstIndex + size) * 0.032 + 1.12,
                if (onset) setOf(midi) else emptySet(), setOf(midi),
                listOf(PianoNoteEvidence(midi, 0.95f, 0.95f, onset, true)),
                if (onset) RecognitionState.ATTACK else RecognitionState.SUSTAIN,
                false, 1.0, scores.copyOf(), scores.copyOf())
        }

    private fun withModel(notes: List<Int> = listOf(60, 62), test: (PracticeViewModel) -> Unit) {
        val context = instrumentation.targetContext.applicationContext as Application
        val sourceId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val metadata = ScoreMetadataDatabase(context).use {
            it.ensureImported(sourceId, ScoreImportMetadata(originalTitle = "TEST DATA 待处理帧队列"))
        }
        val store = ViewModelStore()
        lateinit var model: PracticeViewModel
        main {
            model = PracticeViewModel(context)
            store.put("test-pending-frames", model)
            assertTrue(model.open(metadata.scoreId, "TEST DATA 待处理帧队列", score(notes), 0))
        }
        try {
            awaitNotBusy(model)
            assertNull(model.state.value.error)
            main { model.start(PracticeConfig(CompanionMode.STOP_ON_ERROR, reviewAcknowledged = true)) }
            assertEquals(CompanionPhase.RUNNING, model.state.value.phase)
            test(model)
        } finally {
            main {
                model.setInputQueueFailureListener(null)
                model.setInputCompletedListener(null)
                model.pause()
                model.drainPendingInputFrames()
                store.clear()
            }
            val reportsRoot = File(context.filesDir, "practice-reports").canonicalFile
            val owned = File(reportsRoot, metadata.scoreId).canonicalFile
            require(owned.parentFile == reportsRoot && metadata.scoreId.matches(Regex("[a-f0-9-]{36}")))
            if (owned.exists()) check(owned.deleteRecursively())
            ScoreMetadataDatabase(context).use {
                assertEquals(1, it.writableDatabase.delete("score_metadata", "score_id = ? AND source_id = ?",
                    arrayOf(metadata.scoreId, sourceId)))
            }
        }
    }

    private fun awaitNotBusy(model: PracticeViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (model.state.value.busy) {
            check(SystemClock.elapsedRealtime() < deadline) { "真实仓库准备/保存未按时完成：${model.state.value}" }
            instrumentation.waitForIdleSync()
        }
    }

    private fun score(notes: List<Int>): DigitalScore {
        val measures = notes.mapIndexed { index, midi ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)),
                listOf(ScoreEvent("e$index", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi),
                    1, "1", false, "quarter", 0, emptySet(), index, 0)), false)
        }
        return DigitalScore("test-pending-frames", "TEST DATA", listOf(ScorePart("p", "Piano", measures)),
            emptyList(), ScoreSource("synthetic-pending-frame-test", "0".repeat(64), 1))
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
