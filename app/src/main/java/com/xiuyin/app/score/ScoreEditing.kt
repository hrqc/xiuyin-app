package com.xiuyin.app.score

import kotlin.math.abs

sealed interface ScorePatch {
    data class ReplaceEvent(val eventId: String, val event: ScoreEvent) : ScorePatch
    data class InsertEvent(val measureId: String, val event: ScoreEvent) : ScorePatch
    data class DeleteEvent(val eventId: String) : ScorePatch
    data class UpdateMeasure(
        val measureId: String, val time: ScoreTime?, val fifths: Int?,
        val clefs: List<ScoreClef>, val declaredStaves: Int,
    ) : ScorePatch
}

data class EditResult(val score: DigitalScore, val changedEventIds: Set<String>)

/** Immutable edits. Original file provenance remains untouched; user verification is an external ledger. */
object ScoreEditing {
    fun apply(score: DigitalScore, patch: ScorePatch): EditResult {
        val allEvents = score.parts.flatMap { it.measures }.flatMap { it.events }
        require(allEvents.map { it.id }.distinct().size == allEvents.size) { "重复音符 ID，无法安全编辑" }
        val changed = linkedSetOf<String>()
        var found = 0
        val parts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                when (patch) {
                    is ScorePatch.UpdateMeasure -> if (measure.id == patch.measureId) {
                        found++
                        require(patch.declaredStaves in 1..32) { "谱表数量必须为 1–32" }
                        require(patch.fifths == null || patch.fifths in -7..7) { "调号超出支持范围" }
                        require(patch.time == null || validTime(patch.time)) { "拍号无效" }
                        require(patch.clefs.all { it.staff in 1..patch.declaredStaves }) { "谱号引用未声明谱表" }
                        measure.copy(time = patch.time, fifths = patch.fifths, clefs = patch.clefs.toList(), declaredStaves = patch.declaredStaves)
                    } else measure
                    is ScorePatch.InsertEvent -> if (measure.id == patch.measureId) {
                        found++
                        require(allEvents.none { it.id == patch.event.id }) { "新增音符 ID 已存在" }
                        requireEvent(patch.event)
                        changed.add(patch.event.id)
                        val inserted = patch.event.copy(sourceIndex = -1, sourceLine = -1, sourceBox = null, confidence = null)
                        measure.copy(events = (measure.events + inserted).sortedBy { it.offset })
                    } else measure
                    is ScorePatch.ReplaceEvent -> measure.copy(events = measure.events.map { old ->
                        if (old.id != patch.eventId) old else {
                            found++
                            require(patch.event.id == old.id) { "编辑不能改变音符稳定 ID" }
                            requireEvent(patch.event)
                            changed.add(old.id)
                            patch.event.copy(sourceIndex = old.sourceIndex, sourceLine = old.sourceLine, sourceBox = old.sourceBox, confidence = old.confidence)
                        }
                    })
                    is ScorePatch.DeleteEvent -> measure.copy(events = measure.events.filterNot { event ->
                        (event.id == patch.eventId).also { matches -> if (matches) { found++; changed.add(event.id) } }
                    })
                }
            })
        }
        require(found == 1) { "编辑目标不存在或 ID 不唯一" }
        val edited = score.copy(parts = parts)
        return EditResult(edited.copy(issues = validate(edited)), changed)
    }

    fun writtenDuration(type: String, dots: Int): BeatFraction {
        require(dots in 0..4) { "本版附点数量支持 0–4" }
        val base = when (type) {
            "maxima" -> BeatFraction.of(32); "long" -> BeatFraction.of(16); "breve" -> BeatFraction.of(8)
            "whole" -> BeatFraction.of(4); "half" -> BeatFraction.of(2); "quarter" -> BeatFraction.of(1)
            "eighth" -> BeatFraction.of(1, 2); "16th" -> BeatFraction.of(1, 4); "32nd" -> BeatFraction.of(1, 8)
            "64th" -> BeatFraction.of(1, 16); "128th" -> BeatFraction.of(1, 32); "256th" -> BeatFraction.of(1, 64)
            "512th" -> BeatFraction.of(1, 128); "1024th" -> BeatFraction.of(1, 256)
            else -> throw IllegalArgumentException("未支持音符类型 $type")
        }
        var result = base
        for (dot in 1..dots) result += BeatFraction.of(base.numerator, base.denominator * (1L shl dot))
        return result
    }

    /** One source of truth for editor recomputation and validation; units are quarter notes. */
    fun notatedDuration(type: String, dots: Int, tupletActual: Int? = null,
        tupletNormal: Int? = null, grace: Boolean = false): BeatFraction {
        require((tupletActual == null) == (tupletNormal == null)) { "连音比例必须同时提供；普通音符请同时清空" }
        require(tupletActual == null || tupletActual in 1..32) { "连音实际数量必须为 1–32" }
        require(tupletNormal == null || tupletNormal in 1..32) { "连音正常数量必须为 1–32" }
        val base = writtenDuration(type, dots)
        if (grace) return BeatFraction.ZERO
        return if (tupletActual != null && tupletNormal != null)
            BeatFraction.of(base.numerator * tupletNormal, base.denominator * tupletActual) else base
    }

    fun pitchFromMidi(midi: Int, preferFlats: Boolean = false): ScorePitch {
        require(midi in 0..127) { "音高超出 MIDI 0–127 范围" }
        val names = if (preferFlats) listOf("C", "D", "D", "E", "E", "F", "G", "G", "A", "A", "B", "B")
            else listOf("C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B")
        val alterations = if (preferFlats) listOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)
            else listOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return ScorePitch(names[midi % 12], alterations[midi % 12], midi / 12 - 1)
    }

    /** targetFifths selects the spelling of the first known key; later modulations are preserved. */
    fun transpose(score: DigitalScore, semitones: Int, targetFifths: Int? = null): EditResult {
        require(semitones in -127..127) { "移调半音数超出范围" }
        require(targetFifths == null || targetFifths in -7..7) { "目标调号超出支持范围" }
        val measures = score.parts.flatMap { it.measures }
        val baseKey = measures.firstNotNullOfOrNull { it.fifths }
        if (targetFifths != null) {
            require(baseKey != null) { "原谱缺少调号，不能确定目标调的移调关系" }
            require(Math.floorMod((targetFifths - baseKey) * 7, 12) == Math.floorMod(semitones, 12)) { "目标调与半音移调数不一致" }
        }
        val chosenBase = baseKey?.let { targetFifths ?: transposeKey(it, semitones) }
        val changed = linkedSetOf<String>()
        val parts = score.parts.map { part -> part.copy(measures = part.measures.map { measure ->
            val newKey = measure.fifths?.let { old ->
                if (chosenBase == null) transposeKey(old, semitones)
                else (-7..7).filter { Math.floorMod(it - old - (chosenBase - baseKey), 12) == 0 }
                    .minByOrNull { abs(it - (old + chosenBase - baseKey)) }
                    ?: throw IllegalArgumentException("移调后调号无法表示")
            }
            val shift = if (measure.fifths != null && newKey != null) {
                val letterDelta = Math.floorMod(4 * newKey, 7) - Math.floorMod(4 * measure.fifths, 7)
                (-80..80).filter { Math.floorMod(it - letterDelta, 7) == 0 }
                    .minBy { abs(it - semitones * 7.0 / 12.0) }
            } else null
            measure.copy(fifths = newKey, events = measure.events.map eventMap@{ event ->
                val pitch = event.pitch ?: return@eventMap event
                requirePitch(pitch)
                val midi = pitch.midi + semitones
                require(midi in 0..127) { "${event.id} 移调后超出 MIDI 音高范围" }
                val transposed = if (shift == null) {
                    if (Math.floorMod(semitones, 12) == 0) pitch.copy(octave = pitch.octave + semitones / 12)
                    else pitchFromMidi(midi, (newKey ?: 0) < 0)
                } else {
                    val stepNumber = pitch.octave * 7 + STEPS.indexOf(pitch.step) + shift
                    val step = STEPS[Math.floorMod(stepNumber, 7)]
                    val octave = Math.floorDiv(stepNumber, 7)
                    val natural = ScorePitch(step, 0, octave)
                    ScorePitch(step, midi - natural.midi, octave).also(::requirePitch)
                }
                if (transposed != pitch) changed.add(event.id)
                event.copy(pitch = transposed)
            })
        }) }
        val edited = score.copy(parts = parts)
        return EditResult(edited.copy(issues = validate(edited)), changed)
    }

    private fun transposeKey(old: Int, semitones: Int): Int = if (Math.floorMod(semitones, 12) == 0) old else (-7..7)
        .filter { Math.floorMod((it - old) * 7, 12) == Math.floorMod(semitones, 12) }
        .minWith(compareBy<Int> { abs(it) }.thenBy { if (old < 0) it else -it })

    /** Recompute structural issues; never erase source-only or unsupported-feature warnings. */
    fun validate(score: DigitalScore): List<ScoreIssue> {
        val sourceIssues = score.issues.filterNot { it.id.startsWith(PREFIX) || recomputableImportIssue(it) }
        val computed = linkedMapOf<String, ScoreIssue>()
        fun issue(code: String, message: String, measure: ScoreMeasure? = null, event: ScoreEvent? = null, warning: Boolean = false) {
            val id = "$PREFIX${measure?.id.orEmpty()}:${event?.id.orEmpty()}:$code"
            computed[id] = ScoreIssue(id, message, if (warning) ScoreIssueSeverity.WARNING else ScoreIssueSeverity.ERROR, measure?.id, event?.id)
        }
        val partIds = hashSetOf<String>()
        val measureIds = hashSetOf<String>()
        val eventIds = hashSetOf<String>()
        if (score.parts.isEmpty()) issue("parts", "乐谱没有声部")
        score.parts.forEach { part ->
            if (!partIds.add(part.id) || part.id.isBlank()) issue("part:${part.id}", "声部 ID 缺失或重复")
            if (part.measures.isEmpty()) issue("empty:${part.id}", "声部没有小节")
            part.measures.forEach { measure ->
                if (!measureIds.add(measure.id) || measure.id.isBlank()) issue("measure-id", "小节 ID 缺失或重复", measure)
                val time = measure.time
                val expected = if (time != null && validTime(time)) time.duration else null
                if (expected == null) issue("time", "缺少或无效拍号，无法验证完整时值", measure)
                if (measure.fifths != null && measure.fifths !in -7..7) issue("key", "调号超出支持范围", measure)
                if (measure.declaredStaves !in 1..32) issue("staves", "声明谱表数量无效", measure)
                if (measure.clefs.map { it.staff }.distinct().size != measure.clefs.size) issue("clef-duplicate", "同一谱表存在多个谱号", measure)
                if (measure.clefs.any { it.staff !in 1..measure.declaredStaves }) issue("clef-staff", "谱号引用未声明的谱表", measure)
                if (measure.events.isEmpty()) issue("empty", "小节没有音符或休止符", measure)
                val usable = measure.events.filter { event ->
                    if (!eventIds.add(event.id) || event.id.isBlank()) issue("event-id", "音符 ID 缺失或重复", measure, event)
                    val valid = runCatching { requireEvent(event) }.isSuccess
                    if (!valid) issue("event-value", "音符音高、时值、声部或记谱值无效", measure, event)
                    if (event.staff !in 1..measure.declaredStaves) issue("staff", "音符引用未声明的谱表", measure, event)
                    if (measure.clefs.none { it.staff == event.staff }) issue("clef", "音符所在谱表没有谱号", measure, event, warning = true)
                    if (event.type != null && valid && !event.grace) {
                        val written = runCatching {
                            notatedDuration(event.type, event.dots, event.tupletActual, event.tupletNormal)
                        }.getOrNull()
                        if (written == null || written != event.duration) issue("written", "记谱类型/附点与实际时值不一致", measure, event)
                    }
                    valid
                }
                usable.filterNot { it.grace }.groupBy { it.staff to it.voice }.forEach { (lane, events) ->
                    var end = BeatFraction.ZERO
                    events.groupBy { it.offset }.toSortedMap().forEach { (offset, group) ->
                        val first = group.first()
                        if (offset > end) issue("gap:${lane}:${offset}", "声部存在未记谱空隙", measure, first, warning = true)
                        if (offset < end) issue("overlap:${lane}:${offset}", "声部存在时值重叠", measure, first)
                        if (group.count { !it.chord } != 1) issue("chord:${lane}:${offset}", "和弦必须具有唯一前导音符", measure, first)
                        if (group.size > 1 && group.any { it.isRest }) issue("rest-chord", "休止符不能与同声部音符合并为和弦", measure, first)
                        end = maxOf(end, group.maxOf { it.offset + it.duration })
                    }
                    if (expected != null && end > expected) issue("overfull:$lane", "声部时值超过拍号要求", measure)
                    if (expected != null && end < expected && !measure.implicit) issue("underfull:$lane", "声部时值不足拍号要求；未自动补休止符", measure, warning = true)
                }
            }
        }
        return (sourceIssues + computed.values).distinctBy { it.id }
    }

    private fun recomputableImportIssue(issue: ScoreIssue): Boolean {
        if (!issue.id.startsWith("issue-") || issue.measureId == null) return false
        val text = issue.message
        return text == "小节没有音符或休止符" || text.startsWith("缺少拍号，无法校验") ||
            text.startsWith("谱号编号超出声明谱表数量") || text.startsWith("音符引用谱表 ") ||
            (text.startsWith("谱表 ") && listOf("未声明谱号", "未记谱空隙", "时值重叠", "超过拍号要求", "不足拍号要求").any { it in text }) ||
            text == "同声部多个起音重叠，未声明和弦" ||
            (text.startsWith("记谱类型 ") && "与 duration=" in text) || text.startsWith("未支持音符类型 ")
    }

    private fun validTime(time: ScoreTime): Boolean = time.beats in 1..128 && time.beatType in 1..1024 && (time.beatType and (time.beatType - 1)) == 0
    private fun requirePitch(pitch: ScorePitch) {
        require(pitch.step in STEPS && pitch.alter in -2..2 && pitch.octave in -1..9) { "音高拼写超出支持范围" }
        require(pitch.midi in 0..127) { "音高超出 MIDI 0–127 范围" }
    }
    private fun requireEvent(event: ScoreEvent) {
        require(event.id.isNotBlank() && event.offset >= BeatFraction.ZERO && (if (event.grace) event.duration == BeatFraction.ZERO else event.duration > BeatFraction.ZERO)) { "音符 ID、起点或时值无效" }
        require(event.staff in 1..32 && event.voice.isNotBlank() && event.voice.length <= 64) { "音符谱表或声部无效" }
        require(event.dots in 0..4 && event.ties.all { it == "start" || it == "stop" }) { "附点或延音线值无效" }
        require(!event.isRest || (event.ties.isEmpty() && !event.chord)) { "休止符不能使用延音线或和弦标记" }
        require(!event.grace || (!event.isRest && event.ties.isEmpty())) { "装饰音必须有音高且不使用延音线" }
        require((event.tupletActual == null) == (event.tupletNormal == null)) { "连音比例必须同时提供" }
        require(event.tupletActual == null || event.tupletActual in 1..32) { "连音实际数量无效" }
        require(event.tupletNormal == null || event.tupletNormal in 1..32) { "连音正常数量无效" }
        event.pitch?.let(::requirePitch)
    }
    private const val PREFIX = "validation:"
    private val STEPS = listOf("C", "D", "E", "F", "G", "A", "B")
}


