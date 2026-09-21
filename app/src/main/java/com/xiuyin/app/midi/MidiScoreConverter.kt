package com.xiuyin.app.midi

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime

/** No quantization: PPQ ticks become reduced rational quarter-note values. */
object MidiScoreConverter {
    fun convert(document: MidiDocument, sourceId: String, byteCount: Int): DigitalScore {
        val issues = document.warnings.mapIndexed { i, message -> ScoreIssue("midi:source:$i", message) }.toMutableList()
        if (document.pedals.isNotEmpty()) issues += ScoreIssue("midi:pedal", "CC64 踏板曲线与声音延续时间已独立保存；谱面时值采用真实按键释放时间，不把踏板延音伪写成音符长度")
        if (document.tempos.size > 1) issues += ScoreIssue("midi:tempo", "原始 MIDI 速度变化已保存；固定速度试听时须明确选择练习速度")
        val ppq = document.ppq.toLong()
        val end = BeatFraction.of(document.endTick, ppq)
        require(end > BeatFraction.ZERO) { "MIDI 没有正时间范围，不能构造可运行时间线" }
        val boundaries = mutableListOf<MeasureBoundary>()
        var cursor = BeatFraction.ZERO
        while (cursor < end) {
            MidiCodec.checkCancelled()
            require(boundaries.size < 20_000) { "MIDI 小节数量超过支持范围" }
            val meter = document.meters.last { BeatFraction.of(it.tick, ppq) <= cursor }
            val time = ScoreTime(meter.numerator, meter.denominator)
            val expectedEnd = cursor + time.duration
            val nextChange = document.meters.firstOrNull { BeatFraction.of(it.tick, ppq) > cursor }?.let { BeatFraction.of(it.tick, ppq) }
            val stop = minOf(expectedEnd, end, nextChange ?: end)
            require(stop > cursor)
            boundaries += MeasureBoundary(cursor, stop, time, stop < expectedEnd)
            if (nextChange != null && stop == nextChange && stop < expectedEnd)
                issues += ScoreIssue("midi:meter:${boundaries.size}", "拍号在小节中途变化，已按真实时间分开并标为不完整小节")
            cursor = stop
        }
        require(boundaries.size.toLong() * document.notes.size <= 5_000_000L) {
            "MIDI 音符/小节组合过大，请按乐章分开导入；未截断或量化"
        }
        val lanes = document.notes.withIndex().groupBy { it.value.track to it.value.channel }
        require(lanes.size in 1..128) { "MIDI 音轨/通道组合超过 128 个" }
        val parts = lanes.entries.mapIndexed { partIndex, (lane, indexedNotes) ->
            MidiCodec.checkCancelled()
            val partId = "MIDI-P${partIndex + 1}"
            val voices = mutableListOf<Long>()
            val assigned = mutableListOf<AssignedNote>()
            indexedNotes.groupBy { it.value.onTick to it.value.offTick }.entries
                .sortedWith(compareBy<Map.Entry<Pair<Long, Long>, List<IndexedValue<MidiNote>>>> { it.key.first }.thenBy { it.key.second })
                .forEach { (span, notes) ->
                    MidiCodec.checkCancelled()
                    var voice = voices.indexOfFirst { it <= span.first }
                    if (voice < 0) { voice = voices.size; voices += 0L }
                    require(voices.size <= 128) { "单通道重叠声部超过 128 个" }
                    voices[voice] = span.second
                    notes.forEachIndexed { chordIndex, note -> assigned += AssignedNote(note.index, note.value, voice + 1, chordIndex > 0) }
                }
            val clef = if (indexedNotes.map { it.value.midi }.average() < 60) ScoreClef(1, "F", 4) else ScoreClef(1, "G", 2)
            val measures = boundaries.mapIndexed { measureIndex, boundary ->
                MidiCodec.checkCancelled()
                val id = "$partId-M${measureIndex + 1}"
                val events = mutableListOf<ScoreEvent>()
                assigned.forEach { assignedNote ->
                    val note = assignedNote.note
                    val on = BeatFraction.of(note.onTick, ppq); val off = BeatFraction.of(note.offTick, ppq)
                    val zero = on == off
                    val inMeasure = if (zero) (on >= boundary.start && on < boundary.end) || (on == end && measureIndex == boundaries.lastIndex)
                        else on < boundary.end && off > boundary.start
                    if (inMeasure) {
                        val start = maxOf(on, boundary.start); val stop = minOf(off, boundary.end)
                        val duration = stop - start
                        val key = document.keys.lastOrNull { it.tick <= note.onTick }
                        val pitch = ScoreEditing.pitchFromMidi(note.midi, (key?.fifths ?: 0) < 0)
                        val ties = linkedSetOf<String>()
                        if (!zero && start > on) ties += "stop"
                        if (!zero && stop < off) ties += "start"
                        val written = notation(duration)
                        events += ScoreEvent("$sourceId:n${assignedNote.sourceIndex}:m$measureIndex", start - boundary.start, duration, pitch,
                            1, assignedNote.voice.toString(), assignedNote.chord, written?.first, written?.second ?: 0,
                            ties, assignedNote.sourceIndex, note.track, grace = zero)
                    }
                }
                // Explicit rests retain original timing, including track silence and channel gaps.
                for (voice in 1..maxOf(1, voices.size)) {
                    var voiceEnd = BeatFraction.ZERO; var restIndex = 0
                    val notes = events.filter { it.voice == voice.toString() && !it.grace }.sortedBy { it.offset }
                    fun addRest(start: BeatFraction, stop: BeatFraction) {
                        if (stop <= start) return
                        val duration = stop - start; val written = notation(duration)
                        events += ScoreEvent("$sourceId:$id:v$voice:r${restIndex++}", start, duration, null, 1, voice.toString(), false,
                            written?.first, written?.second ?: 0, emptySet(), -1, lane.first)
                    }
                    notes.forEach { note -> addRest(voiceEnd, note.offset); voiceEnd = maxOf(voiceEnd, note.offset + note.duration) }
                    addRest(voiceEnd, boundary.end - boundary.start)
                }
                val key = document.keys.lastOrNull { BeatFraction.of(it.tick, ppq) <= boundary.start }
                ScoreMeasure(id, (measureIndex + 1).toString(), boundary.time, key?.fifths, listOf(clef),
                    events.sortedWith(compareBy<ScoreEvent> { it.offset }.thenBy { it.sourceIndex }), boundary.implicit,
                    keyMode = key?.let { if (it.minor) "minor" else "major" })
            }
            ScorePart(partId, document.trackNames[lane.first]?.takeIf(String::isNotBlank) ?: "MIDI 轨 ${lane.first + 1} / 通道 ${lane.second + 1}", measures)
        }
        val title = document.trackNames[0].orEmpty()
        val score = DigitalScore(sourceId, title, parts, issues, ScoreSource("MIDI", sourceId, byteCount))
        return score.copy(issues = ScoreEditing.validate(score))
    }

    private data class AssignedNote(val sourceIndex: Int, val note: MidiNote, val voice: Int, val chord: Boolean)
    private data class MeasureBoundary(val start: BeatFraction, val end: BeatFraction, val time: ScoreTime, val implicit: Boolean)
    private fun notation(duration: BeatFraction): Pair<String, Int>? {
        for (type in listOf("maxima", "long", "breve", "whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th", "256th", "512th", "1024th"))
            for (dots in 0..2) if (ScoreEditing.writtenDuration(type, dots) == duration) return type to dots
        return null // The exact duration stays intact; it is not rounded to a convenient written value.
    }
}
