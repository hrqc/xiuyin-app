package com.xiuyin.app.field

import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreTimelineCompiler
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** An exact, local diagnostic input. It is never an independently verified musical reference. */
class FieldScoreSnapshot private constructor(
    private val scoreBytes: ByteArray,
    private val contextBytes: ByteArray,
    private val description: Map<String, Any?>,
) {
    /** Capture these immutable model values at preparation time; encode on the IO dispatcher. */
    data class Request(
        val scoreId: String,
        val revisionGeneration: Long,
        val score: DigitalScore,
        val config: PracticeConfig,
        val phase: CompanionPhase,
        val cursor: Int,
        val originalScore: DigitalScore? = null,
    )

    val byteCount: Long get() = scoreBytes.size.toLong() + contextBytes.size
    internal fun manifest(status: String = "AVAILABLE"): Map<String, Any?> = description + ("status" to status)
    internal fun files(): List<Pair<String, ByteArray>> = listOf(SCORE_FILE to scoreBytes.copyOf(), CONTEXT_FILE to contextBytes.copyOf())
    internal fun requireBinding(metadata: Map<String, String>) = requireBinding(description, metadata)

    companion object {
        const val SCORE_FILE = "score.json"
        const val CONTEXT_FILE = "practice-context.json"
        const val MAX_CONTEXT_BYTES = 4 * 1024 * 1024

        fun capture(request: Request): FieldScoreSnapshot {
            require(request.scoreId.isNotBlank() && request.scoreId.length <= 160 && request.revisionGeneration >= 0) { "实地谱面记录标识无效" }
            require(request.cursor >= 0) { "实地谱面游标无效" }
            val bytes = ScoreJsonCodec.encode(request.score)
            val score = ScoreJsonCodec.decode(bytes)
            check(score == request.score) { "实地谱面 JSON 往返不一致，未启动会话" }
            val timeline = ScoreTimelineCompiler.compile(score)
            require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "实地谱面仍有结构错误，未启动会话" }
            val config = request.config
            val follower = ScoreFollower(timeline, config)
            require(request.cursor <= follower.targets.size) { "实地谱面游标超出当前练习目标" }
            val last = config.endMeasure ?: timeline.measures.lastIndex
            val originalHash = request.originalScore?.let { original ->
                require(original.id == score.id && original.source == score.source) { "原始数字谱与实地快照来源不一致" }
                sha256(ScoreJsonCodec.encode(original))
            }
            val scoreHash = sha256(bytes)
            val context = mapOf(
                "schemaVersion" to 1, "snapshotStatus" to "AVAILABLE",
                "scoreId" to request.scoreId, "digitalScoreId" to score.id,
                "sourceSha256" to score.source.sha256, "sourceFormat" to score.source.format,
                "sourceByteCount" to score.source.byteCount, "revisionGeneration" to request.revisionGeneration,
                "scoreFile" to SCORE_FILE, "scoreSha256" to scoreHash, "scoreBytes" to bytes.size,
                "originalScoreSha256" to originalHash,
                "originalScoreStatus" to if (originalHash == null) "UNAVAILABLE" else "HASHED_FROM_PROVIDED_ORIGINAL",
                "hashDefinition" to "sourceSha256 identifies original imported file bytes; scoreSha256 identifies this exact score.json; originalScoreSha256, when present, identifies encoded original DigitalScore. No source image, video or original file is attached.",
                "practiceConfig" to mapOf("mode" to config.mode.name, "hand" to config.hand.name,
                    "startMeasure" to config.startMeasure, "endMeasure" to config.endMeasure,
                    "resolvedEndMeasure" to last, "repeatTarget" to config.repeatTarget,
                    "reviewAcknowledged" to config.reviewAcknowledged,
                    "measureIndexDefinition" to "Zero-based inclusive indices in the compiled timeline; null endMeasure means the full remaining score.",
                    "tempoBpm" to null, "tempoStatus" to "UNAVAILABLE: PracticeConfig has no tempo/BPM setting"),
                "practicePhaseAtCapture" to request.phase.name, "cursorAtCapture" to request.cursor,
                "capturedAtMillis" to System.currentTimeMillis(), "measureCount" to timeline.measures.size,
                "targetCount" to follower.targets.size,
                "beatUnit" to "quarter note; exact numerators and denominators are in score.json",
                "validationIssues" to timeline.issues.map { issue -> mapOf("id" to issue.id, "severity" to issue.severity.name,
                    "message" to issue.message, "measureId" to issue.measureId, "eventId" to issue.eventId) },
                "structureValidationPassed" to true, "accuracyValidated" to false, "groundTruth" to null,
                "musicalAccuracyStatus" to "UNVERIFIED: reproducible application input, not a corrected score or performance ground truth",
            )
            val contextBytes = FieldJson.encode(context).toByteArray(Charsets.UTF_8)
            require(contextBytes.size in 1..MAX_CONTEXT_BYTES) { "实地练习上下文超过 4 MiB 上限" }
            val descriptor = mapOf("schemaVersion" to 1, "scoreId" to request.scoreId,
                "revisionGeneration" to request.revisionGeneration, "sourceSha256" to score.source.sha256,
                "scoreFile" to SCORE_FILE, "scoreSha256" to scoreHash, "scoreBytes" to bytes.size,
                "practiceContextFile" to CONTEXT_FILE, "practiceContextSha256" to sha256(contextBytes),
                "practiceContextBytes" to contextBytes.size, "originalScoreSha256" to originalHash,
                "accuracyValidated" to false)
            return FieldScoreSnapshot(bytes, contextBytes, descriptor)
        }

        internal fun unavailable(reason: String): Map<String, Any?> = mapOf("schemaVersion" to 1,
            "status" to "UNAVAILABLE", "reason" to reason, "accuracyValidated" to false)

        internal fun verifyFiles(folder: File, manifest: Map<String, Any?>) {
            verify(manifest) { name, maximum ->
                val file = File(folder, name)
                check(file.isFile && file.length() in 1..maximum.toLong()) { "实地谱面快照文件缺失或大小无效：$name" }
                file.inputStream().use { readBounded(it, maximum) }
            }
        }

        internal fun verifyArchive(zip: ZipFile, manifest: Map<String, Any?>) {
            verify(manifest) { name, maximum ->
                val entry = zip.getEntry(name) ?: error("实地 ZIP 缺少谱面快照：$name")
                check(entry.size in 1..maximum.toLong()) { "实地 ZIP 谱面快照大小无效：$name" }
                zip.getInputStream(entry).use { readBounded(it, maximum) }
            }
        }

        private fun verify(manifest: Map<String, Any?>, read: (String, Int) -> ByteArray) {
            if (!manifest.containsKey("scoreSnapshot")) return // Older sessions have no snapshot.
            val descriptor = manifest["scoreSnapshot"] as? Map<*, *> ?: error("实地谱面快照描述损坏")
            check(descriptor["status"] in setOf("AVAILABLE", "PREPARING", "UNAVAILABLE")) { "实地谱面快照状态无效" }
            if (descriptor["status"] != "AVAILABLE") return // Incomplete initialization stays visibly unavailable.
            check(descriptor["scoreFile"] == SCORE_FILE && descriptor["practiceContextFile"] == CONTEXT_FILE) { "实地谱面快照文件名不匹配" }
            val scoreBytes = read(SCORE_FILE, ScoreJsonCodec.MAX_SCORE_BYTES)
            val contextBytes = read(CONTEXT_FILE, MAX_CONTEXT_BYTES)
            check((descriptor["scoreBytes"] as? Number)?.toLong() == scoreBytes.size.toLong() &&
                (descriptor["practiceContextBytes"] as? Number)?.toLong() == contextBytes.size.toLong()) { "实地谱面快照长度不一致" }
            check(descriptor["scoreSha256"] == sha256(scoreBytes) && descriptor["practiceContextSha256"] == sha256(contextBytes)) { "实地谱面快照 SHA256 校验失败，原始记录保留" }
            val score = ScoreJsonCodec.decode(scoreBytes)
            val context = FieldJson.objectValue(contextBytes.toString(Charsets.UTF_8))
            check(context["schemaVersion"] == 1L && context["snapshotStatus"] == "AVAILABLE" &&
                context["digitalScoreId"] == score.id && context["sourceSha256"] == score.source.sha256 &&
                context["sourceSha256"] == descriptor["sourceSha256"] && context["scoreSha256"] == descriptor["scoreSha256"] &&
                context["scoreId"] == descriptor["scoreId"] &&
                (context["revisionGeneration"] as? Number)?.toLong() == (descriptor["revisionGeneration"] as? Number)?.toLong()) { "实地谱面与练习上下文绑定不一致" }
            val metadata = manifest["metadata"] as? Map<*, *> ?: error("实地谱面快照缺少会话绑定")
            requireBinding(descriptor, metadata)
        }

        private fun requireBinding(description: Map<*, *>, metadata: Map<*, *>) {
            require(description["scoreId"] == metadata["scoreId"] &&
                (description["revisionGeneration"] as? Number)?.toLong() == (metadata["revisionGeneration"] as? String)?.toLongOrNull()) { "实地谱面快照与会话乐谱或修订不一致，未启动收音" }
            metadata["sourceSha256"]?.let { require(it == description["sourceSha256"]) { "实地谱面快照来源不一致" } }
        }

        private fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size().toLong() + count <= maximum) { "实地谱面快照超过读取上限" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
