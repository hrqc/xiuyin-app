package com.xiuyin.app.field

import com.xiuyin.app.pitch.ClavierPitchFit
import com.xiuyin.app.pitch.ClavierSinglePitchReference

private val FIT_COLUMNS = listOf("startMidi", "midi", "f1Hz", "f0HzDerived", "b", "score",
    "explained", "unexplained", "gaps", "matchedPartials")

/** Full independent single-pitch evidence; compact rows preserve every field without rounding. */
internal fun ClavierSinglePitchReference.toFieldLogRecord(): Map<String, Any?> = mapOf(
    "schemaVersion" to 2, "algorithmId" to algorithmId, "status" to status.name,
    "statusDefinition" to "ALIGNED describes the audio window alignment only, not recognition correctness.",
    "inputOrigin" to inputOrigin, "inputOriginSample" to inputOriginSample,
    "sampleRateHz" to sampleRateHz, "windowSamples" to windowSamples, "hopSamples" to hopSamples,
    "a4Hz" to a4Hz, "frameAudioTimeSeconds" to frameAudioTimeSeconds,
    "windowStartSample" to windowStartSample, "windowEndSampleExclusive" to windowEndSampleExclusive,
    "windowStartSeconds" to windowStartSeconds, "windowEndSeconds" to windowEndSeconds,
    "ageSeconds" to ageSeconds, "computeMs" to computeMs,
    "fitColumns" to FIT_COLUMNS,
    "fits" to fits?.map { it.toFieldLogRow() }, "best" to best?.toFieldLogRow(), "error" to error,
    "f0Definition" to "f1Hz/sqrt(1+B); original f1 retained",
    "scoreDefinition" to "Per-start-key acoustic fitting scores, not activity probabilities or accuracy.",
    "fitsDefinition" to "Alternative hypotheses for one fundamental; not simultaneously played notes. Null is preserved.",
    "onsetSupported" to false, "polyphonySupported" to false, "pedalState" to "UNKNOWN",
    "usedForOctaveVeto" to false, "assistanceAppliedToDecision" to false,
)

private fun ClavierPitchFit.toFieldLogRow(): List<Number> = listOf(
    startMidi, midi, f1Hz, f0HzDerived, b, score, explained, unexplained, gaps, matchedPartials,
)
