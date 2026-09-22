package com.xiuyin.app.pitch

/** Model activation scores, not calibrated probabilities or measured note accuracy. */
data class PianoNoteEvidence(val midi: Int, val onsetScore: Float, val frameScore: Float, val onset: Boolean, val active: Boolean)
enum class RecognitionState { WARMING_UP, SILENCE, ATTACK, SUSTAIN, UNCERTAIN }
enum class OctaveState { REAL_OCTAVE, HARMONIC_LIKELY, AMBIGUOUS_OCTAVE }

/** Measured evidence, not calibrated probabilities. Unsupported inference scores stay null. */
data class OctaveDiagnostic(
    val lowerMidi: Int, val upperMidi: Int,
    val lowerOnsetScore: Float, val upperOnsetScore: Float,
    val lowerFrameScore: Float, val upperFrameScore: Float,
    val temporalOffsetSeconds: Double?,
    val lowerFundamentalAmplitude: Double?, val upperFundamentalAmplitude: Double?,
    /** Power ratios between adjacent observed spectral frames; null without a usable earlier value. */
    val upperAttackEnergyRise: Double?, val lowerAttackEnergyRise: Double?,
    val finalState: OctaveState, val reason: String,
    val harmonicPrior: Double? = null, val independentOnsetScore: Double? = null,
    val scoreContextWeight: Double? = null, val finalConfidence: Double? = null,
)
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
    val octaveDiagnostics: List<OctaveDiagnostic> = emptyList(),
    val ambiguousOctaveMidi: Set<Int> = emptySet(),
    /** Bound at delivery under the input lifecycle guard; never inferred from expected score. */
    val fieldSessionId: String? = null, val audioStreamId: Long? = null, val engineSessionId: Long? = null,
)
enum class RecognitionEngineStatus { IDLE, LOADING, READY, WARMING_UP, RUNNING, ERROR, CLOSED }
data class RecognitionEngineSnapshot(
    val status: RecognitionEngineStatus = RecognitionEngineStatus.IDLE,
    val message: String = "识音未启动",
    val frameCount: Long = 0,
    val computeMs: Double? = null,
    val modelSha256: String = PianoModel.MODEL_SHA256,
    val accuracyValidated: Boolean = false,
    val modelLoadMs: Double? = null,
    val queueOccupancy: Int = 0, val queueCapacity: Int = 96, val maxQueueOccupancy: Int = 0,
    /** Counts rejected/discarded PCM blocks, not inferred model frames. */
    val droppedFrames: Long = 0, val droppedSamples: Long = 0, val overflowCount: Long = 0,
    val rejectedBeforeReadyFrames: Long = 0,
    val submittedBlocks: Long = 0, val processedBlocks: Long = 0,
    val submittedSamples: Long = 0, val processedSamples: Long = 0,
    val sessionId: Long = 0,
)