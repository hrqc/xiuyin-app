package com.xiuyin.app.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Experimental PCM envelope onset detector; not a note, pedal or correctness recognizer.
 * A candidate needs two successive qualifying frames and an 80 ms refractory interval.
 * Instantiate a new analyzer for each capture session. Call from a single audio worker.
 * Only scalar frame levels are retained during warm-up; PCM is never stored.
 */
class OnsetAnalyzer(private val sampleRate: Int = 48_000) {
    init {
        require(sampleRate in 8_000..192_000) { "采样率必须在 8000–192000 Hz 范围内" }
    }

    private var lastTimestampNanos: Long? = null
    private var previousLevelDbfs = MIN_DBFS
    private var warmupSamples = 0L
    private val warmupLevels = ArrayList<Double>()
    private var noiseFloor: Double? = null
    private var active = false
    private var silentFrames = 0
    private var candidateFrames = 0
    private var candidateLevel = MIN_DBFS
    private var candidateEvidence = 0.0
    private var lastOnsetNanos: Long? = null
    private var confirmedCount = 0

    fun process(samples: ShortArray, count: Int, timestampNanos: Long): FrameAnalysis {
        require(count in 1..samples.size) { "count 必须大于零且不超过 PCM 数组长度" }
        require(timestampNanos >= 0L) { "音频时间戳不能为负" }
        val lastTimestamp = lastTimestampNanos
        require(lastTimestamp == null || timestampNanos > lastTimestamp) { "音频时间戳必须严格递增" }

        val longestExpectedGap = max(MIN_DISCONTINUITY_MS * 1_000_000L, count * 4_000_000_000L / sampleRate)
        if (lastTimestamp != null && timestampNanos - lastTimestamp > longestExpectedGap) {
            // Separated capture fragments are not consecutive evidence. Re-estimate ambient noise.
            warmupSamples = 0L
            warmupLevels.clear()
            noiseFloor = null
            candidateFrames = 0
            silentFrames = 0
            active = false
            previousLevelDbfs = MIN_DBFS
            lastOnsetNanos = null
        }

        var sum = 0.0
        var sumSquares = 0.0
        var clipped = false
        repeat(count) { index ->
            val sample = samples[index].toInt()
            val normalized = sample / 32768.0
            sum += normalized
            sumSquares += normalized * normalized
            if (abs(sample) >= CLIP_SAMPLE_THRESHOLD) clipped = true
        }
        // Remove DC before RMS, so constant ADC offsets cannot create an onset.
        val mean = sum / count
        val rms = sqrt((sumSquares / count - mean * mean).coerceAtLeast(0.0))
        val level = (20.0 * log10(rms.coerceAtLeast(0.000001))).coerceIn(MIN_DBFS, 0.0)
        val rise = level - previousLevelDbfs
        previousLevelDbfs = level
        lastTimestampNanos = timestampNanos

        if (clipped) {
            candidateFrames = 0
            silentFrames = 0
            // After overload, wait for a new envelope rise; a loud plateau is not another key.
            if (noiseFloor != null) active = true
            return result(level, OnsetState.UNCERTAIN, 0.0, true)
        }

        if (noiseFloor == null) {
            warmupSamples += count
            warmupLevels.add(level)
            if (warmupSamples * 1_000L < sampleRate.toLong() * WARMUP_MS) {
                return result(level, OnsetState.WARMING_UP, null, false)
            }
            // Lower quintile resists occasional warm-up transients; this is not piano calibration.
            val ordered = warmupLevels.sorted()
            noiseFloor = ordered[(ordered.lastIndex * 0.2).toInt()].coerceAtLeast(MIN_NOISE_FLOOR_DBFS)
            warmupLevels.clear()
            val state = if (level < silenceThreshold()) OnsetState.SILENCE else OnsetState.UNCERTAIN
            return result(level, state, 0.0, false)
        }

        if (level < silenceThreshold()) {
            candidateFrames = 0
            silentFrames++
            if (silentFrames >= SILENCE_RELEASE_FRAMES) active = false
            // Adapt only in a quiet window, never learn a sustained note as ambient noise.
            noiseFloor = (noiseFloor!! * 0.98 + level * 0.02).coerceAtLeast(MIN_NOISE_FLOOR_DBFS)
            return result(level, OnsetState.SILENCE, 0.0, false)
        }
        silentFrames = 0
        val threshold = max(noiseFloor!! + MIN_SNR_DB, MIN_ONSET_LEVEL_DBFS)
        if (level < threshold) {
            candidateFrames = 0
            return result(level, OnsetState.UNCERTAIN, 0.0, false)
        }

        val previousOnset = lastOnsetNanos
        val refractoryPassed = previousOnset == null || timestampNanos - previousOnset >= REFRACTORY_MS * 1_000_000L
        if (candidateFrames > 0) {
            if (level >= candidateLevel - MAX_CONFIRMATION_DECAY_DB && refractoryPassed) {
                candidateFrames++
                if (candidateFrames >= CONFIRMATION_FRAMES) {
                    candidateFrames = 0
                    confirmedCount++
                    active = true
                    lastOnsetNanos = timestampNanos
                    return result(level, OnsetState.ATTACK, candidateEvidence, false)
                }
            } else {
                candidateFrames = 0
                return result(level, OnsetState.UNCERTAIN, 0.0, false)
            }
        } else if (refractoryPassed && (!active || rise >= MIN_RISE_DB)) {
            candidateFrames = 1
            candidateLevel = level
            val snrEvidence = ((level - noiseFloor!! - MIN_SNR_DB) / 24.0).coerceIn(0.0, 1.0)
            val riseEvidence = ((rise - MIN_RISE_DB) / 18.0).coerceIn(0.0, 1.0)
            candidateEvidence = (0.4 + 0.35 * snrEvidence + 0.25 * riseEvidence).coerceIn(0.0, 1.0)
            // First frame is only a candidate. It must not become a confirmed event yet.
            return result(level, OnsetState.UNCERTAIN, candidateEvidence / 2.0, false)
        }
        return result(level, if (active) OnsetState.SUSTAIN else OnsetState.UNCERTAIN, 0.0, false)
    }

    private fun silenceThreshold(): Double = max(noiseFloor!! + SILENCE_MARGIN_DB, MIN_SILENCE_LEVEL_DBFS)

    private fun result(level: Double, state: OnsetState, confidence: Double?, clipped: Boolean) =
        FrameAnalysis(level, noiseFloor, state, confidence, confirmedCount, clipped)

    companion object {
        const val WARMUP_MS = 300L
        const val CONFIRMATION_FRAMES = 2
        const val REFRACTORY_MS = 80L
        const val MIN_SNR_DB = 12.0
        const val MIN_ONSET_LEVEL_DBFS = -50.0
        const val MIN_RISE_DB = 6.0
        const val MAX_CONFIRMATION_DECAY_DB = 6.0
        const val SILENCE_MARGIN_DB = 6.0
        const val MIN_SILENCE_LEVEL_DBFS = -65.0
        const val SILENCE_RELEASE_FRAMES = 2
        const val CLIP_SAMPLE_THRESHOLD = 32_760
        const val MIN_DISCONTINUITY_MS = 100L
        private const val MIN_DBFS = -120.0
        private const val MIN_NOISE_FLOOR_DBFS = -90.0
    }
}
