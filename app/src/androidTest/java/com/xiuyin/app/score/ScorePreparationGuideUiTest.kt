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

/** TEST DATA for structural guidance only; no OMR/music correctness is claimed. */
class ScorePreparationGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private fun scroll(tag: String) = compose.onNodeWithTag("workbench").performScrollToNode(hasTestTag(tag))

    @Test fun nineMissingMetersAndTwoRepeatsAreSummarizedBeforeListeningAndRequireExplicitChoice() {
        val state = fixture()
        val requests = mutableListOf<List<Any>>()
        compose.setContent { XiuyinTheme { TestWorkbench(state, onFill = { id, generation, top, bottom -> requests += listOf(id, generation, top, bottom) }) } }
        scroll("score_preparation_count")
        compose.onNodeWithTag("score_preparation_count").assertTextContains("11 项谱面准备问题", substring = true)
        compose.onNodeWithTag("score_preparation_count").assertTextContains("尚未开始监听", substring = true)
        compose.onNodeWithTag("score_missing_time_summary").assertTextContains("9 个小节", substring = true)
        scroll("score_fill_missing_time"); compose.onNodeWithTag("score_fill_missing_time").performClick()
        compose.onNodeWithTag("missing_time_apply").assertIsNotEnabled()
        compose.onNodeWithTag("missing_time_2_4").assertIsNotSelected()
        compose.onNodeWithTag("missing_time_3_4").assertIsNotSelected()
        compose.onNodeWithTag("missing_time_4_4").assertIsNotSelected()
        compose.onNodeWithTag("missing_time_6_8").assertIsNotSelected()
        compose.onNodeWithTag("missing_time_2_4").performClick().assertIsSelected()
        assertTrue("选择拍号尚不能自动写入", requests.isEmpty())
        compose.onNodeWithTag("missing_time_apply").assertIsEnabled().performClick()
        assertEquals(listOf(listOf(state.scoreId!!, 7L, 2, 4)), requests)
    }

    @Test fun manualMeterRejectsInvalidDenominatorAndAcceptsExplicitTwelveEight() {
        val requests = mutableListOf<Pair<Int, Int>>()
        compose.setContent { XiuyinTheme { TestWorkbench(fixture(), onFill = { _, _, top, bottom -> requests += top to bottom }) } }
        scroll("score_fill_missing_time"); compose.onNodeWithTag("score_fill_missing_time").performClick()
        compose.onNodeWithTag("missing_time_beats").performTextReplacement("12")
        compose.onNodeWithTag("missing_time_beat_type").performTextReplacement("3")
        compose.onNodeWithTag("missing_time_apply").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("missing_time_beat_type").performTextReplacement("8")
        compose.onNodeWithTag("missing_time_apply").assertIsEnabled().performClick()
        assertEquals(listOf(12 to 8), requests)
    }

    @Test fun oldMeterDialogClosesWhenScoreOrRevisionChanges() {
        val state = mutableStateOf(fixture())
        var writes = 0
        compose.setContent { XiuyinTheme { TestWorkbench(state.value, onFill = { _, _, _, _ -> writes++ }) } }
        scroll("score_fill_missing_time"); compose.onNodeWithTag("score_fill_missing_time").performClick()
        compose.onNodeWithTag("missing_time_2_4").performClick()
        compose.runOnIdle { state.value = state.value.copy(scoreId = "test-data-other-score") }
        compose.onNodeWithTag("missing_time_apply").assertDoesNotExist()
        scroll("score_fill_missing_time"); compose.onNodeWithTag("score_fill_missing_time").performClick()
        compose.onNodeWithTag("missing_time_apply").assertIsNotEnabled()
        compose.onNodeWithTag("missing_time_3_4").performClick()
        compose.runOnIdle { state.value = state.value.copy(revision = state.value.revision!!.copy(generation = 8)) }
        compose.onNodeWithTag("missing_time_apply").assertDoesNotExist()
        assertEquals(0, writes)
    }

    @Test fun globalRepeatIssueOpensTheExistingExplicitStrategyConfirmation() {
        var confirmations = 0
        compose.setContent { XiuyinTheme { TestWorkbench(fixture(), onRepeat = { confirmations++ }) } }
        scroll("score_review_issue_0")
        compose.onNodeWithTag("score_review_issue_0").assertIsEnabled().performClick()
        compose.onNodeWithTag("score_confirm_linear_repeat").assertIsEnabled()
        assertEquals(0, confirmations)
        compose.onNodeWithTag("score_cancel_linear_repeat").performClick()
        assertEquals(0, confirmations)
        scroll("score_guide_repeat"); compose.onNodeWithTag("score_guide_repeat").performClick()
        compose.onNodeWithTag("score_confirm_linear_repeat").performClick()
        assertEquals(1, confirmations)
    }

    @Test fun missingMeterIssueOpensItsMeasureEditorInsteadOfOnlySelectingANote() {
        compose.setContent { XiuyinTheme { TestWorkbench(fixture()) } }
        scroll("score_review_issue_2"); compose.onNodeWithTag("score_review_issue_2").performClick()
        compose.onNodeWithText("第 1 小节设置").assertExists()
        compose.onNodeWithTag("measure_beats").assertExists()
        compose.onNodeWithTag("measure_beat_type").assertExists()
    }

    @Test fun repairNavigationRequestDirectlyOpensTheCorrectMissingMeterEditor() {
        val initial = fixture()
        val target = ScoreStructureTools.reviewIssues(initial.revision!!.current).first { it.measureId == "m5" && ScoreStructureTools.isMissingTimeIssue(it) }
        compose.setContent { XiuyinTheme { TestWorkbench(initial.copy(repairIssue = target)) } }
        compose.onNodeWithText("第 5 小节设置").assertExists()
        compose.onNodeWithTag("measure_beats").assertExists()
    }

    @Composable private fun TestWorkbench(state: ScoreWorkbenchState,
        onFill: (String, Long, Int, Int) -> Unit = { _, _, _, _ -> }, onRepeat: () -> Unit = {}) {
        ScoreWorkbenchScreen(state, PlaybackSnapshot(), onBack = {}, onSelect = {}, onEdit = { _, _ -> }, onTranspose = { _, _ -> },
            onUndo = {}, onRedo = {}, onVerify = {}, onRestoreEvent = {}, onRestoreMeasure = {},
            onPlay = { _, _, _, _, _ -> }, onStop = {}, onPractice = {}, onFillMissingScoreTime = onFill,
            onConfirmLinearRepeat = onRepeat)
    }

    private fun fixture(): ScoreWorkbenchState {
        val measures = (1..9).map { index ->
            val event = ScoreEvent("n$index", BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("C", 0, 4),
                1, "1", false, "quarter", 0, emptySet(), index, 0)
            ScoreMeasure("m$index", index.toString(), null, 0, listOf(ScoreClef(1, "G", 2)), listOf(event), false)
        }
        val score = DigitalScore("test-data-guide", "TEST DATA 续页引导", listOf(ScorePart("p1", "钢琴", measures)),
            (1..2).map { ScoreIssue("omr:repeat:guide:$it", "TEST DATA 全局反复 $it", ScoreIssueSeverity.ERROR) },
            ScoreSource("OMR_IMAGE", "0".repeat(64), 1))
        return ScoreWorkbenchState(scoreId = "test-data-library-score", title = score.title, original = score,
            revision = ScoreRevisionSnapshot(score.id, 7L, score, emptySet(), emptyMap(), false, false))
    }
}
