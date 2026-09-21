package com.xiuyin.app.practice

import android.app.Application
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
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime
import com.xiuyin.app.score.ScoreImportMetadata
import com.xiuyin.app.score.ScoreMetadataDatabase
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: synthetic model frames test lifecycle binding only, never microphone accuracy. */
class PracticeFrameLifecycleTest {
    @Test fun pausingAndResumingKeepsTheLockButDropsAudioFromTheOldStream() = withModel { model ->
        main { repeat(4) { model.accept(frame(it)) } }
        assertEquals(CompanionPhase.LOCKED, model.state.value.phase)
        assertNotNull(model.state.value.lastWrongFrame)
        assertNotNull(model.state.value.lastDecisionFrame)
        main { model.pause() }
        assertEquals(CompanionPhase.PAUSED, model.state.value.phase)
        assertNull(model.state.value.lastWrongFrame)
        assertNull(model.state.value.lastDecisionFrame)
        main { model.resume() }
        assertEquals(CompanionPhase.LOCKED, model.state.value.phase)
        assertNull(model.state.value.lastWrongFrame)
        assertEquals(1, model.state.value.wrong)
    }
    @Test fun framesAlreadyQueuedByWorkerCannotRecreateAnOldStreamErrorAfterInvalidation() = withModel { model ->
        main {
            val worker = Thread { repeat(4) { model.accept(frame(it)) } }
            worker.start()
            worker.join(2000)
            assertFalse("合成帧入队线程没有及时结束", worker.isAlive)
            model.invalidateInputFrames()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, model.state.value.wrong)
        assertNull(model.state.value.lastWrongFrame)
        main { repeat(4) { model.accept(frame(it)) } }
        assertEquals(1, model.state.value.wrong)
        assertNotNull(model.state.value.lastWrongFrame)
        // Audio failure may call the invalidation hook directly from its worker thread.
        val worker = Thread { model.invalidateInputFrames() }
        worker.start(); worker.join(2000)
        assertFalse(worker.isAlive)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull(model.state.value.lastWrongFrame)
        assertNull(model.state.value.lastDecisionFrame)
    }
    @Test fun runningAndPausedSessionsRejectReplacementUntilExplicitlyFinished() = withModel { model ->
        val originalId = requireNotNull(model.state.value.scoreId)
        val otherId = UUID.randomUUID().toString()
        try {
            main {
                assertTrue(model.hasActiveSession())
                assertFalse(model.open(otherId, "TEST DATA 另一首", score(), 1))
            }
            assertEquals(originalId, model.state.value.scoreId)
            assertEquals(CompanionPhase.RUNNING, model.state.value.phase)
            main {
                model.pause()
                assertTrue(model.hasActiveSession())
                assertFalse(model.open(otherId, "TEST DATA 另一首", score(), 1))
            }
            assertEquals(originalId, model.state.value.scoreId)
            assertEquals(CompanionPhase.PAUSED, model.state.value.phase)
            assertNotNull(model.state.value.error)
            main { model.finish(); assertFalse(model.hasActiveSession()) }
            assertEquals(CompanionPhase.READY, model.state.value.phase)
            assertNull(model.state.value.error)
            assertNull(model.state.value.latestReport)
            main { assertTrue(model.open(otherId, "TEST DATA 另一首", score(), 1)) }
            awaitPrepared(model)
            assertEquals(otherId, model.state.value.scoreId)
            assertEquals("TEST DATA 另一首", model.state.value.title)
            assertEquals(1L, model.state.value.revisionGeneration)
            assertNull(model.state.value.error)
            main { assertFalse(model.hasActiveSession()) }
        } finally { removeTestReports(otherId) }
    }
    @Test fun failedActualReportWriteKeepsSessionUntilMetadataRestorationAndSuccessfulRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        val metadata = ScoreMetadataDatabase(context).use {
            it.ensureImported(sourceId, ScoreImportMetadata(originalTitle = "TEST DATA 报告失败重试"))
        }
        val otherId = UUID.randomUUID().toString()
        try {
            withModel(metadata.scoreId) { model ->
                main { repeat(4) { model.accept(frame(it)) } }
                assertEquals(1, model.state.value.confirmedInputs)
                assertEquals(1, model.state.value.wrong)
                ScoreMetadataDatabase(context).use { it.markDeleted(metadata.scoreId) }
                main {
                    model.finish()
                    assertTrue(model.state.value.busy)
                    assertTrue(model.hasActiveSession())
                    assertFalse(model.open(otherId, "TEST DATA 不得提前切谱", score(), 0))
                }
                awaitPrepared(model)
                assertTrue(model.state.value.error.orEmpty().contains("此乐谱已删除"))
                assertEquals(metadata.scoreId, model.state.value.scoreId)
                main {
                    assertTrue(model.hasActiveSession())
                    assertFalse(model.open(otherId, "TEST DATA 保存失败仍不得切谱", score(), 0))
                }
                val reports = PracticeReportStore(File(context.filesDir, "practice-reports"))
                assertTrue(reports.load(metadata.scoreId).isEmpty())
                val restored = ScoreMetadataDatabase(context).use { it.ensureImported(sourceId, restoreDeleted = true) }
                assertEquals(metadata.scoreId, restored.scoreId)
                main {
                    model.finish()
                    assertTrue(model.state.value.busy)
                    assertTrue(model.hasActiveSession())
                }
                awaitPrepared(model)
                assertNull(model.state.value.error)
                assertEquals(CompanionPhase.COMPLETED, model.state.value.phase)
                main { assertFalse(model.hasActiveSession()) }
                val saved = reports.load(metadata.scoreId).single()
                assertEquals(1, saved.confirmedInputs)
                assertEquals(1, saved.wrong)
                assertEquals(saved, model.state.value.latestReport)
                assertEquals(saved.endedAtMillis, ScoreMetadataDatabase(context).use { it.get(metadata.scoreId)!!.lastPracticedAt })
                main { assertTrue(model.open(otherId, "TEST DATA 保存成功后切谱", score(), 0)) }
                awaitPrepared(model)
                assertNull(model.state.value.error)
                assertEquals(otherId, model.state.value.scoreId)
            }
        } finally {
            removeTestReports(otherId)
            // Delete only the exact random row created here; user rows and naming counters are untouched.
            ScoreMetadataDatabase(context).use {
                assertEquals(1, it.writableDatabase.delete("score_metadata", "score_id = ? AND source_id = ?", arrayOf(metadata.scoreId, sourceId)))
            }
        }
    }
    private fun awaitPrepared(model: PracticeViewModel) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (model.state.value.busy) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "测试时间线准备超时" }
            Thread.sleep(10)
        }
    }
    private fun withModel(scoreId: String = UUID.randomUUID().toString(), test: (PracticeViewModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var model: PracticeViewModel
        main {
            model = PracticeViewModel(context)
            store.put("test-practice-frames", model)
            assertTrue(model.open(scoreId, "TEST DATA 音频流生命周期", score(), 0))
        }
        try {
            awaitPrepared(model)
            assertNull(model.state.value.error)
            main { model.start(PracticeConfig(CompanionMode.STOP_ON_ERROR)) }
            assertEquals(CompanionPhase.RUNNING, model.state.value.phase)
            test(model)
        } finally { main { model.pause(); store.clear() }; removeTestReports(scoreId) }
    }
    private fun removeTestReports(scoreId: String) {
        require(scoreId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "practice-reports").canonicalFile
        val owned = File(root, scoreId)
        require(owned.canonicalFile.parentFile == root)
        if (owned.exists()) check(owned.deleteRecursively())
    }
    private fun frame(index: Int) = PianoRecognitionFrame(index.toLong(), index * 0.032, 1.12,
        if (index == 0) setOf(66) else emptySet(), setOf(66),
        listOf(PianoNoteEvidence(66, 0.95f, 0.95f, index == 0, true)),
        if (index == 0) RecognitionState.ATTACK else RecognitionState.SUSTAIN,
        false, 1.0, FloatArray(88), FloatArray(88))
    private fun score(): DigitalScore {
        val measures = listOf(60, 62).mapIndexed { index, midi ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)),
                listOf(ScoreEvent("e$index", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi),
                    1, "1", false, "quarter", 0, emptySet(), index, 0)), false)
        }
        return DigitalScore("test-stream-binding", "TEST DATA", listOf(ScorePart("p", "Piano", measures)),
            emptyList(), ScoreSource("synthetic-lifecycle-test", "0".repeat(64), 1))
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}