package com.xiuyin.app.score

/** Every beat value is in quarter-note units, independent of the notated beat denominator. */
data class MidiNoteSpan(
    val midi: Int, val startBeat: BeatFraction, val duration: BeatFraction,
    val eventIds: List<String>, val partId: String, val staff: Int, val voice: String, val grace: Boolean = false,
) { val endBeat: BeatFraction get() = startBeat + duration }

data class TimedScoreChord(val startBeat: BeatFraction, val notes: List<MidiNoteSpan>)
data class TimedScoreEvent(
    val eventId: String, val partId: String, val measureId: String, val measureIndex: Int,
    val startBeat: BeatFraction, val duration: BeatFraction, val isRest: Boolean,
) { val endBeat: BeatFraction get() = startBeat + duration }

data class TimedScoreMeasure(
    val index: Int, val startBeat: BeatFraction, val duration: BeatFraction, val measureIds: List<String>,
) { val endBeat: BeatFraction get() = startBeat + duration }

data class ScoreTimeline(
    val totalBeats: BeatFraction, val notes: List<MidiNoteSpan>, val chords: List<TimedScoreChord>,
    val events: List<TimedScoreEvent>, val measures: List<TimedScoreMeasure>, val issues: List<ScoreIssue>,
) {
    /** Half-open positions avoid displaying the previous note at an exact measure boundary. */
    fun eventsAt(beat: BeatFraction): List<TimedScoreEvent> = events.filter { beat >= it.startBeat && beat < it.endBeat }
    fun measureAt(beat: BeatFraction): TimedScoreMeasure? = measures.firstOrNull { beat >= it.startBeat && beat < it.endBeat }
}

/** Exact linear score time. Grace notes retain zero notated duration and are not mandatory chord members. */
object ScoreTimelineCompiler {
    fun compile(score: DigitalScore): ScoreTimeline {
        require(score.parts.isNotEmpty()) { "乐谱没有可播放声部" }
        val count = score.parts.first().measures.size
        require(count > 0 && score.parts.all { it.measures.size == count }) { "多声部小节数量不一致，不能可靠对齐" }
        val allEvents = score.parts.flatMap { it.measures }.flatMap { it.events }
        require(allEvents.map { it.id }.distinct().size == allEvents.size) { "音符 ID 重复，不能可靠定位" }
        require(allEvents.all { it.offset >= BeatFraction.ZERO && (if (it.grace) it.duration == BeatFraction.ZERO else it.duration > BeatFraction.ZERO) }) { "音符起点或时值无效" }
        val issues = ScoreEditing.validate(score).toMutableList()
        val measures = mutableListOf<TimedScoreMeasure>()
        val events = mutableListOf<TimedScoreEvent>()
        val located = mutableListOf<LocatedEvent>()
        var cursor = BeatFraction.ZERO
        repeat(count) { index ->
            val sameMeasure = score.parts.map { it.measures[index] }
            val durations = sameMeasure.map { measure ->
                val occupied = measure.events.maxOfOrNull { it.offset + it.duration } ?: BeatFraction.ZERO
                val time = measure.time
                require(time == null || (time.beats > 0 && time.beatType > 0)) { "拍号无效，不能建立时间线" }
                val duration = if (measure.implicit || time == null) occupied else time.duration
                require(duration > BeatFraction.ZERO && occupied <= duration) { "小节为空或超过拍号，不能建立可靠时间线" }
                duration
            }
            require(durations.distinct().size == 1) { "多声部对应小节长度不同，请先校正后试听" }
            val duration = durations.first()
            measures.add(TimedScoreMeasure(index, cursor, duration, sameMeasure.map { it.id }))
            score.parts.forEach { part ->
                val measure = part.measures[index]
                measure.events.forEach { event ->
                    val location = TimedScoreEvent(event.id, part.id, measure.id, index, cursor + event.offset, event.duration, event.isRest)
                    events.add(location)
                    located.add(LocatedEvent(event, location))
                }
            }
            cursor += duration
        }
        val notes = mutableListOf<MidiNoteSpan>()
        val openTies = mutableMapOf<TieKey, MutableList<Int>>()
        fun tieIssue(locatedEvent: LocatedEvent, code: String, message: String) {
            issues.add(ScoreIssue("timeline:${locatedEvent.event.id}:$code", message, ScoreIssueSeverity.ERROR,
                locatedEvent.location.measureId, locatedEvent.event.id))
        }
        located.sortedWith(compareBy<LocatedEvent> { it.location.startBeat }.thenBy { it.event.sourceIndex }).forEach { item ->
            val event = item.event
            if (event.isRest) return@forEach
            val pitch = requireNotNull(event.pitch)
            require(pitch.step in setOf("C", "D", "E", "F", "G", "A", "B") && pitch.midi in 0..127) { "音高无效，不能试听" }
            val key = TieKey(item.location.partId, event.voice, pitch.midi)
            val open = openTies.getOrPut(key) { mutableListOf() }
            var merged: Int? = null
            if ("stop" in event.ties) {
                val candidates = open.filter { notes[it].endBeat == item.location.startBeat }
                val sameStaff = candidates.filter { notes[it].staff == event.staff }
                merged = when {
                    sameStaff.size == 1 -> sameStaff.single()
                    candidates.size == 1 -> candidates.single()
                    else -> null
                }
                if (merged == null) tieIssue(item, "stop", "延音线终点缺少连续、同音高且无歧义的起点；未合并声音")
            }
            val index = if (merged != null) {
                val old = notes[merged]
                notes[merged] = old.copy(duration = old.duration + event.duration, eventIds = old.eventIds + event.id)
                open.remove(merged)
                merged
            } else {
                notes.add(MidiNoteSpan(pitch.midi, item.location.startBeat, event.duration, listOf(event.id), item.location.partId, event.staff, event.voice, event.grace))
                notes.lastIndex
            }
            if ("start" in event.ties) open.add(index)
        }
        openTies.values.flatten().distinct().forEach { index ->
            val span = notes[index]
            val lastId = span.eventIds.last()
            val location = events.first { it.eventId == lastId }
            issues.add(ScoreIssue("timeline:$lastId:start", "延音线起点没有连续终点；仅播放已有时值", ScoreIssueSeverity.ERROR, location.measureId, lastId))
        }
        val orderedNotes = notes.sortedWith(compareBy<MidiNoteSpan> { it.startBeat }.thenBy { it.midi }.thenBy { it.partId })
        return ScoreTimeline(
            totalBeats = cursor,
            notes = orderedNotes,
            chords = orderedNotes.filterNot { it.grace }.groupBy { it.startBeat }.map { (beat, grouped) -> TimedScoreChord(beat, grouped) },
            events = events.sortedWith(compareBy<TimedScoreEvent> { it.startBeat }.thenBy { it.partId }),
            measures = measures,
            issues = issues.distinctBy { it.id },
        )
    }

    private data class LocatedEvent(val event: ScoreEvent, val location: TimedScoreEvent)
    private data class TieKey(val partId: String, val voice: String, val midi: Int)
}

