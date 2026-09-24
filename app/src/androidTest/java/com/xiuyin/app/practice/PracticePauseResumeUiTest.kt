package com.xiuyin.app.practice

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppViewModel
import com.xiuyin.app.MainActivity
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.field.FieldDiagnostics
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.score.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TEST DATA score, real model assets and emulator AudioRecord; no host mic or fabricated recognition. */
class PracticePauseResumeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val practice get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]

    @Test fun pausePreparesModelLocallyAndBackgroundReturnStillRequiresContinue() = withPractice {
        startFromReady()
        compose.onNodeWithTag("companion_pause").performScrollTo().assertIsEnabled().performClick()
        awaitPausedReady()
        compose.onNodeWithTag("companion_resume_preparation").performScrollTo().assertTextContains("模型已就绪", substring = true)
        assertNoCapture("自动准备后仍暂停")
        val beforeResume = practice.state.value
        compose.onNodeWithTag("companion_resume").performScrollTo().assertIsEnabled().performClick()
        awaitActualInput()
        assertEquals(beforeResume.scoreId, practice.state.value.scoreId)
        assertEquals(beforeResume.revisionGeneration, practice.state.value.revisionGeneration)
        assertEquals(beforeResume.config, practice.state.value.config)
        assertEquals(beforeResume.cursor, practice.state.value.cursor)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
        assertEquals(CompanionPhase.PAUSED, practice.state.value.phase)
        assertNoCapture("后台", requireStoppedModel = true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        awaitPausedReady()
        assertNoCapture("返回前台准备完成，尚未点继续")
        compose.onNodeWithTag("companion_resume").performScrollTo().assertIsEnabled().performClick()
        awaitActualInput()
    }

    @Test fun leavingOrFinishingDuringPausePreparationCancelsTheModelWithoutLateCapture() = withPractice {
        startFromReady()
        val pause = clickAction("companion_pause")
        val home = clickAction("nav_home")
        // Invoke the real UI callbacks in one main turn, avoiding timing guesses or a delayed fake model.
        compose.runOnUiThread { assertTrue(pause()); assertTrue(home()) }
        compose.waitForIdle()
        compose.onNodeWithTag("nav_home").assertIsSelected()
        compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
        assertEquals(CompanionPhase.PAUSED, practice.state.value.phase)
        assertNoCapture("离页取消准备", requireStoppedModel = true)
        compose.onNodeWithTag("nav_practice").performClick()
        awaitPausedReady()
        assertNoCapture("回到原会话不可自动继续")
        compose.onNodeWithTag("companion_resume").performScrollTo().assertIsEnabled().performClick()
        awaitActualInput()
        val pauseAgain = clickAction("companion_pause")
        val finish = clickAction("companion_finish")
        compose.runOnUiThread { assertTrue(pauseAgain()); assertTrue(finish()) }
        compose.waitUntil(10_000) { !practice.state.value.busy && app.audio.value.status == AudioStatus.IDLE }
        assertNull(practice.state.value.error)
        compose.runOnUiThread { assertFalse(practice.hasActiveSession()) }
        assertEquals(CompanionPhase.READY, practice.state.value.phase)
        assertNoCapture("结束取消准备", requireStoppedModel = true)
        compose.onNodeWithTag("companion_resume").assertDoesNotExist()
    }

    private fun clickAction(tag: String): () -> Boolean {
        val interaction = compose.onNodeWithTag(tag)
        if (!tag.startsWith("nav_")) interaction.performScrollTo()
        val node = interaction.assertIsEnabled().fetchSemanticsNode()
        return requireNotNull(node.config[SemanticsActions.OnClick].action)
    }
    private fun startFromReady() {
        compose.waitUntil(15_000) { app.recognition.value.status in setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.ERROR) }
        assertEquals(app.recognition.value.message, RecognitionEngineStatus.READY, app.recognition.value.status)
        assertNoCapture("未点开始")
        compose.onNodeWithTag("companion_start").performScrollTo().assertIsEnabled().performClick()
        awaitActualInput()
    }
    private fun awaitActualInput() {
        compose.waitUntil(10_000) {
            (app.audio.value.status == AudioStatus.LISTENING && app.audio.value.totalFrames > 3) ||
                app.recognition.value.status == RecognitionEngineStatus.ERROR || practice.state.value.error != null
        }
        assertNull("真实暂停/继续失败", practice.state.value.error)
        assertNotEquals(app.recognition.value.message, RecognitionEngineStatus.ERROR, app.recognition.value.status)
        assertEquals(AudioStatus.LISTENING, app.audio.value.status)
        assertTrue(app.audio.value.totalFrames > 3)
        assertEquals(CompanionPhase.RUNNING, practice.state.value.phase)
    }
    private fun awaitPausedReady() {
        compose.waitUntil(15_000) {
            assertEquals("准备模型不能恢复业务跟谱", CompanionPhase.PAUSED, practice.state.value.phase)
            assertTrue("准备模型不能开启麦克风：${app.audio.value}", app.audio.value.status in setOf(AudioStatus.IDLE, AudioStatus.STOPPING))
            app.recognition.value.status in setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.ERROR)
        }
        assertEquals(app.recognition.value.message, RecognitionEngineStatus.READY, app.recognition.value.status)
        compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
    }
    private fun assertNoCapture(point: String, requireStoppedModel: Boolean = false) {
        val activeApp = app
        val baseline = activeApp.audio.value
        assertEquals(point, AudioStatus.IDLE, baseline.status)
        val unexpected = runBlocking { withTimeoutOrNull(500) {
            combine(activeApp.audio, activeApp.recognition) { audio, recognition -> audio to recognition }.first { (audio, recognition) ->
                audio.status != AudioStatus.IDLE || audio.totalFrames != baseline.totalFrames || audio.capturedAtNanos != baseline.capturedAtNanos ||
                    (requireStoppedModel && recognition.status != RecognitionEngineStatus.IDLE)
            }
        } }
        assertNull("$point 出现迟到采集或准备：$unexpected", unexpected)
    }
    private fun withPractice(test: () -> Unit) {
        assertTrue("仅模拟器，不启用宿主麦克风", Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.startsWith("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.waitUntil(10_000) { !app.state.value.loading }
        assertNull("不覆盖现有 Field 会话", FieldDiagnostics.get(context).state.value.activeSession)
        val id = UUID.randomUUID().toString()
        val reportRoot = File(context.filesDir, "practice-reports").canonicalFile
        val ownedReports = File(reportRoot, id).canonicalFile
        require(ownedReports.parentFile == reportRoot && !ownedReports.exists())
        val event = ScoreEvent("test-note", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(60),
            1, "1", false, "quarter", 0, emptySet(), 0, 0)
        val measure = ScoreMeasure("test-measure", "1", ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), listOf(event), false)
        val score = DigitalScore("test-pause-resume", "TEST DATA pause lifecycle", listOf(ScorePart("test-part", "Piano", listOf(measure))),
            emptyList(), ScoreSource("TEST DATA lifecycle", "0".repeat(64), 1))
        var failure: Throwable? = null
        try {
            compose.runOnUiThread { assertTrue(practice.open(id, "TEST DATA pause lifecycle", score, 0)) }
            compose.waitUntil(10_000) { !practice.state.value.busy }
            assertNull(practice.state.value.error)
            compose.onNodeWithTag("nav_practice").performClick()
            test()
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try {
                compose.runOnUiThread { app.stopAudio("暂停继续 UI 回归结束"); practice.pause() }
                compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
                require(ownedReports.canonicalFile.parentFile == reportRoot && ownedReports.name == id)
                if (ownedReports.exists()) check(ownedReports.deleteRecursively())
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
}