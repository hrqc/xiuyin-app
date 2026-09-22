package com.xiuyin.app.field

/** Small local JSON codec so storage and corruption checks run on the JVM without Android stubs. */
internal object FieldJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            } }
            append('"')
        }
        is Boolean -> value.toString()
        is Number -> { require(value.toDouble().isFinite()) { "非有限诊断数值" }; value.toString() }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> require(key is String); encode(key) + ":" + encode(item) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is FloatArray -> encode(value.toList())
        is DoubleArray -> encode(value.toList())
        else -> error("不支持的诊断值 ${value.javaClass.simpleName}")
    }
    fun decode(text: String): Any? = Parser(text).read()
    fun objectValue(text: String): Map<String, Any?> {
        val value = decode(text)
        require(value is Map<*, *>) { "诊断记录不是 JSON 对象" }
        return value.entries.associate { (k, v) -> require(k is String); k to v }
    }
    private class Parser(private val text: String) {
        private var index = 0
        fun read(): Any? { val value = value(); space(); require(index == text.length); return value }
        private fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun value(): Any? {
            space(); require(index < text.length)
            return when (text[index]) {
                '"' -> string()
                '{' -> { index++; val result = linkedMapOf<String, Any?>(); space()
                    if (take('}')) result else { do { space(); val key = string(); space(); require(take(':')); require(!result.containsKey(key)); result[key] = value(); space() } while (take(',')); require(take('}')); result }
                }
                '[' -> { index++; val result = mutableListOf<Any?>(); space()
                    if (take(']')) result else { do { result += value(); space() } while (take(',')); require(take(']')); result }
                }
                't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
                else -> { val start = index; while (index < text.length && text[index] in "-+0123456789.eE") index++; require(index > start); val token = text.substring(start, index); require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)); token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) } }
            }
        }
        private fun <T> literal(token: String, result: T): T { require(text.startsWith(token, index)); index += token.length; return result }
        private fun take(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
        private fun string(): String {
            require(take('"'))
            return buildString {
                while (true) {
                    require(index < text.length); val c = text[index++]
                    if (c == '"') break
                    require(c.code >= 32)
                    if (c != '\\') append(c) else {
                        require(index < text.length)
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> append(escaped); 'b' -> append('\b'); 'f' -> append('\u000C')
                            'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                            'u' -> { require(index + 4 <= text.length); append(text.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                            else -> error("非法 JSON 转义")
                        }
                    }
                }
            }
        }
    }
}