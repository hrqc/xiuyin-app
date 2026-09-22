package com.xiuyin.app.field

import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.practice.PracticeDecision
import kotlin.math.abs

internal data class FieldFeedbackContext(val values: Map<String, String>, val audioTimeSeconds: Double?)

/** A frame's decision evidence and the UI at feedback time are intentionally separate records. */
internal fun buildFieldFeedbackContext(
    practice: CompanionState,
    latestFrame: PianoRecognitionFrame?,
    activeFieldSessionId: String?,
): FieldFeedbackContext {
    val candidate = if (practice.locked) practice.lastWrongFrame else latestFrame
    val frame = candidate?.takeIf {
        activeFieldSessionId != null && it.fieldSessionId == activeFieldSessionId &&
            it.audioStreamId != null && it.engineSessionId != null
    }
    // recentEvidence is newest first; lastWrongFrame refers to this latest WRONG decision.
    // Do not search farther back through another stream's reused relative audio timestamp.
    val evidence = if (practice.locked && frame != null) {
        practice.evidence.firstOrNull { it.decision == PracticeDecision.WRONG }?.takeIf {
            it.audioTimeSeconds.isFinite() && frame.audioTimeSeconds.isFinite() &&
                abs(it.audioTimeSeconds - frame.audioTimeSeconds) <= 1e-6
        }
    } else null
    val unknown = "UNKNOWN"
    val values = mapOf(
        "contextKind" to when {
            practice.locked && evidence != null -> "LOCKED_WRONG_EVIDENCE"
            practice.locked -> "LOCKED_EVIDENCE_UNKNOWN"
            frame != null -> "CURRENT_FRAME_WITH_SEPARATE_PAGE_SNAPSHOT"
            else -> "FRAME_UNKNOWN_WITH_PAGE_SNAPSHOT"
        },
        "scoreId" to (practice.scoreId ?: unknown),
        "revisionGeneration" to practice.revisionGeneration.toString(),
        "fieldSessionId" to (frame?.fieldSessionId ?: unknown),
        "audioStreamId" to (frame?.audioStreamId?.toString() ?: unknown),
        "engineSessionId" to (frame?.engineSessionId?.toString() ?: unknown),
        "frameIndex" to (frame?.frameIndex?.toString() ?: unknown),
        "audioTimeSeconds" to (frame?.audioTimeSeconds?.toString() ?: unknown),
        "frameOnsetMidi" to (frame?.onsetMidi?.sorted()?.joinToString() ?: unknown),
        "audioEvidenceAvailable" to (frame != null).toString(),
        "decision" to (evidence?.decision?.name ?: unknown),
        "measureIndex" to (evidence?.measureIndex?.toString() ?: unknown),
        "cursor" to (evidence?.targetIndex?.toString() ?: unknown),
        "expectedMidi" to (evidence?.expectedMidi?.sorted()?.joinToString() ?: unknown),
        "detectedMidi" to (evidence?.detectedMidi?.sorted()?.joinToString() ?: unknown),
        "eventIds" to (evidence?.eventIds?.joinToString() ?: unknown),
        "evidenceAudioTimeSeconds" to (evidence?.audioTimeSeconds?.toString() ?: unknown),
        "reason" to (evidence?.reason ?: unknown),
        "currentPracticeState" to practice.phase.name,
        "currentLocked" to practice.locked.toString(),
        "currentMeasureIndex" to practice.measureIndex.toString(),
        "currentCursor" to practice.cursor.toString(),
        "currentExpectedMidi" to practice.expectedMidi.sorted().joinToString(),
        "currentDetectedMidi" to practice.detectedMidi.sorted().joinToString(),
        "currentDecision" to practice.decision.name,
        "currentReason" to practice.reason,
        "currentContextDefinition" to "UI state at feedback time; not attributed to the selected audio frame",
    )
    return FieldFeedbackContext(values, frame?.audioTimeSeconds)
}