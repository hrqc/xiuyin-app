package com.xiuyin.app.practice

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

    private fun score(): DigitalScore {
        val event = ScoreEvent("e1", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(60),
            1, "1", false, "quarter", 0, emptySet(), 0, 0)
        val measure = ScoreMeasure("m1", "7", ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), listOf(event), false)
        return DigitalScore("test-preparation-ui", "TEST DATA", listOf(ScorePart("p1", "Piano", listOf(measure))),
            emptyList(), ScoreSource("synthetic-preparation-ui-test", "0".repeat(64), 1))
    }
}
