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
import java.io.File
import java.util.UUID
import org.junit.Assert.*
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

    private fun openTestScore(): Pair<String, DigitalScore> {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("浏览本地曲库").fetchSemanticsNodes().isNotEmpty() && !imports.state.value.importing }
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("晨间和弦 · 原创导入测试", "工作台 TEST DATA " + UUID.randomUUID()).toByteArray()
        val document = MusicXmlParser().parseDocument(bytes)
        ScoreSourceStore(File(context.filesDir, "digital-scores")).save(bytes)
        val metadata = ScoreMetadataDatabase(context).use { it.ensureImported(document.score.id, ScoreImportMetadata.from(document)) }
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
    private fun scroll(tag: String) { compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag(tag)) }
}
