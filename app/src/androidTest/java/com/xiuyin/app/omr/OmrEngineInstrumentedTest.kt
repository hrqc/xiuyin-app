package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreStatus
import com.xiuyin.app.score.ScoreTimelineCompiler
import com.xiuyin.app.score.ScoreIssueSeverity
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OmrEngineInstrumentedTest {
    @Test fun blankImageRunsRealModelAndFailsWithoutInventingNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try {
            LocalOMREngine(context).use { engine ->
                val failure = runCatching { engine.recognizePage(bitmap, 0) {} }.exceptionOrNull()
                assertTrue("真实空白图必须报告未找到谱表", failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("五线谱"))
            }
        } finally { bitmap.recycle() }
    }

    /** Requires explicit locally staged user inputs. Absent input is skipped, never a fake pass. */
    @Test fun suppliedRealPagesProducePersistentReviewableDigitalScore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("omrInputDirectory")
        assumeTrue("未提供真实谱图目录，本项未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_-]{1,80}").matches(inputName))
        val inputDirectory = File(context.filesDir, inputName)
        val files = inputDirectory.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "pdf") }.sortedBy { it.name }
        require(files.isNotEmpty()) { "已授权测试目录中没有真实谱图" }
        val runId = UUID.randomUUID().toString()
        val reportFile = File(context.filesDir, "omr-evaluation-$runId.json")
        val report = JSONObject().put("runId", runId).put("startedAt", Instant.now().toString())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("status", "RUNNING")
            .put("accuracyValidation", "UNVERIFIED").put("inputs", JSONArray(files.map { file ->
                JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", sha(file.readBytes()))
            }))
        reportFile.writeText(report.toString(2))
        try {
            // Every execution gets a new app-private namespace: cached results from an older APK
            // can never masquerade as a fresh successful inference. Models remain the real assets.
            val runDirectory = File(context.filesDir, "omr-validation-runs/$runId")
            check(runDirectory.mkdirs())
            val freshContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = runDirectory
            }
            report.put("freshSourceNamespace", runDirectory.absolutePath)
            val service = OmrImportService(freshContext)
            val result = service.importDocuments(files.map(Uri::fromFile))
            assertFalse("本轮真实识别不得复用旧运行结果", result.reusedExisting)
            val events = result.score.parts.flatMap { it.measures }.flatMap { it.events }
            assertTrue("真实模型没有产生音符", events.any { it.pitch != null })
            assertEquals(ScoreStatus.NEEDS_REVIEW, result.score.status)
            assertTrue(events.all { it.confidence != null && it.confidence in 0.0..1.0 && it.sourceBox != null })
            val raw = JSONObject(result.rawResultFile.readText())
            val pages = raw.getJSONArray("pages")
            var rawEventCount = 0
            var rawGraceCount = 0
            var staffCount = 0
            for (p in 0 until pages.length()) {
                val staffs = pages.getJSONObject(p).getJSONArray("staffs")
                staffCount += staffs.length()
                for (s in 0 until staffs.length()) {
                    val staff = staffs.getJSONObject(s)
                    assertTrue("解码器未到达EOS", staff.getBoolean("eos"))
                    assertFalse(staff.getBoolean("sequenceTruncated"))
                    val tokens = staff.getJSONArray("tokens")
                    for (t in 0 until tokens.length()) {
                        val rhythm = tokens.getJSONObject(t).getString("rhythm")
                        if (rhythm.startsWith("note_") || rhythm.startsWith("rest_")) rawEventCount++
                        if (rhythm.startsWith("note_") && 'G' in rhythm) rawGraceCount++
                    }
                }
            }
            assertEquals("转换时不得丢音符、休止符", rawEventCount, events.size)
            assertEquals("不得把装饰音静默变普通音符", rawGraceCount, events.count { it.grace })
            assertTrue(events.filter { it.grace }.all { it.duration.numerator == 0L })
            val timeline = ScoreTimelineCompiler.compile(result.score)
            val timelineErrors = timeline.issues.filter { it.severity == ScoreIssueSeverity.ERROR }
            report.put("timeline", JSONObject().put("compiled", true)
                .put("totalQuarterBeats", timeline.totalBeats.toString()).put("measureCount", timeline.measures.size)
                .put("noteCount", timeline.notes.size).put("requiredChordCount", timeline.chords.size)
                .put("errorCount", timelineErrors.size).put("errors", JSONArray(timelineErrors.map { it.message })))
            assertTrue("实际谱面时间线仍有结构错误，不能宣称陪练结构可用", timelineErrors.isEmpty())
            val loaded = OmrImportService(freshContext).load(result.sourceId)
            assertTrue(ScoreJsonCodec.encode(result.score).contentEquals(ScoreJsonCodec.encode(loaded.score)))
            val reused = service.importDocuments(files.map(Uri::fromFile))
            assertTrue(reused.reusedExisting)
            assertTrue(ScoreJsonCodec.encode(result.score).contentEquals(ScoreJsonCodec.encode(reused.score)))
            report.put("status", "INFERENCE_AND_PERSISTENCE_PASSED")
                .put("reusedExistingAtStart", result.reusedExisting).put("sourceId", result.sourceId)
                .put("pageCount", pages.length()).put("staffCount", staffCount).put("eventCount", events.size)
                .put("graceCount", rawGraceCount).put("issueCount", result.score.issues.size)
                .put("rawResultPath", result.rawResultFile.absolutePath)
                .put("originalScorePath", result.originalScoreFile.absolutePath)
                .put("originalScoreSha256", sha(result.originalScoreFile.readBytes()))
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("error", failure.stackTraceToString())
            throw failure
        } finally {
            report.put("finishedAt", Instant.now().toString())
            reportFile.writeText(report.toString(2))
            android.util.Log.i("XiuyinOMRTest", "OMR_EVIDENCE=${reportFile.absolutePath}")
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
