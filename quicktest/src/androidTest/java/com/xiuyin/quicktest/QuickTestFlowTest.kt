package com.xiuyin.quicktest

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.PianoModel
import com.xiuyin.app.pitch.RecognitionEngineStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Real emulator AudioRecord and unchanged piano model; no injected PCM or score.
 * Run this class with RECORD_AUDIO initially revoked, before instrumentation starts.
 * Subsequent cases grant it explicitly; revoking during instrumentation kills the process.
 * Silence is valid recording evidence, never evidence of piano or cents accuracy.
 * Activity recreation below is NOT an OS process-death recovery test.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickTestFlowTest {
    @get:Rule val compose = createAndroidComposeRule<QuickTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var model: QuickTestViewModel

    @After
    fun releaseOnlyThisTestsCaptureAndKeepEvidence() {
        if (::model.isInitialized) {
            instrumentation.runOnMainSync { model.onForeground(false) }
            await("test cleanup releases real microphone") {
                model.audioState.value.status !in setOf(AudioStatus.STARTING, AudioStatus.LISTENING, AudioStatus.STOPPING)
            }
        }
        // Session evidence, failure records and this test's save-copy remain available for review.
    }

    @Test
    fun test00_systemPermissionDenialNeverStartsRecordingAndAllowsRetry() {
        attachModel()
        assertEquals("Revoke RECORD_AUDIO before this instrumentation run; do not revoke in-process",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        ready()
        val before = model.state.value.sessions.map { it.id }.toSet()
        val automationInfo = instrumentation.uiAutomation.serviceInfo
        automationInfo.flags = automationInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = automationInfo
        compose.onNodeWithTag("quick_start").performScrollTo().assertIsEnabled().performClick()
        var clickedDeny = false
        await("system permission denial is delivered without capture") {
            if (!clickedDeny) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val ids = listOf("com.android.permissioncontroller:id/permission_deny_button",
                    "com.google.android.permissioncontroller:id/permission_deny_button",
                    "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
                    "com.google.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")
                val deny = ids.asSequence().flatMap { id -> root?.findAccessibilityNodeInfosByViewId(id).orEmpty().asSequence() }
                    .firstOrNull { it.isVisibleToUser && it.isEnabled }
                if (deny != null) clickedDeny = deny.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            model.state.value.error?.contains("权限") == true
        }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertEquals(0L, model.state.value.receivedSamples)
        assertEquals(0L, model.state.value.modelFrames)
        assertEquals("Permission refusal must not create a recording session", before, model.state.value.sessions.map { it.id }.toSet())
        assertNotRecordingFor("after permission denial")
        compose.onNodeWithTag("quick_start").performScrollTo().assertIsEnabled()
        // Android may immediately return a prior persistent denial without another dialog.
        assertTrue("Permission outcome must be visible", model.state.value.error.orEmpty().contains("权限"))
    }

    @Test
    fun test10_realCaptureMarkStopExportShareCancelAndVerifiedSave() {
        attachModel()
        grantPermission()
        ready()
        val comment = "TEST DATA real silent emulator capture ${UUID.randomUUID()}"
        startCapture(comment)
        compose.onNodeWithTag("quick_mark").performScrollTo().assertIsEnabled().performClick()
        await("problem marker accepted") { model.state.value.markedProblems == 1 }
        compose.onNodeWithTag("quick_stop").performScrollTo().assertIsEnabled().performClick()
        val zip = saved()
        val summary = verifyArchive(zip)
        assertEquals("COMPLETED", summary.getString("status"))
        assertEquals("USER_ENDED", summary.getString("reason"))
        assertTrue(summary.getBoolean("captureComplete"))
        assertEquals(1L, summary.getLong("markCount"))
        assertEquals(summary.getLong("sampleCount"), model.state.value.receivedSamples)
        assertEquals(summary.getLong("frameCount"), model.state.value.modelFrames)
        ZipFile(zip).use { archive ->
            val metadata = JSONObject(entryBytes(archive, "metadata.json").toString(Charsets.UTF_8))
            assertEquals(comment, metadata.getString("userComment"))
            assertEquals(PianoModel.MODEL_SHA256, metadata.getString("modelSha256"))
            assertEquals("FREE_AUDIO_COLLECTION_NO_SCORE_NO_ACCURACY_GRADING", metadata.getString("testScope"))
            assertFalse(metadata.getBoolean("networkUpload"))
            val marks = entryBytes(archive, "marks.jsonl").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(1, marks.size)
            JSONObject(marks.single())
            val ending = entryBytes(archive, "events.jsonl").toString(Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() }.map(::JSONObject)
                .filter { it.optString("kind") == "RECOGNITION_END" }.toList()
            assertEquals("Exactly one real pre-stop engine snapshot is preserved", 1, ending.size)
            val beforeStop = ending.single().getJSONObject("details")
            assertEquals(RecognitionEngineStatus.RUNNING.name, beforeStop.getString("statusBeforeStop"))
            assertTrue(beforeStop.getString("messageBeforeStop").isNotBlank())
            assertEquals(summary.getLong("frameCount"), beforeStop.getLong("frameCount"))
            assertEquals(summary.getLong("sampleCount"), beforeStop.getLong("submittedSamples"))
            assertEquals(beforeStop.getLong("submittedSamples"), beforeStop.getLong("processedSamples"))
        }
        assertNotRecordingFor("after explicit stop")

        val sourceHash = sha(zip.readBytes())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", zip)
        val sharedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("Actual provider can read the actual generated ZIP", sourceHash, sha(sharedBytes))
        compose.onNodeWithTag("quick_share").performScrollTo().assertIsEnabled().performClick()
        await("Android share chooser is actually shown") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("return from cancelled share") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
        }
        assertEquals("Cancelling share preserves original evidence", sourceHash, sha(zip.readBytes()))
        assertNotRecordingFor("return from share chooser")

        instrumentation.runOnMainSync { model.saveTo(Uri.parse("content://com.xiuyin.quicktest.unavailable-test-destination/target")) }
        await("unavailable save destination returns an actionable error") {
            model.state.value.phase != QuickPhase.SAVING && model.state.value.error != null
        }
        assertEquals("Failed save cannot destroy source ZIP", sourceHash, sha(zip.readBytes()))
        val copy = File(zip.parentFile, "verified-test-copy-${UUID.randomUUID()}.zip")
        val destination = FileProvider.getUriForFile(context, "${context.packageName}.exports", copy)
        instrumentation.runOnMainSync { model.saveTo(destination) }
        await("actual file write and readback finish") { model.state.value.phase != QuickPhase.SAVING }
        assertNull(model.state.value.error)
        assertEquals(sourceHash, sha(copy.readBytes()))
        assertEquals(sourceHash, sha(zip.readBytes()))
    }

    @Test
    fun test20_backgroundEndsCaptureAndReturnDoesNotRestart() {
        attachModel()
        grantPermission()
        ready()
        startCapture("TEST DATA background interruption ${UUID.randomUUID()}")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val zip = saved()
        val summary = verifyArchive(zip)
        assertEquals("INTERRUPTED", summary.getString("status"))
        assertEquals("APP_BACKGROUND", summary.getString("reason"))
        assertFalse(summary.getBoolean("captureComplete"))
        val samples = model.state.value.receivedSamples
        val archiveHash = sha(zip.readBytes())
        assertNotRecordingFor("background")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("quick_share").performScrollTo().assertIsEnabled()
        assertNotRecordingFor("foreground return")
        assertEquals(samples, model.state.value.receivedSamples)
        assertEquals(archiveHash, sha(zip.readBytes()))
    }

    @Test
    fun test30_savedSessionSurvivesActivityRecreationAndCanBeSelectedAgain() {
        attachModel()
        grantPermission()
        ready()
        startCapture("TEST DATA activity recreation, NOT process death ${UUID.randomUUID()}")
        compose.onNodeWithTag("quick_stop").performScrollTo().performClick()
        val zip = saved()
        val summary = verifyArchive(zip)
        val id = summary.getString("sessionId")
        val originalHash = sha(zip.readBytes())
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { model = it.viewModel }
        await("saved session remains in recent history") { model.state.value.sessions.any { it.id == id } }
        assertNotRecordingFor("activity recreation")
        assertEquals(originalHash, sha(zip.readBytes()))
        instrumentation.runOnMainSync { model.selectSession(id) }
        val reopened = saved()
        val reopenedSummary = verifyArchive(reopened)
        assertEquals(id, reopenedSummary.getString("sessionId"))
        assertEquals(summary.getLong("sampleCount"), reopenedSummary.getLong("sampleCount"))
        assertEquals(summary.getLong("frameCount"), reopenedSummary.getLong("frameCount"))
        assertNotRecordingFor("history re-export")
    }

    @Test
    fun test40_corruptOwnedHistoryIndexDoesNotLoseTheCurrentRecordingPackage() {
        attachModel()
        grantPermission()
        ready()
        startCapture("TEST DATA current real recording during an isolated history-index fault ${UUID.randomUUID()}")

        val sessions = File(context.filesDir, "quicktest/sessions").canonicalFile
        val id = UUID.randomUUID().toString()
        val fixture = File(sessions, id).canonicalFile
        val index = File(fixture, "session.properties")
        assertEquals("Fault fixture is confined to a new direct session child", sessions, fixture.parentFile)
        assertFalse("Never overwrite an existing session", fixture.exists())
        assertTrue("Create only this test's isolated fault fixture", fixture.mkdir())
        val completed = try {
            // Intentionally invalid history metadata only; no invented PCM or recognition evidence.
            index.writeText("""
                # TEST DATA: isolated corrupt historical index; removed by this test's finally block.
                id=$id
                startedAtMillis=TEST_DATA_INVALID_TIMESTAMP
                endedAtMillis=
                status=COMPLETED
                sampleCount=0
                frameCount=0
                markCount=0
                error=
                reason=TEST_DATA_CORRUPT_HISTORY_INDEX
            """.trimIndent() + "\n", Charsets.UTF_8)
            assertEquals(QuickPhase.RECORDING, model.state.value.phase)
            compose.onNodeWithTag("quick_stop").performScrollTo().assertIsEnabled().performClick()
            await("current package survives an explicit history-index read failure") {
                model.state.value.phase in setOf(QuickPhase.SAVED, QuickPhase.ERROR)
            }
            assertEquals("History failure cannot turn a valid new ZIP into a failed capture", QuickPhase.SAVED, model.state.value.phase)
            val file = requireNotNull(model.state.value.exportFile)
            val summary = verifyArchive(file)
            assertEquals("COMPLETED", summary.getString("status"))
            assertEquals("USER_ENDED", summary.getString("reason"))
            assertTrue(summary.getBoolean("captureComplete"))
            assertTrue("Current session must be distinct from the corrupt test fixture", summary.getString("sessionId") != id)
            assertTrue("History warning must be visible, not swallowed", model.state.value.error.orEmpty().contains("历史列表读取失败"))
            compose.onNodeWithTag("quick_error").performScrollTo().assertTextContains("历史列表读取失败", substring = true)
            compose.onNodeWithTag("quick_share").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("quick_save").performScrollTo().assertIsEnabled()
            assertNotRecordingFor("after finishing with a corrupt history index")
            file
        } finally {
            // Never recurse or touch another session: remove only the file and UUID directory we created.
            assertEquals(sessions, fixture.canonicalFile.parentFile)
            assertEquals(id, fixture.name)
            assertEquals(fixture, index.canonicalFile.parentFile)
            if (index.exists()) assertTrue("Remove only this test's corrupt index", index.delete())
            assertTrue("Only the owned fault directory must remain empty", fixture.delete())
        }
        assertFalse(fixture.exists())
        val sealedManifestHash = ZipFile(completed).use { sha(entryBytes(it, "manifest.json")) }
        val sessionId = ZipFile(completed).use { JSONObject(entryBytes(it, "summary.json").toString(Charsets.UTF_8)).getString("sessionId") }
        instrumentation.runOnMainSync { model.selectSession(sessionId) }
        val reopened = saved()
        assertEquals(sessionId, verifyArchive(reopened).getString("sessionId"))
        // ZIP entry timestamps may change on re-export; sealed content identities must not.
        assertEquals("Reopening after fixture cleanup preserves the same evidence", sealedManifestHash,
            ZipFile(reopened).use { sha(entryBytes(it, "manifest.json")) })
    }

    private fun attachModel() {
        assertTrue("Real mic tests require a prepared emulator; never enable host microphone here",
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true))
        assertTrue("Runtime permission harness requires API 28+", Build.VERSION.SDK_INT >= 28)
        assertEquals("Independent package must not touch the full app", "com.xiuyin.quicktest", context.packageName)
        compose.activityRule.scenario.onActivity { model = it.viewModel }
    }

    private fun grantPermission() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun ready() {
        await("actual model prepares without opening microphone") {
            model.state.value.phase in setOf(QuickPhase.READY, QuickPhase.SAVED, QuickPhase.ERROR)
                && model.modelState.value.status in setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.ERROR)
        }
        assertNull(model.state.value.error)
        assertEquals(RecognitionEngineStatus.READY, model.modelState.value.status)
        assertNotRecordingFor("before an explicit start")
    }

    private fun startCapture(comment: String) {
        compose.onNodeWithTag("quick_comment").performScrollTo().performTextReplacement(comment)
        compose.onNodeWithTag("quick_start").performScrollTo().assertIsEnabled().performClick()
        await("real AudioRecord samples and real 88-key model frames advance") {
            model.state.value.phase == QuickPhase.ERROR ||
                (model.state.value.phase == QuickPhase.RECORDING && model.state.value.receivedSamples >= 96_000L && model.state.value.modelFrames > 0)
        }
        assertNull(model.state.value.error)
        assertEquals(QuickPhase.RECORDING, model.state.value.phase)
        assertEquals(AudioStatus.LISTENING, model.audioState.value.status)
        assertTrue(model.state.value.receivedSamples > 0)
        assertTrue(model.state.value.modelFrames > 0)
    }

    private fun saved(): File {
        await("stop drains model work, seals recording and builds a real data package") {
            model.state.value.phase in setOf(QuickPhase.SAVED, QuickPhase.ERROR)
        }
        assertNull(model.state.value.error)
        assertEquals(QuickPhase.SAVED, model.state.value.phase)
        return requireNotNull(model.state.value.exportFile).also { assertTrue(it.isFile && it.length() > 0) }
    }

    private fun verifyArchive(file: File): JSONObject = ZipFile(file).use { archive ->
        val expected = setOf("audio.wav", "metadata.json", "recognition.jsonl", "marks.jsonl", "events.jsonl", "summary.json", "session.properties", "manifest.json")
        assertEquals(expected, archive.entries().asSequence().map { it.name }.toSet())
        val manifest = JSONObject(entryBytes(archive, "manifest.json").toString(Charsets.UTF_8))
        val records = manifest.getJSONArray("files")
        assertEquals(expected.size - 1, records.length())
        val named = mutableSetOf<String>()
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val path = record.getString("path")
            assertTrue("No duplicate hash entries", named.add(path))
            val bytes = entryBytes(archive, path)
            assertEquals(path, record.getLong("bytes"), bytes.size.toLong())
            assertEquals(path, record.getString("sha256"), sha(bytes))
        }
        assertEquals(expected - "manifest.json", named)
        val summary = JSONObject(entryBytes(archive, "summary.json").toString(Charsets.UTF_8))
        assertEquals(manifest.getString("sessionId"), summary.getString("sessionId"))
        assertEquals(manifest.getString("status"), summary.getString("status"))
        assertEquals("NOT_ASSESSED", summary.getString("accuracyVerdict"))
        val sampleCount = summary.getLong("sampleCount")
        assertTrue("Actual PCM is nonempty and within the advertised limit", sampleCount in 1..2_880_000L)
        val wav = entryBytes(archive, "audio.wav")
        assertEquals("PCM file length", 44L + sampleCount * 2, wav.size.toLong())
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(48_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(sampleCount * 2, header.getInt(40).toLong())
        val frames = entryBytes(archive, "recognition.jsonl").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue("Actual inference frames must be present", frames.isNotEmpty())
        assertEquals(summary.getLong("frameCount"), frames.size.toLong())
        for (line in frames) {
            val frame = JSONObject(line)
            for (key in listOf("rawOnsetScores", "rawFrameScores")) {
                val values = frame.getJSONArray(key)
                assertEquals("All 88 keys retained", 88, values.length())
                for (keyIndex in 0 until 88) assertTrue(values.getDouble(keyIndex).isFinite())
            }
        }
        summary
    }

    private fun assertNotRecordingFor(description: String) {
        val initial = model.audioState.value
        assertTrue(description, initial.status !in setOf(AudioStatus.STARTING, AudioStatus.LISTENING, AudioStatus.STOPPING))
        runBlocking {
            val until = System.nanoTime() + 400_000_000L
            while (System.nanoTime() < until) {
                val current = model.audioState.value
                assertEquals(description, initial.status, current.status)
                assertEquals(description, initial.totalFrames, current.totalFrames)
                assertEquals(description, initial.capturedAtNanos, current.capturedAtNanos)
                delay(20)
            }
        }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        try {
            runBlocking { withTimeout(60_000) { while (!predicate()) delay(25) } }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$description; UI=${if (::model.isInitialized) model.state.value else "not initialized"}; audio=${if (::model.isInitialized) model.audioState.value else "not initialized"}", error)
        }
    }

    private fun entryBytes(archive: ZipFile, name: String): ByteArray {
        val entry = archive.getEntry(name)
        assertNotNull(name, entry)
        return archive.getInputStream(entry).use { it.readBytes() }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
