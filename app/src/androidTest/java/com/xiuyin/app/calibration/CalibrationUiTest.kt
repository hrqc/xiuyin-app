package com.xiuyin.app.calibration

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppViewModel
import com.xiuyin.app.MainActivity
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.RecognitionEngineStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual emulator microphone metrics/UI only. Does not claim phone piano calibration accuracy. */
class CalibrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realInputProfilePersistsAndLeavingDoesNotKeepRecording() {
        assertTrue(Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.startsWith("generic"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val file = File(context.filesDir, "piano-calibration/piano-profile.xcp")
        val previous = file.takeIf { it.exists() }?.readBytes()
        lateinit var app: AppViewModel
        lateinit var calibration: CalibrationViewModel
        compose.activityRule.scenario.onActivity {
            app = ViewModelProvider(it)[AppViewModel::class.java]
            calibration = ViewModelProvider(it)[CalibrationViewModel::class.java]
        }
        try {
            compose.waitUntil(10_000) { !app.state.value.loading && !calibration.state.value.busy }
            assertFalse(calibration.state.value.caseConsent)
            compose.onNodeWithTag("nav_monitor").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("环境校准与误判案例"))
            compose.onNodeWithText("环境校准与误判案例").performClick()
            compose.waitUntil(15_000) { app.recognition.value.status in setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.ERROR) }
            assertEquals("真实本机模型准备失败：${app.recognition.value.message}", RecognitionEngineStatus.READY, app.recognition.value.status)
            assertEquals("准备模型不得启动收音", AudioStatus.IDLE, app.audio.value.status)
            compose.onNodeWithText("重新校准").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(20_000) { calibration.state.value.measuredSeconds >= 5.1 || calibration.state.value.error != null }
            assertNull(calibration.state.value.error)
            assertTrue(calibration.state.value.audioObservations > 0)
            compose.onNodeWithText("保存实际校准指标").performScrollTo().performClick()
            compose.waitUntil(10_000) { !calibration.state.value.busy && !calibration.state.value.calibrating && app.audio.value.status == AudioStatus.IDLE }
            assertNull(calibration.state.value.error)
            val current = requireNotNull(calibration.state.value.profile)
            assertEquals(current, CalibrationProfileStore(file.parentFile!!).load())
            assertTrue(current.observedSeconds >= 5.0)
            assertNull(current.latencyMs); assertNull(current.reverbSeconds); assertNull(current.pedalResidualSeconds)
            assertFalse(calibration.state.value.caseConsent)
            compose.onNodeWithTag("nav_home").performClick()
            assertEquals(AudioStatus.IDLE, app.audio.value.status)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { !ViewModelProvider(compose.activity)[CalibrationViewModel::class.java].state.value.busy }
            assertEquals(current, ViewModelProvider(compose.activity)[CalibrationViewModel::class.java].state.value.profile)
        } finally {
            compose.runOnUiThread { app.stopAudio("实际校准界面测试结束") }
            // Restore only the profile modified by this test, byte-for-byte; never clear the app/library.
            require(file.canonicalFile.parentFile == File(context.filesDir, "piano-calibration").canonicalFile)
            if (previous == null) { if (file.exists()) check(file.delete()) }
            else {
                val temp = File(file.parentFile, "restore-${UUID.randomUUID()}.tmp")
                temp.writeBytes(previous)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
