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
import com.xiuyin.app.playback.ScorePlaybackEngine
import com.xiuyin.app.playback.ScorePlaybackPlan
import android.util.Log
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
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
        scroll("display_NUMBERED"); compose.onNodeWithTag("display_NUMBERED").performClick()
        transposeToD()
        compose.waitForIdle()
        // The applied result is brought into view; the original photo is never presented as a shifted score.
        compose.onNodeWithTag("current_digital_score").assertIsDisplayed()
        val measure = workbench.state.value.revision!!.current.parts.first().measures.first()
        assertEquals(2, measure.fifths)
        assertEquals(62, measure.events.first().pitch!!.midi)
        val originalMidi = ScoreTimelineCompiler.compile(original).notes.map { it.midi }.sorted()
        assertEquals(originalMidi.map { it + 2 }, workbench.state.value.timeline!!.notes.map { it.midi }.sorted())
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("双谱", substring = true)
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("D4", substring = true)
        scroll("display_NUMBERED"); compose.onNodeWithTag("display_NUMBERED").performClick()
        scroll("notation_${measure.id}")
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("简谱", substring = true)
        scroll("display_BOTH"); compose.onNodeWithTag("display_BOTH").performClick()
        scroll("notation_${measure.id}")
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains("双谱", substring = true)
    }

    @Test fun semitoneButtonsTransposeDownAndUndoRedoPersistWithoutChangingTheOriginal() {
        val (id, original) = openTestScore()
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_mode_semitone").performScrollTo().performClick()
        compose.onNodeWithTag("transpose_semitones").assertTextContains("0")
        compose.onNodeWithTag("transpose_apply").assertIsNotEnabled()
        compose.onNodeWithTag("transpose_plus").performScrollTo().performClick()
        compose.onNodeWithTag("transpose_semitones").assertTextContains("1")
        repeat(3) { compose.onNodeWithTag("transpose_minus").performScrollTo().performClick() }
        compose.onNodeWithTag("transpose_semitones").assertTextContains("-2")
        compose.onNodeWithTag("transpose_shift_preview").assertTextContains("降 2 个半音", substring = true)
        assertEquals("Preview must not write a revision", 0L, workbench.state.value.revision!!.generation)
        assertEquals(original, workbench.state.value.revision!!.current)
        compose.onNodeWithTag("transpose_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        assertNull(workbench.state.value.error)
        val changed = requireNotNull(workbench.state.value.revision)
        val oldMidi = ScoreTimelineCompiler.compile(original).notes.map { it.midi }.sorted()
        assertEquals(oldMidi.map { it - 2 }, workbench.state.value.timeline!!.notes.map { it.midi }.sorted())
        assertEquals("B♭3", changed.current.parts.first().measures.first().events.first().pitch!!.displayName)
        scroll("score_undo"); compose.onNodeWithTag("score_undo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        assertEquals(original, workbench.state.value.revision!!.current)
        assertEquals(oldMidi, workbench.state.value.timeline!!.notes.map { it.midi }.sorted())
        compose.onNodeWithTag("score_redo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 3L }
        assertEquals(changed.current, workbench.state.value.revision!!.current)
        assertEquals(changed.current, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original).current)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.openScore(id) }
        compose.waitUntil(10_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == id }
        assertEquals(changed.current, imports.state.value.selectedScore)
        assertEquals(original, imports.state.value.selectedOriginal)
        assertArrayEquals(requireNotNull(ownedDocuments[original.id]).bytes,
            ScoreSourceStore(File(context.filesDir, "digital-scores")).load(original.id).bytes)
    }

    @Test fun transposedRevisionFeedsPlaybackAndPracticeTargets() {
        val (id, original) = openTestScore()
        transposeToD()
        val saved = requireNotNull(workbench.state.value.revision)
        val timeline = requireNotNull(workbench.state.value.timeline)
        val expectedMidi = ScoreTimelineCompiler.compile(original).notes.map { it.midi + 2 }.sorted()
        val tones = ScorePlaybackPlan.tones(timeline.notes, 0.0, 60.0 / 80, ScorePlaybackEngine.SAMPLE_RATE)
        assertEquals(expectedMidi, tones.map { it.midi }.sorted())
        assertEquals(293.6647679174076, tones.first { it.midi == 62 }.frequency, 0.0000001)
        scroll("score_play"); compose.onNodeWithTag("score_play").performClick()
        compose.waitUntil(15_000) { workbench.playback.value.playedSamples > 0 || workbench.playback.value.error != null }
        assertNull(workbench.playback.value.error)
        assertTrue(workbench.playback.value.playedSamples > 0)
        assertTrue(workbench.playback.value.writtenSamples >= workbench.playback.value.playedSamples)
        assertEquals(saved, workbench.state.value.revision)
        scroll("score_start_practice"); compose.onNodeWithTag("score_start_practice").performClick()
        val practice = ViewModelProvider(compose.activity)[PracticeViewModel::class.java]
        compose.waitUntil(10_000) { !practice.state.value.busy && practice.state.value.scoreId == id }
        assertNull(practice.state.value.error)
        assertEquals(saved.generation, practice.state.value.revisionGeneration)
        assertEquals(saved.current, practice.state.value.score)
        assertEquals(expectedMidi, requireNotNull(practice.state.value.timeline).notes.map { it.midi }.sorted())
        compose.waitUntil(5_000) { !workbench.playback.value.playing }
        // Controller-only target preparation; this test does not start the microphone or simulate recognition.
        compose.runOnUiThread { practice.start(PracticeConfig(reviewAcknowledged = true)) }
        assertNull(practice.state.value.error)
        assertEquals(setOf(50, 62, 66, 69), practice.state.value.expectedMidi)
        assertEquals(0, practice.state.value.confirmedInputs)
        assertEquals(0, practice.state.value.correct)
    }

    @Test fun numberedFlatDInputSavesUndoRedoAndReopensTheRealTransposition() =
        numberedKeyPersists("1=bD", -5, 1, "D♭4")

    @Test fun numberedSharpFInputSavesUndoRedoAndReopensTheRealTransposition() =
        numberedKeyPersists("1=#F", 6, 6, "F♯4")

    @Test fun unsupportedNumberedSpellingCannotWriteOrPretendToPreviewAnotherKey() {
        val (_, original) = openTestScore()
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_numbered_key").performScrollTo().performTextReplacement("1=#D")
        compose.onNodeWithTag("transpose_error").assertTextContains("超出七升七降调号范围", substring = true)
        compose.onNodeWithTag("transpose_apply").assertIsNotEnabled()
        compose.onNodeWithTag("transpose_after").assertDoesNotExist()
        compose.onNodeWithTag("transpose_resolved_key").assertDoesNotExist()
        compose.onNodeWithTag("transpose_numbered_key").performTextReplacement("1=bbD")
        compose.onNodeWithTag("transpose_error").assertTextContains("只支持一个升号或降号", substring = true)
        compose.onNodeWithTag("transpose_apply").assertIsNotEnabled()
        assertEquals(0L, workbench.state.value.revision!!.generation)
        assertEquals(original, workbench.state.value.revision!!.current)
    }

    @Test fun missingSourceKeyBlocksNumberedInputButDoesNotInventAKeyDuringSemitoneShift() {
        val (_, original) = openTestScore()
        val first = original.parts.single().measures.single()
        compose.runOnUiThread { workbench.edit(ScorePatch.UpdateMeasure(first.id, first.time, null, first.clefs, first.declaredStaves), null) }
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_mode_key").assertIsSelected()
        compose.onNodeWithTag("transpose_unknown_key").assertExists()
        compose.onNodeWithTag("transpose_original_key").assertDoesNotExist()
        compose.onNodeWithTag("transpose_numbered_key").assertIsNotEnabled()
        compose.onNodeWithTag("transpose_apply").assertIsNotEnabled()
        compose.onNodeWithTag("transpose_mode_semitone").performScrollTo().performClick()
        compose.onNodeWithTag("transpose_semitones").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("transpose_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        assertNull(workbench.state.value.error)
        val changed = workbench.state.value.revision!!.current.parts.single().measures.single()
        assertNull(changed.fifths)
        assertEquals(62, changed.events.first().pitch!!.midi)
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

    @Test fun manualDragKeepsLoopPlaybackControlsReachableAcrossOnsetsAndRestartFollowsAgain() {
        val (_, original) = openTestScore()
        val evidence = File(context.filesDir, "test-workbench-playback-scroll/${UUID.randomUUID()}").also { check(it.mkdirs()) }
        fun capture(name: String) {
            compose.waitForIdle()
            File(evidence, "$name.png").outputStream().use { output ->
                check(compose.onNodeWithTag("workbench").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
        fun dragToStop() {
            val viewport = compose.onNodeWithTag("workbench").fetchSemanticsNode().boundsInRoot
            val desiredY = viewport.top + viewport.height * 0.72f
            // Real touch drags, not a semantic/programmatic scroll: only a user's drag may suspend following.
            for (attempt in 0 until 12) {
                val stop = compose.onNodeWithTag("score_stop")
                val displayed = stop.isDisplayed()
                val delta = if (displayed) desiredY - stop.fetchSemanticsNode().boundsInRoot.center.y else viewport.height * 0.28f
                if (displayed && kotlin.math.abs(delta) < 18f * context.resources.displayMetrics.density) break
                val distance = delta.coerceIn(-viewport.height * 0.28f, viewport.height * 0.28f)
                compose.onNodeWithTag("workbench").performTouchInput {
                    val start = Offset(width * 0.9f, height * 0.5f)
                    down(start)
                    repeat(8) { step -> moveTo(start + Offset(0f, distance * (step + 1) / 8), delayMillis = 80) }
                    advanceEventTime(250)
                    up()
                }
                compose.waitForIdle()
            }
            compose.onNodeWithTag("score_stop").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("current_digital_score").assertIsNotDisplayed()
        }
        scroll("display_BOTH"); compose.onNodeWithTag("display_BOTH").performClick()
        scroll("playback_loop"); compose.onNodeWithTag("playback_loop").performClick().assertIsSelected()
        scroll("score_play"); compose.onNodeWithTag("score_play").performClick()
        compose.waitUntil(15_000) { workbench.playback.value.playedSamples > ScorePlaybackEngine.SAMPLE_RATE / 5 || workbench.playback.value.error != null }
        assertNull(workbench.playback.value.error)
        assertTrue(workbench.playback.value.playing)
        compose.waitUntil(5_000) { compose.onNodeWithTag("current_digital_score").isDisplayed() }
        capture("01-audio-following")
        dragToStop()
        capture("02-manually-reached-stop")
        val stopBounds = compose.onNodeWithTag("score_stop").fetchSemanticsNode().boundsInRoot
        var previousBeat = workbench.playback.value.positionBeat
        var observedLoops = 0
        val observedActiveSets = mutableSetOf<Set<String>>()
        compose.waitUntil(15_000) {
            val playback = workbench.playback.value
            assertNull(playback.error)
            assertTrue("The real AudioTrack loop must remain playing", playback.playing)
            assertTrue(playback.writtenSamples >= playback.playedSamples)
            assertTrue("Playback must not drag the user away from Stop", compose.onNodeWithTag("score_stop").isDisplayed())
            val nowBounds = compose.onNodeWithTag("score_stop").fetchSemanticsNode().boundsInRoot
            assertEquals("Manual viewport must stay stable across onsets", stopBounds.top, nowBounds.top, 2f)
            if (playback.positionBeat < previousBeat - 1) observedLoops++
            previousBeat = playback.positionBeat
            observedActiveSets += requireNotNull(workbench.state.value.timeline).events.filter {
                playback.positionBeat >= it.startBeat.toDouble() && playback.positionBeat < it.endBeat.toDouble()
            }.map { it.eventId }.toSet()
            observedLoops >= 2 && observedActiveSets.size >= 3
        }
        capture("03-stop-still-reachable-after-two-loops")
        compose.onNodeWithTag("score_stop").performClick()
        compose.waitUntil(5_000) { !workbench.playback.value.playing }
        compose.onNodeWithTag("score_stop").assertIsNotEnabled()
        capture("04-stopped-from-visible-button")
        // A new explicit session restores following; loop iterations above must not restore it.
        compose.onNodeWithTag("score_play").performClick()
        compose.waitUntil(15_000) { workbench.playback.value.playedSamples > ScorePlaybackEngine.SAMPLE_RATE / 5 || workbench.playback.value.error != null }
        assertNull(workbench.playback.value.error)
        assertTrue(workbench.playback.value.playing)
        compose.waitUntil(5_000) { compose.onNodeWithTag("current_digital_score").isDisplayed() }
        capture("05-new-session-following-restored")
        dragToStop()
        compose.onNodeWithTag("score_stop").performClick()
        compose.waitUntil(5_000) { !workbench.playback.value.playing }
        assertEquals(original, workbench.state.value.revision!!.current)
        Log.i("XiuyinWorkbenchTest", "TEST DATA actual AudioTrack loops=$observedLoops activeSets=${observedActiveSets.size} restartFollow=true evidence=${evidence.name}")
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

    private fun transposeToD() {
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_mode_key").assertIsSelected()
        compose.onNodeWithTag("transpose_original_key").assertTextContains("1=C", substring = true)
        compose.onNodeWithTag("transpose_apply").assertIsNotEnabled()
        compose.onNodeWithTag("transpose_numbered_key").performScrollTo().performTextReplacement("1=D")
        compose.onNodeWithTag("transpose_shift_preview").assertTextContains("升 2 个半音", substring = true)
        compose.onNodeWithTag("transpose_pitch_preview").performScrollTo().assertTextContains("D4", substring = true)
        assertEquals(0L, workbench.state.value.revision!!.generation)
        assertEquals(60, workbench.state.value.revision!!.current.parts.first().measures.first().events.first().pitch!!.midi)
        compose.onNodeWithTag("transpose_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        assertNull(workbench.state.value.error)
        compose.onNodeWithTag("transpose_apply").assertDoesNotExist()
    }

    private fun numberedKeyPersists(input: String, fifths: Int, semitones: Int, expectedFirstName: String) {
        val (id, original) = openTestScore()
        scroll("score_transpose"); compose.onNodeWithTag("score_transpose").performClick()
        compose.onNodeWithTag("transpose_mode_key").assertIsSelected()
        compose.onNodeWithTag("transpose_numbered_key").performScrollTo().performTextReplacement(input)
        compose.onNodeWithTag("transpose_resolved_key").assertTextContains(input, substring = true)
        compose.onNodeWithTag("transpose_shift_preview").assertTextContains("升 $semitones 个半音", substring = true)
        compose.onNodeWithTag("transpose_pitch_preview").performScrollTo().assertTextContains(expectedFirstName, substring = true)
        assertEquals(0L, workbench.state.value.revision!!.generation)
        assertEquals(original, workbench.state.value.revision!!.current)
        compose.onNodeWithTag("transpose_apply").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 1L }
        assertNull(workbench.state.value.error)
        val saved = workbench.state.value.revision!!.current
        val measure = saved.parts.single().measures.single()
        assertEquals(fifths, measure.fifths)
        assertEquals(expectedFirstName, measure.events.first().pitch!!.displayName)
        val originalMidi = ScoreTimelineCompiler.compile(original).notes.map { it.midi }.sorted()
        assertEquals(originalMidi.map { it + semitones }, workbench.state.value.timeline!!.notes.map { it.midi }.sorted())
        assertEquals(saved, ScoreRevisionStore(File(context.filesDir, "score-revisions")).load(id, original).current)
        compose.waitForIdle()
        compose.onNodeWithTag("current_digital_score").assertIsDisplayed()
        compose.onNodeWithTag("notation_${measure.id}").assertContentDescriptionContains(expectedFirstName, substring = true)
        scroll("score_undo"); compose.onNodeWithTag("score_undo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 2L }
        assertEquals(original, workbench.state.value.revision!!.current)
        compose.onNodeWithTag("score_redo").performClick()
        compose.waitUntil(10_000) { !workbench.state.value.busy && workbench.state.value.revision?.generation == 3L }
        assertEquals(saved, workbench.state.value.revision!!.current)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !imports.state.value.importing }
        compose.runOnUiThread { imports.openScore(id) }
        compose.waitUntil(10_000) { !imports.state.value.importing && imports.state.value.selectedMetadata?.scoreId == id }
        assertEquals(saved, imports.state.value.selectedScore)
        assertEquals(original, imports.state.value.selectedOriginal)
        assertArrayEquals(requireNotNull(ownedDocuments[original.id]).bytes,
            ScoreSourceStore(File(context.filesDir, "digital-scores")).load(original.id).bytes)
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
