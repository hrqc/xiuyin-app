package com.xiuyin.app.calibration

import com.xiuyin.app.pitch.PitchEvidenceConfig

data class PianoCalibrationProfile(
    val createdAtMillis: Long,
    val deviceName: String?,
    val sampleRate: Int,
    val observations: Int,
    val observedSeconds: Double,
    val noiseFloorDbfs: Double?,
    val meanInputDbfs: Double?,
    val peakInputDbfs: Double?,
    val meanOnsetScore: Double?,
    val recognitionObservations: Int,
    val parameters: PitchEvidenceConfig = PitchEvidenceConfig(),
    val latencyMs: Double? = null,
    val reverbSeconds: Double? = null,
    val pedalResidualSeconds: Double? = null,
) {
    fun environmentWarning(noiseDbfs: Double?, inputDbfs: Double?, onsetScore: Double?): String? {
        val noiseChanged = noiseDbfs != null && noiseFloorDbfs != null && noiseDbfs - noiseFloorDbfs > 10
        val levelChanged = inputDbfs != null && meanInputDbfs != null && kotlin.math.abs(inputDbfs - meanInputDbfs) > 18
        val evidenceChanged = onsetScore != null && meanOnsetScore != null && meanOnsetScore - onsetScore > 0.20
        return if (noiseChanged || levelChanged || evidenceChanged) "当前环境与上次校准差异较大，建议重新校准。" else null
    }
}

enum class CaseTrigger { USER_DISPUTE, CONFIRMED_WRONG, LOW_EVIDENCE, FOLLOWER_ERROR, DEBUG }
data class CaseNoteEvidence(val midi: Int, val onsetScore: Double, val frameScore: Double, val onset: Boolean, val active: Boolean)
data class MisjudgmentContext(
    val trigger: CaseTrigger,
    val scoreId: String? = null,
    val measureIndex: Int? = null,
    val beat: Double? = null,
    val expectedMidi: Set<Int> = emptySet(),
    val rawDetectedMidi: Set<Int> = emptySet(),
    val notes: List<CaseNoteEvidence> = emptyList(),
    val pedalState: String = "UNKNOWN",
    val finalDecision: String = "UNCERTAIN",
    val practiceMode: String = "UNKNOWN",
    val userDisagrees: Boolean = false,
    val modelDescription: String = "未提供模型标识",
) { val isGroundTruth: Boolean get() = false }
data class MisjudgmentCase(val id: String, val createdAtMillis: Long, val context: MisjudgmentContext, val clip: PcmClip)
data class CaseSummary(val id: String, val createdAtMillis: Long, val context: MisjudgmentContext, val durationSeconds: Double, val bytes: Long)
