package com.xiuyin.app.score

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xiuyin.app.playback.PlaybackSnapshot
import com.xiuyin.app.ui.ScoreWorkbenchScreen
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TEST DATA UI checks. Does not claim recognition, OMR or physical piano acceptance. */
class ScoreRangeEditingUiTest {
    @get:Rule val compose = createComposeRule()
    private fun scroll(tag: String) = compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag(tag))

    @Test fun previewNeedsExplicitDirectionAndKeepsTheRequestBoundToItsRevision() {
        val requests = mutableListOf<ScoreOctaveRangeRequest>()
        compose.setContent { XiuyinTheme { Workbench(fixture(), onRange = { requests += it }) } }
        scroll("score_octave_range"); compose.onNodeWithTag("score_octave_range").performClick()
        compose.onNodeWithTag("octave_range_apply").assertIsNotEnabled()
        compose.onNodeWithTag("octave_range_end").performScrollTo().performClick()
        compose.onNodeWithText("2 · 第 2 小节 · 起点 0 · D4").performClick()
        compose.onNodeWithTag("octave_range_up").performScrollTo().performClick()
        compose.onNodeWithTag("octave_range_preview").performScrollTo().assertTextContains("修改 2 个音", substring = true)
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("octave_range_apply").performClick()
        assertEquals(listOf(ScoreOctaveRangeRequest("library-id", 7, "n1", "n2", 1)), requests)
    }

    @Test fun changedRevisionClosesTheOldRangeWithoutWriting() {
        val state = mutableStateOf(fixture())
        var writes = 0
        compose.setContent { XiuyinTheme { Workbench(state.value, onRange = { writes++ }) } }
        scroll("score_octave_range"); compose.onNodeWithTag("score_octave_range").performClick()
        compose.onNodeWithTag("octave_range_up").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(revision = state.value.revision!!.copy(generation = 8)) }
        compose.onNodeWithTag("octave_range_apply").assertDoesNotExist()
        assertEquals(0, writes)
    }

    @Test fun changingSymbolAndDotsRecomputesTheExistingTupletRatio() {
        val changes = mutableListOf<ScorePatch>()
        compose.setContent { XiuyinTheme { Workbench(fixture(), onEdit = { patch, _ -> changes += patch }) } }
        scroll("edit_selected_note"); compose.onNodeWithTag("edit_selected_note").performClick()
        compose.onNodeWithTag("edit_note_type").performScrollTo().performClick()
        compose.onNodeWithText("16th").performClick()
        compose.onNodeWithTag("edit_note_duration").performScrollTo().assertTextContains("1/6", substring = true)
        compose.onNodeWithTag("edit_note_dots").performScrollTo().performClick()
        compose.onNodeWithTag("edit_note_dots_option_1").performClick()
        compose.onNodeWithTag("edit_note_duration").performScrollTo().assertTextContains("1/4", substring = true)
        compose.onNodeWithTag("edit_note_save").performClick()
        val event = (changes.single() as ScorePatch.ReplaceEvent).event
        assertEquals(BeatFraction.of(1, 4), event.duration)
        assertEquals(3, event.tupletActual)
        assertEquals(2, event.tupletNormal)
        assertEquals(1, event.dots)
    }

    @Composable private fun Workbench(state: ScoreWorkbenchState,
        onRange: (ScoreOctaveRangeRequest) -> Unit = {}, onEdit: (ScorePatch, Int?) -> Unit = { _, _ -> }) {
        ScoreWorkbenchScreen(state, PlaybackSnapshot(), onBack = {}, onSelect = {}, onEdit = onEdit,
            onTranspose = { _, _ -> }, onUndo = {}, onRedo = {}, onVerify = {}, onRestoreEvent = {}, onRestoreMeasure = {},
            onPlay = { _, _, _, _, _ -> }, onStop = {}, onPractice = {}, onShiftOctaveRange = onRange)
    }

    private fun fixture(): ScoreWorkbenchState {
        val events = listOf(
            ScoreEvent("n1", BeatFraction.ZERO, BeatFraction.of(1, 3), ScorePitch("C", 0, 4), 1, "1", false,
                "eighth", 0, emptySet(), 0, 0, tupletActual = 3, tupletNormal = 2),
            ScoreEvent("n2", BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("D", 0, 4), 1, "1", false,
                "quarter", 0, emptySet(), 1, 0))
        val measures = events.mapIndexed { index, event -> ScoreMeasure("m${index + 1}", "${index + 1}",
            ScoreTime(4, 4), 0, listOf(ScoreClef(1, "G", 2)), listOf(event), false) }
        val score = DigitalScore("test-data", "TEST DATA", listOf(ScorePart("p1", "Piano", measures)), emptyList(),
            ScoreSource("synthetic-ui-test", "0".repeat(64), 1))
        return ScoreWorkbenchState(scoreId = "library-id", title = score.title, original = score, selectedEventId = "n1",
            revision = ScoreRevisionSnapshot(score.id, 7, score, emptySet(), emptyMap(), false, false))
    }
}
