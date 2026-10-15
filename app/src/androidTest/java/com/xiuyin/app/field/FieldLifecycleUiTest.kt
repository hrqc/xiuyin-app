package com.xiuyin.app.field

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppViewModel
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.MainActivity
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.practice.PracticeViewModel
import com.xiuyin.app.practice.CompanionMode
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.PracticeReportStore
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.score.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

/** Real local storage and emulator PCM. No host mic, no physical-piano accuracy claim. */
class FieldLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val field get() = ViewModelProvider(compose.activity)[FieldTestViewModel::class.java]
    private val practice get() = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
    private fun emulatorOnly() {
        assertTrue("此用例只运行于明确的模拟器", Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.startsWith("generic"))
    }
    private fun openField() {
        compose.waitUntil(10_000) { !app.state.value.loading && !field.operation.value.busy }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("open_field_test").performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun ready() {
        compose.waitUntil(15_000) { app.recognition.value.status in setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.ERROR) }
        assertEquals("实际模型：${app.recognition.value.message}", RecognitionEngineStatus.READY, app.recognition.value.status)
    }
    private fun awake(): Boolean = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    private fun scroll(tag: String) { compose.onNodeWithTag("field_content").performScrollToNode(hasTestTag(tag)) }

    private fun diagnosticSnapshot(point: String, sessionId: String?): JSONObject {
        val audio = app.audio.value
        val recognition = app.recognition.value
        val data = field.state.value
        val operation = field.operation.value
        val practiceState = practice.state.value
        return JSONObject().put("point", point).put("timestampMillis", System.currentTimeMillis())
            .put("version", BuildConfig.VERSION_NAME).put("gitCommit", BuildConfig.GIT_COMMIT)
            .put("testSessionId", sessionId ?: JSONObject.NULL)
            .put("audio", JSONObject().put("status", audio.status.name).put("message", audio.statusMessage)
                .put("totalFrames", audio.totalFrames).put("capturedAtNanos", audio.capturedAtNanos ?: JSONObject.NULL)
                .put("device", audio.deviceName ?: JSONObject.NULL).put("route", audio.actualInputRoute ?: JSONObject.NULL))
            .put("recognition", JSONObject().put("status", recognition.status.name).put("message", recognition.message)
                .put("sessionId", recognition.sessionId).put("frames", recognition.frameCount)
                .put("queueOccupancy", recognition.queueOccupancy).put("queueCapacity", recognition.queueCapacity)
                .put("droppedFrames", recognition.droppedFrames).put("overflowCount", recognition.overflowCount))
            .put("field", JSONObject().put("activeSessionId", data.activeSession?.id ?: JSONObject.NULL)
                .put("busy", data.busy)
                .put("error", data.error ?: JSONObject.NULL).put("message", data.message ?: JSONObject.NULL)
                .put("operationError", operation.error ?: JSONObject.NULL).put("writerQueue", data.writerQueueOccupancy)
                .put("writerQueueCapacity", data.writerQueueCapacity).put("storageWritable", data.storageWritable)
                .put("freeSpaceBytes", data.freeSpaceBytes).put("totalBytes", data.totalBytes).put("clipConsent", data.clipConsent))
            .put("operation", JSONObject().put("busy", operation.busy).put("error", operation.error ?: JSONObject.NULL)
                .put("progressMessage", operation.progressMessage ?: JSONObject.NULL)
                .put("message", operation.message ?: JSONObject.NULL)
                .put("exportedSessionId", operation.exportedSessionId ?: JSONObject.NULL)
                .put("exportedFile", operation.exportedFile?.absolutePath ?: JSONObject.NULL)
                .put("exportedFileExists", operation.exportedFile?.isFile ?: JSONObject.NULL)
                .put("exportedFileBytes", operation.exportedFile?.takeIf { it.isFile }?.length() ?: JSONObject.NULL))
            .put("practice", JSONObject().put("phase", practiceState.phase.name).put("locked", practiceState.locked)
                .put("cursor", practiceState.cursor).put("decision", practiceState.decision.name)
                .put("error", practiceState.error ?: JSONObject.NULL).put("reason", practiceState.reason)
                .put("confirmedInputs", practiceState.confirmedInputs))
            .put("appError", app.state.value.error ?: JSONObject.NULL)
    }
    private fun exportWithBoundedCompletion(sessionId: String): File {
        scroll("field_export_$sessionId")
        val activeField = field
        val startedAtMillis = System.currentTimeMillis()
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        var outcome = "FAILED"
        var originalFailure: Throwable? = null
        try {
            compose.onNodeWithTag("field_export_$sessionId").assertIsEnabled().performClick()
            // The writer creates and verifies both ZIP generations before the UI operation completes.
            // A first-generation ExportSuccess log is not completion, and this never retries the click.
            compose.waitUntil(30_000) {
                val operation = activeField.operation.value
                !operation.busy && (operation.error != null || activeField.state.value.error != null ||
                    (operation.exportedSessionId == sessionId && operation.exportedFile?.let { it.isFile && it.length() > 0L } == true))
            }
            val operation = activeField.operation.value
            assertFalse("导出操作必须实际结束", operation.busy)
            assertNull("实际导出结束后必须清除进行中提示", operation.progressMessage)
            assertNull("导出错误不能放行", operation.error)
            assertNull("诊断错误不能放行", activeField.state.value.error)
            assertEquals("导出必须属于本次会话", sessionId, operation.exportedSessionId)
            val zip = requireNotNull(operation.exportedFile)
            assertEquals("必须返回本次会话的最终 ZIP", "field-$sessionId.zip", zip.name)
            assertTrue("必须实际生成非空 ZIP", zip.isFile && zip.length() > 0L)
            outcome = "EXPORT_OPERATION_COMPLETED_ONLY"
            return zip
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            val endedNanos = SystemClock.elapsedRealtimeNanos()
            try {
                val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "field-ui-export-timings")
                check(folder.isDirectory || folder.mkdirs())
                val evidence = JSONObject().put("sessionId", sessionId).put("version", BuildConfig.VERSION_NAME)
                    .put("gitCommit", BuildConfig.GIT_COMMIT).put("buildNumber", BuildConfig.VERSION_CODE)
                    .put("startedAtMillis", startedAtMillis).put("finishedAtMillis", System.currentTimeMillis())
                    .put("startedElapsedRealtimeNanos", startedNanos).put("finishedElapsedRealtimeNanos", endedNanos)
                    .put("elapsedMs", (endedNanos - startedNanos) / 1e6).put("waitTimeoutMillis", 30_000)
                    .put("outcome", outcome).put("failure", originalFailure?.stackTraceToString() ?: JSONObject.NULL)
                    .put("snapshot", diagnosticSnapshot("export-wait-terminal-before-failure-cleanup", sessionId))
                    .put("musicAcceptance", "UNVERIFIED").put("physicalPhoneAcceptance", "UNVERIFIED")
                    .put("scope", "One real UI export click and bounded operation wait; full ZIP assertions continue in the test. No musical or full-test pass claim.")
                val target = File(folder, "field-export-$sessionId-$startedAtMillis-${UUID.randomUUID()}.json")
                check(target.createNewFile()) { "导出计时证据已存在，不能覆盖" }
                target.writeText(evidence.toString(2))
            } catch (evidenceError: Throwable) {
                val failure = originalFailure
                if (failure != null) failure.addSuppressed(evidenceError) else throw evidenceError
            }
        }
    }
    private fun assertStillListening(point: String, sessionId: String) {
        compose.waitForIdle()
        val snapshot = diagnosticSnapshot(point, sessionId).toString(2)
        assertNull("Field 不应在反馈时故障：$snapshot", field.state.value.error)
        assertNull("App 不应在反馈时故障：$snapshot", app.state.value.error)
        assertNull("反馈操作不应失败：$snapshot", field.operation.value.error)
        assertNotEquals("识别引擎不应在反馈时故障：$snapshot", RecognitionEngineStatus.ERROR, app.recognition.value.status)
        assertEquals("反馈不应停止当前收音：$snapshot", AudioStatus.LISTENING, app.audio.value.status)
        assertEquals("反馈不能切换现场会话：$snapshot", sessionId, field.state.value.activeSession?.id)
    }
    private fun awaitAnother256RealModelFrames(sessionId: String) {
        val activeApp = app
        val activeField = field
        val baseline = activeApp.recognition.value
        val targetFrames = baseline.frameCount + 256L
        try {
            // Observe real inference progress; elapsed time alone never satisfies this regression.
            compose.waitUntil(25_000) {
                val audio = activeApp.audio.value
                val recognition = activeApp.recognition.value
                val data = activeField.state.value
                val operation = activeField.operation.value
                val fault = when {
                    audio.status != AudioStatus.LISTENING -> "真实收音已停止：${audio.status} / ${audio.statusMessage}"
                    data.error != null -> "现场诊断写入错误：${data.error}"
                    operation.error != null -> "现场操作错误：${operation.error}"
                    activeApp.state.value.error != null -> "应用数据错误：${activeApp.state.value.error}"
                    recognition.status !in setOf(RecognitionEngineStatus.WARMING_UP, RecognitionEngineStatus.RUNNING) -> "模型停止处理：${recognition.status} / ${recognition.message}"
                    recognition.sessionId != baseline.sessionId -> "持续收音期间模型会话被替换"
                    data.activeSession?.id != sessionId -> "持续收音期间现场会话被替换或结束"
                    recognition.frameCount < baseline.frameCount -> "真实模型帧计数倒退"
                    else -> null
                }
                if (fault != null) fail(fault)
                recognition.frameCount >= targetFrames
            }
        } catch (failure: Throwable) {
            throw AssertionError("两次反馈后必须持续新增至少 256 个真实模型帧；起点=${baseline.frameCount}，目标=$targetFrames，当前=${activeApp.recognition.value.frameCount}\n" +
                diagnosticSnapshot("sustained-256-model-frames", sessionId).toString(2), failure)
        }
        assertTrue("必须按真实模型帧增长完成吞吐回归", activeApp.recognition.value.frameCount - baseline.frameCount >= 256L)
        assertStillListening("after-256-additional-real-model-frames", sessionId)
    }
    private fun preserveFailure(failure: Throwable, sessionId: String?, diagnostics: FieldDiagnostics) {
        try {
            val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "field-ui-failures")
            check(folder.isDirectory || folder.mkdirs())
            val base = "field-lifecycle-${sessionId ?: "before-session"}-${System.currentTimeMillis()}"
            val snapshot = diagnosticSnapshot("test-failure-before-cleanup", sessionId)
                .put("failure", failure.stackTraceToString())
            val json = File(folder, "$base.json")
            json.writeText(snapshot.toString(2))
            // Freeze capture only after preserving the original statuses, then checkpoint actual evidence.
            try { compose.runOnUiThread { app.stopAudio("UI 测试失败，冻结诊断证据"); practice.pause() } }
            catch (stopError: Throwable) { snapshot.put("freezeError", stopError.stackTraceToString()); failure.addSuppressed(stopError) }
            if (sessionId != null) {
                try {
                    val zip = runBlocking { withTimeout(15_000) { diagnostics.exportSession(sessionId) } }
                    val retained = File(folder, "$base.zip")
                    zip.copyTo(retained, overwrite = false)
                    snapshot.put("retainedZip", retained.absolutePath).put("zipBytes", retained.length())
                } catch (exportError: Throwable) {
                    snapshot.put("exportError", exportError.stackTraceToString()); failure.addSuppressed(exportError)
                }
            }
            json.writeText(snapshot.toString(2))
        } catch (retentionError: Throwable) { failure.addSuppressed(retentionError) }
    }
    private data class RestartFixture(val document: ParsedScoreDocument, val bytes: ByteArray, var scoreId: String? = null)
    private var restartFixture: RestartFixture? = null
    private fun clearOwnedRestartFixture() {
        val owned = restartFixture ?: return
        val sourceId = owned.document.score.id
        val sourceRoot = File(context.filesDir, "digital-scores")
        val source = File(sourceRoot, "$sourceId.musicxml")
        require(source.canonicalFile.parentFile == sourceRoot.canonicalFile)
        if (source.exists()) assertArrayEquals("只可清理本用例的完整原谱", owned.bytes, source.readBytes())
        ScoreMetadataDatabase(context).use { database ->
            val row = database.findBySourceId(sourceId, includeDeleted = true)
            if (row != null) {
                assertEquals(owned.document.originalTitle, row.originalTitle)
                assertEquals(sourceId, row.sourceId)
                owned.scoreId?.let { assertEquals(it, row.scoreId) }
                PracticeReportStore(File(context.filesDir, "practice-reports")).deleteForScore(row.scoreId)
                database.markDeleted(row.scoreId, deletePracticeRecords = true)
                ScoreSourceStore(sourceRoot).delete(sourceId)
                ScoreRevisionStore(File(context.filesDir, "score-revisions")).delete(row.scoreId)
                database.confirmSourceDeleted(row.scoreId)
                assertFalse(File(context.filesDir, "score-revisions/${row.scoreId}.json").exists())
            } else ScoreSourceStore(sourceRoot).delete(sourceId)
        }
        assertFalse(source.exists())
        restartFixture = null
    }
    private fun openPracticeThroughWorkbench(repeatMeasures: Int = 1) {
        val imports = ViewModelProvider(compose.activity)[ScoreImportViewModel::class.java]
        val workbench = ViewModelProvider(compose.activity)[ScoreWorkbenchViewModel::class.java]
        // A parsed score.id is its source SHA256. Practice uses the durable library UUID.
        // Keep a distinct original fixture so an existing user's revisions are never overwritten.
        require(repeatMeasures == 1 || repeatMeasures == 3)
        var xml = InstrumentationRegistry.getInstrumentation().context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("晨间和弦 · 原创导入测试", "实地链路 TEST DATA " + UUID.randomUUID())
        if (repeatMeasures > 1) {
            // Duplicate the complete original measure: both staves, rests, durations and attributes stay intact.
            val original = Regex("""<measure\b[^>]*>[\s\S]*?</measure>""").findAll(xml).toList().single()
            require(original.value.contains("number=\"1\""))
            xml = xml.replaceRange(original.range, (1..repeatMeasures).joinToString("\n") { number ->
                original.value.replaceFirst("number=\"1\"", "number=\"$number\"")
            })
        }
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val document = MusicXmlParser().parseDocument(bytes)
        val timeline = ScoreTimelineCompiler.compile(document.score)
        assertTrue("XML fixture 存在结构错误：${timeline.issues}", timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        if (repeatMeasures > 1) {
            check(restartFixture == null)
            assertFalse(File(context.filesDir, "digital-scores/${document.score.id}.musicxml").exists())
            ScoreMetadataDatabase(context).use { assertNull(it.findBySourceId(document.score.id, includeDeleted = true)) }
            // Register ownership before the first write so failure cleanup also handles partial setup.
            restartFixture = RestartFixture(document, bytes.copyOf())
        }
        val stored = ScoreSourceStore(File(context.filesDir, "digital-scores")).save(bytes)
        assertEquals(document.score.id, stored.id)
        val metadata = ScoreMetadataDatabase(context).use { it.ensureImported(document.score.id, ScoreImportMetadata.from(document)) }
        if (repeatMeasures > 1) requireNotNull(restartFixture).scoreId = metadata.scoreId
        compose.runOnUiThread { imports.reload(); imports.openScore(metadata.scoreId) }
        compose.waitUntil(10_000) { !imports.state.value.importing && (imports.state.value.selectedMetadata?.scoreId == metadata.scoreId || imports.state.value.error != null) }
        assertNull("正式曲库打开失败：${imports.state.value.error}", imports.state.value.error)
        assertEquals(metadata.scoreId, imports.state.value.selectedMetadata?.scoreId)
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
        compose.onNodeWithTag("score_content").performScrollToNode(hasTestTag("open_score_workbench"))
        compose.onNodeWithTag("open_score_workbench").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && (workbench.state.value.revision != null || workbench.state.value.error != null) }
        assertNull("工作台打开失败：${workbench.state.value.error}", workbench.state.value.error)
        assertNotNull(workbench.state.value.revision)
        compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag("score_start_practice"))
        compose.onNodeWithTag("score_start_practice").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && (practice.state.value.timeline != null || practice.state.value.error != null) }
        assertNull("陪练打开失败：${practice.state.value.error}", practice.state.value.error)
        assertNotNull("正式曲库谱面必须生成时间线", practice.state.value.timeline)
        assertEquals(metadata.scoreId, practice.state.value.scoreId)
    }
    @Test fun endedBothHandPracticeRestartsAsLeftWithFreshFieldSnapshot() {
        emulatorOnly()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val diagnostics = FieldDiagnostics.get(context)
        compose.waitUntil(10_000) { !app.state.value.loading && !field.operation.value.busy }
        assertNull("不能覆盖其他活动会话", diagnostics.state.value.activeSession)
        var sessionId: String? = null
        var testFailure: Throwable? = null
        try {
            openPracticeThroughWorkbench(repeatMeasures = 3)
            val original = practice.state.value
            val scoreId = requireNotNull(restartFixture?.scoreId)
            val timeline = requireNotNull(original.timeline)
            val both = PracticeConfig(mode = CompanionMode.FREE, reviewAcknowledged = true, hand = PracticeHand.BOTH)
            assertEquals(3, timeline.measures.size)
            assertEquals(6, ScoreFollower(timeline, both).targets.size)
            assertEquals(3, ScoreFollower(timeline, both.copy(hand = PracticeHand.LEFT)).targets.size)
            // TEST DATA exercises the real business state only; no fabricated recognition frames are supplied.
            compose.runOnUiThread {
                practice.start(both)
                assertNull(practice.state.value.error)
                assertTrue(practice.hasActiveSession())
                practice.seekMeasure(2)
                assertEquals(CompanionPhase.RUNNING, practice.state.value.phase)
                assertEquals(4, practice.state.value.cursor)
                assertEquals(0, practice.state.value.confirmedInputs)
                assertEquals(AudioStatus.IDLE, app.audio.value.status)
                practice.finish()
                assertFalse(practice.hasActiveSession())
                assertEquals(CompanionPhase.READY, practice.state.value.phase)
                assertEquals("必须保留旧游标以复现新会话故障", 4, practice.state.value.cursor)
                assertNull(practice.state.value.latestReport)
            }
            assertTrue("零真实击键不得生成报告", PracticeReportStore(File(context.filesDir, "practice-reports")).load(scoreId).isEmpty())
            openField(); ready()
            assertEquals(4, practice.state.value.cursor)
            compose.runOnUiThread { assertFalse(practice.hasActiveSession()) }
            scroll("field_mode_FREE"); compose.onNodeWithTag("field_mode_FREE").assertIsEnabled().performClick().assertIsSelected()
            scroll("field_hand_LEFT"); compose.onNodeWithTag("field_hand_LEFT").assertIsEnabled().performClick().assertIsSelected()
            val hasWarnings = timeline.issues.isNotEmpty()
            if (hasWarnings) {
                scroll("field_review_ack"); compose.onNodeWithTag("field_review_ack").assertIsOff().performClick().assertIsOn()
            }
            scroll("field_start"); compose.onNodeWithTag("field_start").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { field.state.value.activeSession != null || field.operation.value.error != null || field.state.value.error != null }
            sessionId = field.state.value.activeSession?.id
            assertNull("新建 LEFT 会话不应引用旧 BOTH 游标：${diagnosticSnapshot("restart-left-start", sessionId)}", field.operation.value.error)
            assertNull(field.state.value.error)
            val activeId = requireNotNull(sessionId)
            compose.waitUntil(10_000) {
                (app.audio.value.status == AudioStatus.LISTENING && app.audio.value.totalFrames > 0) ||
                    app.audio.value.status == AudioStatus.ERROR || app.recognition.value.status == RecognitionEngineStatus.ERROR ||
                    field.state.value.error != null || field.operation.value.error != null
            }
            assertStillListening("new-left-session-actual-input", activeId)
            assertTrue("必须收到实际模拟器 PCM", app.audio.value.totalFrames > 0)
            assertEquals(PracticeHand.LEFT, practice.state.value.config.hand)
            val actualInput = diagnosticSnapshot("new-left-session-before-stop", activeId)
            scroll("field_stop_audio"); compose.onNodeWithTag("field_stop_audio").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
            scroll("field_end"); compose.onNodeWithTag("field_end").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { field.state.value.activeSession == null && !field.operation.value.busy }
            assertNull(field.operation.value.error)
            scroll("field_export_$activeId"); compose.onNodeWithTag("field_export_$activeId").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { !field.operation.value.busy && (field.operation.value.exportedFile != null || field.operation.value.error != null) }
            assertNull(field.operation.value.error)
            val zip = requireNotNull(field.operation.value.exportedFile)
            ZipFile(zip).use { archive ->
                listOf("manifest.json", "device.json", "app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl", "performance.json", "session-summary.json", "score.json", "practice-context.json")
                    .forEach { assertNotNull(it, archive.getEntry(it)) }
                val manifestText = archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                FieldScoreSnapshot.verifyArchive(archive, FieldJson.objectValue(manifestText))
                val descriptor = JSONObject(manifestText).getJSONObject("scoreSnapshot")
                assertEquals("AVAILABLE", descriptor.getString("status"))
                assertEquals(scoreId, descriptor.getString("scoreId"))
                assertEquals(original.revisionGeneration, descriptor.getLong("revisionGeneration"))
                val snapshot = ScoreJsonCodec.decode(archive.getInputStream(archive.getEntry("score.json")).use { it.readBytes() })
                assertEquals(original.score, snapshot)
                val config = JSONObject(archive.getInputStream(archive.getEntry("practice-context.json")).bufferedReader().use { it.readText() })
                assertEquals(scoreId, config.getString("scoreId"))
                assertEquals(original.revisionGeneration, config.getLong("revisionGeneration"))
                assertEquals(0, config.getInt("cursorAtCapture"))
                assertEquals("READY", config.getString("practicePhaseAtCapture"))
                assertEquals("LEFT", config.getJSONObject("practiceConfig").getString("hand"))
                assertEquals("FREE", config.getJSONObject("practiceConfig").getString("mode"))
                assertEquals(hasWarnings, config.getJSONObject("practiceConfig").getBoolean("reviewAcknowledged"))
                assertEquals(3, config.getInt("targetCount"))
                assertEquals(3, config.getInt("measureCount"))
                assertFalse(config.getBoolean("accuracyValidated"))
            }
            val evidence = File(context.getExternalFilesDir(null) ?: context.filesDir, "field-ui-evidence")
            check(evidence.isDirectory || evidence.mkdirs())
            val retained = File(evidence, "field-restart-$activeId-${System.currentTimeMillis()}.zip")
            zip.copyTo(retained, overwrite = false)
            assertArrayEquals(zip.readBytes(), retained.readBytes())
            File(evidence, retained.nameWithoutExtension + ".json").writeText(actualInput
                .put("retainedZip", retained.absolutePath).put("zipBytes", retained.length())
                .put("setup", "TEST DATA business-state start/seek/finish; no injected recognition")
                .put("physicalPianoAccuracyValidated", false).toString(2))
        } catch (failure: Throwable) {
            testFailure = failure
            preserveFailure(failure, sessionId, diagnostics)
            throw failure
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            try {
                compose.runOnUiThread { app.stopAudio("重新开始现场会话回归结束"); practice.pause(); practice.finish() }
                compose.waitUntil(10_000) { !field.operation.value.busy && !practice.state.value.busy }
                runBlocking { withTimeout(15_000) {
                    val id = sessionId
                    if (id != null && diagnostics.state.value.activeSession?.id == id) diagnostics.endSession("RESTART_TEST_FINALLY")
                    if (id != null && diagnostics.state.value.sessions.any { it.id == id }) diagnostics.clearSessions(setOf(id))
                } }
            } catch (cleanupError: Throwable) { cleanupErrors += cleanupError }
            try { clearOwnedRestartFixture() } catch (cleanupError: Throwable) { cleanupErrors += cleanupError }
            val originalFailure = testFailure
            if (originalFailure != null) cleanupErrors.forEach(originalFailure::addSuppressed)
            else org.junit.runners.model.MultipleFailureException.assertEmpty(cleanupErrors)
        }
    }

    @Test fun fieldEntryShowsBuildIdentityAndKeepsAwakeOnlyWhileForeground() {
        emulatorOnly()
        openField(); ready()
        val provider = requireNotNull(context.packageManager.resolveContentProvider("${context.packageName}.field-files", 0))
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.field-files", File(context.filesDir, "outside-field-export.zip"))
            fail("FileProvider 不应暴露导出目录之外的文件")
        } catch (_: IllegalArgumentException) { /* Expected: outside the single configured root. */ }
        compose.onNodeWithTag("field_build_identity").performScrollTo().assertTextContains(BuildConfig.GIT_COMMIT, substring = true)
        compose.onNodeWithTag("field_build_identity").assertTextContains(BuildConfig.BUILD_TIME, substring = true)
        scroll("field_start"); compose.onNodeWithTag("field_start").assertIsNotEnabled()
        assertNull(field.state.value.activeSession)
        assertEquals(AudioStatus.IDLE, app.audio.value.status)
        compose.runOnIdle { assertTrue(awake()) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle(); ready()
        assertEquals("回到前台不能自动开始收音", AudioStatus.IDLE, app.audio.value.status)
        compose.runOnIdle { assertTrue(awake()) }
        compose.onNodeWithTag("nav_home").performClick(); compose.waitForIdle()
        compose.runOnIdle { assertFalse(awake()) }
    }

    @Test fun realSessionFeedbackExportReadbackAndConfirmedCleanup() {
        emulatorOnly()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val diagnostics = FieldDiagnostics.get(context)
        compose.waitUntil(10_000) { !app.state.value.loading && !field.operation.value.busy }
        assertNull("不能覆盖其他活动会话", diagnostics.state.value.activeSession)
        openPracticeThroughWorkbench()
        var sessionId: String? = null
        var saved: File? = null
        var testFailure: Throwable? = null
        try {
            openField(); ready()
            assertFalse(field.state.value.clipConsent)
            if (practice.state.value.timeline!!.issues.isNotEmpty()) {
                compose.onNodeWithText("我已校对当前谱面的来源和结构警告").performScrollTo()
                compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) and hasAnySibling(hasText("我已校对当前谱面的来源和结构警告"))).performClick()
            }
            scroll("field_start"); compose.onNodeWithTag("field_start").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { field.state.value.activeSession != null || field.operation.value.error != null }
            assertNull(field.operation.value.error)
            sessionId = requireNotNull(field.state.value.activeSession).id
            compose.waitUntil(10_000) { app.audio.value.totalFrames > 0 || app.audio.value.status == AudioStatus.ERROR }
            assertEquals(AudioStatus.LISTENING, app.audio.value.status)
            assertTrue(app.audio.value.totalFrames > 0)
            val activeApp = app
            val capturedBeforeBackground = activeApp.audio.value.capturedAtNanos
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            runBlocking { withTimeout(5_000) { activeApp.audio.first { it.status == AudioStatus.IDLE } } }
            val stoppedFrames = activeApp.audio.value.totalFrames
            assertNull("后台不应继续产生 PCM 帧", runBlocking { withTimeoutOrNull(500) { activeApp.audio.first { it.totalFrames != stoppedFrames } } })
            compose.activityRule.scenario.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle(); ready()
            assertEquals("恢复前台不得自动开麦", AudioStatus.IDLE, activeApp.audio.value.status)
            assertEquals(sessionId, field.state.value.activeSession?.id)
            scroll("field_start"); compose.onNodeWithTag("field_start").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { activeApp.audio.value.status == AudioStatus.LISTENING && activeApp.audio.value.totalFrames > 0 && activeApp.audio.value.capturedAtNanos != capturedBeforeBackground }
            assertStillListening("before-feedback", sessionId)
            val before = practice.state.value
            scroll("field_feedback_dispute"); compose.onNodeWithTag("field_feedback_dispute").assertIsEnabled().performClick()
            assertStillListening("after-dispute-feedback", sessionId)
            scroll("field_feedback_confirm"); compose.onNodeWithTag("field_feedback_confirm").assertIsEnabled().performClick()
            assertStillListening("after-confirm-feedback", sessionId)
            assertEquals(before.locked, practice.state.value.locked)
            assertEquals(before.cursor, practice.state.value.cursor)
            assertFalse(field.state.value.clipConsent)
            awaitAnother256RealModelFrames(sessionId)
            assertStillListening("before-stop-control-scroll", sessionId)
            scroll("field_stop_audio")
            assertStillListening("after-stop-control-scroll", sessionId)
            compose.onNodeWithTag("field_stop_audio").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { app.audio.value.status == AudioStatus.IDLE }
            val stoppedForCalibration = app.audio.value.totalFrames
            compose.onNodeWithTag("nav_monitor").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("环境校准与误判案例"))
            compose.onNodeWithText("环境校准与误判案例").performClick()
            compose.onNodeWithTag("calibration_start").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("当前实地测试会话尚未结束。请先到实地测试页面结束并导出，再单独校准；两种记录不能混用。").assertExists()
            assertEquals(sessionId, field.state.value.activeSession?.id)
            assertEquals(AudioStatus.IDLE, app.audio.value.status)
            assertEquals(stoppedForCalibration, app.audio.value.totalFrames)
            assertNull(field.state.value.error)
            compose.onNodeWithTag("field_active_banner").performClick()
            scroll("field_end"); compose.onNodeWithTag("field_end").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { field.state.value.activeSession == null && !field.operation.value.busy }
            assertNull("结束会话后必须清除进行中提示", field.operation.value.progressMessage)
            val zip = exportWithBoundedCompletion(sessionId)
            ZipFile(zip).use { archive ->
                listOf("manifest.json", "device.json", "app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl", "performance.json", "session-summary.json").forEach { assertNotNull(it, archive.getEntry(it)) }
                FieldScoreSnapshot.verifyArchive(archive, FieldJson.objectValue(archive.getInputStream(archive.getEntry("manifest.json")).reader().readText()))
                val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).reader().readText())
                assertEquals("ZIP 内容必须属于本次会话", sessionId, manifest.getString("sessionId"))
                assertEquals("AVAILABLE", manifest.getJSONObject("scoreSnapshot").getString("status"))
                val snapshot = ScoreJsonCodec.decode(archive.getInputStream(archive.getEntry("score.json")).use { it.readBytes() })
                val config = JSONObject(archive.getInputStream(archive.getEntry("practice-context.json")).reader().readText())
                assertEquals(practice.state.value.score, snapshot)
                assertEquals(practice.state.value.scoreId, config.getString("scoreId"))
                assertEquals(practice.state.value.revisionGeneration, config.getLong("revisionGeneration"))
                assertEquals(practice.state.value.config.mode.name, config.getJSONObject("practiceConfig").getString("mode"))
                assertEquals(practice.state.value.config.hand.name, config.getJSONObject("practiceConfig").getString("hand"))
                assertFalse(config.getBoolean("accuracyValidated"))
                val logs = archive.getInputStream(archive.getEntry("app.log")).reader().readText()
                assertTrue(logs.contains("USER_SAYS_SYSTEM_WRONG")); assertTrue(logs.contains("USER_CONFIRMS_WRONG_NOTE"))
                assertEquals(0, archive.entries().asSequence().count { it.name.endsWith(".wav") })
            }
            assertNull(field.state.value.sessions.first { it.id == sessionId }.exportedAtMillis)
            compose.runOnUiThread {
                field.saveTo(Uri.parse("content://com.xiuyin.app.unavailable-test-destination/target"), sessionId, zip.absolutePath)
                val operation = field.operation.value
                if (operation.busy) {
                    assertEquals("正在保存 ZIP 并读回校验，请稍候。", operation.progressMessage)
                    assertNull("保存尚未完成不得提前提示成功", operation.message)
                } else {
                    assertNull("保存快速结束后必须清除进行中提示", operation.progressMessage)
                }
            }
            compose.waitUntil(10_000) { !field.operation.value.busy }
            assertNull("保存失败后必须清除进行中提示", field.operation.value.progressMessage)
            assertNotNull("实际保存失败必须可见", field.operation.value.error)
            assertNull("保存失败不能标记为已导出", field.state.value.sessions.first { it.id == sessionId }.exportedAtMillis)
            assertTrue("失败后保留源 ZIP 以便重试", zip.isFile)
            saved = File(context.filesDir, "field-exports/test-ui-save-${UUID.randomUUID()}.zip")
            val destination = FileProvider.getUriForFile(context, "${context.packageName}.field-files", saved)
            compose.runOnUiThread {
                field.saveTo(destination, sessionId, zip.absolutePath)
                val operation = field.operation.value
                if (operation.busy) {
                    assertEquals("正在保存 ZIP 并读回校验，请稍候。", operation.progressMessage)
                    assertNull("保存重试尚未完成不得提前提示成功", operation.message)
                } else {
                    assertNull("保存重试快速结束后必须清除进行中提示", operation.progressMessage)
                }
            }
            compose.waitUntil(10_000) { !field.operation.value.busy }
            assertNull("保存并读回校验结束后必须清除进行中提示", field.operation.value.progressMessage)
            assertNull(field.operation.value.error)
            assertArrayEquals(zip.readBytes(), saved.readBytes())
            assertNotNull(field.state.value.sessions.first { it.id == sessionId }.exportedAtMillis)
            scroll("field_clear_$sessionId"); compose.onNodeWithTag("field_clear_$sessionId").performClick()
            compose.onNodeWithTag("field_clear_cancel").performClick()
            assertTrue(field.state.value.sessions.any { it.id == sessionId })
            compose.onNodeWithTag("field_clear_$sessionId").performClick()
            compose.onNodeWithTag("field_clear_confirm").performClick()
            compose.waitUntil(10_000) { !field.operation.value.busy && field.state.value.sessions.none { it.id == sessionId } }
            assertNull(field.operation.value.error)
        } catch (failure: Throwable) {
            testFailure = failure
            preserveFailure(failure, sessionId, diagnostics)
            throw failure
        } finally {
            try {
                compose.runOnUiThread { app.stopAudio("现场链路 UI 测试结束"); practice.pause() }
                compose.waitUntil(10_000) { !field.operation.value.busy }
                runBlocking {
                    withTimeout(15_000) {
                        if (sessionId != null && diagnostics.state.value.activeSession?.id == sessionId) diagnostics.endSession("TEST_FINALLY")
                        if (sessionId != null && diagnostics.state.value.sessions.any { it.id == sessionId }) diagnostics.clearSessions(setOf(sessionId))
                    }
                }
                saved?.let { require(it.canonicalFile.parentFile == File(context.filesDir, "field-exports").canonicalFile); if (it.exists()) check(it.delete()) }
            } catch (cleanupError: Throwable) {
                val original = testFailure
                if (original != null) original.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
}
