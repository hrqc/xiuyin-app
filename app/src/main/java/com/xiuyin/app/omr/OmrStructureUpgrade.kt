package com.xiuyin.app.omr

import android.content.Context
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreRevisionSnapshot
import com.xiuyin.app.score.ScoreStructureSummary
import com.xiuyin.app.score.ScoreStructureTools
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject

enum class OmrPreviewMethod { ARCHIVED_TOKENS, REAL_IMAGE_RECOGNITION }

data class OmrStructurePreview(
    val sourceId: String,
    val revisionGeneration: Long,
    val candidate: DigitalScore,
    val before: ScoreStructureSummary,
    val after: ScoreStructureSummary,
    val method: OmrPreviewMethod = OmrPreviewMethod.ARCHIVED_TOKENS,
    val evidenceId: String? = null,
) {
    fun annotationsForApply(prior: ScoreRevisionSnapshot): Pair<Set<String>, Map<String, Int>> {
        // New segmentation can reuse page/staff/token IDs for different symbols.
        if (method == OmrPreviewMethod.REAL_IMAGE_RECOGNITION) return emptySet<String>() to emptyMap()
        val ids = candidate.parts.flatMap { it.measures }.flatMap { it.events }.map { it.id }.toSet()
        return prior.verifiedEventIds.intersect(ids) to prior.fingerings.filterKeys { it in ids }
    }
}

/** Reuses archived model tokens only; never reruns models or changes the immutable archive. */
class OmrStructureUpgrade(context: Context) {
    private val service = OmrImportService(context)

    fun preview(original: DigitalScore, current: DigitalScore, generation: Long): OmrStructurePreview {
        requireUnedited(original, current)
        val archive = service.load(original.source.sha256)
        require(archive.score == original) { "原始识谱归档与当前乐谱不一致；未覆盖修订" }
        val rawFile = archive.rawResultFile
        require(Files.isRegularFile(rawFile.toPath(), LinkOption.NOFOLLOW_LINKS) && rawFile.length() in 1..MAX_RAW_BYTES.toLong()) {
            "原始模型记录无效或过大；未修改文件"
        }
        val bytes = rawFile.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RAW_BYTES) { "原始模型记录超过读取限制" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val raw = JSONObject(bytes.toString(Charsets.UTF_8))
        require(raw.getInt("schemaVersion") == 1 && raw.getString("sourceId") == original.source.sha256) { "模型记录来源或版本不匹配" }
        require(raw.getString("status") in setOf("INFERENCE_FINISHED", "INFERENCE_INCOMPLETE")) { "模型记录未完成归档" }
        require(raw.getInt("byteCount") == original.source.byteCount) { "模型记录的来源长度不匹配" }
        val pages = raw.getJSONArray("pages")
        require(pages.length() == archive.sourceImages.size) { "模型记录页数与归档原图不一致" }
        for (index in 0 until pages.length()) {
            require(pages.getJSONObject(index).getInt("page") == index) { "模型记录页面编号不一致" }
        }
        val candidate = OmrScoreAssembler.assemble(original.source.sha256, original.source.byteCount, pages)
        ScoreJsonCodec.validate(candidate)
        return OmrStructurePreview(original.source.sha256, generation, candidate,
            ScoreStructureTools.summarize(current), ScoreStructureTools.summarize(candidate))
    }

    companion object {
        private const val MAX_RAW_BYTES = 32 * 1024 * 1024
        fun requireUnedited(original: DigitalScore, current: DigitalScore) {
            require(original.source.format == "OMR_IMAGE" && current.source == original.source && current.id == original.id) {
                "仅支持同一来源的本地图像识谱归档"
            }
            require(current.parts == original.parts) {
                "当前谱已有人工作出的音符、拍号、谱表或移调修改。请先保留当前修订，再逐步撤销并核对至原始识谱结果后整理；不会覆盖或清空您的修改。"
            }
        }
    }
}
