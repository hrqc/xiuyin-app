package com.xiuyin.app.midi

import android.content.Context
import android.net.Uri
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreJsonCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class MidiImportResult(val score: DigitalScore, val sourceId: String, val sourceDirectory: File,
    val originalMidiFile: File, val originalScoreFile: File, val midi: MidiDocument, val reusedExisting: Boolean)
data class MidiSourceInventory(val ids: List<String>, val issues: List<String>)

/** Immutable original SMF and score snapshot; metadata/revisions are managed by the existing app. */
class MidiImportService(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "midi-sources")

    fun importDocument(uri: Uri): MidiImportResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { MidiCodec.checkCancelled(); val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= MidiCodec.MAX_BYTES) { "MIDI 超过 16 MiB" }; output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: error("无法读取 MIDI 文件")
        return importBytes(bytes)
    }

    fun importBytes(bytes: ByteArray): MidiImportResult = locked {
        val sourceId = sha(bytes)
        if (File(root, sourceId).exists()) return@locked load(sourceId).copy(reusedExisting = true)
        val document = MidiCodec.parse(bytes)
        val score = MidiScoreConverter.convert(document, sourceId, bytes.size)
        val scoreBytes = ScoreJsonCodec.encode(score)
        require(root.listFiles().orEmpty().sumOf { dir -> dir.listFiles().orEmpty().sumOf { it.length() } } + bytes.size + scoreBytes.size <= 256L * 1024 * 1024) { "本地 MIDI 归档已达 256 MiB；已有资料保留" }
        val pending = File(root, ".pending-${UUID.randomUUID()}")
        check(pending.mkdir()) { "不能创建 MIDI 归档" }
        try {
            writeNew(File(pending, "original.mid"), bytes)
            writeNew(File(pending, "original.json"), scoreBytes)
            val raw = JSONObject().put("schemaVersion", 1).put("sourceId", sourceId)
                .put("sourceBytes", bytes.size).put("scoreSha256", sha(scoreBytes)).put("format", document.format).put("ppq", document.ppq)
                .put("tempos", JSONArray(document.tempos.map { JSONObject().put("tick", it.tick).put("microsecondsPerQuarter", it.microsecondsPerQuarter) }))
                .put("meters", JSONArray(document.meters.map { JSONObject().put("tick", it.tick).put("numerator", it.numerator).put("denominator", it.denominator) }))
                .put("keys", JSONArray(document.keys.map { JSONObject().put("tick", it.tick).put("fifths", it.fifths).put("minor", it.minor) }))
                .put("pedals", JSONArray(document.pedals.map { JSONObject().put("tick", it.tick).put("channel", it.channel).put("value", it.value) }))
                .put("noteCount", document.notes.size).put("endTick", document.endTick).put("warnings", JSONArray(document.warnings))
            writeNew(File(pending, "manifest.json"), raw.toString().toByteArray(Charsets.UTF_8))
            val target = File(root, sourceId)
            require(!target.exists()) { "同来源 MIDI 已存在，未覆盖" }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            load(sourceId)
        } catch (failure: Exception) {
            try { writeNew(File(pending, "failure.txt"), (failure.message ?: failure.javaClass.simpleName).toByteArray()) }
            catch (diagnosticFailure: Exception) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
    }

    fun load(sourceId: String): MidiImportResult {
        require(ID.matches(sourceId)) { "MIDI 来源 ID 无效" }
        ensureRoot()
        val directory = File(root, sourceId)
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "MIDI 原始归档缺失" }
        val midiFile = File(directory, "original.mid"); val scoreFile = File(directory, "original.json")
        val raw = JSONObject(read(File(directory, "manifest.json"), 8 * 1024 * 1024).toString(Charsets.UTF_8))
        val bytes = read(midiFile, MidiCodec.MAX_BYTES)
        require(raw.getString("sourceId") == sourceId && raw.getInt("sourceBytes") == bytes.size && sha(bytes) == sourceId) { "MIDI 原始字节校验失败；未修改或重建" }
        val scoreBytes = read(scoreFile, ScoreJsonCodec.MAX_SCORE_BYTES)
        require(sha(scoreBytes) == raw.getString("scoreSha256")) { "MIDI 原始乐谱快照校验失败" }
        val score = ScoreJsonCodec.decode(scoreBytes)
        require(score.id == sourceId && score.source.sha256 == sourceId && score.source.format == "MIDI") { "MIDI 来源绑定无效" }
        return MidiImportResult(score, sourceId, directory, midiFile, scoreFile, MidiCodec.parse(bytes), false)
    }

    fun inspect(): MidiSourceInventory {
        ensureRoot(); val ids = mutableListOf<String>(); val issues = mutableListOf<String>()
        root.listFiles().orEmpty().forEach { entry ->
            when {
                Files.isSymbolicLink(entry.toPath()) -> issues += "MIDI 归档含不安全链接：${entry.name.take(100)}"
                entry.name == ".archive.lock" && entry.isFile && entry.length() == 0L -> Unit
                ID.matches(entry.name) && entry.isDirectory -> ids += entry.name
                else -> issues += "未完成或未知 MIDI 归档 ${entry.name.take(100)}；未作为成功来源"
            }
        }
        return MidiSourceInventory(ids.sorted(), issues)
    }
    fun listSourceIds(): List<String> = inspect().ids

    /** Only after explicit deletion confirmation and durable metadata tombstone. */
    fun delete(sourceId: String): Boolean = locked {
        require(ID.matches(sourceId)) { "MIDI 删除 ID 无效" }
        val base = root.toPath().toAbsolutePath().normalize(); val target = base.resolve(sourceId).normalize()
        require(target.parent == base && target != base)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@locked false
        require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "MIDI 目标不是归档目录" }
        val paths = mutableListOf<java.nio.file.Path>()
        Files.walk(target, 20).use { entries -> entries.forEach { path ->
            require(path.toAbsolutePath().normalize().startsWith(target) && !Files.isSymbolicLink(path)) { "MIDI 删除路径包含不安全链接" }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            paths.add(path); require(paths.size <= 10_000)
        } }
        paths.sortedByDescending { it.nameCount }.forEach(Files::delete)
        true
    }

    fun exportCurrent(uri: Uri, score: DigitalScore, beatsPerMinute: Int = 120): MidiExportResult {
        val result = MidiExporter.export(score, beatsPerMinute)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(result.bytes); it.flush() } ?: error("无法写入用户选择的 MIDI 文件")
        return result
    }

    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        ensureRoot(); val path = File(root, ".archive.lock").toPath()
        require(!Files.isSymbolicLink(path)) { "MIDI 归档锁路径无效" }
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { require(channel.size() == 0L); action() }
        }
    }
    private fun ensureRoot() { require(!Files.isSymbolicLink(root.toPath())); Files.createDirectories(root.toPath()) }
    private fun writeNew(file: File, bytes: ByteArray) {
        require(file.createNewFile()) { "MIDI 归档文件已存在，未覆盖" }
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun read(file: File, maximum: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1L..maximum.toLong()) { "MIDI 归档文件缺失或大小异常：${file.name}" }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val count = input.read(chunk); if (count < 0) break; require(out.size() + count <= maximum); out.write(chunk, 0, count) }
            out.toByteArray()
        }
    }
    companion object {
        private val ID = Regex("[a-f0-9]{64}")
        private val monitor = Any()
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
