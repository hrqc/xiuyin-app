package com.xiuyin.app.pitch

import kotlin.math.abs

/** Single-worker continuous PCM processing. Ending audio is never zero padded into invented evidence. */
class PianoRecognitionProcessor(private val model: PianoInferenceModel, config: PitchEvidenceConfig = PitchEvidenceConfig(), private val onFrame: (PianoRecognitionFrame) -> Unit) {
    private val resampler = Streaming48kTo16k()
    private val tracker = PianoEvidenceTracker(config)
    private val acoustic = PianoAcousticEvidence()
    private val residual = ResidualHarmonicEvidence()
    private val octaveSafety = OctaveSafetyLayer()
    private val ring = FloatArray(PianoModel.INPUT_SAMPLES)
    private val clipRing = BooleanArray(PianoModel.INPUT_SAMPLES)
    private var nextWrite = 0
    private var total16k = 0L
    private var nextWindowEnd = PianoModel.INPUT_SAMPLES.toLong()
    private var expectedInput = 0L
    private var inputOrigin: Long? = null
    private var lastPublishedFrame = -1L
    var frameCount: Long = 0; private set
    var computeMs: Double? = null; private set
    var windowCount: Long = 0; private set
    var inputSampleCount: Long = 0; private set

    fun accept(samples: ShortArray, count: Int = samples.size, firstInputSample: Long = expectedInput) {
        require(count in 1..samples.size && firstInputSample >= 0)
        if (inputOrigin == null) { inputOrigin = firstInputSample; expectedInput = firstInputSample }
        require(firstInputSample == expectedInput) { "PCM输入不连续：需要$expectedInput，收到$firstInputSample；识音已暂停" }
        expectedInput += count
        inputSampleCount += count
        val sourceClipped = (0 until count).any { abs(samples[it].toInt()) >= 32760 }
        val converted = resampler.accept(samples, count)
        for (sample in converted.samples) {
            ring[nextWrite] = sample
            clipRing[nextWrite] = sourceClipped || abs(sample) >= 1f
            nextWrite = (nextWrite + 1) % ring.size
            total16k++
            if (total16k == nextWindowEnd) {
                inferWindow()
                nextWindowEnd += STEP_SAMPLES
            }
        }
    }

    private fun inferWindow() {
        val input = FloatArray(ring.size) { ring[(nextWrite + it) % ring.size] }
        val overloaded = clipRing.any { it }
        val result = model.infer(input)
        computeMs = result.computeMs
        windowCount++
        val firstSample16k = total16k - ring.size
        val firstFrame = firstSample16k / PianoModel.HOP_SAMPLES
        val originSeconds = (checkNotNull(inputOrigin) + Streaming48kTo16k.GROUP_DELAY) / 48_000.0
        val available = originSeconds + total16k / 16_000.0 + Streaming48kTo16k.GROUP_DELAY / 48_000.0
        for (index in 0 until PianoModel.FRAME_COUNT) {
            val absoluteFrame = firstFrame + index
            if (absoluteFrame <= lastPublishedFrame) continue
            val start = index * 88
            val onsetScores = result.onsetScores.copyOfRange(start, start + 88)
            val frameScores = result.frameScores.copyOfRange(start, start + 88)
            val support = acoustic.analyze(input, index, onsetScores, frameScores)
            val independentSupport = residual.analyze(input, index * PianoModel.HOP_SAMPLES, onsetScores, frameScores)
            val frame = tracker.accept(absoluteFrame, originSeconds + (firstSample16k + index * 512) / 16_000.0,
                available, onsetScores, frameScores, overloaded, result.computeMs, support.rejected + independentSupport.rejected)
            onFrame(octaveSafety.observe(frame, support.fundamentalAmplitude))
            lastPublishedFrame = absoluteFrame
            frameCount++
        }
    }
    companion object { const val STEP_SAMPLES = 8192 }
}