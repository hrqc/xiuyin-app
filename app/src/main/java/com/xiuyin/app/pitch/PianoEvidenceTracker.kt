package com.xiuyin.app.pitch

/** PCM evidence confirmation; intentionally has no score/target argument. */
class PianoEvidenceTracker(private val config: PitchEvidenceConfig = PitchEvidenceConfig()) {
    private val active = BooleanArray(88)
    private val releaseFrames = IntArray(88)
    private val candidates = FloatArray(88)
    private val candidateFrames = IntArray(88)
    private val armed = BooleanArray(88) { true }
    private val lastAttack = LongArray(88) { -10_000 }
    private var lastIndex = -1L
    fun accept(frameIndex: Long, audioTimeSeconds: Double, availableAudioTimeSeconds: Double,
        onsetScores: FloatArray, frameScores: FloatArray, clipped: Boolean, computeMs: Double, acousticRejections: Map<Int, String> = emptyMap()): PianoRecognitionFrame {
        require(frameIndex > lastIndex && onsetScores.size == 88 && frameScores.size == 88)
        require(onsetScores.all { it.isFinite() && it in 0f..1f } && frameScores.all { it.isFinite() && it in 0f..1f })
        if (lastIndex >= 0 && frameIndex != lastIndex + 1) reset()
        lastIndex = frameIndex
        val attacks = linkedSetOf<Int>()
        val sounding = linkedSetOf<Int>()
        val pending = linkedSetOf<Int>()
        val evidence = ArrayList<PianoNoteEvidence>()
        var uncertain = clipped || acousticRejections.isNotEmpty()
        for (key in 0 until 88) {
            val onset = onsetScores[key]
            val sustain = frameScores[key]
            var confirmed = 0f
            if (clipped || acousticRejections.containsKey(key + 21)) {
                candidates[key] = 0f
                candidateFrames[key] = 0
                active[key] = false
                armed[key] = false
                releaseFrames[key] = 0
            } else {
                if (onset < REARM_THRESHOLD) armed[key] = true
                if (candidates[key] > 0f) {
                    if (sustain >= config.frameThreshold || onset >= config.onsetThreshold) {
                        candidateFrames[key]++
                        candidates[key] = maxOf(candidates[key], onset)
                        if (candidateFrames[key] >= CONFIRMATION_FRAMES) {
                            confirmed = candidates[key]
                            active[key] = true
                            lastAttack[key] = frameIndex
                            armed[key] = false
                            attacks.add(key + 21)
                            candidates[key] = 0f
                            candidateFrames[key] = 0
                        } else uncertain = true
                    } else {
                        uncertain = true
                        candidates[key] = 0f
                        candidateFrames[key] = 0
                    }
                }
                if (confirmed == 0f && candidates[key] == 0f && armed[key] && onset >= config.onsetThreshold && sustain >= config.frameThreshold &&
                    frameIndex - lastAttack[key] >= REFRACTORY_FRAMES) {
                    candidates[key] = onset
                    candidateFrames[key] = 1
                    uncertain = true
                }
                if (active[key]) {
                    releaseFrames[key] = if (sustain < RELEASE_THRESHOLD) releaseFrames[key] + 1 else 0
                    if (releaseFrames[key] >= RELEASE_FRAMES) active[key] = false
                }
                if (!active[key] && (sustain >= RELEASE_THRESHOLD || onset >= REARM_THRESHOLD)) uncertain = true
            }
            if (active[key]) sounding.add(key + 21)
            if (candidates[key] > 0f) pending.add(key + 21)
            if (active[key] || onset >= REARM_THRESHOLD || sustain >= RELEASE_THRESHOLD || confirmed > 0f) {
                evidence.add(PianoNoteEvidence(key + 21, maxOf(onset, confirmed), sustain, confirmed > 0f, active[key]))
            }
        }
        val state = when {
            clipped -> RecognitionState.UNCERTAIN
            attacks.isNotEmpty() -> RecognitionState.ATTACK
            uncertain -> RecognitionState.UNCERTAIN
            sounding.isNotEmpty() -> RecognitionState.SUSTAIN
            else -> RecognitionState.SILENCE
        }
        return PianoRecognitionFrame(frameIndex, audioTimeSeconds, availableAudioTimeSeconds, attacks, sounding,
            evidence, state, clipped, computeMs, onsetScores.copyOf(), frameScores.copyOf(), uncertainMidi = acousticRejections.keys.toSet(),
            candidateRejections = acousticRejections.toMap(), pendingOnsetMidi = pending)
    }
    fun reset() {
        active.fill(false); releaseFrames.fill(0); candidates.fill(0f); candidateFrames.fill(0); armed.fill(true); lastAttack.fill(-10_000); lastIndex = -1
    }
    companion object {
        const val CONFIRMATION_FRAMES = 3
        const val ONSET_THRESHOLD = 0.60f
        const val FRAME_THRESHOLD = 0.50f
        const val REARM_THRESHOLD = 0.35f
        const val RELEASE_THRESHOLD = 0.35f
        const val RELEASE_FRAMES = 3
        const val REFRACTORY_FRAMES = 3
    }
}
