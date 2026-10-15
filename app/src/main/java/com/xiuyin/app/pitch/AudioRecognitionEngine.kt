package com.xiuyin.app.pitch

/** Replaceable, PCM-only engine contract. Implementations never receive a target score. */
interface AudioRecognitionEngine : AutoCloseable {
    val snapshot: RecognitionEngineSnapshot
    fun start()
    fun stop()
    fun submitPcm(samples: ShortArray, count: Int, firstInputSample: Long)
    fun updateConfig(config: PitchEvidenceConfig)
    override fun close()
}

/** Profiles may make confirmation more conservative; they cannot weaken baseline evidence gates. */
data class PitchEvidenceConfig(val onsetThreshold: Float = .60f, val frameThreshold: Float = .50f,
    val singlePitchReferenceEnabled: Boolean = false) {
    init {
        require(onsetThreshold.isFinite() && onsetThreshold in .60f..0.85f)
        require(frameThreshold.isFinite() && frameThreshold in .50f..0.75f)
    }
}
