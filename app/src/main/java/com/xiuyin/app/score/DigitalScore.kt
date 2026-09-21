package com.xiuyin.app.score

import java.math.BigInteger

/** Exact score time; one unit is a quarter note. */
@ConsistentCopyVisibility
data class BeatFraction private constructor(val numerator: Long, val denominator: Long) : Comparable<BeatFraction> {
    operator fun plus(other: BeatFraction): BeatFraction = fromBig(
        numerator.toBigInteger() * other.denominator.toBigInteger() + other.numerator.toBigInteger() * denominator.toBigInteger(),
        denominator.toBigInteger() * other.denominator.toBigInteger(),
    )
    operator fun minus(other: BeatFraction): BeatFraction = fromBig(
        numerator.toBigInteger() * other.denominator.toBigInteger() - other.numerator.toBigInteger() * denominator.toBigInteger(),
        denominator.toBigInteger() * other.denominator.toBigInteger(),
    )
    override fun compareTo(other: BeatFraction): Int =
        (numerator.toBigInteger() * other.denominator.toBigInteger()).compareTo(other.numerator.toBigInteger() * denominator.toBigInteger())
    fun toDouble(): Double = numerator.toDouble() / denominator
    override fun toString(): String = if (denominator == 1L) numerator.toString() else "$numerator/$denominator"
    companion object {
        val ZERO: BeatFraction = of(0)
        fun of(numerator: Long, denominator: Long = 1): BeatFraction = fromBig(numerator.toBigInteger(), denominator.toBigInteger())
        private fun fromBig(numerator: BigInteger, denominator: BigInteger): BeatFraction {
            require(denominator.signum() > 0) { "拍时分母必须为正数" }
            val divisor = numerator.gcd(denominator)
            val reducedNumerator = numerator / divisor
            val reducedDenominator = denominator / divisor
            require(reducedNumerator.bitLength() <= 63 && reducedDenominator.bitLength() <= 63) { "拍时超出范围" }
            return BeatFraction(reducedNumerator.toLong(), reducedDenominator.toLong())
        }
    }
}

enum class ScoreStatus { READY, NEEDS_REVIEW }
enum class ScoreIssueSeverity { WARNING, ERROR }
data class ScoreSource(val format: String, val sha256: String, val byteCount: Int)
data class SourceBox(val page: Int, val x: Double, val y: Double, val width: Double, val height: Double)
data class ScorePitch(val step: String, val alter: Int, val octave: Int) {
    val midi: Int get() = (octave + 1) * 12 + when (step) {
        "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
        else -> error("未知音名")
    } + alter
    val displayName: String get() = step + when {
        alter > 0 -> "♯".repeat(alter)
        alter < 0 -> "♭".repeat(-alter)
        else -> ""
    } + octave
}
data class ScoreTime(val beats: Int, val beatType: Int) {
    val duration: BeatFraction get() = BeatFraction.of(beats.toLong() * 4, beatType.toLong())
    override fun toString(): String = "$beats/$beatType"
}
data class ScoreClef(val staff: Int, val sign: String, val line: Int?, val octaveChange: Int = 0)
data class ScoreEvent(
    val id: String,
    val offset: BeatFraction,
    val duration: BeatFraction,
    val pitch: ScorePitch?,
    val staff: Int,
    val voice: String,
    val chord: Boolean,
    val type: String?,
    val dots: Int,
    val ties: Set<String>,
    val sourceIndex: Int,
    val sourceLine: Int,
    val confidence: Double? = null,
    val sourceBox: SourceBox? = null,
    val grace: Boolean = false,
    val tupletActual: Int? = null,
    val tupletNormal: Int? = null,
) { val isRest: Boolean get() = pitch == null }
data class ScoreMeasure(
    val id: String,
    val number: String,
    val time: ScoreTime?,
    val fifths: Int?,
    val clefs: List<ScoreClef>,
    val events: List<ScoreEvent>,
    val implicit: Boolean,
    val keyMode: String? = null,
    val declaredStaves: Int = 1,
)
data class ScorePart(val id: String, val name: String, val measures: List<ScoreMeasure>)
data class ScoreIssue(
    val id: String,
    val message: String,
    val severity: ScoreIssueSeverity = ScoreIssueSeverity.WARNING,
    val measureId: String? = null,
    val eventId: String? = null,
)
data class DigitalScore(
    val id: String,
    val title: String,
    val parts: List<ScorePart>,
    val issues: List<ScoreIssue>,
    val source: ScoreSource,
) { val status: ScoreStatus get() = if (issues.isEmpty()) ScoreStatus.READY else ScoreStatus.NEEDS_REVIEW }

class MusicXmlException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)