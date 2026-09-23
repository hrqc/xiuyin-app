package com.xiuyin.app.field

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Disk-only component. The facade invokes it on its single bounded writer, never the PCM thread. */
class FieldSessionStore(
    private val root: File,
    val exports: File,
    private val limits: FieldLimits = FieldLimits(),
    private val now: () -> Long = System::currentTimeMillis,
    private val availableBytes: (File) -> Long = { directory -> Files.getFileStore(directory.toPath()).usableSpace },
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val manifests = linkedMapOf<String, MutableMap<String, Any?>>()
    private var activeId: String? = null
    private var accountedBytes: Long? = null
    private var accountedClips: Int? = null
    private val infoCache = mutableMapOf<String, FieldSessionInfo>()
    private val sessionDataBytes = mutableMapOf<String, Long>()
    private val sessionFileCounts = mutableMapOf<String, Int>()
    private var usingExportReserve = false
    private var spaceBudget = 0L
    private var spaceMeasuredAt: Long? = null
    private var spaceProbeCount = 0L
    private var directoryScanCount = 0L
    private var lastSpaceProbeMs: Double? = null
    private var maxSpaceProbeMs = 0.0
    val recoveredSessionIds = mutableListOf<String>()
    companion object {
        val REQUIRED_FILES = listOf("manifest.json", "device.json", "app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl", "performance.json", "session-summary.json")
        private val STREAM_FILES = setOf("app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl")
    }
    init {
        check(root.isDirectory || root.mkdirs()) { "无法创建实地诊断目录" }
        check(exports.isDirectory || exports.mkdirs()) { "无法创建实地导出目录" }
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            check(folder.name.matches(Regex("[a-zA-Z0-9-]+"))) { "非法诊断会话目录" }
            val source = File(folder, "manifest.json")
            check(source.isFile) { "诊断会话缺少 manifest：${folder.name}，保留原始目录" }
            val manifest = try { FieldJson.objectValue(source.readText()).toMutableMap() } catch (error: Exception) { throw IllegalStateException("诊断清单损坏：${folder.name}，保留原文件", error) }
            check(manifest["sessionId"] == folder.name) { "诊断会话标识不一致" }
            FieldSessionStatus.valueOf(manifest["status"] as String)
            manifests[folder.name] = manifest
            if (manifest["status"] == FieldSessionStatus.ACTIVE.name) {
                manifest["status"] = FieldSessionStatus.INCOMPLETE.name
                manifest["recoveredAtMillis"] = now()
                manifest["endReason"] = "PROCESS_ENDED_WITHOUT_SESSION_END"
                manifest["testEndTime"] = null
                recoverTails(folder)
                atomicJson(source, manifest)
                recoveredSessionIds += folder.name
            }
            sessionDataBytes[folder.name] = size(folder)
            sessionFileCounts[folder.name] = fileCount(folder)
        }
    }
    @Synchronized fun sessions(): List<FieldSessionInfo> = manifests.map { (id, manifest) -> info(id, manifest) }.sortedByDescending { it.startedAtMillis }
    @Synchronized fun active(): FieldSessionInfo? = activeId?.let { info(it, manifests.getValue(it)) }
    @Synchronized fun totalBytes(): Long = accountedBytes ?: measureUsage().also { accountedBytes = it }
    private fun measureUsage(): Long {
        directoryScanCount++
        return size(root) + if (exports.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) 0 else size(exports)
    }
    /** Explicit maintenance/initialization only; log append accounts its actual byte delta. */
    @Synchronized fun refreshAccounting() {
        accountedBytes = measureUsage(); accountedClips = null; infoCache.clear()
        manifests.keys.forEach { sessionDataBytes[it] = size(directory(it)); sessionFileCounts[it] = fileCount(directory(it)) }
    }
    @Synchronized fun totalClips(): Int = accountedClips ?: manifests.keys.sumOf { clips(directory(it)) }.also { accountedClips = it }
    @Synchronized fun writable(): Boolean {
        val probe = File(root, ".write-probe")
        return try { mutateTracked(probe) { FileOutputStream(probe).use { it.write(1); it.fd.sync() }; check(probe.delete()) }; true } catch (_: Exception) { false }
    }
    /** Measurement failure is unavailable capacity, never an invented free-space estimate. */
    @Synchronized fun freeBytes(): Long {
        val began = monotonicNanos()
        spaceProbeCount++
        spaceBudget = try { availableBytes(root).coerceAtLeast(0L) } catch (_: Exception) { 0L }
        val ended = monotonicNanos()
        spaceMeasuredAt = ended
        lastSpaceProbeMs = ((ended - began).coerceAtLeast(0L)) / 1e6
        maxSpaceProbeMs = maxOf(maxSpaceProbeMs, ((ended - began).coerceAtLeast(0L)) / 1e6)
        return spaceBudget
    }
    /** At most one second old, less all locally allocated bytes since the actual measurement. */
    @Synchronized fun spaceBudgetBytes(): Long {
        val age = spaceMeasuredAt?.let { monotonicNanos() - it }
        return if (age == null || age < 0 || age >= 1_000_000_000L) freeBytes() else spaceBudget
    }
    @Synchronized fun storageDiagnostics(): Map<String, Any?> = mapOf("spaceProbeCount" to spaceProbeCount,
        "quotaDirectoryScanCount" to directoryScanCount, "lastSpaceProbeMs" to lastSpaceProbeMs,
        "maxSpaceProbeMs" to maxSpaceProbeMs, "spaceBudgetBytes" to spaceBudget,
        "recordingReserveBytes" to 65_536L, "exportReserveBytes" to exportReserve(),
        "exportReserveDefinition" to "Two archives for the largest unexported session: 2% deflate overhead + 512 bytes per entry + 4096 bytes per ZIP; reserved inside the unchanged total quota.",
        "spaceMeasurementAgeMs" to spaceMeasuredAt?.let { (monotonicNanos() - it).coerceAtLeast(0L) / 1e6 },
        "spaceBudgetDefinition" to "Measured free bytes minus subsequent local allocation; refresh within 1000 ms and before session/clip/export. Actual write failures remain fatal.")
    @Synchronized fun start(label: String, identity: Map<String, Any?>, device: Map<String, Any?>, metadata: Map<String, String> = emptyMap()): FieldSessionInfo {
        check(activeId == null) { "已有实地测试会话" }
        require(label.isNotBlank() && label.length <= 160)
        freeBytes()
        ensureCapacity(65_536, addingSession = true, projectedGrowth = 8192, projectedEntries = 8)
        val id = UUID.randomUUID().toString()
        val folder = directory(id)
        check(folder.mkdir()) { "无法创建测试会话" }
        val started = now()
        val manifest = (identity + mapOf("schemaVersion" to 1, "sessionId" to id, "label" to label,
            "testStartTime" to java.time.Instant.ofEpochMilli(started).toString(), "startedAtMillis" to started,
            "testEndTime" to null, "endedAtMillis" to null, "status" to FieldSessionStatus.ACTIVE.name,
            "exportedAtMillis" to null, "metadata" to metadata, "statisticsAreAccuracy" to false)).toMutableMap()
        // Manifest is first: a crash during initialization remains a visible, recoverable incomplete session.
        atomicJson(File(folder, "manifest.json"), manifest)
        manifests[id] = manifest
        activeId = id
        atomicJson(File(folder, "device.json"), device)
        STREAM_FILES.forEach { name -> val file = File(folder, name); mutateTracked(file) { check(file.createNewFile()) } }
        atomicJson(File(folder, "performance.json"), emptyPerformance())
        atomicJson(File(folder, "session-summary.json"), mapOf("sessionId" to id, "statisticsAreAccuracy" to false))
        append("app.log", mapOf("timestamp" to started, "level" to "INFO", "module" to "Field", "message" to "FieldSessionStart"))
        return info(id, manifest)
    }
    @Synchronized fun append(fileName: String, record: Map<String, Any?>) {
        val id = activeId ?: return
        appendToSession(id, fileName, record)
    }
    @Synchronized fun appendToSession(id: String, fileName: String, record: Map<String, Any?>, reserveBytes: Long = 65_536) {
        requireKnown(id)
        require(fileName in STREAM_FILES)
        val bytes = (FieldJson.encode(record + mapOf("sessionId" to id)) + "\n").toByteArray(Charsets.UTF_8)
        ensureCapacity(bytes.size.toLong() + reserveBytes, protectedId = id, projectedGrowth = bytes.size.toLong(), projectedSessionId = id)
        val file = File(directory(id), fileName)
        mutateTracked(file) { FileOutputStream(file, true).use { it.write(bytes) } }
    }
    /** One capacity check for all records emitted by one recognition frame; no evidence is omitted. */
    @Synchronized fun appendBatch(records: List<Pair<String, Map<String, Any?>>>) {
        val id = activeId ?: return
        val encoded = records.map { (name, record) ->
            require(name in STREAM_FILES)
            name to (FieldJson.encode(record + mapOf("sessionId" to id)) + "\n").toByteArray(Charsets.UTF_8)
        }
        val byteCount = encoded.sumOf { it.second.size.toLong() }
        ensureCapacity(byteCount + 65_536, protectedId = id, projectedGrowth = byteCount, projectedSessionId = id)
        encoded.groupBy({ it.first }, { it.second }).forEach { (name, lines) ->
            val file = File(directory(id), name)
            mutateTracked(file) { FileOutputStream(file, true).use { output -> lines.forEach { output.write(it) } } }
        }
    }
    @Synchronized fun updateDevice(device: Map<String, Any?>) { activeId?.let { atomicJsonQuota(File(directory(it), "device.json"), device) } }
    @Synchronized fun updatePerformance(value: Map<String, Any?>) { activeId?.let { atomicJsonQuota(File(directory(it), "performance.json"), value) } }
    @Synchronized fun checkpoint() {
        val id = activeId ?: return
        STREAM_FILES.forEach { name -> FileOutputStream(File(directory(id), name), true).use { it.fd.sync() } }
    }
    @Synchronized fun end(reason: String): FieldSessionInfo? = withExportReserve { endInternal(reason) }
    private fun endInternal(reason: String): FieldSessionInfo? {
        val id = activeId ?: return null
        appendToSession(id, "app.log", mapOf("timestamp" to now(), "level" to "INFO", "module" to "Field", "message" to "FieldSessionEnd", "reason" to reason), reserveBytes = 8192)
        checkpoint()
        val manifest = manifests.getValue(id)
        val ended = now()
        manifest["status"] = FieldSessionStatus.COMPLETED.name
        manifest["endedAtMillis"] = ended
        manifest["testEndTime"] = java.time.Instant.ofEpochMilli(ended).toString()
        manifest["endReason"] = reason
        atomicJsonQuota(File(directory(id), "manifest.json"), manifest)
        writeSummary(id)
        activeId = null
        return info(id, manifest)
    }
    @Synchronized internal fun saveClip(clip: FieldClip, metadata: Map<String, Any?>): String {
        val id = activeId ?: error("没有实地测试会话")
        require(clip.samples.isNotEmpty() && clip.samples.size <= 5 * clip.sampleRate)
        freeBytes()
        val metaBytes = FieldJson.encode(metadata).toByteArray().size
        ensureCapacity(clip.samples.size * 2L + metaBytes + 65_536, addingClip = true,
            projectedGrowth = clip.samples.size * 2L + metaBytes + 1024, projectedEntries = 2, projectedSessionId = id)
        val folder = File(directory(id), "audio-clips").also { check(it.isDirectory || it.mkdir()) }
        val clipId = UUID.randomUUID().toString()
        val file = File(folder, "$clipId.wav")
        val pcm = java.nio.ByteBuffer.allocate(44 + clip.samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + clip.samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        pcm.putShort(1).putShort(1).putInt(clip.sampleRate).putInt(clip.sampleRate * 2).putShort(2).putShort(16)
        pcm.put("data".toByteArray()).putInt(clip.samples.size * 2)
        clip.samples.forEach { pcm.putShort(it) }
        mutateTracked(file) { FileOutputStream(file).use { it.write(pcm.array()); it.fd.sync() } }
        atomicJson(File(folder, "$clipId.json"), metadata + mapOf("clipId" to clipId, "sessionId" to id,
            "sampleRate" to clip.sampleRate, "firstSample" to clip.firstSample, "endSampleExclusive" to clip.endSample,
            "durationSeconds" to clip.samples.size.toDouble() / clip.sampleRate, "sha256" to sha256(file),
            "groundTruth" to null, "defaultLabel" to FieldFeedbackLabel.UNLABELED.name))
        return clipId
    }
    @Synchronized fun export(id: String): File = withExportReserve { exportInternal(id) }
    private fun exportInternal(id: String): File {
        requireKnown(id)
        freeBytes()
        if (id == activeId) checkpoint()
        writeSummary(id)
        val folder = directory(id)
        check(REQUIRED_FILES.all { File(folder, it).isFile }) { "诊断文件不完整，导出失败" }
        val target = File(exports, "field-$id.zip")
        val temp = File(exports, "field-$id.zip.partial")
        ensureCapacity(65_536, protectedId = id)
        val outputBudget = minOf(limits.maxBytes - totalBytes(), spaceBudgetBytes() - 1_048_576).coerceAtLeast(0L)
        try {
            mutateTracked(temp) {
            val boundedOutput = object : java.io.FilterOutputStream(FileOutputStream(temp)) {
                private var written = 0L
                override fun write(value: Int) { check(written + 1 <= outputBudget) { "ZIP 达到诊断存储上限" }; out.write(value); written++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { check(written + length <= outputBudget) { "ZIP 达到诊断存储上限" }; out.write(bytes, offset, length); written += length }
            }
            ZipOutputStream(boundedOutput).use { zip ->
                folder.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    val relative = file.relativeTo(folder).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            }
            ZipFile(temp).use { zip -> REQUIRED_FILES.forEach { check(zip.getEntry(it) != null) }; zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) Unit } } }
            mutateTracked(temp, target) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return target
        } catch (error: Exception) {
            if (temp.exists()) mutateTracked(temp) { if (!temp.delete()) error.addSuppressed(IllegalStateException("导出临时文件清理失败")) }
            throw IllegalStateException("诊断 ZIP 导出失败：${error.message}", error)
        }
    }
    /** Call only after a user-selected destination was successfully written, not just share-sheet launch. */
    @Synchronized fun markExported(id: String) = withExportReserve {
        requireKnown(id)
        check(File(exports, "field-$id.zip").isFile) { "尚未生成完整 ZIP" }
        check(id != activeId) { "进行中的会话不能标为完整导出，请先结束测试" }
        ZipFile(File(exports, "field-$id.zip")).use { archive ->
            val entry = archive.getEntry("manifest.json") ?: error("ZIP 缺少会话清单")
            val snapshot = FieldJson.objectValue(archive.getInputStream(entry).reader().readText())
            val current = manifests.getValue(id)
            check(snapshot["sessionId"] == id && snapshot["status"] == current["status"] && snapshot["status"] != "ACTIVE" && snapshot["endedAtMillis"] == current["endedAtMillis"]) {
                "保存的是旧活动快照，请结束会话后重新导出"
            }
        }
        val manifest = manifests.getValue(id)
        manifest["exportedAtMillis"] = now()
        atomicJsonQuota(File(directory(id), "manifest.json"), manifest)
    }
    /** Explicit UI confirmation is required before calling this for unexported records. */
    @Synchronized fun clear(ids: Set<String>) {
        check(activeId !in ids) { "请先结束正在进行的实地测试" }
        ids.forEach { requireKnown(it) }
        ids.forEach { remove(it) }
    }
    @Synchronized private fun ensureCapacity(additional: Long, addingSession: Boolean = false, addingClip: Boolean = false,
        protectedId: String? = null, projectedGrowth: Long = 0, projectedEntries: Int = 0, projectedSessionId: String? = activeId) {
        fun full(): Boolean = totalBytes() + additional + (if (usingExportReserve) 0L else exportReserve(projectedSessionId, projectedGrowth, projectedEntries)) > limits.maxBytes || totalClips() + (if (addingClip) 1 else 0) > limits.maxClips || manifests.size + (if (addingSession) 1 else 0) > limits.maxSessions
        while (full()) {
            val removable = manifests.entries.filter { it.key != activeId && it.key != protectedId && it.value["exportedAtMillis"] is Number }
                .minByOrNull { (it.value["startedAtMillis"] as Number).toLong() } ?: error("实地诊断存储已满；未导出会话不会自动删除，请导出后清理")
            remove(removable.key)
        }
        check(spaceBudgetBytes() > additional + 1_048_576 + (if (usingExportReserve) 0L else exportReserve(projectedSessionId, projectedGrowth, projectedEntries))) { "设备可用存储不足，已暂停记录" }
    }
    private fun remove(id: String) {
        val folder = directory(id).canonicalFile
        check(folder.parentFile == root.canonicalFile && folder != root.canonicalFile)
        mutateTracked(folder) { check(folder.deleteRecursively()) { "诊断会话清理失败：$id" } }
        listOf(File(exports, "field-$id.zip"), File(exports, "field-$id.zip.partial")).forEach { file -> mutateTracked(file) { check(!file.exists() || file.delete()) { "导出文件清理失败" } } }
        manifests.remove(id); infoCache.remove(id); sessionDataBytes.remove(id); sessionFileCounts.remove(id); accountedClips = null
    }
    @Synchronized fun markFault(message: String) {
        val id = activeId ?: return
        val manifest = manifests.getValue(id)
        manifest["hasErrors"] = true
        manifest["pauseReason"] = message.take(2048)
        manifest["faultAtMillis"] = now()
        // Normal append keeps 64 KiB reserved for this bounded emergency checkpoint.
        atomicJson(File(directory(id), "manifest.json"), manifest)
        val bytes = (FieldJson.encode(mapOf("timestamp" to now(), "sessionId" to id, "level" to "ERROR", "module" to "Field",
            "message" to (if (message.contains("QueueOverflow")) "QueueOverflow" else "DiagnosticFailure"), "detail" to message.take(2048))) + "\n").toByteArray()
        val log = File(directory(id), "app.log")
        mutateTracked(log) { FileOutputStream(log, true).use { it.write(bytes); it.fd.sync() } }
    }
    private fun writeSummary(id: String) {
        val counts = linkedMapOf<String, Int>()
        fun increment(key: String) { counts[key] = (counts[key] ?: 0) + 1 }
        var wasLocked = false
        val decisionEvents = mutableSetOf<String>()
        var previousUncertain: String? = null
        var previousStream: Any? = null
        val folder = directory(id)
        File(folder, "score-following.jsonl").takeIf { it.exists() }?.forEachLine { line -> if (line.isNotBlank()) {
            val record = FieldJson.objectValue(line)
            val decision = record["decision"] as? String
            if (decision != null && record["confirmedDecision"] == true) {
                val identity = "${record["audioStreamId"]}:${record["decisionEventId"] ?: "${record["frameIndex"]}:$decision"}"
                if (decisionEvents.add(identity)) increment(decision)
            }
            val uncertain = if (decision == "UNCERTAIN") "${record["audioStreamId"]}:${record["currentScorePosition"]}:${record["reason"]}" else null
            if (uncertain != null && uncertain != previousUncertain) increment("UNCERTAIN_EPISODE")
            previousUncertain = uncertain
            if (previousStream != record["audioStreamId"]) wasLocked = false
            val locked = record["lockState"] == true
            if (locked && !wasLocked) increment("LOCK")
            wasLocked = locked; previousStream = record["audioStreamId"]
        } }
        File(folder, "audio-events.jsonl").takeIf { it.exists() }?.forEachLine { line -> if (line.isNotBlank()) {
            val record = FieldJson.objectValue(line)
            if (record["type"] == "RecognitionFrame") {
                increment("recognizedFrames")
                val notes = record["finalCandidates"] as? List<*>
                if (!notes.isNullOrEmpty()) increment("recognizedEvents")
                if (!(record["ambiguousOctaveMidi"] as? List<*>).isNullOrEmpty()) increment("AMBIGUOUS_OCTAVE")
            }
        } }
        File(folder, "app.log").takeIf { it.exists() }?.forEachLine { line -> if (line.isNotBlank()) {
            val record = FieldJson.objectValue(line)
            if (record["beforeSessionStart"] == true) return@forEachLine
            if (record["recoveredFromPreviousProcess"] == true) { increment("RecoveredPreviousException"); return@forEachLine }
            // Context copied from before this session remains visible, but is not a new session event.
            if (record["beforeSessionStart"] != true && record["recoveredFromPreviousProcess"] != true) {
                (record["feedbackLabel"] as? String)?.let { increment(it) }
                when (record["message"]) { "QueueOverflow" -> increment("QueueOverflow"); "ModelLoading" -> increment("ModelLoading"); "Exception" -> increment("Exception") }
            }
        } }
        val initialModelState = (manifests.getValue(id)["metadata"] as? Map<*, *>)?.get("initialModelState")
        val alreadyPrepared = initialModelState in setOf("LOADING", "READY", "WARMING_UP", "RUNNING")
        counts["ModelRestarts"] = maxOf(0, (counts["ModelLoading"] ?: 0) - if (alreadyPrepared) 0 else 1)
        val summary = mapOf("sessionId" to id, "statisticsAreAccuracy" to false, "groundTruth" to null,
            "countDefinition" to "Decisions recorded by follower; frame and octave counts are frame counts, not independent mistakes or measured accuracy.",
            "counts" to counts, "clipCount" to clips(folder), "status" to manifests.getValue(id)["status"],
            "hasErrors" to (manifests.getValue(id)["hasErrors"] ?: false), "pauseReason" to manifests.getValue(id)["pauseReason"])
        atomicJsonQuota(File(folder, "session-summary.json"), summary)
    }
    private fun recoverTails(folder: File) {
        STREAM_FILES.forEach { name ->
            val file = File(folder, name)
            if (!file.exists()) { file.createNewFile(); return@forEach }
            java.io.RandomAccessFile(file, "rw").use { data ->
                var validEnd = 0L
                while (data.filePointer < data.length()) {
                    val start = data.filePointer
                    val line = data.readLine() ?: break
                    val end = data.filePointer
                    data.seek(end - 1)
                    val newline = data.readByte() == 10.toByte()
                    data.seek(end)
                    if (!newline) {
                        // Preserve at most the bounded unfinished record, not an entire 200 MiB log in RAM.
                        data.seek(start)
                        FileOutputStream(File(folder, "$name.recovered-tail")).use { tail ->
                            val buffer = ByteArray(8192)
                            while (data.filePointer < data.length()) { val count = data.read(buffer); if (count < 0) break; tail.write(buffer, 0, count) }
                            tail.fd.sync()
                        }
                        data.setLength(validEnd); data.fd.sync(); break
                    }
                    val decoded = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    check(runCatching { FieldJson.objectValue(decoded) }.isSuccess) { "诊断中间记录损坏：${folder.name}/$name；原文件保留" }
                    validEnd = end
                }
            }
        }
        // Initialization interrupted after manifest creation still produces an honest incomplete export.
        if (!File(folder, "device.json").exists()) atomicJson(File(folder, "device.json"), mapOf("available" to false, "reason" to "Process interrupted during initialization"))
        if (!File(folder, "performance.json").exists()) atomicJson(File(folder, "performance.json"), emptyPerformance())
        if (!File(folder, "session-summary.json").exists()) atomicJson(File(folder, "session-summary.json"), mapOf("status" to "INCOMPLETE"))
    }
    private fun info(id: String, manifest: Map<String, Any?>) = infoCache.getOrPut(id) {
        // Read existing manifest metadata only. Legacy records stay intact and unbound.
        val metadata = manifest["metadata"] as? Map<*, *>
        FieldSessionInfo(id, manifest["label"] as String,
            (manifest["startedAtMillis"] as Number).toLong(), (manifest["endedAtMillis"] as? Number)?.toLong(),
            FieldSessionStatus.valueOf(manifest["status"] as String), (manifest["exportedAtMillis"] as? Number)?.toLong(),
            size(directory(id)) + File(exports, "field-$id.zip").length(), clips(directory(id)),
            scoreId = (metadata?.get("scoreId") as? String)?.takeIf { it.isNotBlank() },
            revisionGeneration = (metadata?.get("revisionGeneration") as? String)?.toLongOrNull()?.takeIf { it >= 0 },
            scoreTitle = (metadata?.get("scoreTitle") as? String)?.take(160))
    }
    private fun requireKnown(id: String) { require(id in manifests) { "未知实地测试会话" } }
    private fun directory(id: String): File { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return File(root, id) }
    private fun atomicJsonQuota(file: File, value: Map<String, Any?>) { ensureCapacity(FieldJson.encode(value).toByteArray().size.toLong() + 4096, protectedId = file.parentFile?.name?.takeIf { it in manifests }, projectedGrowth = FieldJson.encode(value).toByteArray().size.toLong(), projectedSessionId = file.parentFile?.name?.takeIf { it in manifests }); atomicJson(file, value) }
    private fun atomicJson(file: File, value: Map<String, Any?>) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        mutateTracked(temporary) { FileOutputStream(temporary).use { it.write(FieldJson.encode(value).toByteArray(Charsets.UTF_8)); it.fd.sync() } }
        mutateTracked(temporary, file) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    }
    private fun exportReserve(projectedId: String? = null, growth: Long = 0, newEntries: Int = 0): Long {
        fun bound(bytes: Long, entries: Int): Long = bytes + (bytes + 49) / 50 + entries * 512L + 4096
        var largest = if (projectedId == null && growth > 0) bound(growth, newEntries) else 0L
        manifests.forEach { (id, manifest) -> if (manifest["exportedAtMillis"] == null) {
            val bytes = sessionDataBytes[id] ?: 0L
            val entries = sessionFileCounts[id] ?: 0
            largest = maxOf(largest, bound(bytes + if (id == projectedId) growth else 0L,
                entries + if (id == projectedId) newEntries else 0))
        } }
        return 2 * largest
    }
    @Synchronized internal fun <T> withExportReserve(action: () -> T): T {
        val previous = usingExportReserve
        usingExportReserve = true
        return try { action() } finally { usingExportReserve = previous }
    }
    private fun sessionOwner(file: File): String? {
        var item: File? = file
        while (item != null && item.parentFile != root) item = item.parentFile
        return item?.takeIf { it.isDirectory || it.name in sessionDataBytes || it.name in manifests }?.name
    }
    /** Account partial writes too. Removing a file does not fabricate additional physically free space. */
    private fun <T> mutateTracked(vararg files: File, action: () -> T): T {
        val owners = files.associateWith(::sessionOwner)
        val before = files.associateWith { size(it) }
        val countsBefore = files.associateWith { fileCount(it) }
        try { return action() } finally {
            var delta = 0L
            files.forEach { file ->
                val fileDelta = size(file) - before.getValue(file)
                delta += fileDelta
                val owner = owners[file]
                if (owner != null) {
                    sessionDataBytes[owner] = ((sessionDataBytes[owner] ?: 0L) + fileDelta).coerceAtLeast(0L)
                    sessionFileCounts[owner] = ((sessionFileCounts[owner] ?: 0) + fileCount(file) - countsBefore.getValue(file)).coerceAtLeast(0)
                    infoCache.remove(owner)
                }
                if (file.parentFile == exports) infoCache.remove(file.name.removePrefix("field-").substringBefore(".zip"))
                if (file.extension == "wav" || file.isDirectory || !file.exists()) accountedClips = null
            }
            accountedBytes = accountedBytes?.let { (it + delta).coerceAtLeast(0L) }
            spaceBudget = (spaceBudget - maxOf(0L, delta)).coerceAtLeast(0L)
        }
    }
    private fun fileCount(file: File): Int = if (!file.exists()) 0 else if (file.isFile) 1 else file.walkTopDown().count { it.isFile }
    private fun size(file: File): Long = if (!file.exists()) 0 else if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun clips(folder: File): Int = File(folder, "audio-clips").listFiles().orEmpty().count { it.extension == "wav" }
    private fun emptyPerformance(): Map<String, Any?> = mapOf("ModelLoadTime" to null, "AudioLatency" to null, "AverageRecognitionLatency" to null,
        "P95RecognitionLatency" to null, "MaxRecognitionLatency" to null, "ScoreFollowingLatency" to null,
        "AmbiguityResolutionLatency" to null, "CPU" to null, "Memory" to null, "DroppedAudioFrames" to 0, "QueueOverflowCount" to 0,
        "latencyDefinition" to "Unknown values remain null. No hardware acoustic end-to-end calibration.")
    private fun sha256(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
}