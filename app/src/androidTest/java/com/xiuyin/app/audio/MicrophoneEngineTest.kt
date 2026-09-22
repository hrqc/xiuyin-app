package com.xiuyin.app.audio

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.MainActivity
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL EMULATOR PCM PIPELINE ONLY. These tests do not enable the emulator host microphone,
 * play/capture a host recording, or establish phone/piano-room recognition accuracy.
 * The permission-denied context is an explicit test double; system-dialog denial is separate.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneEngineTest {
    private lateinit var context: Context
    private var activity: ActivityScenario<MainActivity>? = null
    private var engine: MicrophoneEngine? = null
    private val events = CopyOnWriteArrayList<String>()

    @Before
    fun prepareEmulatorOnly() {
        assertTrue(
            "Microphone integration tests are allowed only on the prepared emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        } else {
            throw AssertionError("This permission-grant test harness requires API 28+; the App minimum remains API 26")
        }
        // Foreground Activity is required by Android's microphone privacy rules.
        activity = ActivityScenario.launch(MainActivity::class.java)
        Log.i("XiuyinAudioTests", "REAL EMULATOR PCM PIPELINE; NOT A PHYSICAL PHONE OR PIANO ACCURACY TEST")
    }

    @After
    fun releaseOnlyThisTestEngine() {
        try {
            engine?.let { current ->
                current.close()
                waitUntil("close must finish releasing AudioRecord") { current.state.value.status == AudioStatus.IDLE }
            }
        } finally {
            activity?.close()
        }
        // Do not revoke target-package permission: Android can kill the instrumented process.
    }

    @Test
    fun deniedPermissionPathNeverStartsCapture() {
        val deniedContext = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        val current = newEngine(deniedContext)
        current.start()
        assertEquals(AudioStatus.PERMISSION_DENIED, current.state.value.status)
        assertEquals(0L, current.state.value.totalFrames)
        assertEquals(null, current.state.value.capturedAtNanos)
        current.permissionDenied()
        assertEquals(AudioStatus.PERMISSION_DENIED, current.state.value.status)
        assertEquals(FinalDecision.UNCERTAIN, current.state.value.decision)
        assertTrue(current.state.value.notes.isEmpty())
    }

    @Test
    fun realVirtualMicrophoneFramesHaveMetricsAndStopFreezesCapture() {
        val current = newEngine()
        current.start()
        waitForFrames(current)
        val first = current.state.value
        assertEquals(AudioStatus.LISTENING, first.status)
        assertEquals(48_000, first.sampleRate)
        assertEquals(1_024, first.frameSamples)
        assertFalse(first.deviceName.isNullOrBlank())
        assertNotNull(first.inputLevelDbfs)
        assertTrue(first.inputLevelDbfs!!.isFinite())
        assertNotNull(first.capturedAtNanos)
        assertTrue(first.processingMs != null && first.processingMs >= 0.0)
        assertEquals(null, first.audioLatencyMs)
        assertEquals(FinalDecision.UNCERTAIN, first.decision)
        assertTrue(first.notes.isEmpty())
        waitUntil("PCM timestamps must advance") {
            val next = current.state.value
            next.totalFrames > first.totalFrames && next.capturedAtNanos!! > first.capturedAtNanos!!
        }
        current.stop("模拟器采集测试已停止")
        waitUntil("stop releases the microphone") { current.state.value.status == AudioStatus.IDLE }
        val stopped = current.state.value
        SystemClock.sleep(250)
        assertEquals(stopped.totalFrames, current.state.value.totalFrames)
        assertEquals(stopped.capturedAtNanos, current.state.value.capturedAtNanos)
        assertEquals(null, current.state.value.inputLevelDbfs)
        assertEquals(null, current.state.value.processingMs)
    }

    @Test
    fun pcmConsumerFailureStopsCaptureAndPublishesError() {
        val current = newEngine()
        current.setPcmListener { _, _, _ -> throw IllegalStateException("TEST DATA controlled PCM consumer failure") }
        current.start()
        waitUntil("PCM consumer failure must propagate rather than leave diagnostic capture running") {
            current.state.value.status == AudioStatus.ERROR
        }
        assertTrue(current.state.value.statusMessage.contains("PCM识音回调失败"))
        assertEquals(FinalDecision.UNCERTAIN, current.state.value.decision)
        val failedFrameCount = current.state.value.totalFrames
        SystemClock.sleep(300)
        assertEquals(failedFrameCount, current.state.value.totalFrames)
        assertTrue(events.any { it.startsWith("ERROR") })
    }

    @Test
    fun repeatedStartAndRapidRestartRemainUsable() {
        val current = newEngine()
        current.start()
        waitForFrames(current)
        val before = current.state.value.totalFrames
        repeat(3) { current.start() }
        assertEquals(AudioStatus.LISTENING, current.state.value.status)
        assertTrue(current.state.value.totalFrames >= before)
        current.stop("快速重启前停止")
        current.start()
        waitForFrames(current)
        current.stop()
        current.stop()
        waitUntil("repeated stop is idempotent") { current.state.value.status == AudioStatus.IDLE }
        current.start()
        waitForFrames(current)
        assertTrue(events.none { it.startsWith("ERROR") })
    }

    @Test
    fun closeDuringStartupIsIdempotentAndPreventsFutureCapture() {
        val current = newEngine()
        current.start()
        current.close()
        current.close()
        waitUntil("close during startup completes") { current.state.value.status == AudioStatus.IDLE }
        val closed = current.state.value
        current.start()
        SystemClock.sleep(250)
        assertEquals(AudioStatus.IDLE, current.state.value.status)
        assertEquals(closed.totalFrames, current.state.value.totalFrames)
        assertEquals(closed.capturedAtNanos, current.state.value.capturedAtNanos)
    }

    private fun newEngine(source: Context = context): MicrophoneEngine = MicrophoneEngine(source) { level, message, detail ->
        events += "$level $message $detail"
    }.also { engine = it }

    private fun waitForFrames(current: MicrophoneEngine) {
        waitUntil("built-in virtual microphone must provide real PCM frames") {
            val snapshot = current.state.value
            if (snapshot.status in listOf(AudioStatus.ERROR, AudioStatus.UNAVAILABLE, AudioStatus.PERMISSION_DENIED, AudioStatus.SILENCED)) {
                throw AssertionError("Unexpected capture state: $snapshot; events=$events")
            }
            snapshot.status == AudioStatus.LISTENING && snapshot.totalFrames >= 2
        }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(20)
        }
        assertTrue("$message; snapshot=${engine?.state?.value}; events=$events", predicate())
    }
}