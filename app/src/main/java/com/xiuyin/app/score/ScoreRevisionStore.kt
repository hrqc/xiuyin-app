package com.xiuyin.app.score

import com.xiuyin.app.score.ScoreJsonCodec.boolean
import com.xiuyin.app.score.ScoreJsonCodec.fields
import com.xiuyin.app.score.ScoreJsonCodec.int
import com.xiuyin.app.score.ScoreJsonCodec.long
import com.xiuyin.app.score.ScoreJsonCodec.obj
import com.xiuyin.app.score.ScoreJsonCodec.objects
import com.xiuyin.app.score.ScoreJsonCodec.string
import com.xiuyin.app.score.ScoreJsonCodec.strings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class ScoreRevisionSnapshot(
    val sourceId: String,
    val generation: Long,
    val current: DigitalScore,
    val verifiedEventIds: Set<String>,
    val fingerings: Map<String, Int>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val historyTruncated: Boolean = false,
)

open class ScoreRevisionException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ScoreRevisionConflictException(message: String) : ScoreRevisionException(message)

/**
 * Independent local revisions, keyed by ScoreMetadata UUID; source XML and old databases are never touched.
 * Call load with the immutable original before save on each instance. All calls belong on an IO worker.
 * Each atomic file contains current content and at most 20 undo/redo states; old app versions ignore it.
 * Current entry <=4 MiB, revision file <=16 MiB, directory including leftovers <=64 MiB.
 * Oldest history may be trimmed for these limits and is visibly reported as historyTruncated.
 */
class ScoreRevisionStore(root: File) {
    private val directory = root.absoluteFile.toPath().normalize()
    private val monitor = locks.computeIfAbsent(directory.toString()) { Any() }
    private val bindings = ConcurrentHashMap<String, OriginalBinding>()

    fun load(scoreId: String, original: DigitalScore): ScoreRevisionSnapshot = guarded(scoreId) {
        val bytes = ScoreJsonCodec.encode(original)
        val binding = OriginalBinding(bytes, sha256(bytes), original.source)
        val document = read(scoreId, binding)
        bindings[scoreId] = binding
        snapshot(document)
    }

    fun save(
        scoreId: String,
        expectedGeneration: Long,
        current: DigitalScore,
        verifiedEventIds: Set<String>,
        fingerings: Map<String, Int>,
    ): ScoreRevisionSnapshot = guarded(scoreId) {
        val binding = bindings[scoreId] ?: throw ScoreRevisionException("保存前须先读取同一份原始乐谱")
        val document = read(scoreId, binding)
        checkGeneration(document, expectedGeneration)
        val proposed = Entry(current, verifiedEventIds.toSet(), fingerings.toMap())
        validateEntry(proposed, binding)
        if (entryJson(proposed).toString() == entryJson(document.current).toString()) return@guarded snapshot(document)
        val next = document.copy(generation = nextGeneration(document.generation), current = proposed,
            undo = document.undo + document.current, redo = emptyList())
        snapshot(write(scoreId, binding, next))
    }

    fun undo(scoreId: String, original: DigitalScore, expectedGeneration: Long): ScoreRevisionSnapshot =
        moveHistory(scoreId, original, expectedGeneration, undo = true)

    fun redo(scoreId: String, original: DigitalScore, expectedGeneration: Long): ScoreRevisionSnapshot =
        moveHistory(scoreId, original, expectedGeneration, undo = false)

    /** Only for the already confirmed metadata-tombstone deletion flow, including damaged revisions. */
    fun delete(scoreId: String): Boolean = guarded(scoreId) {
        val target = target(scoreId)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            bindings.remove(scoreId)
            return@guarded false
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw ScoreRevisionException("删除目标不是普通修订文件；未删除")
        Files.delete(target)
        bindings.remove(scoreId)
        true
    }

    private fun moveHistory(scoreId: String, original: DigitalScore, expectedGeneration: Long, undo: Boolean): ScoreRevisionSnapshot = guarded(scoreId) {
        val originalBytes = ScoreJsonCodec.encode(original)
        val binding = OriginalBinding(originalBytes, sha256(originalBytes), original.source)
        val document = read(scoreId, binding)
        checkGeneration(document, expectedGeneration)
        val history = if (undo) document.undo else document.redo
        if (history.isEmpty()) throw ScoreRevisionException(if (undo) "没有可撤销的修订" else "没有可重做的修订")
        val next = if (undo) document.copy(generation = nextGeneration(document.generation), current = history.last(),
            undo = history.dropLast(1), redo = document.redo + document.current)
        else document.copy(generation = nextGeneration(document.generation), current = history.last(),
            undo = document.undo + document.current, redo = history.dropLast(1))
        val saved = write(scoreId, binding, next)
        bindings[scoreId] = binding
        snapshot(saved)
    }

    private fun read(scoreId: String, binding: OriginalBinding): Document {
        val target = target(scoreId)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Document(0, Entry(ScoreJsonCodec.decode(binding.bytes), emptySet(), emptyMap()), emptyList(), emptyList(), false)
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw ScoreRevisionException("修订目标不是普通文件；未读取或覆盖")
        val bytes = boundedRead(target)
        try {
            val envelope = ScoreJsonCodec.parseObject(bytes, MAX_FILE_BYTES)
            envelope.fields("schemaVersion", "payloadSha256", "payload")
            require(envelope.int("schemaVersion") == SCHEMA_VERSION) { "不支持的修订格式版本" }
            val payload = envelope.obj("payload")
            require(sha256(payload.toString().toByteArray(Charsets.UTF_8)) == envelope.string("payloadSha256")) { "修订内容校验失败" }
            payload.fields("scoreId", "sourceId", "originalDigest", "generation", "current", "undo", "redo", "historyTruncated")
            require(payload.string("scoreId") == scoreId) { "修订 scoreId 与当前乐谱不一致" }
            require(payload.string("sourceId") == binding.source.sha256 && payload.string("originalDigest") == binding.digest) { "修订与原始乐谱不匹配" }
            val generation = payload.long("generation")
            require(generation > 0) { "修订代数无效" }
            val current = readEntry(payload.obj("current"), binding)
            val undo = payload.objects("undo", MAX_HISTORY) { readEntry(it, binding) }
            val redo = payload.objects("redo", MAX_HISTORY) { readEntry(it, binding) }
            require(undo.size + redo.size <= MAX_HISTORY) { "历史修订数量超出限制" }
            return Document(generation, current, undo, redo, payload.boolean("historyTruncated"))
        } catch (error: Exception) {
            throw ScoreRevisionException("修订文件损坏或不兼容：${error.message}；原文件保留，未重建", error)
        }
    }

    private fun readEntry(json: JSONObject, binding: OriginalBinding): Entry {
        json.fields("score", "verifiedEventIds", "fingerings")
        val verified = json.strings("verifiedEventIds", MAX_ANNOTATIONS)
        require(verified.size == verified.toSet().size) { "已确认音符 ID 重复" }
        val fingerings = json.obj("fingerings")
        val keys = fingerings.keys().asSequence().toList()
        require(keys.size <= MAX_ANNOTATIONS) { "指法标记数量超出限制" }
        val entry = Entry(ScoreJsonCodec.fromJson(json.obj("score")), verified.toSet(), keys.associateWith { fingerings.int(it) })
        validateEntry(entry, binding)
        return entry
    }

    private fun validateEntry(entry: Entry, binding: OriginalBinding) {
        ScoreJsonCodec.validate(entry.score)
        require(entry.score.id == binding.source.sha256 && entry.score.source == binding.source) { "修订不得更改原始来源或数字乐谱 ID" }
        val ids = entry.score.parts.flatMap { it.measures }.flatMap { it.events }.map { it.id }.toSet()
        require(entry.verified.size <= MAX_ANNOTATIONS && entry.verified.all { it in ids }) { "确认标记引用了不存在的音符" }
        require(entry.fingerings.size <= MAX_ANNOTATIONS && entry.fingerings.all { (id, finger) -> id in ids && finger in 1..5 }) { "指法必须引用现存音符且为 1–5" }
        require(entryJson(entry).toString().toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) { "当前乐谱及标记超过 4 MiB；已有修订保留" }
    }

    private fun write(scoreId: String, binding: OriginalBinding, proposed: Document): Document {
        var document = proposed
        fun discardOldest() {
            document = when {
                document.undo.isNotEmpty() -> document.copy(undo = document.undo.drop(1), truncated = true)
                document.redo.isNotEmpty() -> document.copy(redo = document.redo.drop(1), truncated = true)
                else -> throw ScoreRevisionException("当前修订超过文件预算；已有内容未覆盖")
            }
        }
        while (document.undo.size + document.redo.size > MAX_HISTORY) discardOldest()
        var bytes = documentBytes(scoreId, binding, document)
        while (bytes.size > MAX_FILE_BYTES) {
            discardOldest()
            bytes = documentBytes(scoreId, binding, document)
        }
        val target = target(scoreId)
        var usedBytes = 0L
        Files.newDirectoryStream(directory).use { entries -> entries.forEach { path ->
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ScoreRevisionException("修订目录包含非普通文件；已有内容保留")
            val size = Files.size(path)
            if (size > MAX_TOTAL_BYTES - usedBytes) throw ScoreRevisionException("修订目录超过 64 MiB 预算；请先处理存储空间")
            usedBytes += size
        } }
        // Include both current target and pending bytes: atomic replacement needs temporary headroom.
        if (bytes.size > MAX_TOTAL_BYTES - usedBytes) throw ScoreRevisionException("修订目录及保存临时空间超过 64 MiB；已有内容保留")
        val pending = directory.resolve(".pending-${UUID.randomUUID()}.tmp")
        var ownedPending = false
        try {
            FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                ownedPending = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // The persistent directory file lock serializes all cooperating writers and the CAS check.
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return document
        } catch (error: Exception) {
            try { if (ownedPending) Files.deleteIfExists(pending) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw ScoreRevisionException("修订保存失败：${error.message}；此前修订未主动删除", error)
        }
    }

    private fun documentBytes(scoreId: String, binding: OriginalBinding, document: Document): ByteArray {
        val payload = JSONObject().put("scoreId", scoreId).put("sourceId", binding.source.sha256).put("originalDigest", binding.digest)
            .put("generation", document.generation).put("historyTruncated", document.truncated).put("current", entryJson(document.current))
            .put("undo", JSONArray().also { array -> document.undo.forEach { array.put(entryJson(it)) } })
            .put("redo", JSONArray().also { array -> document.redo.forEach { array.put(entryJson(it)) } })
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("payloadSha256", sha256(payload.toString().toByteArray(Charsets.UTF_8)))
            .put("payload", payload).toString().toByteArray(Charsets.UTF_8)
    }

    private fun entryJson(entry: Entry): JSONObject = JSONObject().put("score", ScoreJsonCodec.toJson(entry.score))
        .put("verifiedEventIds", JSONArray(entry.verified.sorted()))
        .put("fingerings", JSONObject().also { json -> entry.fingerings.toSortedMap().forEach { (id, finger) -> json.put(id, finger) } })

    private fun snapshot(document: Document) = ScoreRevisionSnapshot(document.current.score.source.sha256, document.generation,
        document.current.score, document.current.verified.toSet(), document.current.fingerings.toMap(),
        document.undo.isNotEmpty(), document.redo.isNotEmpty(), document.truncated)

    private fun checkGeneration(document: Document, expected: Long) {
        if (expected < 0 || expected != document.generation) throw ScoreRevisionConflictException("乐谱已由其他操作更新，请重新打开后修改（期望 $expected，实际 ${document.generation}）")
    }
    private fun nextGeneration(current: Long): Long {
        if (current == Long.MAX_VALUE) throw ScoreRevisionException("修订代数已达上限，已有数据保留")
        return current + 1
    }

    private fun <T> guarded(scoreId: String, action: () -> T): T {
        require(uuidPattern.matches(scoreId)) { "scoreId 必须为规范 UUID，不能使用名称或路径" }
        return synchronized(monitor) {
            try {
                if (Files.isSymbolicLink(directory)) throw ScoreRevisionException("修订目录不能为符号链接")
                Files.createDirectories(directory)
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw ScoreRevisionException("修订路径不是目录")
                val lockPath = directory.resolve(LOCK_NAME)
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw ScoreRevisionException("修订锁不是普通文件")
                }
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.lock().use {
                        if (channel.size() != 0L) throw ScoreRevisionException("修订锁文件异常")
                        action()
                    }
                }
            } catch (error: ScoreRevisionException) { throw error }
            catch (error: Exception) { throw ScoreRevisionException(error.message ?: "修订操作失败", error) }
        }
    }

    private fun target(scoreId: String) = directory.resolve("$scoreId.json")
    private fun boundedRead(path: Path): ByteArray {
        val size = Files.size(path)
        if (size !in 1..MAX_FILE_BYTES.toLong()) throw ScoreRevisionException("修订文件为空或超过 16 MiB；未重建")
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream(size.toInt())
            val block = ByteArray(8_192)
            while (true) {
                val read = input.read(block)
                if (read < 0) break
                if (output.size() + read > MAX_FILE_BYTES) throw ScoreRevisionException("读取中的修订文件超过 16 MiB")
                output.write(block, 0, read)
            }
            output.toByteArray()
        }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class OriginalBinding(val bytes: ByteArray, val digest: String, val source: ScoreSource)
    private data class Entry(val score: DigitalScore, val verified: Set<String>, val fingerings: Map<String, Int>)
    private data class Document(val generation: Long, val current: Entry, val undo: List<Entry>, val redo: List<Entry>, val truncated: Boolean)
    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_HISTORY = 20
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val LOCK_NAME = ".revisions.lock"
        private const val MAX_ANNOTATIONS = 50_000
        private val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}