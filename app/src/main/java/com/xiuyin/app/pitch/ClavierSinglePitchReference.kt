package com.xiuyin.app.pitch

/** ALIGNED only describes the audio clock. It is never a verdict that a played note is correct. */
enum class ClavierReferenceStatus { WARMING_UP, ALIGNED, NO_ALIGNED_WINDOW, ERROR }

/** Original upstream fit fields. Score is not a probability; MIDI can differ from its starting key. */
data class ClavierPitchFit(
    val startMidi: Int,
    val midi: Int,
    val f1Hz: Double,
    val f0HzDerived: Double,
    val b: Double,
    val score: Double,
    val explained: Double,
    val unexplained: Double,
    val gaps: Double,
    val matchedPartials: Int,
)

/** Diagnostic-only single-pitch evidence. Null fits and null best retain the upstream result. */
data class ClavierSinglePitchReference(
    val status: ClavierReferenceStatus,
    val inputOriginSample: Long,
    val frameAudioTimeSeconds: Double,
    val windowStartSample: Long? = null,
    val windowEndSampleExclusive: Long? = null,
    val windowStartSeconds: Double? = null,
    val windowEndSeconds: Double? = null,
    val ageSeconds: Double? = null,
    val computeMs: Double? = null,
    val fits: List<ClavierPitchFit>? = null,
    val best: ClavierPitchFit? = null,
    val error: String? = null,
) {
    val algorithmId: String get() = "CLAVIER_KEY_IDENTIFIER_abc2bb94902a6194c2b3df69f575cb73a7d8266f"
    val sampleRateHz: Int get() = ClavierSinglePitchReferenceProcessor.SAMPLE_RATE
    val windowSamples: Int get() = ClavierSinglePitchReferenceProcessor.WINDOW_SAMPLES
    val hopSamples: Int get() = ClavierSinglePitchReferenceProcessor.HOP_SAMPLES
    val a4Hz: Double get() = ClavierSinglePitchReferenceProcessor.A4_HZ
    val inputOrigin: String get() = "PCM48K_MONO_PCM16_UNMODIFIED"
}
