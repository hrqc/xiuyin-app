package com.xiuyin.app.score

/** Explicit human request, bound to a saved revision. Never an automatic 8va interpretation. */
data class ScoreOctaveRangeRequest(
    val scoreId: String, val generation: Long, val startEventId: String,
    val endEventId: String, val octaves: Int,
) {
    fun requireCurrent(currentScoreId: String?, currentGeneration: Long) {
        require(scoreId == currentScoreId && generation == currentGeneration) {
            "乐谱或修订已变化，旧八度范围未应用；请重新选择并预览"
        }
    }
}

data class ScoreRangeEndpoint(
    val eventId: String, val partId: String, val partName: String,
    val measureIndex: Int, val measureNumber: String, val event: ScoreEvent,
) {
    val label: String get() = "第 $measureNumber 小节 · 起点 ${event.offset} · ${event.pitch?.displayName ?: "休止符"}"
}

data class ScoreOctaveRangePreview(
    val start: ScoreRangeEndpoint, val end: ScoreRangeEndpoint, val octaves: Int,
    val changedEventIds: Set<String>, val skippedRests: Int,
)

object ScoreRangeEditing {
    fun endpoints(score: DigitalScore): List<ScoreRangeEndpoint> = score.parts.flatMap { part ->
        part.measures.flatMapIndexed { index, measure ->
            measure.events.map { ScoreRangeEndpoint(it.id, part.id, part.name, index, measure.number, it) }
                .sortedWith(compareBy<ScoreRangeEndpoint> { it.event.offset }.thenBy { it.event.sourceIndex }.thenBy { it.eventId })
        }
    }

    /** Inclusive onset bounds: chord members at either boundary are kept together. */
    fun preview(score: DigitalScore, startEventId: String, endEventId: String, octaves: Int): ScoreOctaveRangePreview {
        require(octaves == -1 || octaves == 1) { "每次只可升或降一个八度" }
        val events = endpoints(score)
        require(score.parts.map { it.id }.distinct().size == score.parts.size &&
            events.map { it.eventId }.distinct().size == events.size) { "声部或音符 ID 重复，无法安全选择范围" }
        val start = requireNotNull(events.singleOrNull { it.eventId == startEventId }) { "起始音符不存在" }
        val end = requireNotNull(events.singleOrNull { it.eventId == endEventId }) { "结束音符不存在" }
        require(!start.event.isRest && !end.event.isRest) { "请以有音高的音符作为起止位置；范围内休止符会保留" }
        require(start.partId == end.partId && start.event.staff == end.event.staff && start.event.voice == end.event.voice) {
            "起止音符必须位于同一乐器声部、谱表和 voice；其他手与声部不能一起修改"
        }
        fun before(a: ScoreRangeEndpoint, b: ScoreRangeEndpoint): Boolean = a.measureIndex < b.measureIndex ||
            (a.measureIndex == b.measureIndex && a.event.offset < b.event.offset)
        require(!before(end, start)) { "结束音符不能早于起始音符" }
        val selected = events.filter { it.partId == start.partId && it.event.staff == start.event.staff &&
            it.event.voice == start.event.voice && !before(it, start) && !before(end, it) }
        val notes = selected.filterNot { it.event.isRest }
        notes.forEach { located ->
            val pitch = requireNotNull(located.event.pitch)
            require(pitch.midi + octaves * 12 in 0..127 && pitch.octave + octaves in -1..9) {
                "${located.label} 修改后超出音高范围；整个范围未修改"
            }
        }
        return ScoreOctaveRangePreview(start, end, octaves, notes.map { it.eventId }.toSet(), selected.count { it.event.isRest })
    }

    fun shiftOctave(score: DigitalScore, startEventId: String, endEventId: String, octaves: Int): EditResult {
        val plan = preview(score, startEventId, endEventId, octaves)
        val changed = score.copy(parts = score.parts.map { part -> part.copy(measures = part.measures.map { measure ->
            measure.copy(events = measure.events.map { event ->
                if (event.id !in plan.changedEventIds) event else event.copy(pitch = requireNotNull(event.pitch).let {
                    it.copy(octave = it.octave + octaves)
                })
            })
        }) })
        return EditResult(changed.copy(issues = ScoreEditing.validate(changed)), plan.changedEventIds)
    }
}
