package com.xiuyin.app.score

/** Movable-do spelling choices; never infer a key or silently substitute an enharmonic spelling. */
object ScoreTransposeChoice {
    val fifths = (-7..7).toList()
    private val doNames = listOf("bC", "bG", "bD", "bA", "bE", "bB", "F", "C", "G", "D", "A", "E", "B", "#F", "#C")
    private val doPattern = Regex("^(?:1=)?([b♭#♯]?)([A-Ga-g])([b♭#♯]?)$")

    /** Matches numberedPitch's degree 1 for the signature, including a score whose mode is minor. */
    fun numberedLabel(fifths: Int): String {
        require(fifths in -7..7) { "调号超出支持范围" }
        return "1=" + doNames[fifths + 7]
    }

    fun parseNumberedKey(input: String): Int {
        val text = input.filterNot(Char::isWhitespace)
        val match = doPattern.matchEntire(text)
            ?: throw IllegalArgumentException("请输入 1=音名，例如 1=bD、1=#F；只支持一个升号或降号")
        val prefix = match.groupValues[1]
        val suffix = match.groupValues[3]
        require(prefix.isEmpty() || suffix.isEmpty()) { "只支持一个升号或降号，不能使用重升或重降" }
        val accidental = (prefix + suffix).replace('♭', 'b').replace('♯', '#')
        val spelling = accidental + match.groupValues[2].uppercase()
        val index = doNames.indexOf(spelling)
        require(index >= 0) { "1=$spelling 超出七升七降调号范围；请自行确认正确拼写，不会自动换成其他调名" }
        return index - 7
    }

    /** Choose the nearest pitch-class shift; the dialog displays its direction before save. */
    fun nearestShift(fromFifths: Int, toFifths: Int): Int {
        require(fromFifths in -7..7 && toFifths in -7..7) { "请先确认原谱与目标调号" }
        val up = Math.floorMod((toFifths - fromFifths) * 7, 12)
        return if (up > 6) up - 12 else up
    }

    fun shiftLabel(semitones: Int): String = when {
        semitones > 0 -> "升 $semitones 个半音"
        semitones < 0 -> "降 ${-semitones.toLong()} 个半音"
        else -> "音高不变"
    }

    fun step(value: String, delta: Int): String =
        ((value.toIntOrNull() ?: 0).toLong() + delta).coerceIn(-127L, 127L).toString()
}
