package com.xiuyin.app.omr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.score.*
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA protocol archive; checks real local storage, never claims model/image accuracy. */
@RunWith(AndroidJUnit4::class)
class OmrStructureUpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val owned = mutableListOf<File>()
    private val reanalysesOwned = mutableListOf<File>()
    private val revisionRoot = File(context.cacheDir, "omr-upgrade-revisions-${UUID.randomUUID()}")

    @After fun cleanOwnedFixtures() {
        reanalysesOwned.forEach { directory ->
            check(directory.parentFile == File(context.filesDir, "omr-reanalyses") && UUID.fromString(directory.name).toString() == directory.name)
            directory.listFiles().orEmpty().forEach { check(it.isFile); check(it.delete()) }
            check(directory.delete())
        }
        owned.forEach { directory ->
            check(directory.parentFile == File(context.filesDir, "omr-sources") && directory.name.matches(Regex("[a-f0-9]{64}")))
            directory.listFiles().orEmpty().forEach { check(it.isFile); check(it.delete()) }
            check(directory.delete())
        }
        check(revisionRoot.parentFile == context.cacheDir && revisionRoot.name.startsWith("omr-upgrade-revisions-"))
        revisionRoot.listFiles().orEmpty().forEach { check(it.isFile); check(it.delete()) }
        Files.deleteIfExists(revisionRoot.toPath())
    }

    @Test fun previewLeavesArchiveUntouchedAndAppliedRevisionSurvivesReopenAndUndo() {
        val (original, directory) = archive()
        val originalFiles = directory.listFiles()!!.associate { it.name to it.readBytes() }
        val preview = OmrStructureUpgrade(context).preview(original, original, 0)
        assertTrue(preview.before.errorCount > preview.after.errorCount)
        assertEquals(original.source, preview.candidate.source)
        originalFiles.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
        val scoreId = UUID.randomUUID().toString()
        val store = ScoreRevisionStore(revisionRoot)
        store.load(scoreId, original)
        val id = preview.candidate.parts.first().measures.first().events.first().id
        val saved = store.save(scoreId, 0, preview.candidate, setOf(id), mapOf(id to 3))
        val reopened = ScoreRevisionStore(revisionRoot).load(scoreId, original)
        assertEquals(saved, reopened)
        assertEquals(setOf(id), reopened.verifiedEventIds)
        assertEquals(original, store.undo(scoreId, original, saved.generation).current)
        originalFiles.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
    }

    @Test fun manualPitchMeterAndStaffChangesAreProtectedFromReinterpretation() {
        val (original, _) = archive()
        val part = original.parts.first()
        val measure = part.measures.first()
        val note = measure.events.first()
        val changes = listOf(
            measure.copy(events = listOf(note.copy(pitch = ScorePitch("D", 0, 4)))),
            measure.copy(time = ScoreTime(12, 8)),
            measure.copy(declaredStaves = 2),
        )
        changes.forEach { changed ->
            val current = original.copy(parts = listOf(part.copy(measures = listOf(changed))))
            val error = assertThrows(IllegalArgumentException::class.java) { OmrStructureUpgrade.requireUnedited(original, current) }
            assertTrue(error.message!!.contains("不会覆盖或清空"))
        }
        OmrStructureUpgrade.requireUnedited(original, original.copy(issues = original.issues + ScoreIssue("confirmed", "TEST DATA")))
    }

    @Test fun rawArchiveVersionMismatchIsRejectedWithoutOverwritingAnything() {
        val (original, directory) = archive()
        val rawFile = File(directory, "raw-result.json")
        val bad = JSONObject(rawFile.readText()).put("schemaVersion", 99).toString()
        rawFile.writeText(bad)
        assertThrows(IllegalArgumentException::class.java) { OmrStructureUpgrade(context).preview(original, original, 0) }
        assertEquals(bad, rawFile.readText())
        assertEquals(original, OmrImportService(context).load(original.source.sha256).score)
    }

    @Test fun explicitWholeMeterUsesExistingRevisionFormatAndCanBeUndoneAfterColdLoad() {
        val (original, _) = archive()
        val scoreId = UUID.randomUUID().toString()
        val store = ScoreRevisionStore(revisionRoot)
        store.load(scoreId, original)
        val changed = ScoreStructureTools.setWholeScoreTime(original, ScoreTime(12, 8))
        val snapshot = store.save(scoreId, 0, changed, emptySet(), emptyMap())
        val coldStore = ScoreRevisionStore(revisionRoot)
        assertEquals(changed, coldStore.load(scoreId, original).current)
        assertEquals(original, coldStore.undo(scoreId, original, snapshot.generation).current)
        assertEquals(original.parts.flatMap { it.measures }.flatMap { it.events }, changed.parts.flatMap { it.measures }.flatMap { it.events })
    }

    @Test fun explicitLinearRepeatChoicePersistsWithSameIssueIdsAndUndoRestoresBlocking() {
        val (base, directory) = archive()
        val repeat = ScoreIssue("omr:repeat:0:0:9", "TEST DATA 反复", ScoreIssueSeverity.ERROR)
        val original = base.copy(issues = base.issues + repeat)
        val sourceBytes = File(directory, "original.json").readBytes()
        val rawBytes = File(directory, "raw-result.json").readBytes()
        val scoreId = UUID.randomUUID().toString()
        val store = ScoreRevisionStore(revisionRoot)
        store.load(scoreId, original)
        val choice = ScoreStructureTools.confirmLinearRepeat(original)
        val saved = store.save(scoreId, 0, choice, emptySet(), emptyMap())
        val cold = ScoreRevisionStore(revisionRoot)
        assertEquals(choice, cold.load(scoreId, original).current)
        assertTrue(ScoreStructureTools.hasConfirmedLinearRepeat(choice))
        assertTrue(ScoreStructureTools.reviewIssues(choice).any { it.severity == ScoreIssueSeverity.ERROR && "拍号" in it.message })
        val restored = cold.undo(scoreId, original, saved.generation).current
        assertEquals(listOf(repeat), ScoreStructureTools.pendingRepeatChoices(restored))
        assertArrayEquals(sourceBytes, File(directory, "original.json").readBytes())
        assertArrayEquals(rawBytes, File(directory, "raw-result.json").readBytes())
    }

    @Test fun realReinferenceDoesNotReuseHumanAnnotationsForSameIdsWithDifferentNotes() {
        val (original, _) = archive()
        val scoreId = UUID.randomUUID().toString()
        val store = ScoreRevisionStore(revisionRoot)
        store.load(scoreId, original)
        val note = original.parts.single().measures.single().events.single()
        val confirmed = store.save(scoreId, 0, original, setOf(note.id), mapOf(note.id to 4))
        OmrStructureUpgrade.requireUnedited(original, confirmed.current)
        val newInference = original.copy(parts = original.parts.map { part -> part.copy(measures = part.measures.map { measure ->
            measure.copy(events = measure.events.map { it.copy(pitch = ScorePitch("D", 0, 4)) })
        }) })
        val preview = OmrStructurePreview(original.source.sha256, confirmed.generation, newInference,
            ScoreStructureTools.summarize(original), ScoreStructureTools.summarize(newInference), OmrPreviewMethod.REAL_IMAGE_RECOGNITION, "TEST DATA")
        val (verified, fingerings) = preview.annotationsForApply(confirmed)
        assertTrue(verified.isEmpty()); assertTrue(fingerings.isEmpty())
        val applied = store.save(scoreId, confirmed.generation, preview.candidate, verified, fingerings)
        val cold = ScoreRevisionStore(revisionRoot)
        val reopened = cold.load(scoreId, original)
        assertTrue(reopened.verifiedEventIds.isEmpty()); assertTrue(reopened.fingerings.isEmpty())
        val undone = cold.undo(scoreId, original, applied.generation)
        assertEquals(original, undone.current)
        assertEquals(setOf(note.id), undone.verifiedEventIds)
        assertEquals(mapOf(note.id to 4), undone.fingerings)
        val archivedPreview = preview.copy(method = OmrPreviewMethod.ARCHIVED_TOKENS)
        assertEquals(setOf(note.id) to mapOf(note.id to 4), archivedPreview.annotationsForApply(confirmed))
    }

    @Test fun reanalysisDecodeFailureKeepsFailedEvidenceAndDoesNotTouchOriginal() {
        val (original, directory) = archive()
        val sourceBytes = File(directory, "original.json").readBytes()
        try {
            assertThrows(IllegalArgumentException::class.java) { OmrReanalysisService(context).recognize(original, original, 0) }
            val evidence = reanalysisRecords(original).single()
            assertEquals("FAILED", JSONObject(File(evidence, "manifest.json").readText()).getString("status"))
            assertEquals("FAILED", JSONObject(File(evidence, "raw-result.json").readText()).getString("status"))
            assertFalse(File(evidence, "candidate.json").exists())
            assertArrayEquals(sourceBytes, File(directory, "original.json").readBytes())
        } finally { reanalysesOwned += reanalysisRecords(original) }
    }

    @Test fun reanalysisCancellationBeforeModelStartKeepsRunningThenCancelledEvidence() {
        val (original, directory) = archive(validImage = true)
        val sourceBytes = File(directory, "raw-result.json").readBytes()
        var checks = 0
        var sawRunning = false
        try {
            assertThrows(CancellationException::class.java) {
                OmrReanalysisService(context).recognize(original, original, 0,
                    progress = {
                        sawRunning = JSONObject(File(reanalysisRecords(original).single(), "manifest.json").readText()).getString("status") == "RUNNING"
                    }, checkCancellation = { if (++checks == 2) throw CancellationException("TEST DATA 用户取消") })
            }
            assertTrue(sawRunning)
            val evidence = reanalysisRecords(original).single()
            assertEquals("CANCELLED", JSONObject(File(evidence, "manifest.json").readText()).getString("status"))
            assertFalse(File(evidence, "candidate.json").exists())
            assertArrayEquals(sourceBytes, File(directory, "raw-result.json").readBytes())
        } finally { reanalysesOwned += reanalysisRecords(original) }
    }

    @Test fun reanalysisRecordLimitRejectsNewRunsWithoutDeletingExistingRecords() {
        val (original, _) = archive()
        val reanalysisRoot = File(context.filesDir, "omr-reanalyses")
        check(reanalysisRoot.exists() || reanalysisRoot.mkdir())
        val before = reanalysisRoot.listFiles().orEmpty().map { it.name }.toSet()
        repeat((20 - before.size).coerceAtLeast(0)) {
            val folder = File(reanalysisRoot, UUID.randomUUID().toString())
            check(folder.mkdir()); reanalysesOwned += folder
            File(folder, "manifest.json").writeText("TEST DATA record limit")
        }
        val names = reanalysisRoot.listFiles().orEmpty().map { it.name }.toSet()
        assertThrows(IllegalArgumentException::class.java) { OmrReanalysisService(context).recognize(original, original, 0) }
        assertEquals(names, reanalysisRoot.listFiles().orEmpty().map { it.name }.toSet())
    }

    private fun reanalysisRecords(original: DigitalScore) = File(context.filesDir, "omr-reanalyses").listFiles().orEmpty().filter { directory ->
        val manifest = File(directory, "manifest.json")
        manifest.isFile && runCatching { JSONObject(manifest.readText()).optString("sourceId") == original.source.sha256 }.getOrDefault(false)
    }

    private fun archive(validImage: Boolean = false): Pair<DigitalScore, File> {
        val document = "TEST DATA archived tokens ${UUID.randomUUID()}".toByteArray()
        val id = hash("${document.size}:${hash(document)}\n".toByteArray())
        val directory = File(context.filesDir, "omr-sources/$id")
        check(directory.parentFile!!.exists() || directory.parentFile!!.mkdirs())
        check(directory.mkdir()); owned += directory
        val tokens = JSONArray().put(token("clef_G2")).put(token("timeSignature/4")).put(token("note_1")).put(token("barline"))
        val staff = JSONObject().put("eos", true).put("tokens", tokens)
            .put("sourceBox", JSONArray(listOf(10, 20, 900, 140))).put("lines", JSONArray(listOf(50, 60, 70, 80, 90)))
        val pages = JSONArray().put(JSONObject().put("page", 0).put("staffs", JSONArray().put(staff)))
        val assembled = OmrScoreAssembler.assemble(id, document.size, pages)
        val missingMeter = assembled.copy(parts = assembled.parts.map { part -> part.copy(measures = part.measures.map { it.copy(time = null) }) })
        val original = missingMeter.copy(issues = ScoreEditing.validate(missingMeter))
        val encoded = ScoreJsonCodec.encode(original)
        File(directory, "original-000.document").writeBytes(document)
        val imageBytes = if (validImage) {
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
            finally { bitmap.recycle() }
        } else document
        File(directory, "page-000.image").writeBytes(imageBytes)
        File(directory, "original.json").writeBytes(encoded)
        fun entry(name: String) = JSONObject().put("name", name).put("sha256", hash(document)).put("bytes", document.size)
        val raw = JSONObject().put("schemaVersion", 1).put("sourceId", id).put("status", "INFERENCE_FINISHED")
            .put("byteCount", document.size).put("pages", pages).put("originalScoreSha256", hash(encoded))
            .put("documents", JSONArray().put(entry("original-000.document")))
            .put("images", JSONArray().put(JSONObject().put("name", "page-000.image").put("sha256", hash(imageBytes)).put("bytes", imageBytes.size)))
        File(directory, "raw-result.json").writeText(raw.toString())
        return original to directory
    }
    private fun token(rhythm: String) = JSONObject().put("rhythm", rhythm).put("pitch", "C4").put("lift", "_")
        .put("position", "upper").put("slur", "_").put("articulation", "_")
        .put("modelScores", JSONObject().put("rhythm", 1.0).put("pitch", 1.0).put("lift", 1.0))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
