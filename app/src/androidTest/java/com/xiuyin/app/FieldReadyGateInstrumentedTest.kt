package com.xiuyin.app

import android.Manifest
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.calibration.CalibrationViewModel
import com.xiuyin.app.pitch.RecognitionEngineStatus
import org.junit.Assert.*
import org.junit.Test

/** Real asset loading + emulator AudioRecord lifecycle; not phone/piano recognition accuracy. */
class FieldReadyGateInstrumentedTest {
    @Test fun loadingCannotStartMicAndCancelledLoadCannotStartLater() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var app: AppViewModel
            activity.onActivity {
                app = ViewModelProvider(it)[AppViewModel::class.java]
                assertFalse(app.startCompanionAudio())
                assertEquals(AudioStatus.IDLE, app.audio.value.status)
                app.prepareCompanionModel()
                assertEquals(RecognitionEngineStatus.LOADING, app.recognition.value.status)
                assertFalse("LOADING may not queue a future microphone start", app.startCompanionAudio())
                assertEquals(AudioStatus.IDLE, app.audio.value.status)
                app.stopAudio("TEST DATA cancellation during actual model load")
            }
            SystemClock.sleep(1600)
            assertEquals(AudioStatus.IDLE, app.audio.value.status)
            assertEquals(RecognitionEngineStatus.IDLE, app.recognition.value.status)
            assertEquals(0L, app.audio.value.totalFrames)
        }
    }

    @Test fun genuineModelReadyIsRequiredBeforeRealMicrophoneAndFailureEndsCalibration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var app: AppViewModel
            lateinit var calibration: CalibrationViewModel
            activity.onActivity {
                app = ViewModelProvider(it)[AppViewModel::class.java]
                calibration = ViewModelProvider(it)[CalibrationViewModel::class.java]
            }
            await("application initialized") { !app.state.value.loading }
            instrumentation.waitForIdleSync()
            activity.onActivity { app.prepareCompanionModel() }
            await("real model READY") { app.recognition.value.status == RecognitionEngineStatus.READY }
            assertEquals(AudioStatus.IDLE, app.audio.value.status)
            await("calibration storage loaded") { !calibration.state.value.busy }
            activity.onActivity {
                calibration.startCalibration()
                assertTrue(app.startCompanionAudio())
            }
            await("actual emulator PCM") { app.audio.value.status == AudioStatus.LISTENING && app.audio.value.totalFrames > 3 }
            activity.onActivity {
                // Explicit control-flow fault, not synthetic music or claimed physical queue-overflow evidence.
                calibration.inputUnavailable("TEST DATA Queue Overflow from engine")
                assertFalse(calibration.state.value.calibrating)
                assertTrue(calibration.state.value.error.orEmpty().contains("Queue Overflow"))
                app.stopAudio("TEST DATA input-failure verification complete")
            }
            await("actual microphone release") { app.audio.value.status == AudioStatus.IDLE }
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(label, condition())
    }
}
