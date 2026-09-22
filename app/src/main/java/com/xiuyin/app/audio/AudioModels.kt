package com.xiuyin.app.audio

enum class AudioStatus {
    IDLE, STARTING, LISTENING, STOPPING, PERMISSION_DENIED, UNAVAILABLE, ERROR, SILENCED,
}

enum class OnsetState {
    WARMING_UP, SILENCE, ATTACK, SUSTAIN, UNCERTAIN,
}

enum class FinalDecision {
    CORRECT, WRONG, MISSED, UNCERTAIN,
}

/** Multi-note interface reserved for a separately validated recognition engine. */
data class DetectedNote(val midi: Int, val confidence: Double, val onset: Boolean)

/** Ephemeral metrics only. No recorded audio is retained in this snapshot. */
data class AudioSnapshot(
    val status: AudioStatus = AudioStatus.IDLE,
    val statusMessage: String = "尚未启动",
    val deviceName: String? = null,
    val sampleRate: Int = 48_000,
    val frameSamples: Int = 1_024,
    val totalFrames: Long = 0,
    val capturedAtNanos: Long? = null,
    val inputLevelDbfs: Double? = null,
    val noiseFloorDbfs: Double? = null,
    val onsetState: OnsetState = OnsetState.WARMING_UP,
    val onsetConfidence: Double? = null,
    val onsetCount: Int = 0,
    val clipped: Boolean = false,
    val notes: List<DetectedNote> = emptyList(),
    val decision: FinalDecision = FinalDecision.UNCERTAIN,
    val decisionConfidence: Double? = null,
    val processingMs: Double? = null,
    val audioLatencyMs: Double? = null,
    val actualAudioSource: Int? = null,
    val actualAudioSourceName: String? = null,
    val unprocessedSupported: Boolean? = null,
    val actualInputRoute: String? = null,
    val routedDeviceType: Int? = null,
)

/** onsetConfidence is a heuristic evidence score, never an accuracy percentage. */
data class FrameAnalysis(
    val inputLevelDbfs: Double,
    val noiseFloorDbfs: Double?,
    val onsetState: OnsetState,
    val onsetConfidence: Double?,
    val onsetCount: Int,
    val clipped: Boolean,
)
