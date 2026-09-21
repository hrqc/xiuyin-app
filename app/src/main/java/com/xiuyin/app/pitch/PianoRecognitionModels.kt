package com.xiuyin.app.pitch

/** Model activation scores, not calibrated probabilities or measured note accuracy. */
data class PianoNoteEvidence(val midi: Int, val onsetScore: Float, val frameScore: Float, val onset: Boolean, val active: Boolean)
enum class RecognitionState { WARMING_UP, SILENCE, ATTACK, SUSTAIN, UNCERTAIN }
data class PianoRecognitionFrame(
    val frameIndex: Long,
    val audioTimeSeconds: Double,
    val availableAudioTimeSeconds: Double,
    val onsetMidi: Set<Int>,
    val activeMidi: Set<Int>,
    val notes: List<PianoNoteEvidence>,
    val state: RecognitionState,
    val clipped: Boolean,
    val computeMs: Double,
    val rawOnsetScores: FloatArray,
    val rawFrameScores: FloatArray,
    val modelWindowMs: Double = 1120.0,
    val offsetSupported: Boolean = false,
    val pedalState: String = "UNKNOWN",
    val uncertainMidi: Set<Int> = emptySet(),
    val candidateRejections: Map<Int, String> = emptyMap(),
)
enum class RecognitionEngineStatus { IDLE, LOADING, WARMING_UP, RUNNING, ERROR, CLOSED }
data class RecognitionEngineSnapshot(
    val status: RecognitionEngineStatus = RecognitionEngineStatus.IDLE,
    val message: String = "识音未启动",
    val frameCount: Long = 0,
    val computeMs: Double? = null,
    val modelSha256: String = PianoModel.MODEL_SHA256,
    val accuracyValidated: Boolean = false,
)