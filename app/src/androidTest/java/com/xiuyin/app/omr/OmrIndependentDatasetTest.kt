package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production model + isolated storage; musical acceptance remains UNVERIFIED, with no expected row count. */
@RunWith(AndroidJUnit4::class)
class OmrIndependentDatasetTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun explicitPhaseUsesRealImportOrCrossProcessArchive() {
        val phase = requireNotNull(arguments.getString("omrPhase")) { "Missing omrPhase: IMPORT or REOPEN is required" }
        require(phase == "IMPORT" || phase == "REOPEN") { "Invalid omrPhase: $phase" }
        name("omrRunId")
        when (phase) {
            "IMPORT" -> {
                // Validate every explicit input before creating storage; never skip absent real inputs.
                listOf("omrCase", "omrIndependentInputDirectory", "omrInputFile", "omrReferenceFile").forEach { name(it) }
                expectedSha("omrInputSha256")
                expectedSha("omrReferenceSha256")
                freshDocumentRetainsActualInferenceAndColdRepositorySnapshots()
            }
            "REOPEN" -> retainedArchiveReopensInANewAppProcessWithIdenticalHashes()
        }
    }

    private fun freshDocumentRetainsActualInferenceAndColdRepositorySnapshots() {
        val runId = name("omrRunId")
        val inputDirectory = File(context.filesDir, name("omrIndependentInputDirectory"))
        val source = File(inputDirectory, name("omrInputFile"))
        val reference = File(inputDirectory, name("omrReferenceFile"))
        val expectedInput = expectedSha("omrInputSha256")
        val expectedReference = expectedSha("omrReferenceSha256")
        require(source.isFile && reference.isFile)
        assertEquals("原始图/PDF SHA 不符", expectedInput, sha(source))
        assertEquals("独立参照 SHA 不符", expectedReference, sha(reference))
        val privateRoot = File(context.filesDir, "omr-independent-runs/$runId")
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "omr-independent-regression/$runId")
        require(!privateRoot.exists() && !output.exists()) { "runId 已存在；不能复用缓存或覆盖历史证据" }
        check(privateRoot.mkdirs() && output.mkdirs())
        val databaseName = "omr-independent-$runId.db"
        require(!context.getDatabasePath(databaseName).exists())
        val appSha = sha(File(context.applicationInfo.sourceDir))
        val testSha = sha(File(instrument.context.applicationInfo.sourceDir))
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("phase", "IMPORT").put("case", name("omrCase")).put("startedAt", Instant.now().toString())
            .put("processId", Process.myPid()).put("processStartElapsedRealtime", Process.getStartElapsedRealtime())
            .put("status", "RUNNING").put("musicalAcceptance", "UNVERIFIED")
            .put("teacherConfirmed", false).put("physicalPhoneAcceptance", "UNVERIFIED")
            .put("scope", "Real production import and repository storage; system picker and practice not exercised")
            .put("appSha256", appSha).put("testApkSha256", testSha)
            .put("gitCommitAtBuild", BuildConfig.GIT_COMMIT).put("versionName", BuildConfig.VERSION_NAME)
            .put("sourceFile", source.name).put("sourceSha256", expectedInput)
            .put("referenceFile", reference.name).put("referenceSha256", expectedReference)
            .put("referencePolicy", "Unchanged independent reference; no reference enters the recognizer; teacher review pending")
            .put("databaseName", databaseName).put("privateNamespace", privateRoot.absolutePath)
            .put("crossProcessReload", "NOT_YET_RUN")
        writeNew(File(output, "reference.json"), reference.readBytes())
        writeNew(File(output, "model-manifest.json"), context.assets.open("omr/models.json").use { it.readBytes() })
        var failure: Throwable? = null
        try {
            // Same production path as the existing real problem-page test; no stub engine.
            val freshContext = isolatedContext(privateRoot)
            val imported = OmrImportService(freshContext).importDocuments(listOf(Uri.fromFile(source))) {
                Log.i("XiuyinOMRIndependent", "$runId: $it")
            }
            report.put("sourceId", imported.sourceId).put("reusedExisting", imported.reusedExisting)
            assertFalse("必须真正执行本次模型，不得复用旧识谱", imported.reusedExisting)
            val rawBefore = sha(imported.rawResultFile)
            val originalBefore = sha(imported.originalScoreFile)
            assertEquals(expectedInput, sha(File(imported.sourceDirectory, "original-000.document")))
            val raw = JSONObject(imported.rawResultFile.readText())
            assertEquals(imported.sourceId, raw.getString("sourceId"))
            assertEquals(originalBefore, raw.getString("originalScoreSha256"))
            val score = imported.score
            val info = ScoreImportMetadata.from(ParsedScoreDocument(score, null, null, null,
                imported.sourceImages.size), source.name).copy(sourceType = score.source.format,
                recognitionComplete = true, hasReviewIssues = true)
            val metadata = ScoreMetadataDatabase(context, databaseName).use {
                it.ensureImported(imported.sourceId, info)
            }
            val revisionRoot = File(privateRoot, "score-revisions")
            val revision = ScoreRevisionStore(revisionRoot).load(metadata.scoreId, score)
            report.put("scoreId", metadata.scoreId).put("revisionGeneration", revision.generation)
                .put("currentPolicy", "Initial generation 0: production Current resolves from immutable Original; no invented edit")
            assertEquals(0L, revision.generation)
            assertFalse(revision.canUndo)
            assertTrue(revision.verifiedEventIds.isEmpty() && revision.fingerings.isEmpty())
            writeNew(File(output, "current-score.json"), ScoreJsonCodec.encode(revision.current))
            val currentBefore = sha(File(output, "current-score.json"))
            val coldImported = OmrImportService(isolatedContext(privateRoot)).load(imported.sourceId)
            val coldMetadata = ScoreMetadataDatabase(context, databaseName).use { db ->
                val reloaded = requireNotNull(db.get(metadata.scoreId))
                assertEquals(metadata, reloaded)
                writeNew(File(output, "metadata.json"), metadataRow(db, metadata.scoreId).toString(2).toByteArray())
                reloaded
            }
            val cold = ScoreRevisionStore(revisionRoot).load(coldMetadata.scoreId, coldImported.score)
            assertEquals(revision, cold)
            writeNew(File(output, "digital-score.json"), ScoreJsonCodec.encode(cold.current))
            assertEquals(currentBefore, sha(File(output, "digital-score.json")))
            assertEquals(originalBefore, sha(coldImported.originalScoreFile))
            assertEquals(rawBefore, sha(coldImported.rawResultFile))
            report.put("rawSha256", rawBefore).put("originalSha256", originalBefore)
                .put("currentSha256", currentBefore).put("metadataSha256", sha(File(output, "metadata.json")))
                .put("repositoryReloadEqual", true)
            val measures = score.parts.flatMap { it.measures }
            val events = measures.flatMap { it.events }
            val pages = raw.getJSONArray("pages")
            val staffs = (0 until pages.length()).flatMap { page ->
                val rows = pages.getJSONObject(page).getJSONArray("staffs")
                (0 until rows.length()).map { rows.getJSONObject(it) }
            }
            val rawEvents = staffs.sumOf { staff ->
                val tokens = staff.getJSONArray("tokens")
                (0 until tokens.length()).count { index -> tokens.getJSONObject(index).getString("rhythm")
                    .let { it.startsWith("note_") || it.startsWith("rest_") } }
            }
            // Observations only: reference counts and geometry do not force recognition output.
            report.put("actual", JSONObject().put("pageCount", pages.length())
                .put("staffCountsPerPage", JSONArray((0 until pages.length()).map {
                    pages.getJSONObject(it).getJSONArray("staffs").length() }))
                .put("partCount", score.parts.size).put("measureCount", measures.size)
                .put("noteCount", events.count { !it.isRest }).put("restCount", events.count { it.isRest })
                .put("rawEventCount", rawEvents).put("convertedEventCount", events.size)
                .put("staffNumbers", JSONArray(events.map { it.staff }.distinct().sorted()))
                .put("issueCount", score.issues.size).put("scoreStatus", score.status.name))
            assertEquals("转换不可静默增删原模型事件", rawEvents, events.size)
            assertEquals("数字谱事件 ID 必须唯一", events.size, events.map { it.id }.toSet().size)
            assertTrue("模型没有输出任何发声音符", events.any { !it.isRest })
            assertTrue("未完成解码不能称推理完整", staffs.isNotEmpty() && staffs.all {
                it.getBoolean("eos") && !it.getBoolean("sequenceTruncated") })
            report.put("status", "INFERENCE_AND_REPOSITORY_RELOAD_PASSED")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "FAILED").put("error", error.stackTraceToString())
        }
        // Retain failed .pending archives and first/second decoder passes before returning failure.
        try {
            copyTree(File(privateRoot, "omr-sources"), File(output, "omr-sources"))
            copyTree(File(privateRoot, "score-revisions"), File(output, "score-revisions"))
            val database = context.getDatabasePath(databaseName)
            if (database.isFile) copyFile(database, File(output, "metadata-database.db"))
            assertEquals("运行中 App APK 变化", appSha, sha(File(context.applicationInfo.sourceDir)))
            assertEquals("运行中测试 APK 变化", testSha, sha(File(instrument.context.applicationInfo.sourceDir)))
        } catch (archiveError: Throwable) {
            if (failure == null) failure = archiveError else failure.addSuppressed(archiveError)
            report.put("status", "FAILED").put("archiveError", archiveError.stackTraceToString())
        }
        report.put("finishedAt", Instant.now().toString())
        writeNew(File(output, "report.json"), report.toString(2).toByteArray())
        writeManifest(output, "evidence-manifest.json")
        Log.i("XiuyinOMRIndependent", "OMR_INDEPENDENT_EVIDENCE=${output.absolutePath}")
        failure?.let { throw it }
    }

    /** Invoke separately after force-stop, with the same runId; never starts the model again. */
    private fun retainedArchiveReopensInANewAppProcessWithIdenticalHashes() {
        val runId = name("omrRunId")
        val privateRoot = File(context.filesDir, "omr-independent-runs/$runId")
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "omr-independent-regression/$runId")
        val originalReport = JSONObject(File(output, "report.json").readText())
        val coldReport = JSONObject().put("runId", runId).put("phase", "REOPEN").put("startedAt", Instant.now().toString())
            .put("status", "RUNNING").put("musicalAcceptance", "UNVERIFIED")
            .put("processId", Process.myPid()).put("processStartElapsedRealtime", Process.getStartElapsedRealtime())
        var failure: Throwable? = null
        try {
            require(!File(output, "cold-reload-report.json").exists()) { "冷加载报告已存在，不覆盖" }
            assertEquals("INFERENCE_AND_REPOSITORY_RELOAD_PASSED", originalReport.getString("status"))
            assertTrue("必须是应用新进程，不得把同实例读回冒充重启", Process.myPid() != originalReport.getInt("processId") ||
                Process.getStartElapsedRealtime() != originalReport.getLong("processStartElapsedRealtime"))
            assertEquals(originalReport.getString("appSha256"), sha(File(context.applicationInfo.sourceDir)))
            assertEquals(originalReport.getString("testApkSha256"), sha(File(instrument.context.applicationInfo.sourceDir)))
            val imported = OmrImportService(isolatedContext(privateRoot)).load(originalReport.getString("sourceId"))
            val rowId = originalReport.getString("scoreId")
            ScoreMetadataDatabase(context, originalReport.getString("databaseName")).use { db ->
                val metadata = requireNotNull(db.get(rowId))
                assertEquals(imported.sourceId, metadata.sourceId)
                val metadataBytes = metadataRow(db, rowId).toString(2).toByteArray()
                assertEquals(originalReport.getString("metadataSha256"), sha(metadataBytes))
            }
            val revision = ScoreRevisionStore(File(privateRoot, "score-revisions")).load(rowId, imported.score)
            assertEquals(originalReport.getLong("revisionGeneration"), revision.generation)
            val bytes = ScoreJsonCodec.encode(revision.current)
            assertEquals(originalReport.getString("currentSha256"), sha(bytes))
            assertEquals(originalReport.getString("originalSha256"), sha(imported.originalScoreFile))
            assertEquals(originalReport.getString("rawSha256"), sha(imported.rawResultFile))
            writeNew(File(output, "cold-digital-score.json"), bytes)
            coldReport.put("status", "CROSS_PROCESS_RELOAD_PASSED").put("currentSha256", sha(bytes))
                .put("rawSha256", sha(imported.rawResultFile)).put("originalSha256", sha(imported.originalScoreFile))
                .put("metadataSha256", originalReport.getString("metadataSha256"))
        } catch (error: Throwable) {
            failure = error
            coldReport.put("status", "FAILED").put("error", error.stackTraceToString())
        }
        coldReport.put("finishedAt", Instant.now().toString())
        writeNew(File(output, "cold-reload-report.json"), coldReport.toString(2).toByteArray())
        writeManifest(output, "cold-evidence-manifest.json")
        Log.i("XiuyinOMRIndependent", "OMR_INDEPENDENT_COLD_EVIDENCE=${output.absolutePath}")
        failure?.let { throw it }
    }

    private fun isolatedContext(directory: File): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
    private fun name(key: String): String = requireNotNull(arguments.getString(key)) { "Missing $key" }.also {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}").matches(it)) { "Invalid $key" }
    }
    private fun expectedSha(key: String): String = requireNotNull(arguments.getString(key)) { "Missing $key" }.also {
        require(Regex("[a-f0-9]{64}").matches(it)) { "Invalid $key: lowercase SHA-256 required" }
    }
    private fun metadataRow(db: ScoreMetadataDatabase, scoreId: String): JSONObject =
        db.readableDatabase.rawQuery("SELECT * FROM score_metadata WHERE score_id = ?", arrayOf(scoreId)).use { row ->
            check(row.moveToFirst())
            JSONObject().apply { row.columnNames.forEachIndexed { index, column ->
                put(column, when (row.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> row.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> row.getString(index)
                    else -> error("Unexpected metadata column type: $column")
                })
            } }
        }
    private fun writeNew(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW)
    }
    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        // Create a new external evidence file using that directory's access mode.
        // Files.copy would inherit the private source's 0600 mode and block ADB
        // from archiving it. Only the byte-identical copy is made readable here.
        Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        assertEquals("导出证据字节必须与真实存储一致", sha(source), sha(target))
    }
    private fun copyTree(source: File, target: File) {
        if (!source.exists()) return
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            copyFile(file, File(target, file.relativeTo(source).path))
        }
    }
    private fun writeManifest(directory: File, filename: String) {
        val files = directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.map { file ->
            JSONObject().put("path", file.relativeTo(directory).invariantSeparatorsPath)
                .put("bytes", file.length()).put("sha256", sha(file))
        }.toList()
        writeNew(File(directory, filename), JSONObject().put("schemaVersion", 1)
            .put("musicalAcceptance", "UNVERIFIED").put("files", JSONArray(files)).toString(2).toByteArray())
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}