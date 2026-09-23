package com.xiuyin.app.omr

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScorePitch
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime
import com.xiuyin.app.score.SourceBox
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Converts actual model semantics; no title-specific notes or fixed piece data. */
object OmrScoreAssembler {
    fun assemble(sourceId: String, byteCount: Int, pages: JSONArray): DigitalScore {
        require(pages.length() in 1..20) { "OMR 页数无效" }
        val issues = mutableListOf<ScoreIssue>()
        fun issue(id: String, message: String, event: String? = null, error: Boolean = false) {
            issues += ScoreIssue("omr:$id", message, if (error) ScoreIssueSeverity.ERROR else ScoreIssueSeverity.WARNING, eventId = event)
        }
        issue("review", "图像识谱结果须对照原图复核，尤其是连音时值与 8va/8vb 八度线；目前不能可靠自动还原八度线范围。模型分数未经准确率校准，定位框为谱表范围")
        val pageObjects = (0 until pages.length()).map(pages::getJSONObject)
        val paired = pageObjects.all { page ->
            val staffs = page.getJSONArray("staffs")
            if (staffs.length() < 2 || staffs.length() % 2 != 0) false else {
                fun middle(i: Int): Double = staffs.getJSONObject(i).getJSONArray("lines").getDouble(2)
                val inside = (0 until staffs.length() step 2).map { middle(it + 1) - middle(it) }
                val outside = (1 until staffs.length() - 1 step 2).map { middle(it + 1) - middle(it) }
                outside.isEmpty() || inside.average() < outside.average() * .95
            }
        }
        val laneCount = if (paired) 2 else 1
        if (paired) issue("layout", "依据谱表间距推断双谱表系统；请确认左右手和系统对应关系")
        if (!paired && pageObjects.any { it.getJSONArray("staffs").length() > 1 })
            issue("layout-unpaired", "多谱表未能可靠配对，当前按页面/谱表顺序排列；请对照原图确认系统、左右手和声部，不能假定配对正确")
        val observedDenominators = linkedSetOf<Int>()
        val state = Array(laneCount) { PartState(it) }
        var systemsAligned = true
        for (page in pageObjects) {
            val pageIndex = page.getInt("page")
            val staffs = page.getJSONArray("staffs")
            val systemStarts = IntArray(laneCount)
            for (staffIndex in 0 until staffs.length()) {
                if (paired && staffIndex % 2 == 0) {
                    state.forEachIndexed { index, part -> systemStarts[index] = part.measures.size }
                }
                val staff = staffs.getJSONObject(staffIndex)
                val lane = if (paired) staffIndex % 2 else 0
                val part = state[lane]
                val region = staff.getJSONArray("sourceBox")
                val box = SourceBox(pageIndex, region.getDouble(0), region.getDouble(1), region.getDouble(2) - region.getDouble(0), region.getDouble(3) - region.getDouble(1))
                if (!staff.getBoolean("eos")) issue("eos:$pageIndex:$staffIndex", "第 ${pageIndex + 1} 页谱表 ${staffIndex + 1} 达到模型长度上限，尾部可能遗漏", error = true)
                if (staff.optBoolean("secondPassDisagreement")) issue("second:$pageIndex:$staffIndex", "第 ${pageIndex + 1} 页谱表 ${staffIndex + 1} 两次真实识别有分歧，请对照原图")
                val tokens = staff.getJSONArray("tokens")
                var chordNext = false
                for (tokenIndex in 0 until tokens.length()) {
                    val token = tokens.getJSONObject(tokenIndex)
                    val rhythm = token.getString("rhythm")
                    when {
                        rhythm == "chord" -> { chordNext = true }
                        rhythm.contains("barline") || rhythm.startsWith("repeat") -> {
                            if (rhythm.startsWith("repeat")) issue("repeat:$pageIndex:$staffIndex:$tokenIndex", "重复记号已保留原始识谱结果；当前顺序需用户确认", error = true)
                            part.finishMeasure(); chordNext = false
                        }
                        rhythm.startsWith("clef_") -> {
                            val value = rhythm.removePrefix("clef_")
                            require(value.length == 2 && value[0] in "GFC" && value[1] in '1'..'5') { "模型谱号无效：$rhythm" }
                            chordNext = false
                            part.clef = ScoreClef(1, value[0].toString(), value[1].digitToInt())
                            if (part.events.isNotEmpty()) issue("clef:$pageIndex:$staffIndex:$tokenIndex", "小节内谱号变化已在原始 token 保存；显示当前谱号，请对照原图")
                        }
                        rhythm.startsWith("keySignature_") -> {
                            chordNext = false
                            part.fifths = rhythm.removePrefix("keySignature_").toInt().also { require(it in -7..7) }
                            part.accidentals.clear()
                        }
                        rhythm.startsWith("timeSignature/") -> {
                            chordNext = false
                            part.denominator = rhythm.substringAfter('/').toInt().also { require(it in listOf(1,2,3,4,6,8,12,16,32,48)); observedDenominators += it }
                        }
                        rhythm.startsWith("note_") || rhythm.startsWith("rest_") -> {
                            val id = "$sourceId:p$pageIndex:s$staffIndex:t$tokenIndex"
                            val duration = duration(rhythm.substringAfter('_'))
                            val scores = token.getJSONObject("modelScores")
                            val rest = rhythm.startsWith("rest_")
                            val confidence = if (rest) scores.getDouble("rhythm") else
                                minOf(scores.getDouble("rhythm"), scores.getDouble("pitch"), scores.getDouble("lift"))
                            if (confidence < .80) issue("low:$id", "模型分数较低，需复核音高、升降号或时值", id)
                            val pitch = if (rest) null else parsePitch(token.getString("pitch"), token.getString("lift"), part)
                            val offset = if (chordNext && part.events.isNotEmpty()) part.previousOffset else part.cursor
                            if (chordNext && part.events.isEmpty()) issue("orphan-chord:$id", "和弦连接符缺少前音符", id, error = true)
                            val event = ScoreEvent(id, offset, duration.beats, pitch, 1, "1", chordNext && part.events.isNotEmpty(),
                                duration.type, duration.dots, emptySet(), tokenIndex, staffIndex, confidence, box,
                                grace = duration.grace, tupletActual = duration.actual, tupletNormal = duration.normal)
                            part.events += event
                            if (!chordNext) part.previousOffset = offset
                            if (!duration.grace) {
                                if (!chordNext) part.cursor = offset + duration.beats
                                else if (offset + duration.beats < part.cursor) part.cursor = offset + duration.beats
                            }
                            if (token.optString("slur") !in listOf(".", "_", ""))
                                issue("slur:$id", "连奏线 ${token.getString("slur")} 已保留原始 token；模型不区分延音线，未擅自合并同音，请对照原图补充延音线", id)
                            if (token.optString("position") == "lower") issue("voice:$id", "模型标记下方声部；复杂复声部请手动确认时序", id)
                            if (token.optString("articulation") !in listOf(".", "_", ""))
                                issue("articulation:$id", "奏法 ${token.getString("articulation")} 已保留原始 token；试听暂不解释该奏法", id)
                            chordNext = false
                        }
                        rhythm in listOf("PAD", "BOS", "EOS") -> issue("special:$pageIndex:$staffIndex:$tokenIndex", "识别流出现异常序列控制符", error = true)
                        else -> issue("unsupported:$pageIndex:$staffIndex:$tokenIndex", "未解释符号 $rhythm 已保留原始结果，请手动修订", error = true)
                    }
                }
                // A page/staff break is not allowed to silently join an unfinished measure.
                if (part.events.isNotEmpty()) { issue("openbar:$pageIndex:$staffIndex", "谱表结尾没有小节线，已保留未闭合小节待复核"); part.finishMeasure() }
                if (paired && staffIndex % 2 == 1) {
                    val upperCount = state[0].measures.size - systemStarts[0]
                    val lowerCount = state[1].measures.size - systemStarts[1]
                    if (upperCount != lowerCount) {
                        systemsAligned = false
                        issue("system-alignment:$pageIndex:${staffIndex / 2}",
                            "第 ${pageIndex + 1} 页第 ${staffIndex / 2 + 1} 组上下谱表小节数不同（$upperCount / $lowerCount）；请对照原图修正小节线，未强行配对或补休止符", error = true)
                    }
                }
            }
        }
        require(state.all { it.measures.isNotEmpty() }) { "模型未生成可读取的小节；未创建空乐谱" }
        if (observedDenominators.size > 1) issue("meter-changes", "模型识别到不同拍号分母，未统一套用拍号；各小节拍号留空并阻止练习，请逐小节设置真实拍号后重新校验，原始 token 已保留")
        val denominatorVotes = state.mapNotNull { it.denominator }
        val denominator = denominatorVotes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val durationVotes = state.flatMap { it.measures }.mapNotNull { measure ->
            measure.events.filterNot { it.grace }.maxOfOrNull { it.offset + it.duration }?.takeIf { it > BeatFraction.ZERO }
        }.groupingBy { it }.eachCount()
        val dominant = durationVotes.entries.sortedWith(compareByDescending<Map.Entry<BeatFraction,Int>> { it.value }.thenByDescending { it.key }).firstOrNull()?.key
        val numerator = if (dominant != null && denominator != null) {
            val value = BeatFraction.of(dominant.numerator * denominator, dominant.denominator * 4)
            value.numerator.toInt().takeIf { value.denominator == 1L && it in 1..128 }
        } else null
        val time = if (observedDenominators.size <= 1 && numerator != null && denominator != null) ScoreTime(numerator, denominator) else null
        issue("time", if (time != null) "拍号 $time 的分子依据识别小节时值众数推断，未由图像直接识别；请对照原图确认"
            else "模型未可靠识别完整拍号，请手动设置拍号后校验")
        if (paired && state[0].measures.size != state[1].measures.size) issue("alignment", "上下谱表小节数不同，禁止直接按当前顺序严格陪练", error = true)
        state.forEach { part -> part.measures.forEach { measure ->
            if (measure.events.map { it.voice }.distinct().size > 1) issue("voices:${measure.id}",
                "同起音的不同音长或休止符已保留为独立声部；各音时值和起点未改动，请对照原图复核复声部", measure.events.firstOrNull()?.id)
        } }
        val lanes = state.map { part -> ScorePart("P${part.index + 1}", if (paired) "谱表 ${part.index + 1}" else "识别谱表",
            part.measures.map { it.copy(time = time) }) }
        // Pair within every detected system, not by the page-wide totals: opposite barline
        // omissions in two systems must never silently shift the lower staff onto other music.
        val aligned = paired && systemsAligned && lanes[0].measures.size == lanes[1].measures.size
        val compatibleKeys = !aligned || lanes[0].measures.indices.all { index ->
            lanes[0].measures[index].fifths == lanes[1].measures[index].fifths
        }
        if (!compatibleKeys) issue("staff-keys", "上下谱表调号不一致，当前数字乐谱不能在同一小节保存不同谱表调号；保留分谱结果，本版不能自动合并；可保留原件另导入校对后的 MusicXML，或在撤销手动结构修改后重新识别原图，不能仅确认警告解除此错误", error = true)
        val parts = if (aligned && compatibleKeys) listOf(ScorePart("P1", "钢琴双谱表", lanes[0].measures.indices.map { index ->
            val upper = lanes[0].measures[index]
            val lower = lanes[1].measures[index]
            upper.copy(
                clefs = upper.clefs.map { it.copy(staff = 1) } + lower.clefs.map { it.copy(staff = 2) },
                events = upper.events.map { it.copy(staff = 1) } + lower.events.map { it.copy(staff = 2) },
                declaredStaves = 2,
            )
        })) else lanes
        val score = DigitalScore(sourceId, "", parts, issues, ScoreSource("OMR_IMAGE", sourceId, byteCount))
        return score.copy(issues = ScoreEditing.validate(score))
    }

    private class PartState(val index: Int) {
        var fifths: Int? = null
        var clef: ScoreClef? = null
        var denominator: Int? = null
        var cursor = BeatFraction.ZERO
        var previousOffset = BeatFraction.ZERO
        val accidentals = mutableMapOf<String, Int>()
        var events = mutableListOf<ScoreEvent>()
        val measures = mutableListOf<ScoreMeasure>()
        fun finishMeasure() {
            if (events.isNotEmpty()) {
                val number = measures.size + 1
                measures += ScoreMeasure("omr:P${index + 1}:M$number", number.toString(), null, fifths,
                    listOfNotNull(clef), assignVoices(events), false)
            }
            events = mutableListOf(); cursor = BeatFraction.ZERO; previousOffset = BeatFraction.ZERO; accidentals.clear()
        }
    }

    /**
     * The archived upstream music_xml_generator.py groups a chord by duration and backs
     * up to its shortest duration before the next onset. Preserve those exact onsets:
     * a long note must not push the following short voice later or become an overlap
     * in that voice. Rest tokens have no print/spacer flag, so every rest is retained
     * as a real event in its own concurrent voice, never guessed away as a blank.
     */
    private fun assignVoices(events: List<ScoreEvent>): List<ScoreEvent> {
        val voiceEnds = mutableListOf<BeatFraction>()
        val assigned = mutableMapOf<String, ScoreEvent>()
        events.filterNot { it.grace }.groupBy { it.offset }.toSortedMap().forEach { (offset, onset) ->
            val groups = onset.filterNot { it.isRest }.groupBy { it.duration }.values.toMutableList()
            groups += onset.filter { it.isRest }.map { listOf(it) }
            groups.sortedWith(compareBy<List<ScoreEvent>> { it.first().duration }.thenBy { it.first().sourceIndex }).forEach { group ->
                val available = voiceEnds.indexOfFirst { it <= offset }
                val voice = if (available >= 0) available else voiceEnds.size.also { voiceEnds += BeatFraction.ZERO }
                voiceEnds[voice] = offset + group.first().duration
                group.forEachIndexed { index, event ->
                    assigned[event.id] = event.copy(voice = (voice + 1).toString(), chord = index > 0)
                }
            }
        }
        return events.map { assigned[it.id] ?: it }
    }

    private fun parsePitch(text: String, lift: String, state: PartState): ScorePitch {
        require(text.length in 2..3 && text[0] in "CDEFGAB") { "无法解释模型音高 $text" }
        val step = text[0].toString(); val octave = text.substring(1).toInt()
        val explicit = when (lift) { "#" -> 1; "##" -> 2; "b" -> -1; "bb" -> -2; "N" -> 0; "_" -> null; else -> error("音符升降号无效：$lift") }
        if (explicit != null) state.accidentals[text] = explicit
        val fifths = state.fifths ?: 0
        val altered = if (fifths >= 0) "FCGDAEB".take(fifths) else "BEADGCF".take(abs(fifths))
        val keyAlter = if (step[0] in altered) if (fifths >= 0) 1 else -1 else 0
        return ScorePitch(step, explicit ?: state.accidentals[text] ?: keyAlter, octave).also { require(it.midi in 0..127) { "识别音高超出 MIDI 范围" } }
    }

    private data class Duration(val beats: BeatFraction, val type: String?, val dots: Int, val grace: Boolean, val actual: Int?, val normal: Int?)
    private fun duration(text: String): Duration {
        require(!text.endsWith('m')) { "多小节休止需要手动展开，原始识别结果已保留" }
        val match = Regex("(\\d+)(G?)(\\.*)").matchEntire(text) ?: error("未知模型节奏 $text")
        val kern = match.groupValues[1].toInt(); val grace = match.groupValues[2].isNotEmpty(); val dots = match.groupValues[3].length
        require(kern in 0..128 && dots <= 2) { "节奏超过支持范围 $text" }
        val power = if (kern == 0) 0 else Integer.highestOneBit(kern)
        val type = when (power) { 0 -> "breve"; 1 -> "whole"; 2 -> "half"; 4 -> "quarter"; 8 -> "eighth"; 16 -> "16th"; 32 -> "32nd"; 64 -> "64th"; 128 -> "128th"; else -> null }
        val ratio = if (kern != power && kern != 0) BeatFraction.of(kern.toLong(), power.toLong()) else null
        var beats = if (kern == 0) BeatFraction.of(8) else BeatFraction.of(4, kern.toLong())
        val base = beats
        for (dot in 1..dots) beats += BeatFraction.of(base.numerator, base.denominator * (1L shl dot))
        return Duration(if (grace) BeatFraction.ZERO else beats, type, dots, grace, ratio?.numerator?.toInt(), ratio?.denominator?.toInt())
    }
}
