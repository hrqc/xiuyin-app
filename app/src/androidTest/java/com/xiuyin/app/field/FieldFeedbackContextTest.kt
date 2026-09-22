package com.xiuyin.app.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.practice.PracticeDecision
import com.xiuyin.app.practice.PracticeEvidence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA regression fixtures; these verify evidence binding, never model accuracy. */
@RunWith(AndroidJUnit4::class)
class FieldFeedbackContextTest {
    private fun frame(index: Long, time: Double, session: String = "field-test", stream: Long = 7, engine: Long = 12) = PianoRecognitionFrame(
        frameIndex = index, audioTimeSeconds = time, availableAudioTimeSeconds = time + .1,
        onsetMidi = setOf(61), activeMidi = setOf(61), notes = emptyList(), state = RecognitionState.ATTACK,
        clipped = false, computeMs = 2.0, rawOnsetScores = FloatArray(88), rawFrameScores = FloatArray(88),
        fieldSessionId = session, audioStreamId = stream, engineSessionId = engine,
    )
    private fun wrong(time: Double, target: Int = 3) = PracticeEvidence(PracticeDecision.WRONG, target, 1,
        listOf("test-event"), setOf(60), setOf(61), time, .8, "TEST DATA actual wrong decision")
    private fun state() = CompanionState(scoreId = "test-score", phase = CompanionPhase.LOCKED, locked = true,
        cursor = 9, measureIndex = 4, expectedMidi = setOf(72), detectedMidi = emptySet(),
        decision = PracticeDecision.UNCERTAIN, reason = "TEST DATA current waiting state")

    @Test fun lockedFeedbackUsesWrongEvidenceAndItsFrameSourcesInsteadOfCurrentPage() {
        val oldWrong = frame(33, 2.0)
        val result = buildFieldFeedbackContext(state().copy(lastWrongFrame = oldWrong, evidence = listOf(wrong(2.0))),
            frame(90, 4.0, engine = 99), "field-test")
        assertEquals("LOCKED_WRONG_EVIDENCE", result.values["contextKind"])
        assertEquals("WRONG", result.values["decision"])
        assertEquals("60", result.values["expectedMidi"])
        assertEquals("61", result.values["detectedMidi"])
        assertEquals("3", result.values["cursor"])
        assertEquals("33", result.values["frameIndex"])
        assertEquals("field-test", result.values["fieldSessionId"])
        assertEquals("7", result.values["audioStreamId"])
        assertEquals("12", result.values["engineSessionId"])
        assertEquals("72", result.values["currentExpectedMidi"])
        assertEquals("UNCERTAIN", result.values["currentDecision"])
        assertEquals(2.0, result.audioTimeSeconds!!, 0.0)
    }
    @Test fun unmatchedLatestWrongEvidenceStaysUnknownInsteadOfPickingAnOlderTimestampCollision() {
        val result = buildFieldFeedbackContext(state().copy(lastWrongFrame = frame(33, 2.0),
            evidence = listOf(wrong(3.0, 8), wrong(2.0))), null, "field-test")
        assertEquals("LOCKED_EVIDENCE_UNKNOWN", result.values["contextKind"])
        assertEquals("UNKNOWN", result.values["decision"])
        assertEquals("UNKNOWN", result.values["expectedMidi"])
        assertEquals("UNKNOWN", result.values["cursor"])
        assertEquals("true", result.values["audioEvidenceAvailable"])
        assertEquals(2.0, result.audioTimeSeconds!!, 0.0)
    }
    @Test fun unlockedFeedbackUsesLatestFrameAndExplicitlySeparatesCurrentPageState() {
        val result = buildFieldFeedbackContext(state().copy(phase = CompanionPhase.RUNNING, locked = false,
            lastDecisionFrame = frame(33, 2.0), evidence = listOf(wrong(2.0))), frame(90, 4.0, engine = 99), "field-test")
        assertEquals("CURRENT_FRAME_WITH_SEPARATE_PAGE_SNAPSHOT", result.values["contextKind"])
        assertEquals("90", result.values["frameIndex"])
        assertEquals("99", result.values["engineSessionId"])
        assertEquals("UNKNOWN", result.values["decision"])
        assertEquals("UNKNOWN", result.values["expectedMidi"])
        assertEquals("72", result.values["currentExpectedMidi"])
        assertEquals(4.0, result.audioTimeSeconds!!, 0.0)
    }
    @Test fun frameFromAnotherFieldSessionCannotAnchorFeedbackOrAudioClip() {
        val result = buildFieldFeedbackContext(state().copy(lastWrongFrame = frame(33, 2.0, session = "previous-field"),
            evidence = listOf(wrong(2.0))), null, "field-test")
        assertEquals("UNKNOWN", result.values["fieldSessionId"])
        assertEquals("UNKNOWN", result.values["decision"])
        assertEquals("false", result.values["audioEvidenceAvailable"])
        assertNull(result.audioTimeSeconds)
    }
}