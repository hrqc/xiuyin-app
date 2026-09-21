package com.xiuyin.app.score

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** A deliberately bounded MusicXML subset. This is structured parsing, never OMR. */
class MusicXmlParser {
    fun parse(bytes: ByteArray): DigitalScore = parseDocument(bytes).score

    fun parseDocument(bytes: ByteArray): ParsedScoreDocument {
        if (bytes.isEmpty()) fail("MusicXML 文件为空")
        if (bytes.size > MAX_BYTES) fail("MusicXML 文件超过 2 MiB 上限")
        if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            fail("当前仅支持未压缩 .musicxml / .xml，不支持 ZIP / MXL")
        }
        val xml = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: Exception) {
            throw MusicXmlException("当前只支持 UTF-8 编码的 MusicXML", error)
        }
        if (xml.contains('\u0000')) fail("当前只支持 UTF-8 XML，文件包含无效字符")
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            fail("安全限制：不允许 DOCTYPE、DTD 或实体声明；请导出不含 DTD 的 MusicXML")
        }
        val declaredEncoding = Regex("<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
        if (declaredEncoding != null && !declaredEncoding.equals("UTF-8", ignoreCase = true)) {
            fail("当前只支持 UTF-8 编码的 MusicXML")
        }
        val root = readTree(xml)
        if (root.name != "score-partwise") fail("当前仅支持 score-partwise MusicXML，不支持 ${root.name}")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        try {
            val score = parseScore(root, hash, bytes.size)
            val composers = root.one("identification")?.children("creator")
                ?.filter { it.attributes["type"] == "composer" }
                ?.mapNotNull { it.textValue().takeIf(String::isNotBlank) }?.distinct().orEmpty()
            return ParsedScoreDocument(
                score = score,
                originalTitle = originalTitle(root),
                composer = composers.joinToString(" / ").takeIf(String::isNotBlank),
                workNumber = root.one("work")?.one("work-number")?.textValue()?.takeIf(String::isNotBlank),
            )
        } catch (error: MusicXmlException) {
            throw error
        } catch (error: ArithmeticException) {
            throw MusicXmlException("乐谱时值超出支持范围", error)
        }
    }

    private fun parseScore(root: Node, hash: String, byteCount: Int): DigitalScore {
        val issues = mutableListOf<ScoreIssue>()
        val unsupported = setOf("grace", "cue", "unpitched", "transpose", "repeat", "ending", "time-modification", "multiple-rest", "measure-repeat", "slash", "senza-misura", "staff-tuning", "scordatura", "percussion", "octave-shift", "tremolo")
        root.walk { node ->
            if (node.name in unsupported) fail("暂不支持演奏语义 <${node.name}>（第 ${node.line} 行），未导入以避免误读")
            if (node.name == "sound" && node.attributes.keys.any { it in setOf("dacapo", "dalsegno", "tocoda", "fine", "segno", "coda", "forward-repeat") }) {
                fail("暂不支持跳转 / 反复演奏指令（第 ${node.line} 行）")
            }
        }
        val partList = root.one("part-list") ?: fail("缺少 part-list，无法确认乐器与谱表来源")
        val names = linkedMapOf<String, String>()
        partList.children("score-part").forEach { definition ->
            val id = definition.attribute("id")
            if (id in names) fail("part-list 存在重复声部 ID：$id")
            names[id] = definition.one("part-name")?.textValue().orEmpty().ifBlank { id }
        }
        if (names.isEmpty()) fail("part-list 未包含任何乐器")
        val partNodes = root.children("part")
        if (partNodes.isEmpty()) fail("乐谱没有 part 数据")
        val seenParts = mutableSetOf<String>()
        val parts = partNodes.mapIndexed { partIndex, node ->
            val id = node.attribute("id")
            if (!seenParts.add(id)) fail("存在重复 part ID：$id")
            val name = names[id] ?: fail("part $id 没有对应的 part-list 定义")
            parsePart(node, "$hash:p$partIndex", id, name, issues)
        }
        if (names.keys != seenParts) issue(issues, "部分 part-list 乐器没有对应乐谱数据", severity = ScoreIssueSeverity.ERROR)
        if (parts.sumOf { part -> part.measures.sumOf { it.events.size } } == 0) fail("MusicXML 未包含可用音符或休止符")
        root.walk { node ->
            if (node.name in setOf("direction", "ornaments", "articulations", "slur", "fermata", "arpeggiate", "non-arpeggiate", "technical", "lyric", "midi-instrument", "sound")) {
                issue(issues, "源文件含 <${node.name}>（第 ${node.line} 行）；当前仅保留原始来源，不解释其演奏或展示语义")
            }
        }
        val title = originalTitle(root) ?: "未命名 MusicXML"
        return DigitalScore(hash, title, parts, issues, ScoreSource("MusicXML", hash, byteCount))
    }

    private fun originalTitle(root: Node): String? =
        root.one("work")?.one("work-title")?.textValue()?.takeIf(String::isNotBlank)
            ?: root.one("movement-title")?.textValue()?.takeIf(String::isNotBlank)
    private fun parsePart(node: Node, prefix: String, partId: String, name: String, issues: MutableList<ScoreIssue>): ScorePart {
        var divisions: Int? = null
        var time: ScoreTime? = null
        var fifths: Int? = null
        var keyMode: String? = null
        var declaredStaves = 1
        val clefs = linkedMapOf<Int, ScoreClef>()
        val measures = node.children("measure").mapIndexed { measureIndex, measure ->
            val measureId = "$prefix:m$measureIndex"
            val number = measure.attributes["number"] ?: "${measureIndex + 1}"
            val implicit = measure.attributes["implicit"] == "yes"
            var cursor = BeatFraction.ZERO
            var anchor: ScoreEvent? = null
            var noteIndex = 0
            val events = mutableListOf<ScoreEvent>()
            measure.children.forEach { child ->
                when (child.name) {
                    "attributes" -> {
                        if (events.isNotEmpty() || cursor != BeatFraction.ZERO) fail("暂不支持小节中途改变属性（小节 $number）")
                        child.one("divisions")?.let { divisions = it.positiveInt("divisions", 1_000_000) }
                        child.one("time")?.let { value ->
                            if (value.attributes["number"] != null) fail("暂不支持同小节分谱表拍号")
                            if (value.children("beats").size != 1 || value.children("beat-type").size != 1) fail("暂不支持复合 / 交替拍号")
                            val beats = value.required("beats").positiveInt("拍数", 128)
                            val beatType = value.required("beat-type").positiveInt("拍号分母", 128)
                            if (beatType and (beatType - 1) != 0) fail("暂不支持非二进制拍号分母 $beatType")
                            time = ScoreTime(beats, beatType)
                        }
                        child.one("staves")?.let { declaredStaves = it.positiveInt("谱表数量", 32) }
                        child.one("key")?.let { value ->
                            if (value.attributes["number"] != null) fail("暂不支持分谱表调号")
                            fifths = value.required("fifths").boundedInt("调号", -7, 7)
                            keyMode = value.one("mode")?.textValue()
                            keyMode?.let { mode ->
                                if (mode !in setOf("major", "minor", "none")) issue(issues, "调式 $mode 尚未解释；保留原始文件", measureId)
                            }
                        }
                        child.children("clef").forEach { value ->
                            val staff = value.attributes["number"]?.let { it.toIntOrNull() ?: fail("谱号 number 不是整数") } ?: 1
                            if (staff !in 1..32) fail("谱表编号超出范围")
                            val sign = value.required("sign").textValue()
                            if (sign !in setOf("G", "F", "C")) fail("暂不支持谱号 $sign")
                            val line = value.one("line")?.boundedInt("谱号线", 1, 5)
                            val octaveChange = value.one("clef-octave-change")?.boundedInt("谱号八度", -2, 2) ?: 0
                            clefs[staff] = ScoreClef(staff, sign, line, octaveChange)
                        }
                        val unsupportedAttribute = child.children.firstOrNull { it.name !in setOf("divisions", "key", "time", "staves", "clef") }
                        if (unsupportedAttribute != null) issue(issues, "属性 <${unsupportedAttribute.name}> 未解释；需核对原文件", measureId)
                    }
                    "note" -> {
                        val division = divisions ?: fail("音符前未声明 divisions（小节 $number）")
                        val duration = duration(child, division)
                        val rest = child.one("rest") != null
                        val pitchNode = child.one("pitch")
                        if (rest == (pitchNode != null)) fail("音符必须明确且仅包含 pitch 或 rest（小节 $number）")
                        val pitch = pitchNode?.let { value ->
                            val step = value.required("step").textValue()
                            if (step !in setOf("C", "D", "E", "F", "G", "A", "B")) fail("无效音名 $step")
                            val alter = value.one("alter")?.textValue()?.let {
                                it.toIntOrNull() ?: fail("暂不支持微分音 alter=$it")
                            } ?: 0
                            if (alter !in -2..2) fail("暂不支持超过重升 / 重降的音高")
                            ScorePitch(step, alter, value.required("octave").boundedInt("八度", 0, 9))
                        }
                        val voice = child.one("voice")?.textValue().orEmpty().ifBlank { "1" }
                        if (voice.length > 64) fail("声部标识过长")
                        val staff = child.one("staff")?.boundedInt("谱表", 1, 32) ?: 1
                        val chord = child.one("chord") != null
                        if (chord && rest) fail("休止符不能标记为和弦")
                        val previous = anchor
                        if (chord && (previous == null || previous.voice != voice || previous.isRest)) fail("和弦音没有同声部前导音（小节 $number）")
                        val offset = if (chord) requireNotNull(previous).offset else cursor
                        val type = child.one("type")?.textValue()
                        val dots = child.children("dot").size
                        if (dots > 3) fail("暂不支持超过三个附点")
                        val ties = child.children("tie").map { it.attribute("type") }.toSet()
                        if (!setOf("start", "stop").containsAll(ties)) fail("无效 tie 类型")
                        val eventId = "$measureId:e$noteIndex"
                        val event = ScoreEvent(eventId, offset, duration, pitch, staff, voice, chord, type, dots, ties, noteIndex++, child.line)
                        events += event
                        validateAccidental(child, event, issues, measureId)
                        val notatedTies = child.one("notations")?.children("tied")?.map { it.attribute("type") }?.toSet().orEmpty()
                        if (notatedTies.isNotEmpty() && notatedTies != ties) issue(issues, "连音线 tied 与实际演奏 tie 不一致；需核对原文件", measureId, eventId)
                        if (pitch != null && pitch.midi !in 21..108) issue(issues, "音高 ${pitch.displayName} 超出标准 88 键钢琴范围", measureId, eventId)
                        if (type != null) validateWrittenDuration(type, dots, duration, issues, measureId, eventId)
                        if (!chord) {
                            cursor += duration
                            anchor = event
                        }
                        val understood = setOf("chord", "pitch", "rest", "duration", "tie", "voice", "type", "dot", "accidental", "stem", "staff", "beam", "notations", "lyric")
                        child.children.filter { it.name !in understood }.forEach {
                            issue(issues, "音符属性 <${it.name}> 未解释；需核对原文件", measureId, eventId)
                        }
                        child.one("notations")?.children?.filter { it.name !in setOf("tied", "slur", "articulations", "ornaments", "fermata", "arpeggiate", "non-arpeggiate", "technical") }?.forEach {
                            issue(issues, "记谱符号 <${it.name}> 未解释；需核对原文件", measureId, eventId)
                        }
                    }
                    "backup", "forward" -> {
                        val delta = duration(child, divisions ?: fail("移动拍位前未声明 divisions"))
                        cursor = if (child.name == "backup") cursor - delta else cursor + delta
                        if (cursor < BeatFraction.ZERO) fail("backup 超出小节起点（小节 $number）")
                        anchor = null
                    }
                    "direction", "barline", "print", "sound" -> Unit
                    else -> issue(issues, "小节元素 <${child.name}> 尚未解释；需核对原文件", measureId)
                }
            }
            if (time == null) issue(issues, "缺少拍号，无法校验小节总时值", measureId, severity = ScoreIssueSeverity.ERROR)
            events.map { it.staff }.distinct().forEach { staff ->
                if (staff !in clefs) issue(issues, "谱表 $staff 未声明谱号，谱面展示需要人工核对", measureId)
                if (staff > declaredStaves) issue(issues, "音符引用谱表 $staff，超出声明谱表数量 $declaredStaves", measureId, severity = ScoreIssueSeverity.ERROR)
            }
            if (clefs.keys.any { it > declaredStaves }) issue(issues, "谱号编号超出声明谱表数量 $declaredStaves", measureId, severity = ScoreIssueSeverity.ERROR)
            val result = ScoreMeasure(measureId, number, time, fifths, clefs.values.toList(), events, implicit, keyMode, declaredStaves)
            validateMeasure(result, issues)
            result
        }
        if (measures.isEmpty()) fail("声部 $partId 没有小节")
        return ScorePart(partId, name, measures)
    }

    private fun validateAccidental(note: Node, event: ScoreEvent, issues: MutableList<ScoreIssue>, measureId: String) {
        val accidental = note.one("accidental")?.textValue() ?: return
        val alter = when (accidental) {
            "natural" -> 0
            "sharp", "natural-sharp" -> 1
            "flat", "natural-flat" -> -1
            "double-sharp", "sharp-sharp" -> 2
            "flat-flat" -> -2
            else -> { issue(issues, "临时记号 $accidental 尚未解释，需核对音高", measureId, event.id); return }
        }
        if (event.pitch == null || event.pitch.alter != alter) {
            issue(issues, "临时记号 $accidental 与 pitch.alter 不一致；保留原音高，未自动修改", measureId, event.id, ScoreIssueSeverity.ERROR)
        }
    }
    private fun validateMeasure(measure: ScoreMeasure, issues: MutableList<ScoreIssue>) {
        if (measure.events.isEmpty()) {
            issue(issues, "小节没有音符或休止符", measure.id, severity = ScoreIssueSeverity.ERROR)
            return
        }
        val expected = measure.time?.duration
        measure.events.groupBy { it.staff to it.voice }.forEach { (lane, notes) ->
            var end = BeatFraction.ZERO
            val grouped = notes.groupBy { it.offset }.toSortedMap()
            grouped.forEach { (offset, simultaneous) ->
                if (offset > end) issue(issues, "谱表 ${lane.first} / 声部 ${lane.second} 在第 $end 拍后有未记谱空隙", measure.id)
                if (offset < end) issue(issues, "谱表 ${lane.first} / 声部 ${lane.second} 存在时值重叠", measure.id, simultaneous.first().id, ScoreIssueSeverity.ERROR)
                if (simultaneous.count { !it.chord } > 1) issue(issues, "同声部多个起音重叠，未声明和弦", measure.id, simultaneous.first().id, ScoreIssueSeverity.ERROR)
                val groupEnd = simultaneous.maxOf { it.offset + it.duration }
                if (groupEnd > end) end = groupEnd
            }
            if (expected != null && end > expected) issue(issues, "谱表 ${lane.first} / 声部 ${lane.second} 时值 $end 超过拍号要求 $expected", measure.id, severity = ScoreIssueSeverity.ERROR)
            if (expected != null && end < expected && !measure.implicit) issue(issues, "谱表 ${lane.first} / 声部 ${lane.second} 时值 $end 不足拍号要求 $expected；未自动补休止符", measure.id)
        }
    }

    private fun validateWrittenDuration(type: String, dots: Int, duration: BeatFraction, issues: MutableList<ScoreIssue>, measureId: String, eventId: String) {
        val base = when (type) {
            "maxima" -> BeatFraction.of(32); "long" -> BeatFraction.of(16); "breve" -> BeatFraction.of(8)
            "whole" -> BeatFraction.of(4); "half" -> BeatFraction.of(2); "quarter" -> BeatFraction.of(1)
            "eighth" -> BeatFraction.of(1, 2); "16th" -> BeatFraction.of(1, 4); "32nd" -> BeatFraction.of(1, 8)
            "64th" -> BeatFraction.of(1, 16); "128th" -> BeatFraction.of(1, 32); "256th" -> BeatFraction.of(1, 64)
            "512th" -> BeatFraction.of(1, 128); "1024th" -> BeatFraction.of(1, 256)
            else -> { issue(issues, "未支持音符类型 $type", measureId, eventId); return }
        }
        var written = base
        for (dot in 1..dots) written += BeatFraction.of(base.numerator, base.denominator * (1L shl dot))
        if (written != duration) issue(issues, "记谱类型 $type / 附点 $dots 与 duration=$duration 不一致", measureId, eventId, ScoreIssueSeverity.ERROR)
    }

    private fun duration(node: Node, divisions: Int): BeatFraction = BeatFraction.of(
        node.required("duration").positiveInt("时值", 100_000_000).toLong(), divisions.toLong(),
    )

    private fun issue(issues: MutableList<ScoreIssue>, message: String, measureId: String? = null, eventId: String? = null, severity: ScoreIssueSeverity = ScoreIssueSeverity.WARNING) {
        if (issues.size >= MAX_ISSUES) fail("校验问题超过 $MAX_ISSUES 项，请先在制谱软件中修正源文件")
        issues += ScoreIssue("issue-${issues.size}", message, severity, measureId, eventId)
    }

    private fun readTree(xml: String): Node {
        val handler = TreeHandler()
        try {
            // The character input is verified UTF-8 and rejects all DTD/entity declarations before parsing.
            // No schemas or XInclude are enabled; this resolver fails closed for any external access.
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("禁止外部实体访问") }
            reader.parse(InputSource(StringReader(xml)))
        } catch (error: Exception) {
            throw MusicXmlException("MusicXML 无法解析：${error.message?.take(240) ?: "格式不正确"}", error)
        }
        return handler.root ?: fail("XML 缺少根节点")
    }

    private data class Node(val name: String, val attributes: Map<String, String>, val line: Int, val children: MutableList<Node> = mutableListOf(), val text: StringBuilder = StringBuilder()) {
        fun children(name: String): List<Node> = children.filter { it.name == name }
        fun one(name: String): Node? {
            val matching = children(name)
            if (matching.size > 1) fail("元素 <$name> 重复（第 $line 行）")
            return matching.firstOrNull()
        }
        fun required(name: String): Node = one(name) ?: fail("缺少 <$name>（第 $line 行）")
        fun attribute(name: String): String = attributes[name]?.takeIf { it.isNotBlank() } ?: fail("缺少 $name 属性（第 $line 行）")
        fun textValue(): String = text.toString().trim()
        fun positiveInt(label: String, max: Int): Int = boundedInt(label, 1, max)
        fun boundedInt(label: String, min: Int, max: Int): Int {
            val value = textValue().toIntOrNull() ?: fail("$label 不是支持的整数（第 $line 行）")
            if (value !in min..max) fail("$label 超出 $min..$max 范围（第 $line 行）")
            return value
        }
        fun walk(block: (Node) -> Unit) { block(this); children.forEach { it.walk(block) } }
    }

    private class TreeHandler : DefaultHandler() {
        var root: Node? = null
        private val stack = ArrayDeque<Node>()
        private var count = 0
        private var locator: Locator? = null
        override fun setDocumentLocator(locator: Locator) { this.locator = locator }
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (uri.isNotEmpty() || qName.contains(':')) throw SAXException("暂不支持带命名空间的扩展元素")
            if (stack.size >= MAX_DEPTH) throw SAXException("XML 嵌套超过 $MAX_DEPTH 层")
            if (++count > MAX_NODES) throw SAXException("XML 节点超过 $MAX_NODES 个")
            val attributeMap = (0 until attributes.length).associate { attributes.getQName(it) to attributes.getValue(it) }
            if (attributeMap.keys.any { it.contains(':') && it != "xml:space" && it != "xml:lang" }) throw SAXException("不支持外部命名空间属性")
            val node = Node(localName.ifEmpty { qName }, attributeMap, locator?.lineNumber ?: -1)
            if (stack.isEmpty()) root = node else stack.last().children += node
            stack.addLast(node)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { stack.lastOrNull()?.text?.append(ch, start, length) }
        override fun endElement(uri: String, localName: String, qName: String) { stack.removeLast() }
        override fun error(error: SAXParseException): Unit = throw error
        override fun fatalError(error: SAXParseException): Unit = throw error
    }

    companion object {
        const val MAX_BYTES: Int = 2 * 1024 * 1024
        const val MAX_DEPTH: Int = 64
        const val MAX_NODES: Int = 50_000
        const val MAX_ISSUES: Int = 1_000
        private fun fail(message: String): Nothing = throw MusicXmlException(message)
    }
}