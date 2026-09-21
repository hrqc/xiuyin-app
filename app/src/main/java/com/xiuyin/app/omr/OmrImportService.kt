package com.xiuyin.app.omr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.util.Log
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreJsonCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

interface OMREngine : AutoCloseable {
    fun recognizePage(bitmap: Bitmap, pageIndex: Int, progress: (String) -> Unit): JSONObject
}

class LocalOMREngine(context: Context) : OMREngine {
    private val models: NativeOmrModels
    init {
        val directory = OmrModelFiles.installAndVerify(context)
        val vocabulary = context.assets.open("omr/vocabulary.json").bufferedReader().use { JSONObject(it.readText()) }
        models = NativeOmrModels(directory, vocabulary, 2)
    }
    override fun recognizePage(bitmap: Bitmap, pageIndex: Int, progress: (String) -> Unit): JSONObject =
        OmrPageRecognizer(models).recognize(bitmap, pageIndex, progress)
    override fun close() = models.close()
}

data class OmrSourceInventory(val ids: List<String>, val issues: List<String>)

data class OmrImportResult(
    val score: DigitalScore,
    val sourceId: String,
    val sourceDirectory: File,
    val rawResultFile: File,
    val originalScoreFile: File,
    val sourceImages: List<File>,
    val reusedExisting: Boolean,
)

/** Blocking local IO; invoke on a cancellable IO worker. Never writes metadata or user revisions. */
class OmrImportService(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "omr-sources")

    fun importDocuments(uris: List<Uri>, progress: (String) -> Unit = {}): OmrImportResult = importImages(uris, progress)

    fun importImages(uris: List<Uri>, progress: (String) -> Unit = {}): OmrImportResult = synchronized(importLock) {
        require(uris.size in 1..20) { "请选择 1–20 页谱图" }
        ensureRoot()
        var total = 0L
        val originals = mutableListOf<ByteArray>()
        var preparedPageCount = 0
        var preparedBytes = 0L
        val images = uris.flatMapIndexed { index, uri ->
            NativeOmrModels.checkCancelled()
            progress("正在本地读取第 ${index + 1} 页原图…")
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= MAX_SOURCE_BYTES) { "单份原始文件超过 64 MiB，请分页导入" }
                    total += count; require(total <= MAX_SOURCE_BYTES) { "本次原图合计超过 64 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法打开第 ${index + 1} 页原图")
            require(bytes.isNotEmpty()) { "第 ${index + 1} 页原图为空" }
            originals += bytes
            require(preparedPageCount < 20) { "累计超过 20 页，请分页导入" }
            val prepared = if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                renderPdf(bytes, progress, 20 - preparedPageCount, MAX_SOURCE_BYTES - preparedBytes)
            } else {
            require(bytes.size <= MAX_IMAGE_BYTES) { "单张原图超过 20 MiB，请按页导入" }
            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
            require(info.outWidth >= 160 && info.outHeight >= 160 && info.outWidth.toLong() * info.outHeight <= 8_000_000L) {
                "第 ${index + 1} 页不是有效谱图，或超过 800 万像素；请按页/谱表裁剪"
            }
            listOf(normalizeJpegOrientation(bytes))
            }
            preparedPageCount += prepared.size
            preparedBytes += prepared.sumOf { it.size.toLong() }
            require(preparedPageCount <= 20 && preparedBytes <= MAX_SOURCE_BYTES && prepared.all { it.size <= MAX_IMAGE_BYTES }) {
                "累计识谱图超过 20 页或 64 MiB，请分页导入"
            }
            prepared
        }
        require(images.size in 1..20 && images.sumOf { it.size.toLong() } <= MAX_SOURCE_BYTES) { "PDF 渲染后超过 20 页或 64 MiB，请分页导入" }
        val sourceId = sha256(originals.map { "${it.size}:${sha256(it)}\n" }.joinToString("").toByteArray(Charsets.UTF_8))
        if (File(root, sourceId).exists()) return@synchronized load(sourceId).copy(reusedExisting = true)
        require(root.listFiles().orEmpty().sumOf { folder -> folder.listFiles().orEmpty().sumOf { it.length() } } + total <= MAX_ARCHIVE_BYTES) {
            "本地识谱原图归档已达 512 MiB，请先管理原图；已有资料未删除"
        }
        val pending = File(root, ".pending-${UUID.randomUUID()}")
        check(pending.mkdir()) { "无法建立识谱临时归档" }
        val raw = JSONObject().put("schemaVersion", 1).put("sourceId", sourceId)
            .put("pagePreparation", "Original documents retained byte-for-byte; JPEG EXIF orientation applied to inference pages; PDFs rendered up to 3 pixels/point and 2560px longest side; no user crop applied").put("status", "RUNNING").put("pages", JSONArray()).put("images", JSONArray()).put("documents", JSONArray())
        try {
            originals.forEachIndexed { index, bytes ->
                val name = "original-${index.toString().padStart(3, '0')}.document"
                writeNew(File(pending, name), bytes)
                raw.getJSONArray("documents").put(JSONObject().put("name", name).put("sha256", sha256(bytes)).put("bytes", bytes.size))
            }
            images.forEachIndexed { index, bytes ->
                val name = "page-${index.toString().padStart(3, '0')}.image"
                writeNew(File(pending, name), bytes)
                raw.getJSONArray("images").put(JSONObject().put("name", name).put("sha256", sha256(bytes)).put("bytes", bytes.size))
            }
            LocalOMREngine(context).use { engine ->
                images.forEachIndexed { index, bytes ->
                    NativeOmrModels.checkCancelled()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("原图解码失败")
                    try { raw.getJSONArray("pages").put(engine.recognizePage(bitmap, index, progress)) }
                    finally { bitmap.recycle() }
                }
            }
            progress("正在校验节拍、升降号及上下谱表…")
            val score = OmrScoreAssembler.assemble(sourceId, total.toInt(), raw.getJSONArray("pages"))
            val original = ScoreJsonCodec.encode(score)
            val pages = raw.getJSONArray("pages")
            val complete = (0 until pages.length()).all { pages.getJSONObject(it).getString("status") == "INFERENCE_FINISHED" }
            raw.put("status", if (complete) "INFERENCE_FINISHED" else "INFERENCE_INCOMPLETE").put("validation", "NEEDS_REVIEW")
                .put("originalScoreSha256", sha256(original)).put("byteCount", total)
            writeNew(File(pending, "original.json"), original)
            writeNew(File(pending, "raw-result.json"), raw.toString().toByteArray(Charsets.UTF_8))
            val target = File(root, sourceId)
            require(!target.exists()) { "同来源识谱目录已存在；未覆盖" }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Log.i("XiuyinOMR", "Local image import finished: sourceId=$sourceId pages=${images.size} issues=${score.issues.size}; NEEDS_REVIEW")
            load(sourceId)
        } catch (error: Exception) {
            raw.put("status", "FAILED").put("error", error.message ?: error.javaClass.simpleName)
            try { writeNew(File(pending, "failure.json"), raw.toString().toByteArray(Charsets.UTF_8)) }
            catch (evidenceError: Exception) { error.addSuppressed(evidenceError) }
            Log.e("XiuyinOMR", "Local OMR failed; diagnostic directory preserved: ${pending.name}", error)
            throw error
        }
    }

    fun load(sourceId: String): OmrImportResult {
        require(SOURCE_ID.matches(sourceId)) { "OMR 来源 ID 无效" }
        ensureRoot()
        val directory = File(root, sourceId)
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "OMR 来源目录不存在" }
        val rawFile = File(directory, "raw-result.json")
        val raw = JSONObject(readBounded(rawFile, MAX_RAW_BYTES).toString(Charsets.UTF_8))
        require(raw.getString("sourceId") == sourceId && raw.getString("status") in listOf("INFERENCE_FINISHED", "INFERENCE_INCOMPLETE")) { "识谱归档未完整完成" }
        val documents = raw.getJSONArray("documents")
        require(documents.length() in 1..20) { "原始文档清单无效" }
        val sourceBinding = (0 until documents.length()).joinToString("") { index ->
            val entry = documents.getJSONObject(index)
            val expectedName = "original-${index.toString().padStart(3, '0')}.document"
            require(entry.getString("name") == expectedName) { "原始文档路径无效" }
            val original = readBounded(File(directory, expectedName), MAX_SOURCE_BYTES)
            require(original.size == entry.getInt("bytes") && sha256(original) == entry.getString("sha256")) { "原始文档校验失败" }
            "${original.size}:${sha256(original)}\n"
        }
        require(sha256(sourceBinding.toByteArray(Charsets.UTF_8)) == sourceId) { "原始文档与来源 ID 不一致" }
        val images = raw.getJSONArray("images")
        require(images.length() in 1..20) { "识谱原图清单无效" }
        val imageFiles = (0 until images.length()).map { index ->
            val entry = images.getJSONObject(index)
            val expectedName = "page-${index.toString().padStart(3, '0')}.image"
            require(entry.getString("name") == expectedName) { "识谱原图路径无效" }
            val file = File(directory, expectedName)
            val bytes = readBounded(file, MAX_IMAGE_BYTES)
            require(bytes.size == entry.getInt("bytes") && sha256(bytes) == entry.getString("sha256")) { "识谱原图校验失败，未修改文件" }
            file
        }
        val originalFile = File(directory, "original.json")
        val bytes = readBounded(originalFile, ScoreJsonCodec.MAX_SCORE_BYTES)
        require(sha256(bytes) == raw.getString("originalScoreSha256")) { "原始识谱结果校验失败，未覆盖用户修订" }
        val score = ScoreJsonCodec.decode(bytes)
        require(score.id == sourceId && score.source.sha256 == sourceId && score.source.format == "OMR_IMAGE") { "OMR 来源绑定不一致" }
        return OmrImportResult(score, sourceId, directory, rawFile, originalFile, imageFiles, false)
    }

    fun listSourceIds(): List<String> = inspect().ids

    fun inspect(): OmrSourceInventory = synchronized(importLock) {
        ensureRoot()
        val ids = mutableListOf<String>()
        val issues = mutableListOf<String>()
        root.listFiles().orEmpty().sortedBy { it.name }.forEach { entry ->
            when {
                Files.isSymbolicLink(entry.toPath()) -> issues += "OMR 归档含符号链接，未读取：${entry.name.take(100)}"
                SOURCE_ID.matches(entry.name) && entry.isDirectory -> ids += entry.name
                entry.name.startsWith(".pending-") -> issues += "发现未完成/失败的 OMR 导入 ${entry.name.take(100)}；原图及失败证据已保留，未作为可用乐谱"
                else -> issues += "OMR 归档含未知条目 ${entry.name.take(100)}；未读取或删除"
            }
        }
        OmrSourceInventory(ids, issues)
    }

    /** Caller must durably tombstone metadata after explicit user confirmation before this method. */
    fun delete(sourceId: String): Boolean = synchronized(importLock) {
        require(SOURCE_ID.matches(sourceId)) { "OMR 来源 ID 无效，禁止删除该路径" }
        ensureRoot()
        val base = root.toPath().toAbsolutePath().normalize()
        val target = base.resolve(sourceId).normalize()
        require(target.parent == base && target.startsWith(base) && target != base) { "OMR 删除路径越界" }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@synchronized false
        require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "OMR 删除目标不是普通归档目录" }
        val paths = mutableListOf<java.nio.file.Path>()
        Files.walk(target, 20).use { entries ->
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().toAbsolutePath().normalize()
                require(entry.startsWith(target) && !Files.isSymbolicLink(entry)) { "OMR 归档包含不安全路径，未删除" }
                require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) { "OMR 归档包含特殊文件，未删除" }
                paths.add(entry)
                require(paths.size <= 10_000) { "OMR 归档条目异常，未删除" }
            }
        }
        paths.sortedByDescending { it.nameCount }.forEach { Files.delete(it) }
        true
    }

    /** Keep untouched camera bytes in original-*.document; inference page records EXIF orientation. */
    private fun normalizeJpegOrientation(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || (bytes[0].toInt() and 255) != 255 || (bytes[1].toInt() and 255) != 216) return bytes
        val orientation = ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return bytes
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("相机原图解码失败")
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> { original.recycle(); error("相机原图方向值无效，请重新保存图片") }
        }
        try {
            val upright = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            try {
                val output = ByteArrayOutputStream()
                check(upright.compress(Bitmap.CompressFormat.PNG, 100, output)) { "保存转正谱页失败" }
                return output.toByteArray().also { require(it.size <= MAX_IMAGE_BYTES) { "转正后谱图过大，请按谱表裁剪" } }
            } finally { if (upright !== original) upright.recycle() }
        } finally { original.recycle() }
    }

    private fun renderPdf(bytes: ByteArray, progress: (String) -> Unit, remainingPages: Int, remainingBytes: Long): List<ByteArray> {
        val temporary = File(context.cacheDir, "omr-pdf-${UUID.randomUUID()}.pdf")
        try {
            writeNew(temporary, bytes)
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount in 1..remainingPages) { "PDF 累计超过 20 页，请分页导入" }
                    var renderedBytes = 0L
                    return (0 until renderer.pageCount).map { pageIndex ->
                        NativeOmrModels.checkCancelled()
                        progress("正在本地高分辨率渲染 PDF 第 ${pageIndex + 1}/${renderer.pageCount} 页…")
                        renderer.openPage(pageIndex).use { page ->
                            val scale = minOf(3.0, 2560.0 / maxOf(page.width, page.height))
                            val width = maxOf(160, (page.width * scale).toInt())
                            val height = maxOf(160, (page.height * scale).toInt())
                            require(width.toLong() * height <= 8_000_000L) { "PDF 页面过大，请分页或裁剪后导入" }
                            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val output = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PDF 页面渲染保存失败" }
                                val image = output.toByteArray()
                                renderedBytes += image.size
                                require(image.size <= MAX_IMAGE_BYTES && renderedBytes <= remainingBytes) { "PDF 渲染图超过本地限制，请分页导入" }
                                image
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun ensureRoot() {
        require(!Files.isSymbolicLink(root.toPath())) { "OMR 归档目录不能为符号链接" }
        Files.createDirectories(root.toPath())
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { it.write(bytes) }
    }
    private fun readBounded(file: File, maximum: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1L..maximum.toLong()) { "OMR 文件缺失、损坏或大小无效：${file.name}" }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= maximum); output.write(buffer, 0, count) }
            output.toByteArray()
        }
    }
    companion object {
        private val importLock = Any()
        private val SOURCE_ID = Regex("[a-f0-9]{64}")
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
        private const val MAX_RAW_BYTES = 32 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private object OmrModelFiles {
    @Synchronized fun installAndVerify(context: Context): File {
        val manifestBytes = context.assets.open("omr/models.json").use { it.readBytes() }
        val manifestHash = OmrImportService.sha256(manifestBytes)
        val directory = File(context.noBackupFilesDir, "omr-models/$manifestHash")
        require(!Files.isSymbolicLink(directory.toPath())) { "OMR 模型目录无效" }
        Files.createDirectories(directory.toPath())
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8)).getJSONArray("models")
        require(manifest.length() == 3) { "OMR 模型清单缺失" }
        for (i in 0 until manifest.length()) {
            val entry = manifest.getJSONObject(i); val name = entry.getString("name")
            require(name in setOf("segnet_308_int8.tflite", "encoder_414_int8.tflite", "decoder_414_int8_scores.onnx"))
            val target = File(directory, name); val expected = entry.getString("sha256"); val size = entry.getLong("bytes")
            if (!target.exists()) {
                val pending = File(directory, ".pending-${UUID.randomUUID()}")
                try {
                    context.assets.open("omr/models/$name").use { input -> FileOutputStream(pending).use { output ->
                        var count = 0L; val chunk = ByteArray(64 * 1024)
                        while (true) { val n = input.read(chunk); if (n < 0) break; count += n; require(count <= size); output.write(chunk, 0, n) }
                        require(count == size); output.fd.sync()
                    } }
                    require(fileHash(pending) == expected) { "OMR 模型包完整性校验失败：$name" }
                    require(!target.exists()) { "OMR 模型目标已存在，未覆盖" }
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally { Files.deleteIfExists(pending.toPath()) }
            }
            require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.length() == size && fileHash(target) == expected) { "OMR 模型损坏：$name；未运行伪识别" }
        }
        return directory
    }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val chunk = ByteArray(64 * 1024); while (true) { val count = input.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
