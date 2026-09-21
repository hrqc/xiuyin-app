package com.xiuyin.app.practice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Local, versioned reports. Corruption is visible; no destructive migration or silent replacement. */
class PracticeReportStore(private val root: File) {
    fun save(report: PracticeReport): File = synchronized(lock) {
        validate(report)
        val folder = folder(report.scoreId)
        val marker = File(folder, "deleted-before.txt")
        if (marker.exists()) require(report.startedAtMillis > marker.readText().trim().toLong()) { "此练习早于记录删除操作，不能恢复已删除记录" }
        val file = File(folder, "${report.id}.xpr")
        val bytes = encode(report)
        if (file.exists()) {
            require(file.readBytes().contentEquals(bytes)) { "报告 ID 冲突，原报告保留" }
            return@synchronized file
        }
        atomicWrite(file, bytes)
        file
    }
    fun load(scoreId: String): List<PracticeReport> = synchronized(lock) {
        val folder = folder(scoreId)
        folder.listFiles().orEmpty().filter { it.name.endsWith(".xpr") }.map { file ->
            check(!Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_BYTES.toLong()) { "练习报告文件无效：${file.name}" }
            val report = decode(file.readBytes())
            check(report.scoreId == scoreId && file.name == "${report.id}.xpr") { "报告归属或文件名不一致" }
            report
        }.sortedByDescending { it.startedAtMillis }
    }
    /** Tombstone rejects an old session racing with deletion; a new session may be saved later. */
    fun deleteForScore(scoreId: String, deletedAtMillis: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        require(deletedAtMillis >= 0)
        val folder = folder(scoreId)
        val marker = File(folder, "deleted-before.txt")
        val timestamp = maxOf(deletedAtMillis, if (marker.exists()) marker.readText().trim().toLong() else 0)
        atomicWrite(marker, timestamp.toString().toByteArray(Charsets.UTF_8))
        var count = 0
        folder.listFiles().orEmpty().filter { it.name.endsWith(".xpr") }.forEach { file ->
            check(!Files.isSymbolicLink(file.toPath())) { "拒绝删除非本地报告" }
            check(file.delete()) { "无法删除报告：${file.name}" }; count++
        }
        count
    }
    private fun folder(scoreId: String): File {
        checkId(scoreId)
        if (!root.exists()) check(root.mkdirs()) { "不能创建练习报告目录" }
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "练习报告根目录无效" }
        val folder = File(root, scoreId)
        check(!Files.isSymbolicLink(folder.toPath())) { "练习报告目录不能是链接" }
        if (!folder.exists()) check(folder.mkdir()) { "不能创建乐谱练习目录" }
        check(folder.isDirectory && folder.canonicalFile.parentFile == root.canonicalFile) { "练习报告目录越界" }
        return folder
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        check(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(file.toPath())) { "拒绝覆盖文件链接" }
        val temp = File(file.parentFile, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (temp.exists() && !temp.delete()) throw IllegalStateException("临时报告未清理：${temp.name}") }
    }
    private fun validate(report: PracticeReport) {
        checkId(report.id); checkId(report.scoreId)
        require(report.title.length <= 2048 && report.modelDescription.length <= 2048 && report.revisionGeneration >= 0)
        require(report.startedAtMillis >= 0 && report.endedAtMillis >= report.startedAtMillis)
        require(report.config.startMeasure >= 0 && (report.config.endMeasure == null || report.config.endMeasure >= report.config.startMeasure) && report.config.repeatTarget in 1..20)
        require(listOf(report.confirmedInputs, report.correct, report.wrong, report.missed, report.uncertainFrames, report.completedPasses, report.consecutivePasses).all { it >= 0 })
        require(report.evidence.size <= 2000)
        report.evidence.forEach { item ->
            require(item.targetIndex >= 0 && item.measureIndex >= 0 && item.audioTimeSeconds.isFinite() && item.audioTimeSeconds >= 0 && item.evidenceScore.isFinite() && item.evidenceScore in 0.0..1.0)
            require(item.eventIds.size <= 256 && item.eventIds.all { it.length <= 2048 } && item.reason.length <= 2048)
            require(item.expectedMidi.size <= 128 && item.detectedMidi.size <= 128 && (item.expectedMidi + item.detectedMidi).all { it in 0..127 })
        }
    }
    private fun encode(report: PracticeReport): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(2); out.writeUTF(report.id); out.writeUTF(report.scoreId); out.writeUTF(report.title)
            out.writeLong(report.revisionGeneration); out.writeUTF(report.modelDescription); out.writeLong(report.startedAtMillis); out.writeLong(report.endedAtMillis)
            out.writeUTF(report.config.mode.name); out.writeInt(report.config.startMeasure); out.writeInt(report.config.endMeasure ?: -1); out.writeInt(report.config.repeatTarget); out.writeBoolean(report.config.reviewAcknowledged); out.writeUTF(report.config.hand.name)
            listOf(report.confirmedInputs, report.correct, report.wrong, report.missed, report.uncertainFrames, report.completedPasses, report.consecutivePasses).forEach(out::writeInt)
            out.writeBoolean(report.completed); out.writeBoolean(report.evidenceTruncated); out.writeInt(report.evidence.size)
            report.evidence.forEach { item ->
                out.writeUTF(item.decision.name); out.writeInt(item.targetIndex); out.writeInt(item.measureIndex)
                out.writeInt(item.eventIds.size); item.eventIds.forEach(out::writeUTF)
                out.writeInt(item.expectedMidi.size); item.expectedMidi.sorted().forEach(out::writeInt)
                out.writeInt(item.detectedMidi.size); item.detectedMidi.sorted().forEach(out::writeInt)
                out.writeDouble(item.audioTimeSeconds); out.writeDouble(item.evidenceScore); out.writeUTF(item.reason)
            }
        } }.toByteArray()
        require(payload.size + 32 <= MAX_BYTES) { "练习报告超过本地限制" }
        return MessageDigest.getInstance("SHA-256").digest(payload) + payload
    }
    private fun decode(bytes: ByteArray): PracticeReport {
        check(bytes.size in 33..MAX_BYTES) { "练习报告长度无效" }
        val payload = bytes.copyOfRange(32, bytes.size)
        check(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) { "练习报告校验失败，原文件保留" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val version = input.readInt()
            check(version in 1..2) { "不支持的练习报告版本，原文件保留" }
            val id = input.readUTF(); val scoreId = input.readUTF(); val title = input.readUTF(); val generation = input.readLong()
            val model = input.readUTF(); val start = input.readLong(); val end = input.readLong()
            val config = PracticeConfig(CompanionMode.valueOf(input.readUTF()), input.readInt(), input.readInt().let { if (it == -1) null else it }, input.readInt(), input.readBoolean(), if (version >= 2) PracticeHand.valueOf(input.readUTF()) else PracticeHand.BOTH)
            val counters = List(7) { input.readInt() }
            val completed = input.readBoolean(); val truncated = input.readBoolean()
            val evidence = List(boundedCount(input, 2000)) {
                val decision = PracticeDecision.valueOf(input.readUTF()); val target = input.readInt(); val measure = input.readInt()
                val ids = List(boundedCount(input, 256)) { input.readUTF() }
                val expected = List(boundedCount(input, 128)) { input.readInt() }.toSet()
                val detected = List(boundedCount(input, 128)) { input.readInt() }.toSet()
                PracticeEvidence(decision, target, measure, ids, expected, detected, input.readDouble(), input.readDouble(), input.readUTF())
            }
            check(input.available() == 0) { "报告存在未识别的尾部内容" }
            PracticeReport(id, scoreId, title, generation, model, start, end, config, counters[0], counters[1], counters[2], counters[3], counters[4], counters[5], counters[6], completed, evidence, truncated).also(::validate)
        }
    }
    private fun boundedCount(input: DataInputStream, maximum: Int): Int = input.readInt().also { require(it in 0..maximum) { "报告集合长度无效" } }
    private fun checkId(value: String) { require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "报告或乐谱 ID 无效" } }
    companion object { private val lock = Any(); private const val MAX_BYTES = 4 * 1024 * 1024 }
}


