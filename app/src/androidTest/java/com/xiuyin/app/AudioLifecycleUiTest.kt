package com.xiuyin.app

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.audio.FinalDecision
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real emulator AudioRecord lifecycle verification. Never enables host microphone input. */
@RunWith(AndroidJUnit4::class)
class AudioLifecycleUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun backgroundAndLeavingAudioPagesReleaseCaptureAndResumeDoesNotRestart() {
        assertTrue(
            "Real PCM lifecycle tests must only run on the prepared emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true),
        )
        assertTrue("This permission harness requires API 28+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
        Log.i("XiuyinAudioTests", "REAL EMULATOR PCM LIFECYCLE; NOT PHONE/PIANO ACCURACY; HOST MICROPHONE UNCHANGED")

        lateinit var model: AppViewModel
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[AppViewModel::class.java]
        }
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("浏览本地曲库").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("nav_practice").performClick()
            compose.onNodeWithTag("audio_start").performScrollTo().assertIsEnabled().performClick()
            val firstCapture = awaitAudio(model, "first explicit start receives real virtual PCM") {
                it.status == AudioStatus.LISTENING && it.totalFrames > 0 && it.capturedAtNanos != null
            }
            assertEquals(FinalDecision.UNCERTAIN, firstCapture.decision)
            assertTrue(firstCapture.notes.isEmpty())
            awaitAudio(model, "real PCM frames advance before backgrounding") {
                it.status == AudioStatus.LISTENING && it.totalFrames > firstCapture.totalFrames
            }

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val stoppedInBackground = awaitAudio(model, "onStop releases microphone") { it.status == AudioStatus.IDLE }
            assertCaptureRemainsStopped(model, stoppedInBackground, "background")

            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("audio_status").performScrollTo()
                .assertTextEquals("⚪ 未运行 · 尚未开始采集")
            compose.onNodeWithTag("audio_start").performScrollTo().assertIsEnabled()
            assertCaptureRemainsStopped(model, stoppedInBackground, "resume must not restart without a tap")

            compose.onNodeWithTag("audio_start").performClick()
            awaitAudio(model, "explicit restart receives another real PCM stream") {
                it.status == AudioStatus.LISTENING && it.totalFrames > 0 && it.capturedAtNanos != null
            }
            compose.onNodeWithTag("nav_home").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("nav_home").assertIsSelected()
            val stoppedOnHome = awaitAudio(model, "leaving audio pages releases microphone") { it.status == AudioStatus.IDLE }
            assertCaptureRemainsStopped(model, stoppedOnHome, "home page")
            assertEquals(FinalDecision.UNCERTAIN, stoppedOnHome.decision)
            assertTrue(stoppedOnHome.notes.isEmpty())
        } finally {
            model.stopAudio("音频生命周期测试结束")
            // Do not revoke target-package permission; revocation can kill instrumentation.
        }
    }

    private fun awaitAudio(
        model: AppViewModel,
        description: String,
        predicate: (AudioSnapshot) -> Boolean,
    ): AudioSnapshot = try {
        runBlocking {
            withTimeout(10_000) { model.audio.first { predicate(it) } }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Timed out: $description; actual=${model.audio.value}", timeout)
    }

    private fun assertCaptureRemainsStopped(model: AppViewModel, baseline: AudioSnapshot, description: String) {
        assertEquals("$description must be idle before observing", AudioStatus.IDLE, baseline.status)
        // Observe the actual state stream for a bounded window: any resumed
        // recorder/frame event fails immediately. This is not a blind sleep.
        val unexpectedChange = runBlocking {
            withTimeoutOrNull(400) {
                model.audio.first { snapshot ->
                    snapshot.status != AudioStatus.IDLE ||
                        snapshot.totalFrames != baseline.totalFrames ||
                        snapshot.capturedAtNanos != baseline.capturedAtNanos
                }
            }
        }
        assertNull("Capture changed while stopped ($description): $unexpectedChange", unexpectedChange)
        assertEquals(baseline.totalFrames, model.audio.value.totalFrames)
        assertEquals(baseline.capturedAtNanos, model.audio.value.capturedAtNanos)
    }
}