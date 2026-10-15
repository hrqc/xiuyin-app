package com.xiuyin.app.pitch

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** SYNTHETIC TEST DATA: deterministic model activations prove isolation, not recognition accuracy. */
class ClavierProcessorIsolationTest {
    @Test fun enablingReferenceDoesNotChangeInputActivationsOrAnyProductionDecision() {
        val pcm = ShortArray(160_000) { (10_000 * sin(2 * PI * 440 * it / 48000.0)).toInt().toShort() }
        // Preserve the original per-submitted-block clipping semantics, including a large replay block.
        pcm[72_000] = 32767
        val disabled = run(pcm, false)
        val enabled = run(pcm, true)
        assertEquals(0L, disabled.processor.singlePitchReferenceWindowCount)
        assertEquals(1L + (pcm.size - 16384) / 2048, enabled.processor.singlePitchReferenceWindowCount)
        assertEquals(disabled.modelInputs.size, enabled.modelInputs.size)
        disabled.modelInputs.zip(enabled.modelInputs).forEach { (a, b) -> assertArrayEquals(a, b, 0f) }
        assertTrue(enabled.frames.isNotEmpty())
        assertEquals(disabled.frames.size, enabled.frames.size)
        disabled.frames.zip(enabled.frames).forEach { (baseline, diagnostic) ->
            assertNull(baseline.singlePitchReference)
            assertArrayEquals(baseline.rawOnsetScores, diagnostic.rawOnsetScores, 0f)
            assertArrayEquals(baseline.rawFrameScores, diagnostic.rawFrameScores, 0f)
            assertEquals(baseline, diagnostic.copy(singlePitchReference = null,
                rawOnsetScores = baseline.rawOnsetScores, rawFrameScores = baseline.rawFrameScores))
            val record = checkNotNull(diagnostic.singlePitchReference)
            if (record.status == ClavierReferenceStatus.ALIGNED) {
                assertTrue(record.windowEndSeconds!! <= diagnostic.audioTimeSeconds)
                assertTrue(record.ageSeconds!! in 0.0..(2048 / 48000.0))
            } else assertEquals(ClavierReferenceStatus.WARMING_UP, record.status)
        }
        assertTrue(enabled.frames.any { it.singlePitchReference!!.status == ClavierReferenceStatus.WARMING_UP })
        assertTrue(enabled.frames.any { it.singlePitchReference!!.status == ClavierReferenceStatus.ALIGNED })
    }

    private data class Run(val processor: PianoRecognitionProcessor, val modelInputs: List<FloatArray>,
        val frames: List<PianoRecognitionFrame>)

    private fun run(pcm: ShortArray, enabled: Boolean): Run {
        val modelInputs = mutableListOf<FloatArray>()
        val frames = mutableListOf<PianoRecognitionFrame>()
        val model = object : PianoInferenceModel {
            override fun infer(samples16k: FloatArray): PianoModel.Prediction {
                modelInputs.add(samples16k.copyOf())
                val onset = FloatArray(32 * 88) { if (it % 88 == 69 - 21) .99f else .05f }
                val active = FloatArray(32 * 88) { if (it % 88 == 69 - 21) .98f else .05f }
                return PianoModel.Prediction(FloatArray(32 * 88), FloatArray(32 * 88), active, onset,
                    FloatArray(32 * 88), 1.0)
            }
            override fun close() = Unit
        }
        val processor = PianoRecognitionProcessor(model, PitchEvidenceConfig(singlePitchReferenceEnabled = enabled), frames::add)
        processor.accept(pcm, firstInputSample = 17003)
        return Run(processor, modelInputs, frames)
    }
}
