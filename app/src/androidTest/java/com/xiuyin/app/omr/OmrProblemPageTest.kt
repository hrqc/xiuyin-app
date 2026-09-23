package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.practice.supportsHandSelection
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreStructureTools
import com.xiuyin.app.score.ScoreTime
import com.xiuyin.app.score.ScoreTimelineCompiler
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

/** Real model diagnostics and structural regression for the user's exact photo; no synthetic image. */
@RunWith(AndroidJUnit4::class)
class OmrProblemPageTest {
    @Test fun captureActualSegmentationAndStaffDetectionDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("omrProblemInput")
        assumeTrue("未提供实际问题谱图，本诊断未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_.-]{1,100}").matches(inputName) && inputName !in setOf(".", ".."))
        val source = File(context.filesDir, inputName)
        require(source.isFile && source.length() in 1..20L * 1024 * 1024)
        val sourceBytes = source.readBytes()
        assertEquals("只接受用户本次问题谱原件", "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf", sha(sourceBytes))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size))
        val directory = File(context.filesDir, "omr-problem-probe-${UUID.randomUUID()}")
        check(directory.mkdir())
        val report = JSONObject().put("sourceSha256", sha(sourceBytes)).put("startedAt", Instant.now().toString())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("versionName", BuildConfig.VERSION_NAME).put("width", bitmap.width).put("height", bitmap.height)
            .put("status", "RUNNING").put("scope", "Real segmentation diagnostics only; not OMR accuracy acceptance")
        try {
            LocalOMREngine(context).use { engine ->
                // Test-only reflection observes the actual implementation without adding production debug I/O.
                val field = LocalOMREngine::class.java.getDeclaredField("models").apply { isAccessible = true }
                val recognizer = OmrPageRecognizer(field.get(engine) as NativeOmrModels)
                fun method(name: String) = OmrPageRecognizer::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }
                val gray = method("grayscale").invoke(recognizer, bitmap) as IntArray
                val enhanced = method("clahe").invoke(recognizer, gray, bitmap.width, bitmap.height) as IntArray
                val progress: (String) -> Unit = { Log.i("XiuyinOMRProbe", it) }
                val labels = method("segment").invoke(recognizer, enhanced, bitmap.width, bitmap.height, progress) as ByteArray
                assertEquals(bitmap.width * bitmap.height, labels.size)
                assertTrue(labels.all { it.toInt() in 0..5 })
                assertTrue("真实分割必须包含谱线类别", labels.any { it.toInt() == 4 })
                val labelsFile = File(directory, "labels.bin")
                Files.write(labelsFile.toPath(), labels, StandardOpenOption.CREATE_NEW)
                val histogram = IntArray(bitmap.height)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (labels[y * bitmap.width + x].toInt() == 4) histogram[y]++
                report.put("labelsFile", labelsFile.absolutePath).put("labelsSha256", sha(labels))
                    .put("labelsEncoding", "ROW_MAJOR_UINT8_CLASSES_0_TO_5_STAFF_LINE_CLASS_4")
                    .put("classCounts", JSONArray((0..5).map { c -> labels.count { it.toInt() == c } }))
                    .put("staffLineRowHistogram", JSONArray(histogram.toList()))
                    .put("legacyRowThreshold", maxOf(30.0, bitmap.width * .16))
                try {
                    val regions = method("detectStaffs").invoke(recognizer, labels, bitmap.width, bitmap.height) as List<*>
                    report.put("detectedStaffCount", regions.size).put("regions", JSONArray(regions.map { region ->
                        requireNotNull(region)
                        fun value(name: String): Any = region.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(region)!!
                        JSONObject().put("lines", JSONArray((value("lines") as DoubleArray).toList()))
                            .put("box", JSONArray((value("box") as IntArray).toList())).put("spacing", value("spacing"))
                    }))
                } catch (error: ReflectiveOperationException) {
                    report.put("detectorError", (error.cause ?: error).stackTraceToString())
                }
                report.put("status", "DIAGNOSTICS_CAPTURED").put("staffAccuracy", "NOT_ACCEPTED_BY_THIS_DIAGNOSTIC")
            }
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.stackTraceToString())
            throw error
        } finally {
            bitmap.recycle()
            report.put("finishedAt", Instant.now().toString())
            val reportFile = File(directory, "probe.json")
            reportFile.writeText(report.toString(2))
            Log.i("XiuyinOMRProbe", "OMR_PROBLEM_PROBE=${reportFile.absolutePath}")
        }
    }

    /**
     * The staged image is Czerny Op. 849 No. 8, printed page 15, measures 18-26.
     * This page omits the meter; 2/4 and one linear pass are explicit test choices,
     * never recognition output. Musical pitch/rhythm/8va accuracy remains unverified.
     */
    @Test fun actualProblemPhotoImportsAllStaffsAndRequiresExplicitPracticeChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("omrProblemInput")
        assumeTrue("未提供实际问题谱图，本真实导入回归未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_.-]{1,100}").matches(inputName) && inputName !in setOf(".", ".."))
        val source = File(context.filesDir, inputName)
        require(source.isFile && source.length() in 1L..(20L * 1024 * 1024))
        assertEquals("本回归只接受用户本次原始谱图", "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf", sha(source.readBytes()))
        val runId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "omr-problem-import-$runId")
        check(directory.mkdir())
        val reportFile = File(directory, "report.json")
        val report = JSONObject().put("runId", runId).put("startedAt", Instant.now().toString())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("versionName", BuildConfig.VERSION_NAME)
            .put("status", "RUNNING").put("sourceName", source.name).put("sourceSha256", sha(source.readBytes()))
            .put("freshSourceNamespace", directory.absolutePath).put("reportPath", reportFile.absolutePath)
            .put("structuralReference", "User problem photo: 3 systems, 6 staffs, 9 visible measures, 8 written rests")
            .put("explicitTestChoices", JSONObject().put("time", "2/4").put("repeatStrategy", "ONE_LINEAR_PASS"))
            .put("musicalPitchRhythmAndOctaveAccuracy", "UNVERIFIED").put("phonePianoRoomAccuracy", "UNVERIFIED")
        reportFile.writeText(report.toString(2))
        try {
            val freshContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = directory
            }
            val service = OmrImportService(freshContext)
            val result = service.importDocuments(listOf(Uri.fromFile(source))) { Log.i("XiuyinOMRProblem", it) }
            // Persist all paths before acceptance assertions so a real recognition failure remains inspectable.
            report.put("sourceId", result.sourceId).put("rawResultPath", result.rawResultFile.absolutePath)
                .put("originalScorePath", result.originalScoreFile.absolutePath)
                .put("originalScoreSha256", sha(result.originalScoreFile.readBytes()))
                .put("reusedExisting", result.reusedExisting)
            val raw = JSONObject(result.rawResultFile.readText())
            val pages = raw.getJSONArray("pages")
            val staffCounts = (0 until pages.length()).map { pages.getJSONObject(it).getJSONArray("staffs").length() }
            val score = result.score
            val measures = score.parts.flatMap { it.measures }
            val events = measures.flatMap { it.events }
            val allStaffs = (0 until pages.length()).flatMap { page ->
                val staffs = pages.getJSONObject(page).getJSONArray("staffs")
                (0 until staffs.length()).map(staffs::getJSONObject)
            }
            val rawEventCount = allStaffs.sumOf { staff ->
                val tokens = staff.getJSONArray("tokens")
                (0 until tokens.length()).count { index -> tokens.getJSONObject(index).getString("rhythm").let { it.startsWith("note_") || it.startsWith("rest_") } }
            }
            val rawRestByStaff = allStaffs.map { staff ->
                val tokens = staff.getJSONArray("tokens")
                (0 until tokens.length()).count { tokens.getJSONObject(it).getString("rhythm").startsWith("rest_") }
            }
            report.put("pageCount", pages.length()).put("staffCounts", JSONArray(staffCounts))
                .put("partCount", score.parts.size).put("measureCount", measures.size).put("eventCount", events.size)
                .put("restCount", events.count { it.isRest }).put("rawRestByStaff", JSONArray(rawRestByStaff))
                .put("rawEventCount", rawEventCount).put("originalIssues", JSONArray(score.issues.map { issue ->
                    JSONObject().put("id", issue.id).put("severity", issue.severity.name).put("message", issue.message)
                        .put("measureId", issue.measureId ?: JSONObject.NULL).put("eventId", issue.eventId ?: JSONObject.NULL)
                }))
            assertFalse("必须真实新推理，不能复用旧识谱缓存", result.reusedExisting)
            assertEquals(1, pages.length())
            assertEquals("原图三系统共六谱表不能漏检", listOf(6), staffCounts)
            assertTrue("各谱表必须真正解码完毕", allStaffs.all { it.getBoolean("eos") && !it.getBoolean("sequenceTruncated") })
            assertEquals("六个谱表须合成一个钢琴Part", 1, score.parts.size)
            assertEquals("原图每系统3小节，共9小节", 9, measures.size)
            assertTrue(measures.all { it.declaredStaves == 2 })
            assertEquals("转换不得丢弃或增加模型事件", rawEventCount, events.size)
            assertEquals("原图可见休止符所在谱表分布", listOf(0, 0, 0, 2, 1, 5), rawRestByStaff)
            assertEquals("不得将原图的八个休止符删除", 8, events.count { it.isRest })
            assertTrue("此续页未印拍号，不能自动假设", measures.all { it.time == null })
            val originalIssues = ScoreStructureTools.reviewIssues(score)
            assertTrue("缺拍号必须保留阻断", originalIssues.any { it.severity == ScoreIssueSeverity.ERROR && it.id.startsWith("validation:") && "拍号" in it.message })
            assertEquals("两谱表末尾均有反复终止符，需明确演奏选择", 2, ScoreStructureTools.pendingRepeatChoices(score).size)
            assertThrows(IllegalArgumentException::class.java) { ScoreFollower(ScoreTimelineCompiler.compile(score), PracticeConfig()) }
            val withMeter = ScoreStructureTools.setWholeScoreTime(score, ScoreTime(2, 4))
            assertEquals(2, ScoreStructureTools.pendingRepeatChoices(withMeter).size)
            assertThrows(IllegalArgumentException::class.java) { ScoreFollower(ScoreTimelineCompiler.compile(withMeter), PracticeConfig()) }
            val confirmed = ScoreStructureTools.confirmLinearRepeat(withMeter)
            assertTrue(ScoreStructureTools.hasConfirmedLinearRepeat(confirmed))
            val confirmedFile = File(directory, "explicit-linear-2-4-score.json")
            Files.write(confirmedFile.toPath(), ScoreJsonCodec.encode(confirmed), StandardOpenOption.CREATE_NEW)
            report.put("explicitChoiceScorePath", confirmedFile.absolutePath)
            val timeline = ScoreTimelineCompiler.compile(confirmed)
            val errors = timeline.issues.filter { it.severity == ScoreIssueSeverity.ERROR }
            report.put("afterExplicitChoices", JSONObject().put("errorCount", errors.size)
                .put("errors", JSONArray(errors.map { JSONObject().put("id", it.id).put("message", it.message) }))
                .put("totalQuarterBeats", timeline.totalBeats.toString()).put("supportsHandSelection", supportsHandSelection(timeline)))
            assertEquals("拍号及顺序练习选择不能偷偷改变任何音符", events, confirmed.parts.flatMap { it.measures }.flatMap { it.events })
            assertTrue("其他结构错误不得被选择顺序练习隐藏", errors.isEmpty())
            assertTrue(supportsHandSelection(timeline))
            assertTrue(ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.RIGHT)).targets.isNotEmpty())
            assertTrue(ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.LEFT)).targets.isNotEmpty())
            val reloaded = service.load(result.sourceId)
            assertTrue("正式原始结果不能被测试选择覆盖", ScoreJsonCodec.encode(score).contentEquals(ScoreJsonCodec.encode(reloaded.score)))
            report.put("status", "STRUCTURAL_IMPORT_AND_EXPLICIT_CHOICES_PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error", error.stackTraceToString())
            throw error
        } finally {
            report.put("finishedAt", Instant.now().toString())
            report.put("retainedEvidenceFiles", JSONArray(directory.walkTopDown().maxDepth(4).filter { it.isFile }.take(1000).map { it.absolutePath }.toList()))
            reportFile.writeText(report.toString(2))
            Log.i("XiuyinOMRProblem", "OMR_PROBLEM_IMPORT=${reportFile.absolutePath}")
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
