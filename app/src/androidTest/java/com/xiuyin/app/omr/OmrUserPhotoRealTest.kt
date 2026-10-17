package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses exactly the user's staged bytes. No expected pitch, meter, staff count or measure count enters inference. */
@RunWith(AndroidJUnit4::class)
class OmrUserPhotoRealTest {
    @Suppress("DEPRECATION") // Legacy PackageInfo lookup supports minSdk 26 and records the actual installed app.
    @Test fun exactUserPhotoUsesProductionImportAndPreservesAllAvailableEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val inputName = args.getString("omrUserPhotoInput")
        assumeTrue("未提供本次用户原图，本项未验证", inputName != null)
        require(inputName != null && Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}").matches(inputName))
        val expectedSha = requireNotNull(args.getString("omrUserPhotoSha256")) { "必须提供冻结原图 SHA-256" }
        require(Regex("[a-f0-9]{64}").matches(expectedSha))
        val source = File(context.filesDir, inputName)
        val runId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "omr-user-photo-tests/$runId").apply { check(mkdirs()) }
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "omr-user-photo-tests/$runId").apply { check(mkdirs()) }
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("status", "RUNNING")
            .put("startedAt", Instant.now().toString()).put("compiledAgainstAppVersion", BuildConfig.VERSION_NAME)
            .put("gitCommitAtTestCompile", BuildConfig.GIT_COMMIT).put("worktreeStateAtTestCompile", BuildConfig.GIT_WORKTREE_STATE)
            .put("expectedInputSha256", expectedSha).put("scope", "Exact original bytes, production import, new-service readback; no music reference injected")
            .put("musicalAccuracy", "UNVERIFIED").put("physicalPhoneAcceptance", "UNVERIFIED")
        val appSha = sha(File(context.applicationInfo.sourceDir))
        val testSha = sha(File(instrumentation.context.applicationInfo.sourceDir))
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        report.put("appApkSha256", appSha).put("testApkSha256", testSha)
            .put("installedAppVersion", installed.versionName).put("installedVersionCode", installed.versionCode)
        val progress = JSONArray()
        var failure: Throwable? = null
        try {
            require(source.isFile && source.length() in 1..20L * 1024 * 1024) { "暂存原图缺失或超过图片大小限制" }
            val input = source.readBytes()
            assertEquals("原图 SHA 不符，不能以另一张图片替代", expectedSha, sha(input))
            writeNew(File(output, "original-input.document"), input)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            report.put("inputBytes", input.size).put("inputWidth", bounds.outWidth).put("inputHeight", bounds.outHeight)
                .put("inputPixels", bounds.outWidth.toLong() * bounds.outHeight).put("inputMime", bounds.outMimeType ?: JSONObject.NULL)
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = directory
            }
            val result = OmrImportService(isolated).importDocuments(listOf(Uri.fromFile(source))) { message ->
                progress.put(JSONObject().put("at", Instant.now().toString()).put("message", message))
                Log.i("XiuyinOMRUserPhoto", "$runId: $message")
            }
            assertFalse("本轮必须重新真实推理", result.reusedExisting)
            val rawBytes = result.rawResultFile.readBytes()
            val originalScoreBytes = result.originalScoreFile.readBytes()
            val raw = JSONObject(rawBytes.toString(Charsets.UTF_8))
            report.put("sourceId", result.sourceId).put("rawSha256", sha(rawBytes))
                .put("originalScoreSha256", sha(originalScoreBytes)).put("rawStatus", raw.getString("status"))
                .put("scoreStatus", result.score.status.name).put("issueCount", result.score.issues.size)
            val pages = raw.getJSONArray("pages")
            val measures = result.score.parts.flatMap { it.measures }
            val events = measures.flatMap { it.events }
            report.put("observed", JSONObject().put("pageCount", pages.length()).put("partCount", result.score.parts.size)
                .put("staffCounts", JSONArray((0 until pages.length()).map { pages.getJSONObject(it).getJSONArray("staffs").length() }))
                .put("measureCount", measures.size).put("noteCount", events.count { !it.isRest }).put("restCount", events.count { it.isRest }))
            assertEquals(ScoreStatus.NEEDS_REVIEW, result.score.status)
            assertArrayEquals(input, File(result.sourceDirectory, "original-000.document").readBytes())
            val reloaded = OmrImportService(isolated).load(result.sourceId)
            assertArrayEquals(ScoreJsonCodec.encode(result.score), ScoreJsonCodec.encode(reloaded.score))
            assertArrayEquals(rawBytes, reloaded.rawResultFile.readBytes())
            assertArrayEquals(originalScoreBytes, reloaded.originalScoreFile.readBytes())
            assertEquals(expectedSha, sha(File(reloaded.sourceDirectory, "original-000.document")))
            assertEquals(expectedSha, sha(source))
            report.put("repositoryReload", "NEW_SERVICE_SAME_PROCESS_PASSED")
                .put("status", "IMPORT_AND_REPOSITORY_READBACK_PASSED_NEEDS_REVIEW")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "FAILED").put("error", error.stackTraceToString())
        } finally {
            try {
                val files = directory.walkTopDown().filter { it.isFile }.toList()
                report.put("retainedArchiveFileCount", files.size)
                    .put("rawOutputPresent", files.any { it.name in setOf("raw-result.json", "failure-raw.json") })
                files.forEach { file ->
                    val target = File(output, "archive/${file.relativeTo(directory).invariantSeparatorsPath}")
                    check(target.parentFile!!.let { it.exists() || it.mkdirs() })
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use { sink -> file.inputStream().use { it.copyTo(sink) } }
                    assertEquals("证据复制不一致", sha(file), sha(target))
                }
                assertEquals("原图在运行期间变化", expectedSha, sha(source))
                assertEquals("运行中 App APK 变化", appSha, sha(File(context.applicationInfo.sourceDir)))
                assertEquals("运行中测试 APK 变化", testSha, sha(File(instrumentation.context.applicationInfo.sourceDir)))
            } catch (archiveError: Throwable) {
                failure?.addSuppressed(archiveError) ?: run { failure = archiveError }
                report.put("status", "FAILED").put("archiveError", archiveError.stackTraceToString())
            }
            report.put("finishedAt", Instant.now().toString()).put("progress", progress)
            writeNew(File(output, "report.json"), report.toString(2).toByteArray(Charsets.UTF_8))
            val manifest = JSONArray(output.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(output).invariantSeparatorsPath }.map {
                JSONObject().put("path", it.relativeTo(output).invariantSeparatorsPath).put("bytes", it.length()).put("sha256", sha(it))
            }.toList())
            writeNew(File(output, "evidence-manifest.json"), JSONObject().put("schemaVersion", 1).put("files", manifest)
                .put("musicalAcceptance", "UNVERIFIED").put("musicalAccuracy", "UNVERIFIED").toString(2).toByteArray(Charsets.UTF_8))
            instrumentation.addResults(android.os.Bundle().apply {
                putString("OMR_USER_PHOTO_RESULT", File(output, "report.json").absolutePath)
            })
            Log.i("XiuyinOMRUserPhoto", "OMR_USER_PHOTO_EVIDENCE=${output.absolutePath}")
        }
        failure?.let { throw it }
    }

    private fun writeNew(file: File, bytes: ByteArray) { Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
