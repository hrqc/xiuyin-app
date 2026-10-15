package com.xiuyin.app.practice

import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState

data class ScoreAssistanceContext(
    val scoreId: String, val revisionGeneration: Long, val hand: PracticeHand,
    val startMeasure: Int, val endMeasure: Int?, val mode: CompanionMode,
    val follower: FollowerContextSnapshot,
)

/** Independent audio facts. Raw index i always denotes MIDI i + 21; no score filtering. */
data class IndependentAudioTrace(
    val rawOnsetScores: List<Float>, val rawFrameScores: List<Float>,
    val onsetMidi: Set<Int>, val activeMidi: Set<Int>, val pendingOnsetMidi: Set<Int>,
    val uncertainMidi: Set<Int>, val ambiguousOctaveMidi: Set<Int>, val candidateRejections: Map<Int, String>,
    val clipped: Boolean, val recognitionState: RecognitionState,
)

data class ScoreOctaveRelation(
    val lowerMidi: Int, val upperMidi: Int, val lowerObserved: Boolean, val upperObserved: Boolean,
    val lowerExpected: Boolean, val upperExpected: Boolean, val independentState: String?,
)

data class AssistedScoreRelation(
    val observedTargetIntersection: Set<Int>, val observedExtraMidi: Set<Int>,
    val expectedWithoutConfirmedOnsetThisFrame: Set<Int>, val octaveRelations: List<ScoreOctaveRelation>,
    val positionPriorEligible: Boolean, val priorDisabledReason: String?,
)

data class AssistanceFinalDecision(
    val cursorAfter: Int, val decision: PracticeDecision, val detectedMidi: Set<Int>,
    val confirmedInput: Boolean, val locked: Boolean, val reason: String,
    val evidence: List<PracticeEvidence>,
)

/** Observer only: it returns diagnostics, never a RecognitionObservation or a replacement note set. */
data class ScoreAssistanceTrace(
    val frameIndex: Long, val audioTimeSeconds: Double, val availableAudioTimeSeconds: Double,
    val fieldSessionId: String?, val audioStreamId: Long?, val engineSessionId: Long?,
    val context: ScoreAssistanceContext, val independent: IndependentAudioTrace,
    val assisted: AssistedScoreRelation, val finalDecision: AssistanceFinalDecision? = null,
) {
    fun withDecision(update: FollowerUpdate): ScoreAssistanceTrace = copy(finalDecision = AssistanceFinalDecision(
        update.cursor, update.decision, update.detectedMidi.toSet(), update.confirmedInput, update.locked, update.reason,
        update.evidence.map { it.copy(eventIds = it.eventIds.toList(), expectedMidi = it.expectedMidi.toSet(), detectedMidi = it.detectedMidi.toSet()) }))

    /** Optional nested field in the existing following JSONL; old ZIPs need no migration. */
    fun toLogRecord(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "mode" to "OBSERVE_ONLY", "assistanceAppliedToDecision" to false,
        "countsAsMusicalEvidence" to false, "frameIndex" to frameIndex,
        "audioTimeSeconds" to audioTimeSeconds, "availableAudioTimeSeconds" to availableAudioTimeSeconds,
        "fieldSessionId" to fieldSessionId, "audioStreamId" to audioStreamId, "engineSessionId" to engineSessionId,
        "context" to mapOf("scoreId" to context.scoreId, "revisionGeneration" to context.revisionGeneration,
            "hand" to context.hand.name, "startMeasure" to context.startMeasure, "endMeasure" to context.endMeasure,
            "mode" to context.mode.name, "targetIndexBefore" to context.follower.targetIndex,
            "positionState" to context.follower.positionState.name, "expectedMidi" to context.follower.expectedMidi.sorted(),
            "targetEventIds" to context.follower.eventIds, "measureIndex" to context.follower.measureIndex,
            "startBeat" to context.follower.startBeat, "lastInputFrameIndex" to context.follower.lastInputFrameIndex,
            "lastInputTimeSeconds" to context.follower.lastInputTimeSeconds),
        "independentAudio" to mapOf("rawMidiMin" to 21, "rawMidiMax" to 108,
            "rawScoreDefinition" to "Index i = MIDI i+21. Neural activations, not calibrated probabilities.",
            "rawOnsetScores" to independent.rawOnsetScores, "rawFrameScores" to independent.rawFrameScores,
            "onsetMidi" to independent.onsetMidi.sorted(), "activeMidi" to independent.activeMidi.sorted(),
            "pendingOnsetMidi" to independent.pendingOnsetMidi.sorted(), "uncertainMidi" to independent.uncertainMidi.sorted(),
            "ambiguousOctaveMidi" to independent.ambiguousOctaveMidi.sorted(),
            "candidateRejections" to independent.candidateRejections.mapKeys { it.key.toString() },
            "clipped" to independent.clipped, "recognitionState" to independent.recognitionState.name),
        "scoreRelation" to mapOf("observedTargetIntersection" to assisted.observedTargetIntersection.sorted(),
            "observedExtraMidi" to assisted.observedExtraMidi.sorted(),
            "expectedWithoutConfirmedOnsetThisFrame" to assisted.expectedWithoutConfirmedOnsetThisFrame.sorted(),
            "missingDefinition" to "No confirmed onset in this frame; not evidence of a missed physical key.",
            "positionPriorEligible" to assisted.positionPriorEligible, "priorDisabledReason" to assisted.priorDisabledReason,
            "octaveRelations" to assisted.octaveRelations.map { relation -> mapOf(
                "lowerMidi" to relation.lowerMidi, "upperMidi" to relation.upperMidi,
                "lowerObserved" to relation.lowerObserved, "upperObserved" to relation.upperObserved,
                "lowerExpected" to relation.lowerExpected, "upperExpected" to relation.upperExpected,
                "independentState" to relation.independentState) }),
        "finalDecision" to finalDecision?.let { result -> mapOf("cursorAfter" to result.cursorAfter,
            "decision" to result.decision.name, "detectedMidi" to result.detectedMidi.sorted(),
            "confirmedInput" to result.confirmedInput, "locked" to result.locked, "reason" to result.reason,
            "evidence" to result.evidence.map { item -> mapOf("targetIndex" to item.targetIndex,
                "decision" to item.decision.name, "audioTimeSeconds" to item.audioTimeSeconds,
                "expectedMidi" to item.expectedMidi.sorted(), "detectedMidi" to item.detectedMidi.sorted(), "reason" to item.reason) }) }
    )

    companion object {
        fun observe(frame: PianoRecognitionFrame, context: ScoreAssistanceContext): ScoreAssistanceTrace {
            require(frame.rawOnsetScores.size == 88 && frame.rawFrameScores.size == 88)
            require((frame.rawOnsetScores.asSequence() + frame.rawFrameScores.asSequence()).all { it.isFinite() && it in 0f..1f })
            val snapshot = context.copy(follower = context.follower.copy(expectedMidi = context.follower.expectedMidi.toSet(), eventIds = context.follower.eventIds.toList()))
            val expected = snapshot.follower.expectedMidi
            val observed = frame.onsetMidi + frame.activeMidi
            val all = observed + expected + frame.pendingOnsetMidi + frame.uncertainMidi + frame.ambiguousOctaveMidi
            val octave = (21..96).filter { it in all && it + 12 in all }.map { lower ->
                ScoreOctaveRelation(lower, lower + 12, lower in observed, lower + 12 in observed,
                    lower in expected, lower + 12 in expected,
                    frame.octaveDiagnostics.firstOrNull { it.lowerMidi == lower && it.upperMidi == lower + 12 }?.finalState?.name)
            }
            val disabled = when {
                snapshot.follower.positionState != FollowerPositionState.RELIABLE -> "POSITION_${snapshot.follower.positionState.name}"
                snapshot.follower.lastInputFrameIndex?.let { frame.frameIndex != it + 1 } == true -> "INPUT_FRAME_DISCONTINUITY"
                snapshot.follower.lastInputTimeSeconds?.let { frame.audioTimeSeconds <= it } == true -> "INPUT_TIME_NON_MONOTONIC"
                snapshot.follower.lastInputTimeSeconds?.let { frame.audioTimeSeconds - it > 0.25 } == true -> "INPUT_TIME_DISCONTINUITY"
                frame.clipped -> "CLIPPED"
                frame.state == RecognitionState.WARMING_UP -> "WARMING_UP"
                frame.state == RecognitionState.UNCERTAIN || frame.pendingOnsetMidi.isNotEmpty() ||
                    frame.uncertainMidi.isNotEmpty() || frame.ambiguousOctaveMidi.isNotEmpty() -> "ACOUSTIC_UNCERTAINTY"
                else -> null
            }
            return ScoreAssistanceTrace(frame.frameIndex, frame.audioTimeSeconds, frame.availableAudioTimeSeconds,
                frame.fieldSessionId, frame.audioStreamId, frame.engineSessionId, snapshot,
                IndependentAudioTrace(frame.rawOnsetScores.toList(), frame.rawFrameScores.toList(), frame.onsetMidi.toSet(),
                    frame.activeMidi.toSet(), frame.pendingOnsetMidi.toSet(), frame.uncertainMidi.toSet(),
                    frame.ambiguousOctaveMidi.toSet(), frame.candidateRejections.toMap(), frame.clipped, frame.state),
                AssistedScoreRelation(observed intersect expected, observed - expected,
                    expected - (frame.onsetMidi - frame.uncertainMidi - frame.ambiguousOctaveMidi), octave, disabled == null, disabled))
        }
    }
}
