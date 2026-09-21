package com.xiuyin.app.score

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Immutable original MusicXML bytes, independent of the version 1 SQLite database. */
data class SavedScoreSource(val id: String, val bytes: ByteArray)

data class ScoreSourceInventory(val ids: List<String>, val issues: List<String>)

class ScoreStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Local-only source archive. The caller parses and validates before save, and runs IO off the UI
 * thread. No display name, document URI or score metadata is ever used as a filesystem path.
 * Source content is immutable; only an explicit confirmed delete may remove a source.
 * Rollback to an older app leaves these files untouched.
 */
class ScoreSourceStore(root: File) {
    private val directory = root.absoluteFile.toPath().normalize()
    private val lock = locks.computeIfAbsent(directory.toString()) { Any() }

    fun listIds(): List<String> = inspect().ids

    /** Damaged/unknown entries are visible without making healthy sources unavailable. */
    fun inspect(): ScoreSourceInventory = synchronized(lock) {
        ensureDirectory()
        val ids = mutableListOf<String>()
        val issues = mutableListOf<String>()
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { path ->
                val name = path.fileName.toString()
                when {
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                        issues += "原谱目录中存在非普通文件：${displayName(name)}；未读取或删除"
                    name == ARCHIVE_LOCK && Files.size(path) == 0L -> Unit
                    name == ARCHIVE_LOCK -> issues += "原谱归档锁文件内容异常；写入和删除已阻止"
                    sourceName.matches(name) -> ids += name.removeSuffix(SUFFIX)
                    name.startsWith(PENDING_PREFIX) && name.endsWith(".tmp") ->
                        issues += "发现未完成导入的临时文件：${displayName(name)}；原文件已保留"
                    else -> issues += "原谱目录中存在未知文件：${displayName(name)}；未读取或删除"
                }
            }
        }
        ScoreSourceInventory(ids.sorted(), issues.sorted())
    }

    fun load(id: String): SavedScoreSource = synchronized(lock) {
        require(sourceId.matches(id)) { "原谱 ID 格式无效" }
        ensureDirectory()
        val path = directory.resolve("$id$SUFFIX")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ScoreStorageException("原谱文件不存在或不是普通文件：$id")
        }
        val length = Files.size(path)
        if (length !in 1L..MAX_SOURCE_BYTES.toLong()) {
            throw ScoreStorageException("原谱文件大小无效：$id；允许 1 字节至 2 MiB")
        }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream(length.toInt())
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (output.size() + count > MAX_SOURCE_BYTES) {
                    throw ScoreStorageException("读取中的原谱超过 2 MiB：$id")
                }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.isEmpty() || sha256(bytes) != id) {
            throw ScoreStorageException("原谱校验失败，文件可能损坏：$id；原文件未修改")
        }
        SavedScoreSource(id, bytes)
    }

    fun save(bytes: ByteArray): SavedScoreSource = synchronized(lock) {
        require(bytes.size in 1..MAX_SOURCE_BYTES) { "原谱大小必须为 1 字节至 2 MiB" }
        val snapshot = bytes.copyOf()
        val id = sha256(snapshot)
        withArchiveLock {
            val target = directory.resolve("$id$SUFFIX")
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return@withArchiveLock verifyExisting(id, snapshot)
            }
            var sourceCount = 0
            var totalBytes = 0L
            Files.newDirectoryStream(directory).use { entries ->
                entries.forEach { path ->
                    if (sourceName.matches(path.fileName.toString())) sourceCount++
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        val length = Files.size(path)
                        // Saturating addition keeps unexpected oversized files from overflowing.
                        totalBytes = if (length > MAX_TOTAL_BYTES - totalBytes) MAX_TOTAL_BYTES else totalBytes + length
                    }
                }
            }
            if (sourceCount >= MAX_SOURCES) throw ScoreStorageException("本地原谱已达到 100 份上限；已有原谱保留")
            if (totalBytes > MAX_TOTAL_BYTES - snapshot.size) {
                throw ScoreStorageException("本地原谱与遗留文件已达到 100 MiB 空间上限；已有文件保留")
            }
            val pending = directory.resolve("$PENDING_PREFIX${UUID.randomUUID()}.tmp")
            var ownsPending = false
            try {
                FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    ownsPending = true
                    val buffer = ByteBuffer.wrap(snapshot)
                    while (buffer.hasRemaining()) output.write(buffer)
                    output.force(true)
                }
                // All cooperating app processes hold the same persistent exclusive file lock.
                // Re-check under that lock before rename: never replace a source, including a
                // damaged source whose filename has the expected hash. Android may deny hard links.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    verifyExisting(id, snapshot)
                    Files.delete(pending)
                } else {
                    Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
                }
                SavedScoreSource(id, snapshot)
            } catch (error: Exception) {
                try {
                    if (ownsPending) Files.deleteIfExists(pending)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw ScoreStorageException("原谱保存失败：${error.message ?: error.javaClass.simpleName}；已有原谱未替换", error)
            }
        }
    }

    /** Only after an explicit user-confirmed deletion has a metadata tombstone. */
    fun delete(id: String): Boolean = synchronized(lock) {
        require(sourceId.matches(id)) { "原谱 ID 格式无效" }
        withArchiveLock {
            val path = directory.resolve("$id$SUFFIX")
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withArchiveLock false
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw ScoreStorageException("删除目标不是普通原谱文件；未操作该路径")
            }
            Files.delete(path)
            true
        }
    }

    /** The lock file is never renamed or deleted, so every process locks the same inode. */
    private fun <T> withArchiveLock(action: () -> T): T {
        ensureDirectory()
        val path = directory.resolve(ARCHIVE_LOCK)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ScoreStorageException("原谱归档锁不是普通文件；写入和删除已阻止")
        }
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                if (channel.size() != 0L) throw ScoreStorageException("原谱归档锁文件内容异常；写入和删除已阻止")
                action()
            }
        }
    }
    private fun verifyExisting(id: String, expected: ByteArray): SavedScoreSource {
        val existing = load(id)
        if (!existing.bytes.contentEquals(expected)) {
            throw ScoreStorageException("同 ID 原谱内容冲突：$id；已有原谱未替换")
        }
        return existing
    }

    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory)) throw ScoreStorageException("原谱目录不能是符号链接")
        Files.createDirectories(directory)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw ScoreStorageException("原谱存储路径不是目录")
        }
    }

    private fun displayName(name: String): String = name.take(100).map { if (it.isISOControl()) '_' else it }.joinToString("")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
        const val MAX_SOURCES = 100
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        const val ARCHIVE_LOCK = ".archive.lock"
        private const val SUFFIX = ".musicxml"
        private const val PENDING_PREFIX = ".pending-"
        private val sourceId = Regex("[a-f0-9]{64}")
        private val sourceName = Regex("[a-f0-9]{64}\\.musicxml")
        private val locks = ConcurrentHashMap<String, Any>()
    }
}