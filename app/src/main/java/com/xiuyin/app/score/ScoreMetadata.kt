package com.xiuyin.app.score

import java.util.Locale

/** Source declarations only; user naming never changes DigitalScore or its event identities. */
data class ParsedScoreDocument(
    val score: DigitalScore,
    val originalTitle: String?,
    val composer: String?,
    val workNumber: String?,
    val pageCount: Int? = null,
)

data class ScoreImportMetadata(
    val originalTitle: String? = null,
    val sourceFileName: String? = null,
    val composer: String? = null,
    val workNumber: String? = null,
    val pageCount: Int? = null,
    val timeSignatures: List<String> = emptyList(),
    val keySignatures: List<String> = emptyList(),
    val sourceType: String = "MusicXML",
    val recognitionComplete: Boolean = false,
    val hasReviewIssues: Boolean = false,
    val firstMeasureSummary: String? = null,
) {
    /** Pure preflight: call before saving original bytes so invalid metadata cannot strand a file. */
    fun normalized(): ScoreImportMetadata {
        require(pageCount == null || pageCount in 1..100_000) { "页数无效；未知页数应为空" }
        return copy(
            originalTitle = ScoreMetadataLimits.text(originalTitle, 512, "原始曲名"),
            sourceFileName = ScoreMetadataLimits.text(sourceFileName, 256, "来源文件名"),
            composer = ScoreMetadataLimits.text(composer, 512, "作者"),
            workNumber = ScoreMetadataLimits.text(workNumber, 256, "作品编号"),
            sourceType = requireNotNull(ScoreMetadataLimits.text(sourceType, 32, "来源类型")) { "来源类型不能为空" },
            timeSignatures = ScoreMetadataLimits.labels(timeSignatures, 64, "拍号", 256),
            keySignatures = ScoreMetadataLimits.labels(keySignatures, 64, "调号", 256),
            firstMeasureSummary = ScoreMetadataLimits.text(firstMeasureSummary, 1024, "首小节摘要"),
        )
    }
    companion object {
        fun from(document: ParsedScoreDocument, sourceFileName: String? = null): ScoreImportMetadata {
            val measures = document.score.parts.flatMap { it.measures }
            return ScoreImportMetadata(
                originalTitle = document.originalTitle,
                sourceFileName = sourceFileName,
                composer = document.composer,
                workNumber = document.workNumber,
                pageCount = document.pageCount,
                timeSignatures = measures.mapNotNull { it.time?.toString() }.distinct(),
                keySignatures = measures.mapNotNull { measure -> measure.fifths?.let { "$it fifths${measure.keyMode?.let { mode -> " / $mode" }.orEmpty()}" } }.distinct(),
                sourceType = document.score.source.format,
                // A MusicXML parse completed. This does not claim optical recognition happened.
                recognitionComplete = true,
                hasReviewIssues = document.score.issues.isNotEmpty(),
                firstMeasureSummary = measures.firstOrNull()?.events?.take(16)?.joinToString(" · ") {
                    "${it.pitch?.displayName ?: "休止符"}@${it.offset}/${it.duration}"
                }?.takeIf(String::isNotBlank),
            )
        }
    }
}

data class ScoreMetadata(
    val scoreId: String,
    val sourceId: String,
    val originalTitle: String?,
    val customTitle: String?,
    val temporaryTitle: String?,
    val aliases: List<String>,
    val tags: List<String>,
    val sourceFileName: String?,
    val composer: String?,
    val workNumber: String?,
    val importedAt: Long,
    val lastPracticedAt: Long?,
    val favorite: Boolean,
    val pageCount: Int?,
    val timeSignatures: List<String>,
    val keySignatures: List<String>,
    val sourceType: String,
    val recognitionComplete: Boolean,
    val hasReviewIssues: Boolean,
    val firstMeasureSummary: String?,
    val deletedAt: Long? = null,
    val sourceFileDeleted: Boolean = false,
) {
    val displayTitle: String get() = customTitle ?: originalTitle ?: requireNotNull(temporaryTitle)
    val customNamed: Boolean get() = customTitle != null
}

enum class ScoreSort { RECENT_IMPORT, RECENT_PRACTICE, NAME, FAVORITES }

/** In-memory search is bounded by the local archive and never sends queries to a service. */
fun queryScoreMetadata(
    scores: List<ScoreMetadata>,
    query: String = "",
    tag: String? = null,
    sort: ScoreSort = ScoreSort.RECENT_IMPORT,
): List<ScoreMetadata> {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    val tagFilter = tag?.trim()?.takeIf(String::isNotBlank)
    val filtered = scores.filter { score ->
        score.deletedAt == null &&
            (tagFilter == null || score.tags.any { it.equals(tagFilter, ignoreCase = true) }) &&
            terms.all { term -> searchableText(score).contains(term) }
    }
    val recent = compareByDescending<ScoreMetadata> { it.importedAt }.thenBy { it.scoreId }
    val comparator = when (sort) {
        ScoreSort.RECENT_IMPORT -> recent
        ScoreSort.RECENT_PRACTICE -> compareByDescending<ScoreMetadata> { it.lastPracticedAt ?: Long.MIN_VALUE }.then(recent)
        ScoreSort.NAME -> compareBy<ScoreMetadata> { it.displayTitle.lowercase(Locale.ROOT) }.then(recent)
        ScoreSort.FAVORITES -> compareByDescending<ScoreMetadata> { it.favorite }.then(recent)
    }
    return filtered.sortedWith(comparator)
}

private fun searchableText(score: ScoreMetadata): String = buildList {
    addAll(listOfNotNull(score.customTitle, score.originalTitle, score.temporaryTitle, score.sourceFileName, score.composer, score.workNumber, score.firstMeasureSummary))
    addAll(score.aliases)
    addAll(score.tags)
    addAll(score.timeSignatures)
    addAll(score.keySignatures)
    add(score.sourceType)
}.joinToString("\n").lowercase(Locale.ROOT)

/** Limits are shared with the editor; invalid input must fail before a transaction changes data. */
object ScoreMetadataLimits {
    const val TITLE_LENGTH = 120
    const val ALIAS_LENGTH = 100
    const val TAG_LENGTH = 50
    const val LIST_COUNT = 20
    fun title(value: String?): String? = text(value, TITLE_LENGTH, "自定义名称")
    fun aliases(values: List<String>): List<String> = labels(values, ALIAS_LENGTH, "别名")
    fun tags(values: List<String>): List<String> = labels(values, TAG_LENGTH, "标签")
    internal fun text(value: String?, max: Int, label: String): String? {
        val normalized = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotBlank) ?: return null
        require(normalized.length <= max) { "$label 最多 $max 个字符" }
        require(normalized.none(Char::isISOControl)) { "$label 含无效控制字符" }
        return normalized
    }
    internal fun labels(values: List<String>, max: Int, label: String, count: Int = LIST_COUNT): List<String> {
        require(values.size <= count) { "$label 最多 $count 项" }
        return values.mapNotNull { text(it, max, label) }.distinctBy { it.lowercase(Locale.ROOT) }
    }
}

class ScoreDeletedException(val sourceId: String) : IllegalStateException("该来源已删除；自动扫描不会恢复，请通过导入文件主动重新添加")