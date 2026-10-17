package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.ScoreJsonCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real model on an explicitly identified resized real page. This is import coverage, not new musical ground truth. */
@RunWith(AndroidJUnit4::class)
class OmrLargePhotoRealTest {
    @Test fun enlargedRealPageImportsWithOriginalBytesAndColdRepositoryReadback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("omrInputDirectory")
        assumeTrue("未提供已授权的真实58/59谱图目录，本项未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_-]{1,80}").matches(inputName))
        val originalFile = File(context.filesDir, inputName).listFiles().orEmpty().firstOrNull { file ->
            file.isFile && file.length() < OmrImageSizing.MAX_IMAGE_BYTES && OmrImportService.sha256(file.readBytes()) == REAL_PAGE_SHA
        } ?: error("未找到冻结的真实58页原件；不能用别的图片冒充")
        val originalBytes = originalFile.readBytes()
        val runId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "omr-large-photo-tests/$runId").apply { check(mkdirs()) }
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "omr-large-photo-tests/$runId").apply { check(mkdirs()) }
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("status", "RUNNING")
            .put("startedAt", Instant.now().toString()).put("version", BuildConfig.VERSION_NAME).put("gitCommit", BuildConfig.GIT_COMMIT)
            .put("sourceFixtureSha256", REAL_PAGE_SHA).put("inputDerivation", "TEST DATA: real page scaled 3x on both axes; not a new camera capture")
            .put("musicalAccuracy", "UNVERIFIED").put("physicalPhoneAcceptance", "UNVERIFIED")
        var failure: Throwable? = null
        try {
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size))
            val derived = File(directory, "TEST-DATA-enlarged-real-page.jpg")
            try {
                val enlarged = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3, bitmap.height * 3, true)
                try {
                    assertTrue("测试输入必须真实超过旧8MP准入门限", enlarged.width.toLong() * enlarged.height > 8_000_000L)
                    derived.outputStream().use { check(enlarged.compress(Bitmap.CompressFormat.JPEG, 96, it)) }
                    report.put("sourceWidth", enlarged.width).put("sourceHeight", enlarged.height)
                } finally { enlarged.recycle() }
            } finally { bitmap.recycle() }
            val derivedBytes = derived.readBytes()
            val derivedSha = OmrImportService.sha256(derivedBytes)
            report.put("derivedInputBytes", derivedBytes.size).put("derivedInputSha256", derivedSha)
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = directory
            }
            val result = OmrImportService(isolated).importDocuments(listOf(Uri.fromFile(derived))) {
                Log.i("XiuyinOMRLargePhoto", "$runId: $it")
            }
            assertFalse(result.reusedExisting)
            assertArrayEquals(derivedBytes, File(result.sourceDirectory, "original-000.document").readBytes())
            assertArrayEquals(originalBytes, originalFile.readBytes())
            assertArrayEquals(derivedBytes, derived.readBytes())
            val raw = JSONObject(result.rawResultFile.readText())
            val preparation = raw.getJSONArray("images").getJSONObject(0).getJSONObject("preparation")
            assertTrue(preparation.getBoolean("inferencePageModified"))
            assertFalse(preparation.getBoolean("sourceDocumentModified"))
            assertEquals(2, preparation.getInt("decodeSampleSize"))
            assertTrue(preparation.getLong("outputWidth") * preparation.getLong("outputHeight") <= 8_000_000L)
            assertTrue(result.score.parts.flatMap { it.measures }.flatMap { it.events }.any { !it.isRest })
            val reloaded = OmrImportService(isolated).load(result.sourceId)
            assertArrayEquals(ScoreJsonCodec.encode(result.score), ScoreJsonCodec.encode(reloaded.score))
            assertEquals(OmrImportService.sha256(result.rawResultFile.readBytes()), OmrImportService.sha256(reloaded.rawResultFile.readBytes()))
            assertEquals(derivedSha, OmrImportService.sha256(File(reloaded.sourceDirectory, "original-000.document").readBytes()))
            report.put("status", "LARGE_IMAGE_IMPORT_AND_REPOSITORY_READBACK_PASSED")
                .put("sourceId", result.sourceId).put("preparation", preparation).put("issueCount", result.score.issues.size)
                .put("archiveOriginalSha256", derivedSha).put("scoreStatus", result.score.status.name)
                .put("reopenScope", "NEW_SERVICE_INSTANCE_SAME_PROCESS")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "FAILED").put("error", error.stackTraceToString())
        } finally {
            try {
                directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    val target = File(output, file.relativeTo(directory).path)
                    check(target.parentFile!!.let { it.exists() || it.mkdirs() })
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use { sink -> file.inputStream().use { it.copyTo(sink) } }
                    check(OmrImportService.sha256(file.readBytes()) == OmrImportService.sha256(target.readBytes()))
                }
            } catch (archiveError: Throwable) {
                failure?.addSuppressed(archiveError) ?: run { failure = archiveError }
                report.put("status", "FAILED").put("archiveError", archiveError.stackTraceToString())
            }
            report.put("finishedAt", Instant.now().toString())
            Files.write(File(output, "report.json").toPath(), report.toString(2).toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
            Log.i("XiuyinOMRLargePhoto", "OMR_LARGE_PHOTO_EVIDENCE=${output.absolutePath}")
        }
        failure?.let { throw it }
    }
    companion object { private const val REAL_PAGE_SHA = "7b2f61b9311fd606512c545a7b28dae7b3d3ec19f55bb93b21b77b02ce306eba" }
}
