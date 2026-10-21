package com.xiuyin.app.score

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.MainActivity
import com.xiuyin.app.ui.ScoreWorkbenchScreen
import com.xiuyin.app.omr.OmrImportService
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.ScoreFollower
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA archive/UI/real revision integration; never invokes or fabricates OMR inference. */
@RunWith(AndroidJUnit4::class)
class ScoreBarlineWorkbenchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val imports get() = ViewModelProvider(compose.activity)[ScoreImportViewModel::class.java]
    private val workbench get() = ViewModelProvider(compose.activity)[ScoreWorkbenchViewModel::class.java]
    private val ownedArchives = linkedMapOf<String, Map<String, ByteArray>>()
    private val ownedRevisions = mutableSetOf<String>()
    private val ownedMetadata = mutableSetOf<String>()

    @After fun cleanupOnlyThisRunsFixtures() {
        compose.runOnUiThread { workbench.stopPlayback("TEST DATA barline cleanup") }
        compose.waitUntil(15_000) { !workbench.state.value.busy && !imports.state.value.importing && !workbench.playback.value.playing }
        ownedArchives.forEach { (sourceId, files) -> assertArchive(sourceId, files) }
        ownedRevisions.forEach { ScoreRevisionStore(File(context.filesDir, "score-revisions")).delete(it) }
        ScoreMetadataDatabase(context).use { database ->
            ownedMetadata.forEach { id ->
                val metadata = requireNotNull(database.get(id, includeDeleted = true))
                check(metadata.sourceId in ownedArchives) { "Unexpected metadata ownership; keeping data" }
                database.markDeleted(id, deletePracticeRecords = true)
            }
            ownedArchives.forEach { (sourceId, _) -> OmrImportService(context).delete(sourceId) }
            ownedMetadata.forEach { database.confirmSourceDeleted(it) }
        }
    }

    @Test fun manualMergePreviewCancelApplyUndoRedoAndColdReopenPreserveSourceAndEdits() {
        val original = stageSyntheticArchive()
        val metadata = ScoreMetadataDatabase(context).use { it.ensureImported(original.id,
            ScoreImportMetadata(originalTitle = original.title, sourceType = "OMR_IMAGE", hasReviewIssues = true)) }
        ownedMetadata += metadata.scoreId; ownedRevisions += metadata.scoreId
        openThroughMain(metadata.scoreId)
        val editedNote = original.parts[0].measures.last().events.first().copy(pitch = ScorePitch("F", 1, 5))
        compose.runOnUiThread { workbench.edit(ScorePatch.ReplaceEvent(editedNote.id, editedNote), 4) }
        awaitGeneration(1)
        val before = requireNotNull(workbench.state.value.revision)
        val request = ScoreBarlineMergeRequest(metadata.scoreId, 1, "P1", "r3a", "r3b")
        clickMerge("r3a")
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.barlinePreview != null }
        compose.onNodeWithTag("barline_preview").assertExists()
        compose.onNodeWithTag("barline_before").assertExists()
        compose.onNodeWithTag("barline_after").assertExists()
        assertEquals(before, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(metadata.scoreId, original))
        assertArchive(original.id, requireNotNull(ownedArchives[original.id]))
        compose.onNodeWithTag("barline_cancel").performClick()
        compose.waitUntil(5_000) { workbench.state.value.barlinePreview == null }
        assertEquals(before, workbench.state.value.revision)
        clickMerge("r3a")
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.barlinePreview != null }
        val preview = requireNotNull(workbench.state.value.barlinePreview)
        assertEquals(request.revisionGeneration, preview.revisionGeneration)
        compose.onNodeWithTag("barline_apply").performClick()
        awaitGeneration(2)
        compose.onNodeWithTag("barline_preview").assertDoesNotExist()
        val saved = requireNotNull(workbench.state.value.revision)
        assertEquals(1, saved.current.parts.size)
        assertEquals(3, saved.current.parts.single().measures.size)
        assertEquals(ScorePitch("F", 1, 5), saved.current.parts.single().measures.flatMap { it.events }.first { it.id == editedNote.id }.pitch)
        assertEquals(before.fingerings, saved.fingerings)
        assertTrue("Structural editing cannot certify new notes", before.verifiedEventIds.containsAll(saved.verifiedEventIds))
        assertEquals(before.verifiedEventIds - preview.changedEventIds, saved.verifiedEventIds)
        assertEquals(saved, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(metadata.scoreId, original))
        assertArchive(original.id, requireNotNull(ownedArchives[original.id]))
        val timeline = requireNotNull(workbench.state.value.timeline)
        assertTrue(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        assertTrue(ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.RIGHT)).targets.flatMap { it.notes }.all { it.staff == 1 })
        assertTrue(ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.LEFT)).targets.flatMap { it.notes }.all { it.staff == 2 })
        scroll("score_play"); compose.onNodeWithTag("score_play").performClick()
        compose.waitUntil(15_000) { workbench.playback.value.playedSamples > 0 || workbench.playback.value.error != null }
        assertNull(workbench.playback.value.error)
        assertTrue(workbench.playback.value.playedSamples > 0)
        assertTrue(workbench.playback.value.writtenSamples >= workbench.playback.value.playedSamples)
        compose.runOnUiThread { workbench.stopPlayback("TEST DATA verified real AudioTrack samples") }
        compose.runOnUiThread { workbench.undo() }; awaitGeneration(3)
        assertEquals(before.current, workbench.state.value.revision!!.current)
        assertEquals(before.fingerings, workbench.state.value.revision!!.fingerings)
        compose.runOnUiThread { workbench.redo() }; awaitGeneration(4)
        assertEquals(saved.current, workbench.state.value.revision!!.current)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.openScore(metadata.scoreId) }
        compose.waitUntil(10_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == metadata.scoreId }
        assertEquals(saved.current, imports.state.value.selectedScore)
        assertEquals(original, imports.state.value.selectedOriginal)
        assertEquals(saved.fingerings, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(metadata.scoreId, original).fingerings)
        assertArchive(original.id, requireNotNull(ownedArchives[original.id]))
        Log.i("XiuyinBarlineTest", "TEST_DATA_UI_MERGE_PASS generation=4 realAudioTrack=true bothHands=true")
    }

    @Test fun staleOrCrossSystemMergeCannotWriteAnyRevision() {
        val original = stageSyntheticArchive()
        val id = UUID.randomUUID().toString(); ownedRevisions += id
        compose.runOnUiThread { workbench.open(metadata(id, original), original) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.scoreId == id }
        assertNull(workbench.state.value.error)
        val before = requireNotNull(workbench.state.value.revision)
        for (request in listOf(ScoreBarlineMergeRequest(id, 1, "P1", "r3a", "r3b"),
            ScoreBarlineMergeRequest(id, 0, "P1", "r2", "r3a"))) {
            compose.runOnUiThread { workbench.previewBarlineMerge(request) }
            compose.waitUntil(10_000) { !workbench.state.value.busy }
            assertNotNull(workbench.state.value.error)
            assertNull(workbench.state.value.barlinePreview)
            assertEquals(before, workbench.state.value.revision)
            assertEquals(before, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original))
            assertArchive(original.id, requireNotNull(ownedArchives[original.id]))
        }
    }

    /** Optional external fixture. Run explicitly with all SHA-bound args; assert zero skipped runs. */
    @Test fun shaBoundUserArchiveMergePersistsOnAnIsolatedRevisionWithoutChangingTheArchive() {
        val args = InstrumentationRegistry.getArguments()
        val sourceId = args.getString("barlineSourceId")
        assumeTrue("External user archive is supplied only for its explicit directed run", sourceId != null)
        val actualSourceId = requireNotNull(sourceId)
        require(actualSourceId.matches(Regex("[a-f0-9]{64}")))
        fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing SHA-bound input: $name" }
        val loaded = OmrImportService(context).load(actualSourceId)
        assertEquals(arg("barlineScoreSha256"), hash(loaded.originalScoreFile.readBytes()))
        assertEquals(arg("barlineDocumentSha256"), hash(File(loaded.sourceDirectory, "original-000.document").readBytes()))
        val files = loaded.sourceDirectory.listFiles()!!.associate { it.name to it.readBytes() }
        compose.waitUntil(15_000) { !imports.state.value.importing }
        val userMetadata = ScoreMetadataDatabase(context).use { it.findBySourceId(actualSourceId, includeDeleted = true) }
        val userRevisionFile = userMetadata?.let { File(context.filesDir, "score-revisions/${it.scoreId}.json") }
        val userRevisionBytes = userRevisionFile?.takeIf { it.isFile }?.readBytes()
        val evidenceDirectory = File(requireNotNull(context.getExternalFilesDir(null)), "barline-component-${UUID.randomUUID()}")
        check(evidenceDirectory.mkdir())
        Log.i("XiuyinBarlineTest", "USER_BARLINE_COMPONENT_UI_BEGIN ${evidenceDirectory.absolutePath} sourceId=$actualSourceId scoreSha=${arg("barlineScoreSha256")}")
        val screenshots = JSONArray()
        val original = loaded.score
        val expectedMeasures = arg("barlineExpectedMeasures").toInt().also { require(it > 0) }
        assertEquals("SHA-bound input must contain the two unmatched source staves", 2, original.parts.size)
        assertEquals(expectedMeasures + 1, original.parts[0].measures.size)
        assertEquals(expectedMeasures, original.parts[1].measures.size)
        val originalErrors = original.issues.filter { it.severity == ScoreIssueSeverity.ERROR }
        assertEquals("Frozen user fixture should retain its original two structural errors", 2, originalErrors.size)
        val unrelatedErrors = originalErrors.filterNot { it.id == "omr:alignment" || it.id.startsWith("omr:system-alignment:") }
        val id = UUID.randomUUID().toString(); ownedRevisions += id
        compose.runOnUiThread { workbench.open(metadata(id, original), original) }
        compose.waitUntil(15_000) { !workbench.state.value.busy && workbench.state.value.scoreId == id }
        assertNull(workbench.state.value.error)
        val request = ScoreBarlineMergeRequest(id, 0, arg("barlinePartId"), arg("barlineLeftMeasureId"), arg("barlineRightMeasureId"))
        bindProductionWorkbenchComponent()
        clickMerge(request.leftMeasureId)
        compose.waitUntil(15_000) { !workbench.state.value.busy && workbench.state.value.barlinePreview != null }
        assertNull(workbench.state.value.error)
        compose.onNodeWithTag("barline_preview").assertExists()
        compose.onNodeWithTag("barline_before").assertExists()
        compose.onNodeWithTag("barline_after").assertExists()
        val sourceExpand = compose.onNode(hasTestTag("source_expand") and
            hasAnyAncestor(hasTestTag("barline_preview")), useUnmergedTree = true)
        compose.waitUntil(15_000) { runCatching { sourceExpand.assertIsEnabled() }.isSuccess }
        sourceExpand.performScrollTo().assertIsDisplayed()
        screenshots.put(captureComponentScreenshot(evidenceDirectory, "01-real-preview.png"))
        sourceExpand.performClick()
        compose.onNodeWithTag("source_zoom").assertIsDisplayed()
        compose.onNodeWithContentDescription("可缩放原始谱图").assertIsDisplayed()
        screenshots.put(captureComponentScreenshot(evidenceDirectory, "02-source-expanded.png"))
        compose.onNodeWithText("返回修谱").performClick()
        compose.onNodeWithTag("barline_cancel").performClick()
        compose.waitUntil(5_000) { workbench.state.value.barlinePreview == null }
        assertEquals(0L, workbench.state.value.revision!!.generation)
        assertEquals(original, workbench.state.value.revision!!.current)
        assertArchive(actualSourceId, files)
        clickMerge(request.leftMeasureId)
        compose.waitUntil(15_000) { !workbench.state.value.busy && workbench.state.value.barlinePreview != null }
        val preview = requireNotNull(workbench.state.value.barlinePreview)
        assertEquals(request, ScoreBarlineMergeRequest(preview.scoreId, preview.revisionGeneration,
            request.partId, preview.leftMeasure.id, preview.rightMeasure.id))
        assertEquals(BeatFraction.of(1, 2), preview.joinBeat)
        assertEquals(0L, workbench.state.value.revision!!.generation)
        compose.onNodeWithTag("barline_apply").performClick(); awaitGeneration(1)
        compose.onNodeWithTag("barline_preview").assertDoesNotExist()
        screenshots.put(captureComponentScreenshot(evidenceDirectory, "03-after-apply.png"))
        val saved = requireNotNull(workbench.state.value.revision)
        assertTrue(saved.verifiedEventIds.isEmpty())
        assertEquals(original.source, saved.current.source)
        assertEquals(1, saved.current.parts.size)
        assertEquals(expectedMeasures, saved.current.parts.single().measures.size)
        assertTrue(saved.current.parts.single().measures.all { it.declaredStaves == 2 })
        assertTrue(saved.current.parts.single().measures.all { measure ->
            measure.clefs.map { it.staff }.toSet() == setOf(1, 2)
        })
        val remainingErrors = saved.current.issues.filter { it.severity == ScoreIssueSeverity.ERROR }
        unrelatedErrors.forEach { error ->
            assertTrue("Unrelated source ERROR must be retained: $error", remainingErrors.contains(error))
        }
        assertTrue("Expected 2 alignment ERRORs -> 0; unrelated source ERRORs must remain reported: $unrelatedErrors; actual=$remainingErrors",
            remainingErrors.isEmpty())
        val sourceEvents = original.parts.flatMap { it.measures }.flatMap { it.events }.associateBy { it.id }
        val savedEvents = saved.current.parts.single().measures.flatMap { it.events }.associateBy { it.id }
        assertEquals(sourceEvents.keys, savedEvents.keys)
        val shiftedIds = original.parts.single { it.id == request.partId }.measures
            .single { it.id == request.rightMeasureId }.events.map { it.id }.toSet()
        original.parts.forEachIndexed { lane, part ->
            part.measures.flatMap { it.events }.forEach { sourceEvent ->
                val actual = savedEvents.getValue(sourceEvent.id)
                val expectedOffset = if (sourceEvent.id in shiftedIds) sourceEvent.offset + preview.joinBeat else sourceEvent.offset
                // Compare the complete payload: sourceIndex/sourceLine/SourceBox, pitch, duration,
                // identity and all other annotations survive. Only the selected right fragment
                // changes offset; explicit staff pairing changes staff to lane 1 or 2.
                assertEquals("Immutable musical/source payload for ${sourceEvent.id}",
                    sourceEvent.copy(offset = expectedOffset, staff = lane + 1), actual)
            }
        }
        val timeline = ScoreTimelineCompiler.compile(saved.current)
        assertEquals(expectedMeasures, timeline.measures.size)
        assertTrue("Compiled repaired timeline still has ERRORs: ${timeline.issues}",
            timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        val right = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.RIGHT)).targets.flatMap { it.notes }
        val left = ScoreFollower(timeline, PracticeConfig(hand = PracticeHand.LEFT)).targets.flatMap { it.notes }
        assertTrue("RIGHT must have real targets", right.isNotEmpty())
        assertTrue("LEFT must have real targets", left.isNotEmpty())
        assertTrue(right.all { it.staff == 1 })
        assertTrue(left.all { it.staff == 2 })
        compose.runOnUiThread { workbench.undo() }; awaitGeneration(2)
        assertEquals(original, workbench.state.value.revision!!.current)
        compose.runOnUiThread { workbench.redo() }; awaitGeneration(3)
        compose.runOnUiThread { workbench.open(metadata(id, original), original) }
        compose.waitUntil(15_000) { !workbench.state.value.busy }
        assertEquals(saved.current, workbench.state.value.revision!!.current)
        assertEquals(saved.current, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original).current)
        assertArchive(actualSourceId, files)
        assertEquals(userMetadata, ScoreMetadataDatabase(context).use { it.findBySourceId(actualSourceId, includeDeleted = true) })
        if (userRevisionBytes != null) assertArrayEquals(userRevisionBytes, requireNotNull(userRevisionFile).readBytes())
        else assertFalse("No revision may be created for the real user's metadata ID", userRevisionFile?.exists() == true)
        val evidence = JSONObject().put("status", "PASSED")
            .put("scope", "Production ScoreWorkbenchScreen component bound to real Workbench VM flows; not Main navigation or new OMR accuracy acceptance")
            .put("sourceId", actualSourceId).put("scoreSha256", arg("barlineScoreSha256"))
            .put("documentSha256", arg("barlineDocumentSha256")).put("isolatedRevisionScoreId", id)
            .put("generation", 3).put("measureCount", expectedMeasures).put("originalSourceErrors", 2).put("remainingErrors", 0)
            .put("previewCancelledBeforeApply", true).put("sourceImageExpandedAndDisplayed", true)
            .put("sourcePayloadPreserved", true).put("userMetadataAndRevisionUnchanged", true).put("screenshots", screenshots)
        val evidenceFile = File(evidenceDirectory, "evidence.json")
        evidenceFile.writeText(evidence.toString(2))
        Log.i("XiuyinBarlineTest", "USER_BARLINE_COMPONENT_UI_EVIDENCE ${evidenceFile.absolutePath} SHA=${hash(evidenceFile.readBytes())}")
        Log.i("XiuyinBarlineTest", "SHA_BOUND_USER_ARCHIVE_PASS sourceId=$actualSourceId scoreSha=${arg("barlineScoreSha256")} generation=3 measures=$expectedMeasures errors=2->0 bothHands=true sourcePayloadPreserved=true componentUi=true mainNavigation=false")
    }

    /** The first test covers real Main navigation; this binds only the production component. */
    private fun bindProductionWorkbenchComponent() {
        val model = workbench
        compose.runOnUiThread {
            compose.activity.setContent {
                val state = model.state.collectAsState().value
                val playback = model.playback.collectAsState().value
                MaterialTheme {
                    ScoreWorkbenchScreen(state = state, playback = playback,
                        onBack = {}, onPractice = {},
                        onSelect = model::select, onEdit = model::edit,
                        onTranspose = model::transpose, onUndo = model::undo, onRedo = model::redo,
                        onVerify = model::verifySelected, onRestoreEvent = model::restoreSelected,
                        onRestoreMeasure = model::restoreMeasure,
                        onPlay = model::startPlayback, onStop = { model.stopPlayback() },
                        onPreviewOmrStructure = model::previewOmrStructure,
                        onApplyOmrStructure = model::applyOmrStructure,
                        onDismissStructurePreview = model::dismissStructurePreview,
                        onSetWholeScoreTime = model::setWholeScoreTime,
                        onConfirmLinearRepeat = model::confirmLinearRepeat,
                        onRecognizeOmrAgain = model::recognizeOmrAgain,
                        onFillMissingScoreTime = model::fillMissingScoreTime,
                        onShiftOctaveRange = model::shiftOctaveRange,
                        onPreviewBarlineMerge = model::previewBarlineMerge,
                        onApplyBarlineMerge = model::applyBarlineMerge,
                        onDismissBarlinePreview = model::dismissBarlinePreview)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workbench").assertExists()
    }

    private fun captureComponentScreenshot(directory: File, name: String): JSONObject {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val target = File(directory, name)
        check(!target.exists())
        try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        check(target.length() > 100)
        return JSONObject().put("path", target.absolutePath).put("bytes", target.length()).put("sha256", hash(target.readBytes()))
    }
    private fun openThroughMain(id: String) {
        compose.waitUntil(15_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.reload() }
        compose.waitUntil(15_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.openScore(id) }
        compose.waitUntil(15_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == id }
        assertNull(imports.state.value.error)
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
        compose.onNodeWithTag("score_content").performScrollToNode(hasTestTag("open_score_workbench"))
        compose.onNodeWithTag("open_score_workbench").performClick()
        compose.waitUntil(15_000) { !workbench.state.value.busy && workbench.state.value.scoreId == id }
        assertNull(workbench.state.value.error)
    }
    private fun awaitGeneration(expected: Long) {
        compose.waitUntil(15_000) { !workbench.state.value.busy }
        assertNull(workbench.state.value.error)
        assertEquals(expected, workbench.state.value.revision!!.generation)
    }
    private fun scroll(tag: String) { compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag(tag)) }
    private fun clickMerge(id: String) {
        val tag = "score_merge_next_$id"
        scroll(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun metadata(id: String, score: DigitalScore) = ScoreMetadata(id, score.id, "TEST DATA isolated barline revision", null,
        null, emptyList(), emptyList(), null, null, null, 1, null, false, 1,
        emptyList(), emptyList(), "OMR_IMAGE", false, true, null)
    private fun assertArchive(id: String, files: Map<String, ByteArray>) {
        val directory = File(context.filesDir, "omr-sources/$id")
        assertEquals(files.keys, directory.listFiles()!!.map { it.name }.toSet())
        files.forEach { (name, bytes) -> assertArrayEquals("Immutable original archive: $name", bytes, File(directory, name).readBytes()) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun stageSyntheticArchive(): DigitalScore {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val document = try {
            bitmap.eraseColor(Color.WHITE)
            bitmap.setPixel(0, 0, UUID.randomUUID().hashCode() or (255 shl 24))
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val sourceId = hash("${document.size}:${hash(document)}\n".toByteArray())
        val directory = File(context.filesDir, "omr-sources/$sourceId")
        check(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(directory.parentFile!!.exists() || directory.parentFile!!.mkdirs())
        check(directory.mkdir())
        fun event(line: Int, token: Int, duration: BeatFraction = BeatFraction.of(3), offset: BeatFraction = BeatFraction.ZERO) =
            ScoreEvent("$sourceId:p0:s$line:t$token", offset, duration, ScorePitch("B", -1, if (line % 2 == 0) 4 else 3),
                1, "1", false, if (duration == BeatFraction.of(3)) "half" else "eighth", if (duration == BeatFraction.of(3)) 1 else 0,
                emptySet(), token, line, .83, SourceBox(0, 1.0, 1.0 + line * 3, 28.0, 2.0))
        fun measure(id: String, line: Int, events: List<ScoreEvent> = listOf(event(line, 2))) = ScoreMeasure(id, id,
            ScoreTime(3, 4), -1, listOf(ScoreClef(1, if (line % 2 == 0) "G" else "F", if (line % 2 == 0) 2 else 4)), events, false)
        val score = DigitalScore(sourceId, "TEST DATA barline ${UUID.randomUUID()}", listOf(
            ScorePart("P1", "TEST DATA upper", listOf(measure("r1", 0), measure("r2", 2),
                measure("r3a", 4, listOf(event(4, 2, BeatFraction.of(1, 2)))),
                measure("r3b", 4, (0..4).map { event(4, 4 + it, BeatFraction.of(1, 2), BeatFraction.of(it.toLong(), 2)) }))),
            ScorePart("P2", "TEST DATA lower", listOf(measure("l1", 1), measure("l2", 3), measure("l3", 5)))),
            listOf(ScoreIssue("omr:review", "TEST DATA not inferred, notes still need review"),
                ScoreIssue("omr:layout", "TEST DATA staff pairing review"),
                ScoreIssue("omr:system-alignment:0:2", "TEST DATA third system 2/1", ScoreIssueSeverity.ERROR),
                ScoreIssue("omr:alignment", "TEST DATA unaligned bar counts", ScoreIssueSeverity.ERROR)),
            ScoreSource("OMR_IMAGE", sourceId, document.size))
        val encoded = ScoreJsonCodec.encode(score)
        val entry = JSONObject().put("name", "original-000.document").put("bytes", document.size).put("sha256", hash(document))
        val image = JSONObject().put("name", "page-000.image").put("bytes", document.size).put("sha256", hash(document))
        val raw = JSONObject().put("schemaVersion", 1).put("sourceId", sourceId).put("status", "INFERENCE_FINISHED")
            .put("testData", true).put("method", "TEST DATA protocol fixture; no model inference")
            .put("pages", JSONArray()).put("byteCount", document.size).put("originalScoreSha256", hash(encoded))
            .put("documents", JSONArray().put(entry)).put("images", JSONArray().put(image)).toString().toByteArray()
        val files = linkedMapOf("original-000.document" to document, "page-000.image" to document.copyOf(), "original.json" to encoded, "raw-result.json" to raw)
        ownedArchives[sourceId] = files
        files.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        assertEquals(score, OmrImportService(context).load(sourceId).score)
        return score
    }
}