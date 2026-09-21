package com.xiuyin.app.score

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Versioned, bounded, lossless JSON for the existing DigitalScore; never reparses or edits source XML. */
object ScoreJsonCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_SCORE_BYTES = 4 * 1024 * 1024
    private const val MAX_EVENTS = 50_000
    private const val MAX_TEXT = 16_384

    fun encode(score: DigitalScore): ByteArray {
        validate(score)
        val bytes = toJson(score).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_SCORE_BYTES) { "数字乐谱超过 4 MiB 修订上限" }
        return bytes
    }

    fun decode(bytes: ByteArray): DigitalScore {
        val root = parseObject(bytes, MAX_SCORE_BYTES)
        return fromJson(root)
    }

    internal fun toJson(score: DigitalScore): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION).put("id", score.id).put("title", score.title)
        .put("source", JSONObject().put("format", score.source.format).put("sha256", score.source.sha256).put("byteCount", score.source.byteCount))
        .put("parts", array(score.parts) { part -> JSONObject().put("id", part.id).put("name", part.name)
            .put("measures", array(part.measures) { measure -> JSONObject().put("id", measure.id).put("number", measure.number)
                .put("time", measure.time?.let { JSONObject().put("beats", it.beats).put("beatType", it.beatType) } ?: JSONObject.NULL)
                .put("fifths", measure.fifths ?: JSONObject.NULL).put("implicit", measure.implicit)
                .put("keyMode", measure.keyMode ?: JSONObject.NULL).put("declaredStaves", measure.declaredStaves)
                .put("clefs", array(measure.clefs) { clef -> JSONObject().put("staff", clef.staff).put("sign", clef.sign)
                    .put("line", clef.line ?: JSONObject.NULL).put("octaveChange", clef.octaveChange) })
                .put("events", array(measure.events) { event -> JSONObject().put("id", event.id)
                    .put("offset", fraction(event.offset)).put("duration", fraction(event.duration))
                    .put("pitch", event.pitch?.let { JSONObject().put("step", it.step).put("alter", it.alter).put("octave", it.octave) } ?: JSONObject.NULL)
                    .put("staff", event.staff).put("voice", event.voice).put("chord", event.chord)
                    .put("type", event.type ?: JSONObject.NULL).put("dots", event.dots)
                    .put("ties", JSONArray(event.ties.sorted())).put("sourceIndex", event.sourceIndex).put("sourceLine", event.sourceLine)
                    .put("confidence", event.confidence ?: JSONObject.NULL)
                    .apply { if (event.grace) put("grace", true)
                        if (event.tupletActual != null) put("tupletActual", event.tupletActual)
                        if (event.tupletNormal != null) put("tupletNormal", event.tupletNormal) }
                    .put("sourceBox", event.sourceBox?.let { box -> JSONObject().put("page", box.page).put("x", box.x).put("y", box.y)
                        .put("width", box.width).put("height", box.height) } ?: JSONObject.NULL) }) }) })
        .put("issues", array(score.issues) { issue -> JSONObject().put("id", issue.id).put("message", issue.message)
            .put("severity", issue.severity.name).put("measureId", issue.measureId ?: JSONObject.NULL).put("eventId", issue.eventId ?: JSONObject.NULL) })

    internal fun fromJson(root: JSONObject): DigitalScore {
        root.fields("schemaVersion", "id", "title", "source", "parts", "issues")
        require(root.int("schemaVersion") == SCHEMA_VERSION) { "不支持的 Digital Score JSON 版本；已有内容未重建" }
        val source = root.obj("source").also { it.fields("format", "sha256", "byteCount") }
        val parts = root.objects("parts", 128) { part ->
            part.fields("id", "name", "measures")
            ScorePart(part.string("id"), part.string("name"), part.objects("measures", 20_000) { measure ->
                measure.fields("id", "number", "time", "fifths", "clefs", "events", "implicit", "keyMode", "declaredStaves")
                val time = measure.nullableObject("time")?.let {
                    it.fields("beats", "beatType"); ScoreTime(it.int("beats"), it.int("beatType"))
                }
                ScoreMeasure(measure.string("id"), measure.string("number"), time, measure.nullableInt("fifths"),
                    measure.objects("clefs", 64) { clef ->
                        clef.fields("staff", "sign", "line", "octaveChange")
                        ScoreClef(clef.int("staff"), clef.string("sign"), clef.nullableInt("line"), clef.int("octaveChange"))
                    }, measure.objects("events", MAX_EVENTS) { event ->
                        event.fields(*(listOf("id", "offset", "duration", "pitch", "staff", "voice", "chord", "type", "dots", "ties", "sourceIndex", "sourceLine", "confidence", "sourceBox") + listOf("grace", "tupletActual", "tupletNormal").filter { event.has(it) }).toTypedArray())
                        val pitch = event.nullableObject("pitch")?.let {
                            it.fields("step", "alter", "octave"); ScorePitch(it.string("step"), it.int("alter"), it.int("octave"))
                        }
                        val box = event.nullableObject("sourceBox")?.let {
                            it.fields("page", "x", "y", "width", "height")
                            SourceBox(it.int("page"), it.double("x"), it.double("y"), it.double("width"), it.double("height"))
                        }
                        val ties = event.strings("ties", 8)
                        require(ties.size == ties.toSet().size) { "连音标记重复" }
                        ScoreEvent(event.string("id"), readFraction(event.obj("offset")), readFraction(event.obj("duration")), pitch,
                            event.int("staff"), event.string("voice"), event.boolean("chord"), event.nullableString("type"),
                            event.int("dots"), ties.toSet(), event.int("sourceIndex"), event.int("sourceLine"),
                            if (event.isNull("confidence")) null else event.double("confidence"), box,
                            if (event.has("grace")) event.boolean("grace") else false,
                            if (event.has("tupletActual")) event.int("tupletActual") else null,
                            if (event.has("tupletNormal")) event.int("tupletNormal") else null)
                    }, measure.boolean("implicit"), measure.nullableString("keyMode"), measure.int("declaredStaves"))
            })
        }
        val issues = root.objects("issues", 2_000) { issue ->
            issue.fields("id", "message", "severity", "measureId", "eventId")
            ScoreIssue(issue.string("id"), issue.string("message"), ScoreIssueSeverity.valueOf(issue.string("severity")),
                issue.nullableString("measureId"), issue.nullableString("eventId"))
        }
        return DigitalScore(root.string("id"), root.string("title"), parts, issues,
            ScoreSource(source.string("format"), source.string("sha256"), source.int("byteCount"))).also(::validate)
    }

    fun validate(score: DigitalScore) {
        require(score.source.sha256.matches(Regex("[a-f0-9]{64}"))) { "来源 SHA256 无效" }
        require(score.id == score.source.sha256) { "数字乐谱 ID 与不可变来源不一致" }
        text(score.title)
        text(score.source.format, nonEmpty = true)
        require(score.source.byteCount in 1..(if (score.source.format == "MusicXML") ScoreSourceStore.MAX_SOURCE_BYTES else 64 * 1024 * 1024)) { "来源大小无效" }
        require(score.parts.size in 1..128) { "声部数量无效" }
        val partIds = HashSet<String>()
        val measureIds = HashSet<String>()
        val eventIds = HashSet<String>()
        var totalEvents = 0
        var totalMeasures = 0
        score.parts.forEach { part ->
            text(part.id, true); text(part.name)
            require(partIds.add(part.id)) { "声部 ID 重复" }
            require(part.measures.isNotEmpty()) { "声部缺少小节" }
            part.measures.forEach { measure ->
                totalMeasures++
                require(totalMeasures <= 20_000) { "小节数量超出限制" }
                text(measure.id, true); text(measure.number)
                require(measureIds.add(measure.id)) { "小节 ID 重复" }
                require(measure.declaredStaves in 1..64) { "谱表数量无效" }
                measure.time?.let { require(it.beats in 1..128 && it.beatType in 1..128) { "拍号无效" } }
                measure.fifths?.let { require(it in -14..14) { "调号无效" } }
                measure.keyMode?.let { text(it) }
                require(measure.clefs.size <= 64) { "谱号数量超出限制" }
                measure.clefs.forEach { clef ->
                    require(clef.staff in 1..64 && clef.octaveChange in -8..8) { "谱号字段无效" }
                    clef.line?.let { require(it in 1..5) { "谱号线无效" } }
                    text(clef.sign, true)
                }
                measure.events.forEach { event ->
                    totalEvents++
                    require(totalEvents <= MAX_EVENTS) { "音符数量超出限制" }
                    text(event.id, true); text(event.voice, true)
                    require(eventIds.add(event.id)) { "音符 ID 重复" }
                    require(event.offset >= BeatFraction.ZERO && (if (event.grace) event.duration == BeatFraction.ZERO else event.duration > BeatFraction.ZERO)) { "音符拍位或时值无效" }
                    require(event.staff in 1..64 && event.dots in 0..8 && event.sourceIndex >= -1 && event.sourceLine >= -1) { "音符字段无效" }
                    require(!event.grace || (event.pitch != null && event.ties.isEmpty())) { "装饰音结构无效" }
                    require((event.tupletActual == null) == (event.tupletNormal == null)) { "连音比例不完整" }
                    require(event.tupletActual == null || event.tupletActual in 1..32) { "连音数量无效" }
                    require(event.tupletNormal == null || event.tupletNormal in 1..32) { "连音数量无效" }
                    event.type?.let { text(it) }
                    require(event.ties.all { it == "start" || it == "stop" }) { "连音标记无效" }
                    event.pitch?.let { pitch ->
                        require(pitch.step in listOf("C", "D", "E", "F", "G", "A", "B") && pitch.alter in -4..4 && pitch.octave in -1..9) { "音高字段无效" }
                        require(pitch.midi in 0..127) { "音高超出 MIDI 范围" }
                    }
                    event.confidence?.let { require(it.isFinite() && it in 0.0..1.0) { "置信度无效" } }
                    event.sourceBox?.let { box ->
                        require(box.page >= 0 && listOf(box.x, box.y, box.width, box.height).all { it.isFinite() && it >= 0.0 }) { "原图区域包含无效数值" }
                    }
                }
            }
        }
        require(score.issues.size <= 2_000) { "疑点数量超出限制" }
        require(score.issues.map { it.id }.toSet().size == score.issues.size) { "疑点 ID 重复" }
        score.issues.forEach { issue ->
            text(issue.id, true); text(issue.message)
            issue.measureId?.let { text(it, true) }
            issue.eventId?.let { text(it, true) }
        }
    }

    internal fun parseObject(bytes: ByteArray, maximumBytes: Int): JSONObject {
        require(bytes.size in 1..maximumBytes) { "修订 JSON 为空或超出大小上限" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        var depth = 0
        var quoted = false
        var escaped = false
        input.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "修订 JSON 嵌套过深" } }
                '}', ']' -> { depth--; require(depth >= 0) { "修订 JSON 括号无效" } }
            }
        }
        require(!quoted && depth == 0) { "修订 JSON 不完整" }
        val tokenizer = JSONTokener(input)
        val result = tokenizer.nextValue()
        require(result is JSONObject && tokenizer.nextClean() == '\u0000') { "修订 JSON 根节点或尾随内容无效" }
        return result
    }

    internal fun JSONObject.fields(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) { "修订 JSON 字段缺失或包含未知字段" }
    }
    internal fun JSONObject.string(key: String): String = (get(key) as? String ?: error("$key 必须为字符串")).also { text(it) }
    internal fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key 必须为整数" }
        return (value as Number).toLong()
    }
    internal fun JSONObject.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key 整数溢出" } }.toInt()
    internal fun JSONObject.double(key: String): Double = (get(key) as? Number ?: error("$key 必须为数字")).toDouble().also { require(it.isFinite()) { "$key 不是有限数字" } }
    internal fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: error("$key 必须为布尔值")
    internal fun JSONObject.obj(key: String): JSONObject = get(key) as? JSONObject ?: error("$key 必须为对象")
    internal fun JSONObject.nullableObject(key: String): JSONObject? = if (isNull(key)) null else obj(key)
    internal fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else string(key)
    internal fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else int(key)
    internal fun JSONObject.strings(key: String, maximum: Int): List<String> {
        val values = get(key) as? JSONArray ?: error("$key 必须为数组")
        require(values.length() <= maximum) { "$key 项目过多" }
        return List(values.length()) { index -> (values.get(index) as? String ?: error("$key 必须为字符串数组")).also { text(it) } }
    }
    internal fun <T> JSONObject.objects(key: String, maximum: Int, transform: (JSONObject) -> T): List<T> {
        val values = get(key) as? JSONArray ?: error("$key 必须为数组")
        require(values.length() <= maximum) { "$key 项目过多" }
        return List(values.length()) { index -> transform(values.get(index) as? JSONObject ?: error("$key 必须为对象数组")) }
    }
    private fun fraction(value: BeatFraction) = JSONObject().put("numerator", value.numerator).put("denominator", value.denominator)
    private fun readFraction(value: JSONObject): BeatFraction {
        value.fields("numerator", "denominator")
        return BeatFraction.of(value.long("numerator"), value.long("denominator"))
    }
    private fun <T> array(values: List<T>, transform: (T) -> Any) = JSONArray().also { output -> values.forEach { output.put(transform(it)) } }
    private fun text(value: String, nonEmpty: Boolean = false) {
        require(value.length <= MAX_TEXT && (!nonEmpty || value.isNotBlank())) { "修订字符串为空或过长" }
    }
}
