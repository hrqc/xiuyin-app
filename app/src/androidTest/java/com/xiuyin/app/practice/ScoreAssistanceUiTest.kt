package com.xiuyin.app.practice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xiuyin.app.pitch.PianoNoteEvidence
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** TEST DATA presentation and callback safety; no microphone or recognition accuracy claim. */
class ScoreAssistanceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandingExplanationKeepsExtraOctaveVisibleAndDoesNotPerformPracticeActions() {
        val midi = setOf(60, 72)
        val frame = PianoRecognitionFrame(0, 0.0, 1.12, midi, midi,
            midi.map { PianoNoteEvidence(it, .95f, .95f, true, true) }, RecognitionState.UNCERTAIN,
            false, 1.0, FloatArray(88) { if (it + 21 in midi) .95f else 0f }, FloatArray(88) { if (it + 21 in midi) .95f else 0f },
            uncertainMidi = midi, ambiguousOctaveMidi = midi)
        val trace = ScoreAssistanceTrace.observe(frame, ScoreAssistanceContext("TEST DATA score", 0, PracticeHand.BOTH,
            0, null, CompanionMode.STOP_ON_ERROR,
            FollowerContextSnapshot(0, FollowerPositionState.INITIAL_UNLOCATED, setOf(60), listOf("event"), 0, 0.0)))
            .withDecision(FollowerUpdate(0, reason = "TEST DATA 八度歧义"))
        var actions = 0
        compose.setContent { XiuyinTheme {
            PracticeCompanionScreen(CompanionState(scoreId = "TEST DATA score", phase = CompanionPhase.RUNNING,
                scoreAssistance = trace), onStart = { actions++ }, onPause = { actions++ }, onResume = { actions++ },
                onSeekMeasure = { actions++ }, onFinish = { actions++ }, onBack = { actions++ })
        } }
        compose.onNodeWithTag("companion_assistance_expand").performScrollTo().performClick()
        compose.onNodeWithText("目标以外候选：C5", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("当前禁用谱面先验建议", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("原跟谱判定：UNCERTAIN", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag("companion_assistance_expand").performScrollTo().performClick()
        compose.onNodeWithText("目标以外候选：C5", substring = true).assertDoesNotExist()
        assertEquals(0, actions)
    }
}
