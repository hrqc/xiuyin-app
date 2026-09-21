package com.xiuyin.app.midi

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

data class MidiNote(val track: Int, val channel: Int, val midi: Int, val velocity: Int,
    val onTick: Long, val offTick: Long, val soundingOffTick: Long?)
data class MidiTempo(val tick: Long, val microsecondsPerQuarter: Int)
data class MidiMeter(val tick: Long, val numerator: Int, val denominator: Int)
data class MidiKey(val tick: Long, val fifths: Int, val minor: Boolean)
data class MidiPedal(val tick: Long, val channel: Int, val value: Int)
data class MidiMessage(val tick: Long, val track: Int, val order: Int, val status: Int, val data1: Int, val data2: Int?)
data class MidiDocument(val format: Int, val ppq: Int, val trackNames: Map<Int, String>,
    val notes: List<MidiNote>, val tempos: List<MidiTempo>, val meters: List<MidiMeter>,
    val keys: List<MidiKey>, val pedals: List<MidiPedal>, val messages: List<MidiMessage>,
    val endTick: Long, val warnings: List<String>)

/** Strict SMF 0/1 PPQ reader. This path never invokes OMR or infers notes from a picture. */
object MidiCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun parse(bytes: ByteArray): MidiDocument {
        require(bytes.size in 14..MAX_BYTES) { "MIDI 文件大小无效，支持最多 16 MiB" }
        val input = Cursor(bytes)
        require(input.tag() == "MThd") { "文件不是 Standard MIDI File" }
        val headerLength = input.u32().toInt()
        require(headerLength in 6..1024 && headerLength <= input.remaining) { "MIDI 头长度无效" }
        val format = input.u16(); val tracks = input.u16(); val division = input.u16()
        require(format in 0..1) { "仅支持 SMF 0/1；Type 2 独立序列不能自动合并" }
        require(tracks in 1..128 && (format != 0 || tracks == 1)) { "MIDI 轨道数量无效" }
        require((division and 0x8000) == 0 && division > 0) { "SMPTE 时间格式暂不支持；不会擅自量化" }
        input.skip(headerLength - 6)
        val messages = mutableListOf<MidiMessage>()
        val tempos = mutableListOf<MidiTempo>()
        val meters = mutableListOf<MidiMeter>()
        val keys = mutableListOf<MidiKey>()
        val names = mutableMapOf<Int, String>()
        val warnings = linkedSetOf<String>()
        var finalTick = 0L
        repeat(tracks) { track ->
            checkCancelled()
            require(input.tag() == "MTrk") { "MIDI 缺少 MTrk 轨道" }
            val length = input.u32()
            require(length <= input.remaining && length <= MAX_BYTES) { "MIDI 轨道长度越界" }
            val end = input.position + length.toInt()
            var tick = 0L; var running = -1; var order = 0; var eot = false
            while (input.position < end) {
                if (order % 1024 == 0) checkCancelled()
                tick = Math.addExact(tick, input.vlq(end))
                require(tick <= 10_000_000_000L && ++order <= 1_000_000) { "MIDI 时间或事件数量超过安全范围" }
                val next = input.peek(end)
                val status = if ((next and 0x80) != 0) input.u8(end) else {
                    require(running in 0x80..0xef) { "MIDI running status 没有有效前导状态" }; running
                }
                when {
                    status in 0x80..0xef -> {
                        running = status
                        val first = input.dataByte(end)
                        val second = if ((status and 0xf0) in listOf(0xc0, 0xd0)) null else input.dataByte(end)
                        messages += MidiMessage(tick, track, order, status, first, second)
                        require(messages.size <= 500_000) { "MIDI 消息数量过多" }
                    }
                    status == 0xff -> {
                        running = -1
                        val type = input.u8(end); val size = input.vlq(end)
                        require(size <= end - input.position) { "MIDI meta 长度越界" }
                        val payload = input.take(size.toInt(), end)
                        when (type) {
                            0x2f -> { require(payload.isEmpty() && input.position == end) { "End of Track 必须完整且位于轨道结尾" }; eot = true }
                            0x03 -> { require(payload.size <= 16_384) { "MIDI 轨道名称过长" }; names[track] = String(payload, StandardCharsets.UTF_8).filterNot(Char::isISOControl) }
                            0x51 -> {
                                require(payload.size == 3) { "MIDI 速度事件长度无效" }
                                val value = ((payload[0].toInt() and 255) shl 16) or ((payload[1].toInt() and 255) shl 8) or (payload[2].toInt() and 255)
                                require(value > 0) { "MIDI 速度不能为零" }; tempos += MidiTempo(tick, value)
                            }
                            0x58 -> {
                                require(payload.size == 4) { "MIDI 拍号事件长度无效" }
                                val numerator = payload[0].toInt() and 255; val power = payload[1].toInt() and 255
                                require(numerator in 1..128 && power in 0..7) { "MIDI 拍号超出支持范围" }
                                meters += MidiMeter(tick, numerator, 1 shl power)
                            }
                            0x59 -> {
                                require(payload.size == 2 && payload[0].toInt() in -7..7 && payload[1].toInt() in 0..1) { "MIDI 调号事件无效" }
                                keys += MidiKey(tick, payload[0].toInt(), payload[1].toInt() == 1)
                            }
                        }
                    }
                    status == 0xf0 || status == 0xf7 -> {
                        running = -1; val size = input.vlq(end)
                        require(size <= end - input.position) { "MIDI SysEx 长度越界" }; input.skip(size.toInt())
                        warnings += "SysEx 保留在不可变原始 MIDI；数字乐谱不执行设备专用指令"
                    }
                    else -> error("MIDI 中出现不支持的系统状态 0x${status.toString(16)}")
                }
            }
            require(eot) { "MIDI 轨道缺少 End of Track；未猜测截断音符" }
            finalTick = maxOf(finalTick, tick)
        }
        require(input.remaining == 0) { "MIDI 文件含未解释的尾随数据" }
        fun <T> normalize(values: List<T>, tick: (T) -> Long, label: String): List<T> {
            val grouped = values.groupBy(tick)
            require(grouped.values.all { it.distinct().size == 1 }) { "多轨在同一时刻给出冲突的 $label" }
            return grouped.toSortedMap().values.map { it.first() }
        }
        val tempoMap = normalize(tempos, MidiTempo::tick, "速度")
        val meterMap = normalize(meters, MidiMeter::tick, "拍号")
        val keyMap = normalize(keys, MidiKey::tick, "调号")
        val ordered = messages.sortedWith(compareBy<MidiMessage> { it.tick }.thenBy { it.track }.thenBy { it.order })
        val active = mutableMapOf<Pair<Int, Int>, ArrayDeque<StartedNote>>()
        val pedalDown = BooleanArray(16)
        val released = Array(16) { mutableListOf<Int>() }
        val notes = mutableListOf<MidiNote>()
        val pedals = mutableListOf<MidiPedal>()
        ordered.forEachIndexed { messageIndex, message ->
            if (messageIndex % 1024 == 0) checkCancelled()
            val channel = message.status and 15
            val kind = message.status and 0xf0
            val second = message.data2 ?: 0
            when {
                kind == 0x90 && second > 0 -> {
                    val queue = active.getOrPut(channel to message.data1) { ArrayDeque() }
                    if (queue.isNotEmpty()) warnings += "存在同通道同音高叠音，按实际消息先入先出配对；请核对原始演奏"
                    queue.addLast(StartedNote(message.track, message.tick, second))
                }
                kind == 0x80 || (kind == 0x90 && second == 0) -> {
                    val queue = active[channel to message.data1]
                    require(queue != null && queue.isNotEmpty()) { "Note Off 缺少对应 Note On，未伪造起音" }
                    val start = queue.removeFirst()
                    val note = MidiNote(start.track, channel, message.data1, start.velocity, start.tick, message.tick,
                        if (pedalDown[channel]) null else message.tick)
                    if (start.track != message.track) warnings += "存在跨轨 Note Off，已按通道和音高匹配，原始轨道保留"
                    if (start.tick == message.tick) warnings += "存在零时值 MIDI 音符，数字乐谱以零时值事件保留，不自动加长"
                    notes += note; if (pedalDown[channel]) released[channel] += notes.lastIndex
                    require(notes.size <= 50_000) { "MIDI 音符超过 50000 个" }
                }
                kind == 0xb0 && message.data1 == 64 -> {
                    pedals += MidiPedal(message.tick, channel, second)
                    val nextDown = second >= 64
                    if (pedalDown[channel] && !nextDown) {
                        released[channel].forEach { index -> notes[index] = notes[index].copy(soundingOffTick = message.tick) }
                        released[channel].clear()
                    }
                    pedalDown[channel] = nextDown
                }
                kind == 0xb0 && message.data1 == 121 -> {
                    released[channel].forEach { index -> notes[index] = notes[index].copy(soundingOffTick = message.tick) }
                    released[channel].clear(); pedalDown[channel] = false
                    warnings += "Reset All Controllers 在真实消息位置释放踏板；原始控制器消息保留"
                }
                kind == 0xb0 && message.data1 in listOf(120, 123) -> {
                    // These commands are real note releases, not an assumed file-end release.
                    active.filterKeys { it.first == channel }.forEach { (key, queue) ->
                        while (queue.isNotEmpty()) {
                            val start = queue.removeFirst()
                            notes += MidiNote(start.track, channel, key.second, start.velocity, start.tick, message.tick,
                                if (message.data1 == 120 || !pedalDown[channel]) message.tick else null)
                            if (notes.last().soundingOffTick == null) released[channel] += notes.lastIndex
                        }
                    }
                    if (message.data1 == 120) { released[channel].forEach { index -> notes[index] = notes[index].copy(soundingOffTick = message.tick) }; released[channel].clear() }
                }
                kind == 0xe0 && (message.data1 != 0 || second != 64) -> warnings += "原始 MIDI 含弯音；消息已保存，数字乐谱音高采用真实 Note On 键号"
            }
            require(notes.size <= 50_000) { "MIDI 音符超过 50000 个" }
        }
        require(active.values.all { it.isEmpty() }) { "文件结束仍有未关闭 Note On，未猜测音符时长" }
        require(notes.isNotEmpty()) { "MIDI 中没有完整音符" }
        if (notes.any { it.soundingOffTick == null }) warnings += "文件结束时踏板仍按下，声音结束时刻未知；未用固定长度替代"
        if (notes.any { it.channel == 9 }) warnings += "通道 10 是打击乐语义，不能直接等同钢琴音名"
        return MidiDocument(format, division, names, notes.sortedWith(compareBy<MidiNote> { it.onTick }.thenBy { it.track }.thenBy { it.midi }),
            if (tempoMap.firstOrNull()?.tick == 0L) tempoMap else listOf(MidiTempo(0, 500_000)) + tempoMap,
            if (meterMap.firstOrNull()?.tick == 0L) meterMap else listOf(MidiMeter(0, 4, 4)) + meterMap,
            keyMap, pedals, ordered, finalTick, warnings.toList())
    }

    internal fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MIDI 处理已取消")
    }

    private data class StartedNote(val track: Int, val tick: Long, val velocity: Int)
    private class Cursor(private val data: ByteArray) {
        var position = 0
        val remaining: Int get() = data.size - position
        fun u8(end: Int = data.size): Int { require(position < end && end <= data.size) { "MIDI 数据截断" }; return data[position++].toInt() and 255 }
        fun peek(end: Int): Int { require(position < end); return data[position].toInt() and 255 }
        fun dataByte(end: Int): Int = u8(end).also { require(it < 128) { "MIDI 数据字节包含非法状态位" } }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
        fun tag(): String = String(take(4, data.size), StandardCharsets.US_ASCII)
        fun take(length: Int, end: Int): ByteArray { require(length >= 0 && length <= end - position); return data.copyOfRange(position, position + length).also { position += length } }
        fun skip(length: Int) { require(length >= 0 && length <= remaining); position += length }
        fun vlq(end: Int): Long {
            var value = 0L
            repeat(4) { val byte = u8(end); value = (value shl 7) or (byte and 127).toLong(); if ((byte and 128) == 0) return value }
            error("MIDI VLQ 超过 4 字节")
        }
    }
}
