package com.xiuyin.app.practice

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.score.*
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TEST DATA controls check structural blocking, hand status wording and repair navigation. */
class PracticePreparationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedPreparationExplainsLocationAndRepairsWithoutClaimingMissingStaves() {
        val score = score()
        val issue = ScoreIssue("test:structure", "TEST DATA 记谱类型与时值不一致", ScoreIssueSeverity.ERROR, "m1", "e1")
        var repaired: ScoreIssue? = null
        var starts = 0
        compose.setContent { XiuyinTheme {
            PracticeCompanionScreen(CompanionState(scoreId = score.id, score = score, preparationIssues = listOf(issue), error = "练习准备失败"),
                onStart = { starts++ }, onPause = {}, onResume = {}, onSeekMeasure = {}, onFinish = {}, onBack = {},
                recognition = RecognitionEngineSnapshot(RecognitionEngineStatus.READY), onRepairScore = { repaired = it })
        } }
        compose.onNodeWithText("第 7 小节：TEST DATA 记谱类型与时值不一致", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag("companion_hand_status").performScrollTo().assertTextContains("谱面结构尚未通过", substring = true)
        compose.onNodeWithText("当前谱面缺少明确双谱表音符", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("companion_hand_LEFT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("companion_hand_RIGHT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("companion_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("companion_repair").performScrollTo().performClick()
        assertEquals(issue, repaired)
        assertEquals(0, starts)
    }

    @Test fun validSingleStaffScoreStillReportsTheActualHandLimitationAndPreservesBackAction() {
        val score = score()
        var back = 0
        compose.setContent { XiuyinTheme {
            PracticeCompanionScreen(CompanionState(scoreId = score.id, score = score, timeline = ScoreTimelineCompiler.compile(score)),
                onStart = {}, onPause = {}, onResume = {}, onSeekMeasure = {}, onFinish = {}, onBack = { back++ },
                recognition = RecognitionEngineSnapshot(RecognitionEngineStatus.READY))
        } }
        compose.onNodeWithTag("companion_hand_status").performScrollTo().assertTextContains("缺少明确双谱表音符", substring = true)
        compose.onNodeWithTag("companion_start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("companion_repair").assertDoesNotExist()
        compose.onNodeWithText("返回乐谱工作台").performScrollTo().performClick()
        assertEquals(1, back)
    }

    @Test fun continuationPageShowsElevenPreparationItemsAsNineMetersAndTwoRepeatChoices() {
        val base = score()
        val measures = (1..9).map { index -> base.parts.single().measures.single().copy(
            id = "m$index", number = index.toString(), time = null,
            events = base.parts.single().measures.single().events.map { it.copy(id = "e$index") }) }
        val page = base.copy(parts = listOf(ScorePart("p1", "钢琴", measures)),
            source = ScoreSource("OMR_IMAGE", "0".repeat(64), 1),
            issues = (1..2).map { ScoreIssue("omr:repeat:test:$it", "TEST DATA 全局反复", ScoreIssueSeverity.ERROR) })
        val issues = ScoreStructureTools.reviewIssues(page)
        var repairs = 0
        var target: ScoreIssue? = issues.first()
        var starts = 0
        compose.setContent { XiuyinTheme {
            PracticeCompanionScreen(CompanionState(scoreId = page.id, score = page, preparationIssues = issues, error = "TEST DATA 11 项结构问题"),
                onStart = { starts++ }, onPause = {}, onResume = {}, onSeekMeasure = {}, onFinish = {}, onBack = {},
                recognition = RecognitionEngineSnapshot(RecognitionEngineStatus.READY), onRepairScore = { repairs++; target = it })
        } }
        compose.onNodeWithTag("companion_preparation_count").performScrollTo().assertTextContains("11 项谱面准备问题", substring = true)
        compose.onNodeWithTag("companion_preparation_count").assertTextContains("尚未开始监听", substring = true)
        compose.onNodeWithTag("companion_missing_time_summary").performScrollTo().assertTextContains("9 个小节", substring = true)
        compose.onNodeWithTag("companion_repeat_summary").performScrollTo().assertTextContains("2 处反复记号", substring = true)
        compose.onNodeWithTag("companion_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("companion_repair").performScrollTo().assertTextContains("返回补全谱面信息").performClick()
        assertEquals(1, repairs)
        assertNull("汇总返回顶部引导，不误定位为第一个音弹错", target)
        assertEquals(0, starts)
    }

    @Test fun changingRevisionClearsThePriorHumanReviewAcknowledgement() {
        val score = score()
        val warning = ScoreIssue("test-review-warning", "TEST DATA 来源需复核")
        val state = mutableStateOf(CompanionState(scoreId = score.id, revisionGeneration = 7, score = score,
            timeline = ScoreTimelineCompiler.compile(score).copy(issues = listOf(warning)), preparationIssues = listOf(warning)))
        var starts = 0
        compose.setContent { XiuyinTheme {
            PracticeCompanionScreen(state.value, onStart = { starts++ }, onPause = {}, onResume = {}, onSeekMeasure = {}, onFinish = {}, onBack = {},
                recognition = RecognitionEngineSnapshot(RecognitionEngineStatus.READY))
        } }
        compose.onNodeWithTag("companion_review_acknowledgement").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(revisionGeneration = 8) }
        compose.onNodeWithTag("companion_review_acknowledgement").performScrollTo().assertIsOff()
        assertEquals(0, starts)
    }
    private fun score(): DigitalScore {
        val event = ScoreEvent("e1", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(60),
            1, "1", false, "quarter", 0, emptySet(), 0, 0)
        val measure = ScoreMeasure("m1", "7", ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), listOf(event), false)
        return DigitalScore("test-preparation-ui", "TEST DATA", listOf(ScorePart("p1", "Piano", listOf(measure))),
            emptyList(), ScoreSource("synthetic-preparation-ui-test", "0".repeat(64), 1))
    }
}
