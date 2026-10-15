package com.xiuyin.app.score

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.MainActivity
import com.xiuyin.app.practice.PracticeViewModel
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.PracticeReportStore
import android.util.Log
import java.nio.file.Files
import java.nio.file.LinkOption
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.After
import org.junit.runners.model.MultipleFailureException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Original TEST DATA through the real app, revision files and editor; no recognition output is fabricated. */
@RunWith(AndroidJUnit4::class)
class ScoreWorkbenchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val imports get() = ViewModelProvider(compose.activity)[ScoreImportViewModel::class.java]
    private val workbench get() = ViewModelProvider(compose.activity)[ScoreWorkbenchViewModel::class.java]
    private data class OwnedDocument(val bytes: ByteArray, val document: ParsedScoreDocument, var scoreId: String? = null)
    private val ownedDocuments = linkedMapOf<String, OwnedDocument>()

    @After fun removeOnlyDocumentsCreatedByThisTest() = cleanupOwnedDocuments()

    private fun cleanupOwnedDocuments() {
        if (ownedDocuments.isEmpty()) return
        // The Activity is still alive during @After. Quiesce all writers before deleting only our IDs.
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        compose.waitUntil(15_000) { !imports.state.value.importing && !workbench.state.value.busy && !practice.state.value.busy }
        compose.runOnUiThread {
            workbench.stopPlayback("TEST DATA teardown")
            if (practice.state.value.scoreId in ownedDocuments.values.mapNotNull { it.scoreId }) practice.finish()
        }
        compose.waitUntil(15_000) { !imports.state.value.importing && !workbench.state.value.busy && !practice.state.value.busy && !workbench.playback.value.playing }
        val sources = ScoreSourceStore(File(context.filesDir, "digital-scores"))
        val revisions = ScoreRevisionStore(File(context.filesDir, "score-revisions"))
        val reports = PracticeReportStore(File(context.filesDir, "practice-reports"))
        val failures = mutableListOf<Throwable>()
        ownedDocuments.toMap().forEach { (sourceId, owned) ->
            try {
                val sourceFile = File(context.filesDir, "digital-scores/$sourceId.musicxml")
                if (Files.exists(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    // Refuse deletion if this run's exact bytes were replaced, damaged or redirected.
                    check(sources.load(sourceId).bytes.contentEquals(owned.bytes)) { "本次测试原件已变化，保留文件：$sourceId" }
                }
                ScoreMetadataDatabase(context).use { database ->
                    val metadata = database.findBySourceId(sourceId, includeDeleted = true)
                    if (metadata != null) {
                        check(metadata.sourceId == sourceId && metadata.sourceType == "MusicXML" &&
                            metadata.originalTitle == owned.document.originalTitle &&
                            (owned.scoreId == null || metadata.scoreId == owned.scoreId)) { "测试元数据归属已变化，保留：$sourceId" }
                        reports.deleteForScore(metadata.scoreId)
                        database.markDeleted(metadata.scoreId, deletePracticeRecords = true)
                        sources.delete(sourceId)
                        revisions.delete(metadata.scoreId)
                        database.confirmSourceDeleted(metadata.scoreId)
                        check(database.get(metadata.scoreId) == null) { "本次测试元数据仍可见" }
                        check(!File(context.filesDir, "score-revisions/${metadata.scoreId}.json").exists()) { "本次测试修订未清理" }
                    } else {
                        // Covers failures after save(bytes) but before ensureImported returns.
                        sources.delete(sourceId)
                        owned.scoreId?.let { id -> reports.deleteForScore(id); revisions.delete(id) }
                    }
                }
                check(!Files.exists(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "本次测试原件未清理：$sourceId" }
                ownedDocuments.remove(sourceId)
                Log.i("XiuyinWorkbenchTest", "OWNED_TEST_DOCUMENT_CLEANED sourceId=$sourceId")
            } catch (error: Throwable) {
                failures += error
                Log.e("XiuyinWorkbenchTest", "OWNED_TEST_DOCUMENT_CLEANUP_FAILED sourceId=$sourceId", error)
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test fun editingUpdatesNotationPersistsAndUndoRedoSurviveReopen() {
        val (id, original) = openTestScore()
        val first = original.parts.first().measures.first().events.first()
        scroll("edit_selected_note")
        compose.onNodeWithTag("edit_selected_note").performClick()
        compose.onNodeWithTag("edit_pitch_octave").performTextReplacement("5")
        compose.onNodeWithTag("edit_note_save").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        val changed = requireNotNull(workbench.state.value.revision)
        assertEquals(72, changed.current.parts.first().measures.first().events.first().pitch!!.midi)
        assertTrue(first.id in changed.verifiedEventIds)
        assertEquals(first.id, changed.current.parts.first().measures.first().events.first().id)
        scroll("score_undo"); compose.onNodeWithTag("score_undo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        assertEquals(original, workbench.state.value.revision!!.current)
        compose.onNodeWithTag("score_redo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 3L }
        assertEquals(changed.current, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original).current)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.openScore(id) }
        compose.waitUntil(10_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == id }
        assertEquals(changed.current, imports.state.value.selectedScore)
        assertEquals(original, imports.state.value.selectedOriginal)
    }

    @Test fun octaveRangeIsOnePersistentUndoableRevisionAndRejectsStaleRequests() {
        val (id, original) = openTestScore()
        val lane = ScoreRangeEditing.endpoints(original).filter { it.event.staff == 1 && it.event.voice == "1" && !it.event.isRest }
        compose.runOnUiThread { workbench.verifySelected() }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        val before = workbench.state.value.revision!!
        val request = ScoreOctaveRangeRequest(id, 1, lane.first().eventId, lane.last().eventId, 1)
        scroll("score_octave_range"); compose.onNodeWithTag("score_octave_range").performClick()
        compose.onNodeWithTag("octave_range_end").performScrollTo().performClick()
        compose.onNodeWithTag("octave_range_end_option_${lane.size} · ${lane.last().label}").performClick()
        compose.onNodeWithTag("octave_range_up").performScrollTo().performClick()
        compose.onNodeWithTag("octave_range_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        assertNull(workbench.state.value.error)
        val changed = workbench.state.value.revision!!
        val expected = ScoreRangeEditing.shiftOctave(original, request.startEventId, request.endEventId, 1)
        assertEquals(expected.score, changed.current)
        assertTrue(changed.verifiedEventIds.intersect(expected.changedEventIds).isEmpty())
        assertEquals(before.fingerings, changed.fingerings)
        assertEquals(changed.current, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original).current)
        compose.runOnUiThread { workbench.shiftOctaveRange(request) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.error != null }
        assertEquals(2L, workbench.state.value.revision!!.generation)
        assertEquals(changed.current, workbench.state.value.revision!!.current)
        assertTrue(workbench.state.value.error!!.contains("修订已变化"))
        compose.runOnUiThread { workbench.undo() }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 3L }
        assertEquals(original, workbench.state.value.revision!!.current)
        assertEquals(before.verifiedEventIds, workbench.state.value.revision!!.verifiedEventIds)
        compose.runOnUiThread { workbench.redo() }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 4L }
        assertEquals(changed.current, workbench.state.value.revision!!.current)
        assertEquals(original, imports.state.value.selectedOriginal)
    }

    @Test fun transposeChangesSoundingPitchesAndBothScoreDisplays() {
        val (_, original) = openTestScore()
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_semitones").performTextReplacement("2")
        compose.onNodeWithTag("transpose_target_key").performTextReplacement("2")
        compose.onNodeWithTag("transpose_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        val measure = workbench.state.value.revision!!.current.parts.first().measures.first()
        assertEquals(2, measure.fifths)
        assertEquals(62, measure.events.first().pitch!!.midi)
        val originalMidi = ScoreTimelineCompiler.compile(original).notes.map { it.midi }.sorted()
        assertEquals(originalMidi.map { it + 2 }, workbench.state.value.timeline!!.notes.map { it.midi }.sorted())
        scroll("display_NUMBERED"); compose.onNodeWithTag("display_NUMBERED").performClick()
        scroll("notation_${measure.id}")
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("简谱", substring = true)
        scroll("display_BOTH"); compose.onNodeWithTag("display_BOTH").performClick()
        scroll("notation_${measure.id}")
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("双谱", substring = true)
    }

    @Test fun realPlaybackAdvancesAudioHeadAndLeavingStopsIt() {
        openTestScore()
        scroll("score_play"); compose.onNodeWithTag("score_play").performClick()
        compose.waitUntil(15_000) { workbench.playback.value.playedSamples > 0 || workbench.playback.value.error != null }
        assertNull(workbench.playback.value.error)
        assertTrue(workbench.playback.value.writtenSamples >= workbench.playback.value.playedSamples)
        assertTrue(workbench.playback.value.playedSamples > 0)
        compose.onNodeWithTag("nav_home").performClick()
        compose.waitUntil(5_000) { !workbench.playback.value.playing }
        assertFalse(workbench.playback.value.playing)
    }

    @Test fun pausedPracticeRequiresExplicitFinishBeforeOpeningDifferentScore() {
        val (firstId, _) = openTestScore()
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.timeline != null }
        compose.runOnUiThread { practice.start(PracticeConfig(reviewAcknowledged = true)) }
        assertEquals(CompanionPhase.RUNNING, practice.state.value.phase)
        compose.onNodeWithTag("nav_home").performClick()
        compose.waitUntil(5_000) { practice.state.value.phase == CompanionPhase.PAUSED }
        compose.onNodeWithTag("nav_practice").performClick()
        assertEquals(firstId, practice.state.value.scoreId)
        compose.onNodeWithTag("nav_home").performClick()
        val (secondId, _) = openTestScore()
        assertNotEquals(firstId, secondId)
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        compose.onNodeWithText("切换练习乐谱").assertIsDisplayed()
        assertEquals(firstId, practice.state.value.scoreId)
        compose.onNodeWithTag("practice_switch_finish").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.scoreId == secondId }
        assertEquals(CompanionPhase.READY, practice.state.value.phase)
        assertNull(practice.state.value.error)
        assertTrue(practice.state.value.reports.isEmpty())
    }

    @Test fun repairNavigationLoadsThePracticeScoreEvenWhenAnotherWorkbenchWasOpened() {
        val (firstId, original) = openTestScore()
        val changed = missingMeter(original)
        val metadata = ScoreMetadataDatabase(context).use { requireNotNull(it.get(firstId)) }
        val revisions = ScoreRevisionStore(File(context.filesDir, "score-revisions"))
        revisions.load(firstId, original)
        revisions.save(firstId, 0, changed, emptySet(), emptyMap())
        compose.runOnUiThread { workbench.open(metadata, original) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.error != null }
        assertEquals(firstId, practice.state.value.scoreId)
        compose.onNodeWithTag("nav_home").performClick()
        val (secondId, _) = openTestScore()
        assertEquals(secondId, workbench.state.value.scoreId)
        compose.onNodeWithTag("nav_practice").performClick()
        compose.onNodeWithTag("companion_repair").performScrollTo().performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.scoreId == firstId && workbench.state.value.revision != null }
        assertNull(workbench.state.value.error)
        assertEquals(original, workbench.state.value.original)
        assertEquals(changed, workbench.state.value.revision!!.current)
        assertEquals(changed.parts.first().measures.first().events.first().id, workbench.state.value.selectedEventId)
        assertNotEquals(secondId, workbench.state.value.scoreId)
    }

    @Test fun repairReadFailureIsVisibleAndCannotLeaveAnotherScoreEditable() {
        val (otherId, otherOriginal) = openTestScore()
        // This intentionally lacks a source file, but its metadata is still owned by this test.
        val document = stageOwnedDocument("缺失原件 TEST DATA ", saveSource = false)
        val metadata = persistOwnedMetadata(document)
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        compose.runOnUiThread { practice.open(metadata.scoreId, metadata.displayTitle, missingMeter(document.score), 0, "TEST DATA navigation") }
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.error != null }
        compose.onNodeWithTag("nav_practice").performClick()
        compose.onNodeWithTag("companion_repair").performScrollTo().performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.error != null }
        assertEquals(metadata.scoreId, workbench.state.value.scoreId)
        assertNull(workbench.state.value.original)
        assertNull(workbench.state.value.revision)
        compose.onNodeWithTag("workbench_error").assertIsDisplayed()
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").assertIsNotEnabled()
        assertEquals(otherOriginal, MusicXmlParser().parse(ScoreSourceStore(File(context.filesDir, "digital-scores")).load(otherOriginal.id).bytes))
        assertNotEquals(otherId, workbench.state.value.scoreId)
    }

    @Test fun ownedCleanupCoversTwoScoresAndFailureBetweenSourceAndMetadata() {
        compose.waitUntil(10_000) { !imports.state.value.importing }
        val store = ScoreSourceStore(File(context.filesDir, "digital-scores"))
        val before = store.listIds().toSet()
        val first = stageOwnedDocument("工作台 TEST DATA ")
        val firstMetadata = persistOwnedMetadata(first)
        val second = stageOwnedDocument("工作台 TEST DATA ")
        val secondMetadata = persistOwnedMetadata(second)
        val revisions = ScoreRevisionStore(File(context.filesDir, "score-revisions"))
        revisions.load(firstMetadata.scoreId, first.score)
        revisions.save(firstMetadata.scoreId, 0, first.score, setOf(first.score.parts.first().measures.first().events.first().id), emptyMap())
        assertThrows(IllegalStateException::class.java) {
            stageOwnedDocument("中途失败 TEST DATA ")
            error("TEST DATA: deliberately stop after source save, before metadata creation")
        }
        val created = ownedDocuments.keys.toSet()
        assertEquals(3, created.size)
        assertTrue(store.listIds().containsAll(created))
        cleanupOwnedDocuments()
        assertEquals("只能删除本次三份原件，其他来源 ID 必须原样保留", before, store.listIds().toSet())
        ScoreMetadataDatabase(context).use { database ->
            for (id in listOf(firstMetadata.scoreId, secondMetadata.scoreId)) {
                assertNull(database.get(id))
                assertTrue(requireNotNull(database.get(id, includeDeleted = true)).sourceFileDeleted)
            }
        }
        assertFalse("清理后不得残留或重新创建本次修订", Files.exists(File(context.filesDir, "score-revisions/${firstMetadata.scoreId}.json").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(ownedDocuments.isEmpty())
    }

    @Test fun bottomPracticeReturnReloadsSavedMeterAndUndoWithoutStartingAudio() {
        val (id, original) = openTestScore()
        val first = original.parts.first().measures.first()
        val expectedTime = requireNotNull(first.time)
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        compose.runOnUiThread { workbench.edit(ScorePatch.UpdateMeasure(first.id, null, first.fifths, first.clefs, first.declaredStaves), null) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.error != null }
        assertEquals(1L, practice.state.value.revisionGeneration)
        fun returnToWorkbench() {
            compose.onNodeWithTag("nav_library").performClick()
            compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
            compose.onNodeWithTag("score_content").performScrollToNode(hasTestTag("open_score_workbench"))
            compose.onNodeWithTag("open_score_workbench").performClick()
            compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.scoreId == id }
        }
        returnToWorkbench()
        scroll("score_set_whole_time"); compose.onNodeWithTag("score_set_whole_time").performClick()
        compose.onNodeWithTag("whole_time_beats").performTextReplacement(expectedTime.beats.toString())
        compose.onNodeWithTag("whole_time_beat_type").performTextReplacement(expectedTime.beatType.toString())
        compose.onNodeWithTag("whole_time_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        val saved = requireNotNull(workbench.state.value.revision)
        compose.onNodeWithTag("nav_practice").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.revisionGeneration == saved.generation }
        assertNull(practice.state.value.error)
        assertEquals(saved.current, practice.state.value.score)
        assertNotNull(practice.state.value.timeline)
        assertFalse(practice.hasActiveSession())
        assertEquals(original, MusicXmlParser().parse(ScoreSourceStore(File(context.filesDir, "digital-scores")).load(original.id).bytes))
        returnToWorkbench()
        scroll("score_undo"); compose.onNodeWithTag("score_undo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 3L }
        compose.onNodeWithTag("nav_practice").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.revisionGeneration == 3L }
        assertNull(practice.state.value.timeline)
        assertNotNull(practice.state.value.error)
        assertEquals(id, practice.state.value.scoreId)
        assertFalse(practice.hasActiveSession())
    }

    @Test fun bottomPracticeReturnPreservesPausedSessionAfterWorkbenchEdit() {
        val (id, _) = openTestScore()
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.timeline != null }
        val frozen = practice.state.value
        compose.runOnUiThread { practice.start(PracticeConfig(reviewAcknowledged = true)) }
        compose.onNodeWithTag("nav_home").performClick()
        compose.waitUntil(5_000) { practice.state.value.phase == CompanionPhase.PAUSED }
        compose.runOnUiThread { workbench.setWholeScoreTime(3, 4) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        compose.onNodeWithTag("nav_practice").performClick()
        compose.waitForIdle()
        assertEquals(id, practice.state.value.scoreId)
        assertEquals(frozen.revisionGeneration, practice.state.value.revisionGeneration)
        assertEquals(frozen.score, practice.state.value.score)
        assertEquals(CompanionPhase.PAUSED, practice.state.value.phase)
        assertTrue(practice.hasActiveSession())
    }

    private fun missingMeter(original: DigitalScore): DigitalScore {
        val changed = original.copy(parts = original.parts.map { part ->
            part.copy(measures = part.measures.map { it.copy(time = null) })
        })
        return changed.copy(issues = ScoreEditing.validate(changed))
    }

    private fun openTestScore(): Pair<String, DigitalScore> {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("浏览本地曲库").fetchSemanticsNodes().isNotEmpty() && !imports.state.value.importing }
        val document = stageOwnedDocument("工作台 TEST DATA ")
        val metadata = persistOwnedMetadata(document)
        compose.runOnUiThread { imports.reload(); imports.openScore(metadata.scoreId) }
        compose.waitUntil(10_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == metadata.scoreId }
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
        compose.onNodeWithTag("score_content").performScrollToNode(hasTestTag("open_score_workbench"))
        compose.onNodeWithTag("open_score_workbench").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.scoreId == metadata.scoreId }
        assertNull(workbench.state.value.error)
        return metadata.scoreId to document.score
    }
    private fun stageOwnedDocument(prefix: String, saveSource: Boolean = true): ParsedScoreDocument {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("晨间和弦 · 原创导入测试", prefix + UUID.randomUUID()).toByteArray()
        val document = MusicXmlParser().parseDocument(bytes)
        val sourceId = document.score.id
        val sourceFile = File(context.filesDir, "digital-scores/$sourceId.musicxml")
        check(!Files.exists(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "测试 ID 与已有原件冲突，未登记所有权" }
        ScoreMetadataDatabase(context).use { database ->
            check(database.findBySourceId(sourceId, includeDeleted = true) == null) { "测试 ID 与已有元数据冲突，未登记所有权" }
        }
        check(!ownedDocuments.containsKey(sourceId))
        // Register BEFORE the first persistent write, so every exception path remains recoverable.
        ownedDocuments[sourceId] = OwnedDocument(bytes.copyOf(), document)
        if (saveSource) ScoreSourceStore(File(context.filesDir, "digital-scores")).save(bytes)
        return document
    }

    private fun persistOwnedMetadata(document: ParsedScoreDocument): ScoreMetadata {
        val owned = requireNotNull(ownedDocuments[document.score.id]) { "只能持久化本次已登记的测试乐谱" }
        val metadata = ScoreMetadataDatabase(context).use { it.ensureImported(document.score.id, ScoreImportMetadata.from(document)) }
        owned.scoreId = metadata.scoreId
        return metadata
    }
    private fun scroll(tag: String) { compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag(tag)) }
}
