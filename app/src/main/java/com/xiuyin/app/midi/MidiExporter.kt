package com.xiuyin.app.midi

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreTimelineCompiler
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class MidiExportResult(val bytes: ByteArray, val ppq: Int, val warnings: List<String>)

/** Export the CURRENT score using exact rational timing; fail instead of silently quantizing. */
object MidiExporter {
    fun export(score: DigitalScore, beatsPerMinute: Int = 120): MidiExportResult {
        require(beatsPerMinute in 20..300) { "MIDI 导出速度支持 20–300 四分音符/分钟" }
        val timeline = ScoreTimelineCompiler.compile(score)
        require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "乐谱有结构错误，需修正后导出 MIDI" }
        require(score.parts.size <= 15) { "钢琴 MIDI 导出最多 15 个独立声部，不占用打击乐通道" }
        var divisor = 1L
        fun include(fraction: BeatFraction) {
            val denominator = fraction.denominator
            val gcd = java.math.BigInteger.valueOf(divisor).gcd(java.math.BigInteger.valueOf(denominator)).toLong()
            divisor = Math.multiplyExact(divisor / gcd, denominator)
            require(divisor <= 32767) { "当前精确拍时需要超过 MIDI PPQ 上限；未进行有损量化" }
        }
        include(timeline.totalBeats)
        timeline.notes.forEach { include(it.startBeat); include(it.endBeat) }
        timeline.measures.forEach { include(it.startBeat) }
        val preferred = divisor / java.math.BigInteger.valueOf(divisor).gcd(java.math.BigInteger.valueOf(480)).toLong() * 480
        val ppq = (if (preferred <= 32767) preferred else divisor).toInt()
        fun tick(beat: BeatFraction): Long {
            require(ppq.toLong() % beat.denominator == 0L)
            return Math.multiplyExact(beat.numerator, ppq.toLong() / beat.denominator)
        }
        val meta = mutableListOf<Event>()
        val micros = 60_000_000 / beatsPerMinute
        meta += Event(0, 0, byteArrayOf(0xff.toByte(), 0x51, 3, (micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
        val firstPart = score.parts.first()
        var previousTime: com.xiuyin.app.score.ScoreTime? = null
        var previousKey: Pair<Int, String?>? = null
        timeline.measures.forEach { measure ->
            val original = firstPart.measures[measure.index]
            original.time?.let { time ->
                require(time.beatType > 0 && (time.beatType and (time.beatType - 1)) == 0) { "MIDI 拍号分母必须为 2 的幂" }
                if (time != previousTime) {
                    meta += Event(tick(measure.startBeat), 1, byteArrayOf(0xff.toByte(), 0x58, 4, time.beats.toByte(), Integer.numberOfTrailingZeros(time.beatType).toByte(), 24, 8))
                    previousTime = time
                }
            }
            original.fifths?.let { key ->
                val keyAndMode = key to original.keyMode
                if (keyAndMode != previousKey) {
                    meta += Event(tick(measure.startBeat), 2, byteArrayOf(0xff.toByte(), 0x59, 2, key.toByte(), if (original.keyMode == "minor") 1 else 0))
                    previousKey = keyAndMode
                }
            }
        }
        val channels = (0..15).filter { it != 9 }
        val tracks = mutableListOf(writeTrack(meta, tick(timeline.totalBeats)))
        score.parts.forEachIndexed { index, part ->
            MidiCodec.checkCancelled()
            val channel = channels[index]
            val events = mutableListOf<Event>()
            val name = part.name.toByteArray(Charsets.UTF_8)
            val trackName = ByteArrayOutputStream().apply { write(0xff); write(3); writeVlq(this, name.size.toLong()); write(name) }.toByteArray()
            events += Event(0, 0, trackName)
            events += Event(0, 1, byteArrayOf((0xc0 or channel).toByte(), 0))
            val partNotes = timeline.notes.filter { it.partId == part.id }
            partNotes.groupBy { it.midi }.values.forEach { samePitch ->
                var previousEnd = BeatFraction.ZERO
                samePitch.sortedWith(compareBy<com.xiuyin.app.score.MidiNoteSpan> { it.startBeat }.thenBy { it.duration }).forEach { note ->
                    require(note.startBeat >= previousEnd) { "同声部同音高存在重叠，单 MIDI 通道不能无歧义保留；请先拆分声部" }
                    previousEnd = maxOf(previousEnd, note.endBeat)
                }
            }
            partNotes.forEachIndexed { noteIndex, note ->
                if (noteIndex % 1024 == 0) MidiCodec.checkCancelled()
                val start = tick(note.startBeat); val stop = tick(note.endBeat)
                // Zero-time grace remains zero; never invent 80 ms in a notation interchange file.
                val priority = if (start == stop) 3 + noteIndex * 2 else 200_000
                events += Event(start, priority, byteArrayOf((0x90 or channel).toByte(), note.midi.toByte(), 80))
                events += Event(stop, if (start == stop) priority + 1 else 2, byteArrayOf((0x80 or channel).toByte(), note.midi.toByte(), 0))
            }
            tracks += writeTrack(events, tick(timeline.totalBeats))
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeBytes("MThd"); stream.writeInt(6); stream.writeShort(1); stream.writeShort(tracks.size); stream.writeShort(ppq)
            tracks.forEach { track -> stream.writeBytes("MTrk"); stream.writeInt(track.size); stream.write(track) }
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MidiCodec.MAX_BYTES) { "MIDI 导出超过 16 MiB" }
        val warnings = mutableListOf("导出当前数字乐谱，使用指定练习速度 $beatsPerMinute BPM、统一力度 80 和钢琴音色；不声称重建原始演奏表情")
        if (timeline.notes.any { it.grace }) warnings += "零时值装饰音以同一 tick 的 Note On/Off 保留，未擅自量化或延长"
        if (score.source.format == "MIDI") warnings += "原始 MIDI 的速度曲线、控制器和音色保存在原文件；当前修订导出只包含可编辑谱面音符与当前设置"
        return MidiExportResult(bytes, ppq, warnings)
    }

    private data class Event(val tick: Long, val priority: Int, val bytes: ByteArray)
    private fun writeTrack(events: List<Event>, end: Long): ByteArray {
        val output = ByteArrayOutputStream(); var cursor = 0L
        events.sortedWith(compareBy<Event> { it.tick }.thenBy { it.priority }).forEach { event ->
            require(event.tick >= cursor && event.tick <= end) { "MIDI 导出事件时序无效" }
            writeVlq(output, event.tick - cursor); output.write(event.bytes); cursor = event.tick
        }
        writeVlq(output, end - cursor); output.write(byteArrayOf(0xff.toByte(), 0x2f, 0))
        return output.toByteArray()
    }
    private fun writeVlq(output: ByteArrayOutputStream, value: Long) {
        require(value in 0..0x0fffffffL) { "MIDI 时间间隔超过 VLQ 上限，未截断" }
        val bytes = IntArray(4); var count = 0; var remaining = value
        bytes[count++] = (remaining and 127).toInt(); remaining = remaining shr 7
        while (remaining > 0) { bytes[count++] = ((remaining and 127) or 128).toInt(); remaining = remaining shr 7 }
        for (i in count - 1 downTo 0) output.write(bytes[i])
    }
}
