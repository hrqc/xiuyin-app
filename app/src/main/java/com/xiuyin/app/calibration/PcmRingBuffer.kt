package com.xiuyin.app.calibration

/** A snapshot is independent of the ring; it contains only real samples, never zero padding. */
data class PcmClip(val sampleRate: Int, val firstSample: Long, val samples: ShortArray) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
}

class PcmRingBuffer(val sampleRate: Int = 48000, seconds: Int = 5) {
    private val data: ShortArray
    private var write = 0
    private var size = 0
    private var nextSample: Long? = null
    init { require(sampleRate in 8000..192000 && seconds in 1..5); data = ShortArray(sampleRate * seconds) }
    @Synchronized fun append(samples: ShortArray, count: Int, firstInputSample: Long) {
        require(count in 0..samples.size && firstInputSample >= 0 && firstInputSample <= Long.MAX_VALUE - count)
        if (count == 0) return
        if (nextSample != null && nextSample != firstInputSample) clear()
        val start = (count - data.size).coerceAtLeast(0)
        for (index in start until count) { data[write] = samples[index]; write = (write + 1) % data.size }
        size = (size.toLong() + count).coerceAtMost(data.size.toLong()).toInt()
        nextSample = firstInputSample + count
    }
    @Synchronized fun snapshot(): PcmClip? {
        val end = nextSample ?: return null
        if (size == 0) return null
        val output = ShortArray(size) { index -> data[(write - size + data.size + index) % data.size] }
        return PcmClip(sampleRate, end - size, output)
    }
    /** Reject an expired/missing/future event instead of attaching unrelated latest audio to it. */
    @Synchronized fun snapshotAt(audioTimeSeconds: Double?): PcmClip? {
        if (audioTimeSeconds == null || !audioTimeSeconds.isFinite() || audioTimeSeconds < 0) return null
        val end = nextSample ?: return null
        val sample = kotlin.math.floor(audioTimeSeconds * sampleRate)
        if (!sample.isFinite() || sample >= Long.MAX_VALUE.toDouble()) return null
        val index = sample.toLong()
        if (size == 0 || index < end - size || index >= end) return null
        return snapshot()
    }
    @Synchronized fun clear() { data.fill(0); write = 0; size = 0; nextSample = null }
}
