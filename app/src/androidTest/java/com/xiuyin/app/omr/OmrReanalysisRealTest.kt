package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.MusicXmlParser
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreSourceStore
import com.xiuyin.app.score.ScoreTimelineCompiler
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.practice.supportsHandSelection
import com.xiuyin.app.score.ScoreRevisionStore
import com.xiuyin.app.score.ScoreStructureSummary
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit user problem-photo archive from v0.3.0-octave.2-fieldtest.1 only.
 * Real model output is never replaced with a fixture. Added annotation IDs/fingerings are
 * TEST CONTROL actions for revision preservation; they are not musical truth labels.
 */
@RunWith(AndroidJUnit4::class)
class OmrReanalysisRealTest {
    @Test fun stagedLegacyFiveStaffArchiveReallyReinfersAndKeepsReversibleRevisions() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("omrReanalysisArchive")
        assumeTrue("未提供旧版真实识谱归档；真实重识别成功链路未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_-]{1,80}").matches(inputName)) { "仅接受 filesDir 内显式准备的单层测试归档目录" }
        val input = File(base.filesDir, inputName)
        require(input.canonicalFile.parentFile == base.filesDir.canonicalFile &&
            Files.isDirectory(input.toPath(), LinkOption.NOFOLLOW_LINKS)) { "测试归档目录无效" }
        assertEquals("只接受已冻结的旧归档文件，不能混入新推理结果", expected.keys, input.listFiles().orEmpty().map { it.name }.toSet())
        verifyFrozenArchive(input)
        val baselineRaw = JSONObject(File(input, "raw-result.json").readText())
        assertEquals(sourceId, baselineRaw.getString("sourceId"))
        assertEquals(listOf(5), staffCounts(baselineRaw))
        val runId = UUID.randomUUID().toString()
        val runDirectory = File(base.filesDir, "omr-reanalysis-validation-$runId")
        check(runDirectory.mkdir()) { "无法建立隔离重识别测试目录" }
        val reportFile = File(runDirectory, "report.json")
        val report = JSONObject().put("runId", runId).put("status", "RUNNING")
            .put("startedAt", Instant.now().toString()).put("gitCommit", BuildConfig.GIT_COMMIT)
            .put("versionName", BuildConfig.VERSION_NAME).put("gitWorktreeState", BuildConfig.GIT_WORKTREE_STATE)
            .put("inputDirectory", input.absolutePath).put("sourceId", sourceId)
            .put("baselineStaffCounts", JSONArray(listOf(5))).put("frozenArchiveHashes", frozenHashes())
            .put("runDirectory", runDirectory.absolutePath)
            .put("musicalPitchRhythmAndOctaveAccuracy", "UNVERIFIED").put("phonePianoRoomAccuracy", "UNVERIFIED")
            .put("annotationSetup", "TEST CONTROL: user confirmation and fingering on one existing source event, no note/core modification")
            .put("applyPath", "Production OmrStructurePreview.annotationsForApply and ScoreRevisionStore.save; UI delivery tested separately")
        reportFile.writeText(report.toString(2))
        try {
            val archiveDirectory = File(runDirectory, "omr-sources/$sourceId")
            check(archiveDirectory.mkdirs())
            expected.keys.forEach { name -> Files.copy(File(input, name).toPath(), File(archiveDirectory, name).toPath()) }
            verifyFrozenArchive(archiveDirectory)
            val fresh = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = runDirectory
            }
            val original = OmrImportService(fresh).load(sourceId).score
            val scoreId = UUID.randomUUID().toString()
            val revisionDirectory = File(runDirectory, "score-revisions")
            val store = ScoreRevisionStore(revisionDirectory)
            val initial = store.load(scoreId, original)
            assertEquals(0L, initial.generation)
            val existingNoteId = original.parts.flatMap { it.measures }.flatMap { it.events }.first { !it.isRest }.id
            val prior = store.save(scoreId, initial.generation, original, setOf(existingNoteId), mapOf(existingNoteId to 3))
            assertEquals(original, prior.current)
            val startedMillis = System.currentTimeMillis()
            val preview = OmrReanalysisService(fresh).recognize(original, prior.current, prior.generation,
                progress = { Log.i("XiuyinOMRReanalysis", it) })
            report.put("inferenceEvidenceId", preview.evidenceId).put("previewMethod", preview.method.name)
                .put("before", summary(preview.before)).put("after", summary(preview.after))
            assertEquals(OmrPreviewMethod.REAL_IMAGE_RECOGNITION, preview.method)
            assertEquals(sourceId, preview.sourceId)
            assertEquals(prior.generation, preview.revisionGeneration)
            val evidenceId = requireNotNull(preview.evidenceId)
            assertEquals(evidenceId, UUID.fromString(evidenceId).toString())
            val evidence = File(runDirectory, "omr-reanalyses/$evidenceId")
            val manifest = JSONObject(File(evidence, "manifest.json").readText())
            val rawFile = File(evidence, "raw-result.json")
            val raw = JSONObject(rawFile.readText())
            val candidateFile = File(evidence, "candidate.json")
            report.put("newRawPath", rawFile.absolutePath).put("newRawSha256", sha(rawFile))
                .put("candidatePath", candidateFile.absolutePath).put("candidateSha256", sha(candidateFile))
                .put("manifestPath", File(evidence, "manifest.json").absolutePath)
                .put("newStaffCounts", JSONArray(staffCounts(raw)))
            assertEquals("FINISHED", manifest.getString("status"))
            assertEquals("REAL_LOCAL_IMAGE_REINFERENCE", manifest.getString("method"))
            assertEquals(evidenceId, manifest.getString("runId"))
            assertEquals(sourceId, manifest.getString("sourceId"))
            assertTrue("必须是本次调用产生的新模型运行", manifest.getLong("startedAtMillis") >= startedMillis)
            assertEquals(sha(rawFile), manifest.getString("rawSha256"))
            assertEquals(sha(candidateFile), manifest.getString("candidateSha256"))
            assertEquals(expected.getValue("original.json").second, manifest.getString("originalScoreSha256"))
            assertEquals(preview.candidate, ScoreJsonCodec.decode(candidateFile.readBytes()))
            assertEquals(original.source, preview.candidate.source)
            assertEquals("旧版漏掉的第六谱表应由新模型读取原图找回", listOf(6), staffCounts(raw))
            val expectedEventIds = linkedSetOf<String>()
            var rawRestCount = 0
            val pages = raw.getJSONArray("pages")
            for (pageIndex in 0 until pages.length()) {
                val page = pages.getJSONObject(pageIndex)
                val staffs = page.getJSONArray("staffs")
                for (staffIndex in 0 until staffs.length()) {
                    val staff = staffs.getJSONObject(staffIndex)
                    assertTrue("真实谱表解码须到达 EOS", staff.getBoolean("eos"))
                    assertFalse(staff.getBoolean("sequenceTruncated"))
                    val tokens = staff.getJSONArray("tokens")
                    for (tokenIndex in 0 until tokens.length()) {
                        val rhythm = tokens.getJSONObject(tokenIndex).getString("rhythm")
                        if (rhythm.startsWith("note_") || rhythm.startsWith("rest_")) {
                            expectedEventIds += "$sourceId:p${page.getInt("page")}:s$staffIndex:t$tokenIndex"
                            if (rhythm.startsWith("rest_")) rawRestCount++
                        }
                    }
                }
            }
            val candidateEvents = preview.candidate.parts.flatMap { it.measures }.flatMap { it.events }
            assertEquals(expectedEventIds, candidateEvents.map { it.id }.toSet())
            assertEquals(expectedEventIds.size, candidateEvents.size)
            assertEquals(rawRestCount, candidateEvents.count { it.isRest })
            report.put("rawEventCount", expectedEventIds.size).put("rawRestCount", rawRestCount)

            verifyFrozenArchive(input); verifyFrozenArchive(archiveDirectory)
            report.put("oldArchivePreservedAfterPreview", true)
            assertEquals("仅预览不得写入当前修订", prior, ScoreRevisionStore(revisionDirectory).load(scoreId, original))
            val (verified, fingerings) = preview.annotationsForApply(prior)
            assertTrue(verified.isEmpty()); assertTrue(fingerings.isEmpty())
            val applied = store.save(scoreId, prior.generation, preview.candidate, verified, fingerings)
            val cold = ScoreRevisionStore(revisionDirectory)
            val reopened = cold.load(scoreId, original)
            assertEquals(applied, reopened)
            assertEquals(preview.candidate, reopened.current)
            assertTrue(reopened.verifiedEventIds.isEmpty()); assertTrue(reopened.fingerings.isEmpty())
            val undone = cold.undo(scoreId, original, reopened.generation)
            assertEquals("撤销必须恢复旧版五谱表数字乐谱", original, undone.current)
            assertEquals(prior.verifiedEventIds, undone.verifiedEventIds)
            assertEquals(prior.fingerings, undone.fingerings)
            assertEquals(undone, ScoreRevisionStore(revisionDirectory).load(scoreId, original))
            verifyFrozenArchive(input); verifyFrozenArchive(archiveDirectory)
            report.put("revision", JSONObject().put("scoreId", scoreId).put("priorGeneration", prior.generation)
                .put("appliedGeneration", applied.generation).put("undoGeneration", undone.generation)
                .put("coldLoadVerified", true).put("newAnnotationsCleared", true).put("undoRestoredOldAnnotations", true))
                .put("status", "REAL_REINFERENCE_AND_REVERSIBLE_PERSISTENCE_PASSED")
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("error", failure.stackTraceToString())
            throw failure
        } finally {
            report.put("finishedAt", Instant.now().toString())
            reportFile.writeText(report.toString(2))
            Log.i("XiuyinOMRReanalysis", "OMR_REANALYSIS_REAL_REPORT=${reportFile.absolutePath}")
        }
    }

    /** This is a separately provided human reference, never a replacement for the model archive. */
    @Test fun stagedManualReferenceReallyImportsAndPreparesBothHands() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val inputName = InstrumentationRegistry.getArguments().getString("manualReviewScore")
        assumeTrue("未提供人工对照 MusicXML；实际导入链路未验证", inputName != null)
        require(inputName != null && Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,99}").matches(inputName)) {
            "仅接受 filesDir 内显式准备的单个对照 MusicXML 文件"
        }
        val input = File(base.filesDir, inputName)
        require(input.canonicalFile.parentFile == base.filesDir.canonicalFile &&
            Files.isRegularFile(input.toPath(), LinkOption.NOFOLLOW_LINKS)) { "对照 MusicXML 文件无效" }
        assertEquals(46236L, input.length())
        assertEquals(manualReferenceSha256, sha(input))
        val runId = UUID.randomUUID().toString()
        val runDirectory = File(base.filesDir, "manual-reference-import-validation-$runId")
        check(runDirectory.mkdir()) { "无法建立隔离人工对照谱测试目录" }
        val reportFile = File(runDirectory, "report.json")
        val report = JSONObject().put("runId", runId).put("status", "RUNNING")
            .put("inputKind", "MANUAL_REVIEW_INPUT_NOT_OMR")
            .put("inputPath", input.absolutePath).put("inputSha256", manualReferenceSha256)
            .put("inputBytes", input.length()).put("startedAt", Instant.now().toString())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("versionName", BuildConfig.VERSION_NAME)
            .put("gitWorktreeState", BuildConfig.GIT_WORKTREE_STATE)
            .put("importPath", "Production MusicXmlParser + ScoreSourceStore + ScoreRevisionStore; system picker/UI not exercised")
            .put("omrAccuracy", "NOT_APPLICABLE_MANUAL_REFERENCE")
            .put("teacherMusicalReview", "UNVERIFIED").put("phonePianoRoomAccuracy", "UNVERIFIED")
            .put("audioPlayback", "UNVERIFIED").put("originalOmrEvidence", "RETAINED_UNCHANGED_IN_SEPARATE_ARCHIVES")
        reportFile.writeText(report.toString(2))
        try {
            val bytes = input.readBytes()
            val parsed = MusicXmlParser().parseDocument(bytes)
            val original = parsed.score
            assertEquals("MusicXML", original.source.format)
            assertEquals(manualReferenceSha256, original.id)
            assertEquals(manualReferenceSha256, original.source.sha256)
            assertEquals(bytes.size, original.source.byteCount)
            val sourceDirectory = File(runDirectory, "digital-scores")
            val saved = ScoreSourceStore(sourceDirectory).save(bytes)
            assertEquals(original.id, saved.id)
            val coldSource = ScoreSourceStore(sourceDirectory).load(saved.id)
            assertArrayEquals(bytes, coldSource.bytes)
            assertEquals(original, MusicXmlParser().parse(coldSource.bytes))
            val scoreId = UUID.randomUUID().toString()
            val revisionDirectory = File(runDirectory, "score-revisions")
            val revision = ScoreRevisionStore(revisionDirectory).load(scoreId, original)
            val reopened = ScoreRevisionStore(revisionDirectory).load(scoreId, MusicXmlParser().parse(coldSource.bytes))
            assertEquals(revision, reopened)
            val score = reopened.current
            assertEquals(1, score.parts.size)
            val measures = score.parts.single().measures
            val events = measures.flatMap { it.events }
            assertEquals(9, measures.size)
            assertTrue(measures.all { it.declaredStaves == 2 })
            assertEquals(184, events.size)
            assertEquals(176, events.count { !it.isRest })
            assertEquals(8, events.count { it.isRest })
            val timeline = ScoreTimelineCompiler.compile(score)
            val errors = timeline.issues.filter { it.severity == ScoreIssueSeverity.ERROR }
            report.put("errors", JSONArray(errors.map { JSONObject().put("id", it.id).put("message", it.message) }))
            assertTrue("人工对照谱真实解析后不得有结构阻断", errors.isEmpty())
            assertEquals(BeatFraction.of(18), timeline.totalBeats)
            assertEquals(9, timeline.measures.size)
            assertTrue(supportsHandSelection(timeline))
            val right = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.RIGHT))
            val left = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.LEFT))
            val both = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.BOTH))
            assertTrue(right.targets.isNotEmpty()); assertTrue(left.targets.isNotEmpty())
            val rightIds = right.targets.flatMap { it.notes }.flatMap { it.eventIds }.toSet()
            val leftIds = left.targets.flatMap { it.notes }.flatMap { it.eventIds }.toSet()
            val bothIds = both.targets.flatMap { it.notes }.flatMap { it.eventIds }.toSet()
            assertTrue(right.targets.flatMap { it.notes }.all { it.staff == 1 })
            assertTrue(left.targets.flatMap { it.notes }.all { it.staff == 2 })
            assertTrue(rightIds.intersect(leftIds).isEmpty())
            assertEquals(bothIds, rightIds + leftIds)
            assertEquals(events.filter { !it.isRest && !it.grace }.map { it.id }.toSet(), bothIds)
            assertEquals(manualReferenceSha256, sha(input))
            assertEquals(manualReferenceSha256, sha(File(sourceDirectory, "${saved.id}.musicxml")))
            report.put("scoreId", scoreId).put("sourceId", saved.id).put("partCount", score.parts.size)
                .put("measureCount", measures.size).put("eventCount", events.size).put("pitchedEventCount", 176)
                .put("restCount", 8).put("totalQuarterBeats", timeline.totalBeats.toString()).put("errorCount", errors.size)
                .put("supportsHandSelection", true).put("rightTargetCount", right.targets.size)
                .put("leftTargetCount", left.targets.size).put("bothTargetCount", both.targets.size)
                .put("coldLoadVerified", true).put("sourceHashPreserved", true)
                .put("status", "MANUAL_REFERENCE_IMPORT_AND_STRUCTURE_PASSED")
        } catch (failure: Throwable) {
            report.put("status", "FAILED").put("error", failure.stackTraceToString())
            throw failure
        } finally {
            report.put("finishedAt", Instant.now().toString())
            reportFile.writeText(report.toString(2))
            Log.i("XiuyinManualReference", "MANUAL_REFERENCE_IMPORT_REPORT=${reportFile.absolutePath}")
        }
    }

    private fun verifyFrozenArchive(directory: File) {
        expected.forEach { (name, value) ->
            val file = File(directory, name)
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "旧归档缺少普通文件 $name" }
            assertEquals("旧归档大小不符：$name", value.first, file.length())
            assertEquals("旧归档字节发生变化：$name", value.second, sha(file))
        }
    }
    private fun frozenHashes() = JSONObject().also { json -> expected.forEach { (name, value) ->
        json.put(name, JSONObject().put("bytes", value.first).put("sha256", value.second))
    } }
    private fun staffCounts(raw: JSONObject): List<Int> {
        val pages = raw.getJSONArray("pages")
        return (0 until pages.length()).map { pages.getJSONObject(it).getJSONArray("staffs").length() }
    }
    private fun summary(value: ScoreStructureSummary) = JSONObject().put("partCount", value.partCount)
        .put("staffCount", value.staffCount).put("measureCount", value.measureCount).put("eventCount", value.eventCount)
        .put("restCount", value.restCount).put("errorCount", value.errorCount).put("timelineProblem", value.timelineProblem ?: JSONObject.NULL)
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) { val n = input.read(chunk); if (n < 0) break; digest.update(chunk, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        private const val manualReferenceSha256 = "1a264ba2356e995b61e32f1fdce8d4a412c07dd50650597b14b82222694b5ed8"
        private const val sourceId = "077c4adef010a37a6634ce95357ee226bd920eedc33744a0381305f9ba8d481f"
        private val expected = linkedMapOf(
            "raw-result.json" to (813491L to "b5c389f9d8ae9b67bf96498ef97676ad8286110fb4ccfeeeac382bbc328a561c"),
            "original.json" to (89496L to "74f217992dfadc4d28bb5896881a901a3689bde58ba4145341d500b322c5aac5"),
            "original-000.document" to (855285L to "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf"),
            "page-000.image" to (855285L to "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf"),
        )
    }
}
