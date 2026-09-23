package com.xiuyin.app.omr

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreStructureTools
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Real inference from verified archived images. Originals and previous reanalyses are never overwritten. */
class OmrReanalysisService(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "omr-reanalyses")

    fun recognize(original: DigitalScore, current: DigitalScore, generation: Long,
        progress: (String) -> Unit = {}, checkCancellation: () -> Unit = {}): OmrStructurePreview = synchronized(lock) {
        OmrStructureUpgrade.requireUnedited(original, current)
        val archive = OmrImportService(context).load(original.source.sha256)
        require(archive.score == original) { "原始识谱归档与当前乐谱不一致；未覆盖修订" }
        checkCancellation()
        require(!Files.isSymbolicLink(root.toPath())) { "重识别归档目录不能为符号链接" }
        Files.createDirectories(root.toPath())
        val existing = requireNotNull(root.listFiles()) { "无法检查重识别归档预算，未开始新识别" }
        require(existing.size < MAX_RECORDS) { "已保留 $MAX_RECORDS 次重识别记录；本次未开始，旧记录未删除" }
        var used = 0L
        existing.forEach { directory ->
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "重识别归档含非普通目录，未覆盖" }
            requireNotNull(directory.listFiles()) { "无法读取既有重识别记录，未开始新识别" }.forEach { file ->
                require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "重识别归档含非普通文件，未覆盖" }
                used += file.length()
                require(used <= MAX_TOTAL_BYTES) { "重识别归档超过 64 MiB，已有记录保留" }
            }
        }
        require(MAX_TOTAL_BYTES - used >= MAX_RECORD_BYTES) { "重识别归档剩余预算不足以安全保存一次完整结果（总上限 64 MiB）；旧记录未删除" }
        val storage = requireNotNull(context.getSystemService(android.os.storage.StorageManager::class.java))
        val allocatable = storage.getAllocatableBytes(storage.getUuidForPath(root))
        val available = android.os.StatFs(root.absolutePath).availableBytes
        require(minOf(allocatable, available) >= MAX_RECORD_BYTES) { "本机空间不足以保存重识别结果，已有乐谱未更改" }
        val originalBytes = archive.originalScoreFile.readBytes()
        val runId = UUID.randomUUID().toString()
        val directory = File(root, runId)
        check(directory.mkdir()) { "无法建立重识别记录目录" }
        val manifest = JSONObject().put("schemaVersion", 1).put("runId", runId).put("sourceId", original.source.sha256)
            .put("originalScoreSha256", hash(originalBytes)).put("revisionGeneration", generation)
            .put("status", "RUNNING").put("startedAtMillis", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME).put("gitCommit", BuildConfig.GIT_COMMIT)
            .put("gitTag", BuildConfig.FIELD_TAG).put("worktreeState", BuildConfig.GIT_WORKTREE_STATE).put("buildTime", BuildConfig.BUILD_TIME)
            .put("method", "REAL_LOCAL_IMAGE_REINFERENCE").put("model", "Andromr-segnet308-transformer414-int8")
            .put("pageCount", archive.sourceImages.size).put("completedPages", 0).put("confidenceCalibrated", false)
            .put("limits", JSONObject().put("totalBytes", MAX_TOTAL_BYTES).put("maxRecords", MAX_RECORDS).put("maxRawBytes", MAX_RAW_BYTES))
        val raw = JSONObject().put("schemaVersion", 1).put("runId", runId).put("sourceId", original.source.sha256)
            .put("method", "REAL_LOCAL_IMAGE_REINFERENCE").put("status", "RUNNING").put("byteCount", original.source.byteCount)
            .put("originalScoreSha256", hash(originalBytes)).put("pages", JSONArray())
        try {
            saveManifest(directory, manifest)
            archive.sourceImages.forEach { image ->
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.absolutePath, info)
                require(info.outWidth >= 160 && info.outHeight >= 160 && info.outWidth.toLong() * info.outHeight <= 8_000_000) {
                    "归档原图无法解码或尺寸超出支持范围；未修改原图"
                }
            }
            progress("准备对本机原图进行真实重识别，原始结果与修订保持不变…")
            checkCancellation()
            LocalOMREngine(context).use { engine ->
                archive.sourceImages.forEachIndexed { index, image ->
                    checkCancellation(); NativeOmrModels.checkCancelled()
                    val bitmap = BitmapFactory.decodeFile(image.absolutePath) ?: error("归档第 ${index + 1} 页原图无法解码")
                    try {
                        val page = engine.recognizePage(bitmap, index) { message -> checkCancellation(); progress(message) }
                        raw.getJSONArray("pages").put(page)
                        saveRunningRaw(directory, raw)
                        manifest.put("completedPages", index + 1)
                        saveManifest(directory, manifest)
                    } finally { bitmap.recycle() }
                }
            }
            checkCancellation()
            progress("真实识别已结束，正在组装数字乐谱并校验结构…")
            val candidate = OmrScoreAssembler.assemble(original.source.sha256, original.source.byteCount, raw.getJSONArray("pages"))
            val candidateBytes = ScoreJsonCodec.encode(candidate)
            val pages = raw.getJSONArray("pages")
            val complete = (0 until pages.length()).all { pages.getJSONObject(it).getString("status") == "INFERENCE_FINISHED" }
            raw.put("status", if (complete) "INFERENCE_FINISHED" else "INFERENCE_INCOMPLETE")
            val rawBytes = saveRunningRaw(directory, raw)
            Files.move(File(directory, "raw-progress.json").toPath(), File(directory, "raw-result.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
            writeNew(File(directory, "candidate.json"), candidateBytes)
            require(ScoreJsonCodec.decode(File(directory, "candidate.json").readBytes()) == candidate) { "候选乐谱落盘校验失败" }
            require(hash(File(directory, "raw-result.json").readBytes()) == hash(rawBytes)) { "原始重识别结果落盘校验失败" }
            require(archive.originalScoreFile.readBytes().contentEquals(originalBytes)) { "原始识谱结果在运行期间发生变化；未应用候选" }
            manifest.put("status", "FINISHED").put("finishedAtMillis", System.currentTimeMillis())
                .put("validation", "NEEDS_REVIEW").put("rawSha256", hash(rawBytes)).put("candidateSha256", hash(candidateBytes))
            saveManifest(directory, manifest)
            Log.i("XiuyinOMR", "Real image reanalysis finished: runId=$runId sourceId=${original.source.sha256}; NEEDS_REVIEW")
            OmrStructurePreview(original.source.sha256, generation, candidate, ScoreStructureTools.summarize(current),
                ScoreStructureTools.summarize(candidate), OmrPreviewMethod.REAL_IMAGE_RECOGNITION, runId)
        } catch (error: Exception) {
            val cancelled = error is CancellationException || error is InterruptedException
            val status = if (cancelled) "CANCELLED" else "FAILED"
            manifest.put("status", status).put("finishedAtMillis", System.currentTimeMillis())
                .put("error", (error.message ?: error.javaClass.simpleName).take(2000)).put("stack", error.stackTraceToString().take(16000))
            try {
                val rawFile = File(directory, "raw-result.json")
                if (!rawFile.exists()) {
                    val partial = raw.put("status", status).toString().toByteArray(Charsets.UTF_8)
                    if (partial.size <= MAX_RAW_BYTES) {
                        saveRunningRaw(directory, raw)
                        Files.move(File(directory, "raw-progress.json").toPath(), rawFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } else manifest.put("partialRawOmitted", "Output exceeded bounded archive budget; completed page count retained")
                }
                saveManifest(directory, manifest)
            } catch (evidenceError: Exception) { error.addSuppressed(evidenceError) }
            Log.e("XiuyinOMR", "Real reanalysis $status; evidence=$runId; originals untouched", error)
            throw error
        }
    }

    /** Only this run's in-progress file is rewritten; finished records are immutable. */
    private fun saveRunningRaw(directory: File, raw: JSONObject): ByteArray {
        val bytes = raw.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RAW_BYTES) { "本次原始识别输出超过 32 MiB，停止且保留已写入记录" }
        val file = File(directory, "raw-progress.json")
        require(!Files.isSymbolicLink(file.toPath())) { "重识别进度文件不是普通文件" }
        FileOutputStream(file, false).use { it.write(bytes); it.fd.sync() }
        return bytes
    }

    private fun saveManifest(directory: File, manifest: JSONObject) {
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "重识别状态记录超出限制" }
        val temporary = File(directory, "manifest.next")
        FileOutputStream(temporary, false).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), File(directory, "manifest.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        require(!file.exists()) { "重识别证据文件已存在，未覆盖" }
        Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { it.write(bytes) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        private val lock = Any()
        private const val MAX_RECORDS = 20
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_RAW_BYTES = 32 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_RECORD_BYTES = 36L * 1024 * 1024 + 128L * 1024
    }
}
