package com.xiuyin.app.field

/** Exactly five seconds of ephemeral mono PCM. No references to preceding streams survive reset. */
internal class FieldPcmRing(private val sampleRate: Int = 48_000) {
    private val data = ShortArray(sampleRate * 5)
    private var nextSample: Long? = null
    private var retained = 0
    @Synchronized fun append(samples: ShortArray, count: Int, firstSample: Long): Boolean {
        require(count in 1..samples.size && firstSample >= 0 && firstSample <= Long.MAX_VALUE - count)
        val continuous = nextSample == null || nextSample == firstSample
        if (!continuous) clear()
        for (index in 0 until count) data[((firstSample + index) % data.size).toInt()] = samples[index]
        retained = minOf(data.size, retained + count)
        nextSample = firstSample + count
        return continuous
    }
    @Synchronized fun endSample(): Long? = nextSample
    @Synchronized fun snapshot(start: Long, endExclusive: Long): FieldClip? {
        val end = nextSample ?: return null
        if (start < end - retained || endExclusive > end || endExclusive <= start || endExclusive - start > data.size) return null
        return FieldClip(start, ShortArray((endExclusive - start).toInt()) { data[((start + it) % data.size).toInt()] }, sampleRate)
    }
    @Synchronized fun retainedStart(): Long? = nextSample?.minus(retained)
    @Synchronized fun clear() { data.fill(0); retained = 0; nextSample = null }
}