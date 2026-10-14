package com.xiuyin.quicktest

import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.nio.channels.FileLock
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One owner per root directory. Construct/start/finish/list/export/remove on an IO dispatcher.
 * append methods copy their input before returning and never wait for disk or queue capacity.
 * Stop the microphone, then drain the engine BEFORE finish; finish closes admission, drains the
 * writer, syncs evidence, fixes the WAV header and commits a hash manifest. No late frames accepted.
 * onError may run on an audio/writer thread: dispatch a stop request; do not call finish inline.
 * The store never deletes sessions automatically and never uploads anything.
 */
class QuickTestStore internal constructor(
    private val root: File,
    private val onError: (String) -> Unit,
    private val queueCapacity: Int,
    private val dispatchWriter: (Runnable) -> Unit,
) : AutoCloseable {
    constructor(root: File, onError: (String) -> Unit = {}, queueCapacity: Int = 256) :
        this(root, onError, queueCapacity, { task ->
            Thread(task, "quicktest-evidence-writer").apply { isDaemon = true; start() }
        })
    data class SessionInfo(
        val id: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long?,
        val status: String,
        val sampleCount: Long,
        val frameCount: Long,
        val error: String?,
        val directory: File,
        val markCount: Long = 0,
        val reason: String? = null,
    ) {
        val durationSeconds: Double get() = sampleCount.toDouble() / SAMPLE_RATE
    }

    private sealed interface Work {
        data class Pcm(val samples: ShortArray) : Work
        data class Line(val file: String, val text: String) : Work
        data class End(val reason: String, val engine: RecognitionEngineSnapshot?) : Work
    }

    private class Live(val initial: SessionInfo, capacity: Int, val wav: RandomAccessFile) {
        val queue = ArrayBlockingQueue<Work>(capacity)
        val done = CountDownLatch(1)
        val streams = linkedMapOf<String, FileOutputStream>()
        var accepting = true
        var finishing = false
        var acceptedSamples = 0L
        var rejectedCommands = 0L
        var writtenSamples = 0L
        var frames = 0L
        var marks = 0L
        var ioFailed = false
        @Volatile var error: String? = null
        @Volatile var result: SessionInfo? = null
    }

    private val guard = Any()
    private val maintenanceGuard = Any()
    private val sessions = File(root, "sessions")
    private val exports = File(root, "exports")
    private val ownership = acquireOwnership(root)
    private var live: Live? = null
    private var closed = false
    val acceptedSamples: Long get() = synchronized(guard) { live?.acceptedSamples ?: 0L }

    init {
        try {
            require(queueCapacity > 0)
            check(sessions.isDirectory || sessions.mkdirs()) { "无法创建录音目录：$sessions" }
            check(exports.isDirectory || exports.mkdirs()) { "无法创建导出目录：$exports" }
            recoverInterruptedSessions()
        } catch (failure: Exception) { ownership.release(); throw failure }
    }

    fun start(metadata: Map<String, Any?>): SessionInfo = synchronized(guard) {
        check(!closed) { "录音存储已经关闭" }
        check(live == null || live?.done?.count == 0L) { "上一段录音尚未结束" }
        val id = UUID.randomUUID().toString()
        val directory = File(sessions, id)
        check(directory.mkdir()) { "无法创建会话目录" }
        val info = SessionInfo(id, System.currentTimeMillis(), null, "ACTIVE", 0, 0, null, directory)
        // Marker first: even a failed start remains discoverable/recoverable.
        writeAtomic(File(directory, ".active"), id)
        saveInfo(info)
        writeAtomic(File(directory, "metadata.json"), json(metadata + mapOf(
            "schemaVersion" to 1, "sessionId" to id, "startedAtMillis" to info.startedAtMillis,
            "sampleRate" to SAMPLE_RATE, "channels" to 1, "bitsPerSample" to 16,
            "maxDurationSeconds" to MAX_SECONDS, "audioFormat" to "PCM_S16LE",
            "scope" to "Microphone PCM and model MIDI classification; no calibrated Hz/cents or score assessment",
        )))
        for (name in listOf("recognition.jsonl", "marks.jsonl", "events.jsonl")) {
            writeAtomic(File(directory, name), "")
        }
        val wav = RandomAccessFile(File(directory, "audio.wav"), "rw")
        var openedSession: Live? = null
        try {
            wav.setLength(44)
            writeWavHeader(wav, 0)
            wav.fd.sync()
            val session = Live(info, queueCapacity, wav)
            openedSession = session
            for (name in listOf("recognition.jsonl", "marks.jsonl", "events.jsonl")) {
                session.streams[name] = FileOutputStream(File(directory, name), true)
            }
            live = session
            dispatchWriter(Runnable { writerLoop(session) })
            info
        } catch (failure: Exception) {
            openedSession?.streams?.values?.forEach { stream ->
                try { stream.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            }
            try { wav.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            // No writer was successfully started; do not leave finish waiting on an orphan queue.
            live = null
            throw failure
        }
    }

    /** PCM must begin at sample zero and stay contiguous. Caller trims the last block at MAX_SAMPLES. */
    fun appendPcm(samples: ShortArray, count: Int, firstSample: Long): Boolean {
        require(count > 0 && count <= samples.size)
        var failed: Pair<Live, String>? = null
        val accepted = synchronized(guard) {
            val session = live ?: return@synchronized false
            if (!session.accepting) return@synchronized false
            when {
                firstSample != session.acceptedSamples -> failed = session to
                    "PCM_DISCONTINUITY: expected=${session.acceptedSamples}, actual=$firstSample"
                count.toLong() > MAX_SAMPLES - firstSample -> failed = session to "MAX_DURATION_EXCEEDED: limit=$MAX_SAMPLES"
                !session.queue.offer(Work.Pcm(samples.copyOf(count))) -> failed = session to "STORAGE_QUEUE_FULL: PCM"
                else -> { session.acceptedSamples += count; return@synchronized true }
            }
            session.accepting = false
            session.rejectedCommands++
            failed.let { if (session.error == null) session.error = it.second else failed = null }
            false
        }
        failed?.let { notifyError(it.second) }
        return accepted
    }

    fun appendFrame(frame: PianoRecognitionFrame): Boolean {
        if (frame.rawOnsetScores.size != 88 || frame.rawFrameScores.size != 88) {
            val session = synchronized(guard) { live?.takeIf { it.accepting } } ?: return false
            fail(session, "INVALID_RAW88: onset=${frame.rawOnsetScores.size}, frame=${frame.rawFrameScores.size}")
            return false
        }
        // Encode now: mutable arrays/sets belonging to the engine must not escape into the queue.
        val value = mapOf(
            "frameIndex" to frame.frameIndex, "audioTimeSeconds" to frame.audioTimeSeconds,
            "availableAudioTimeSeconds" to frame.availableAudioTimeSeconds,
            "onsetMidi" to frame.onsetMidi.sorted(), "activeMidi" to frame.activeMidi.sorted(),
            "uncertainMidi" to frame.uncertainMidi.sorted(), "state" to frame.state.name,
            "ambiguousOctaveMidi" to frame.ambiguousOctaveMidi.sorted(),
            "clipped" to frame.clipped, "computeMs" to frame.computeMs,
            "modelWindowMs" to frame.modelWindowMs, "offsetSupported" to frame.offsetSupported,
            "pedalState" to frame.pedalState, "rawOnsetScores" to frame.rawOnsetScores.toList(),
            "rawFrameScores" to frame.rawFrameScores.toList(), "rawMidiStart" to 21,
            "candidateRejections" to frame.candidateRejections.mapKeys { it.key.toString() },
            "fieldSessionId" to frame.fieldSessionId, "audioStreamId" to frame.audioStreamId,
            "engineSessionId" to frame.engineSessionId,
            "notes" to frame.notes.map { mapOf("midi" to it.midi, "onsetScore" to it.onsetScore,
                "frameScore" to it.frameScore, "onset" to it.onset, "active" to it.active) },
            "octaveDiagnostics" to frame.octaveDiagnostics.map { mapOf(
                "lowerMidi" to it.lowerMidi, "upperMidi" to it.upperMidi,
                "lowerOnsetScore" to it.lowerOnsetScore, "upperOnsetScore" to it.upperOnsetScore,
                "lowerFrameScore" to it.lowerFrameScore, "upperFrameScore" to it.upperFrameScore,
                "temporalOffsetSeconds" to it.temporalOffsetSeconds,
                "lowerFundamentalAmplitude" to it.lowerFundamentalAmplitude,
                "upperFundamentalAmplitude" to it.upperFundamentalAmplitude,
                "upperAttackEnergyRise" to it.upperAttackEnergyRise,
                "lowerAttackEnergyRise" to it.lowerAttackEnergyRise,
                "finalState" to it.finalState.name, "reason" to it.reason,
                "harmonicPrior" to it.harmonicPrior, "independentOnsetScore" to it.independentOnsetScore,
                "scoreContextWeight" to it.scoreContextWeight, "finalConfidence" to it.finalConfidence,
            ) },
        )
        return enqueueLine("recognition.jsonl", json(value))
    }

    fun mark(label: String, sample: Long): Boolean {
        require(sample >= 0 && sample <= acceptedSamples)
        return enqueueLine("marks.jsonl", json(mapOf("label" to label, "sample" to sample,
            "audioTimeSeconds" to sample.toDouble() / SAMPLE_RATE, "wallTimeMillis" to System.currentTimeMillis())))
    }

    fun appendEvent(kind: String, details: Map<String, Any?> = emptyMap()): Boolean = enqueueLine(
        "events.jsonl", json(mapOf("kind" to kind, "wallTimeMillis" to System.currentTimeMillis(),
            "sample" to acceptedSamples, "details" to details)),
    )

    /** Synchronous barrier. After it returns, files are closed and can be exported safely. */
    fun finish(reason: String, engineSnapshot: RecognitionEngineSnapshot? = null): SessionInfo {
        val (session, sendEnd) = synchronized(guard) {
            val current = checkNotNull(live) { "没有正在录制的会话" }
            val send = !current.finishing
            current.finishing = true
            current.accepting = false
            current to send
        }
        if (sendEnd) session.queue.put(Work.End(reason, engineSnapshot))
        session.done.await()
        return checkNotNull(session.result)
    }

    /** Nonblocking lifecycle shutdown. A successor constructor waits on root ownership in IO. */
    override fun close() {
        val current = synchronized(guard) {
            if (closed) return
            closed = true
            live?.also { it.accepting = false }
        }
        Thread({
            try {
                if (current != null && current.done.count != 0L) finish("APP_CLOSED")
            } finally { synchronized(maintenanceGuard) { ownership.release() } }
        }, "quicktest-evidence-close").apply { isDaemon = true; start() }
    }

    fun listSessions(): List<SessionInfo> = sessions.listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }.mapNotNull { directory ->
            // A corrupt index is not silently hidden; callers receive the actual IO exception.
            if (File(directory, "session.properties").exists()) readInfo(directory) else null
        }.sortedByDescending { it.startedAtMillis }

    fun export(sessionId: String): File = synchronized(maintenanceGuard) {
        val directory = sessionDirectory(sessionId)
        synchronized(guard) {
            check(live?.let { it.initial.id == sessionId && it.done.count != 0L } != true) { "请先结束录音" }
        }
        val info = readInfo(directory)
        check(info.status != "ACTIVE") { "录音尚未收尾" }
        val manifestFile = File(directory, "manifest.json")
        check(manifestFile.isFile) { "会话清单缺失，不能导出为完整证据包" }
        val files = EVIDENCE_FILES.map { File(directory, it) }
        files.forEach { check(it.isFile) { "证据文件缺失：${it.name}" } }
        // Compare against the sealed manifest before exporting: never silently bless later changes.
        check(manifestFile.readText() == manifestText(directory, info)) { "会话文件已改变，SHA256 清单不一致" }
        val temporary = File(exports, "$sessionId.zip.partial")
        val target = File(exports, "$sessionId.zip")
        try {
            FileOutputStream(temporary).use { output ->
                ZipOutputStream(output).use { zip ->
                    for (file in files + manifestFile) {
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.finish()
                    output.fd.sync()
                }
            }
            ZipFile(temporary).use { zip ->
                check(zip.size() == files.size + 1) { "ZIP 文件数校验失败" }
                for (file in files + manifestFile) {
                    val entry = checkNotNull(zip.getEntry(file.name))
                    val hash = zip.getInputStream(entry).use { stream -> digest(stream.readBytes()) }
                    check(entry.size == file.length() && hash == sha256(file)) { "ZIP 内容校验失败：${file.name}" }
                }
            }
            replaceFile(temporary, target)
            target
        } catch (failure: Exception) {
            // Retain a .partial archive as failure evidence; do not mark it exported.
            throw IllegalStateException("导出失败：${failure.message}", failure)
        }
    }

    /** Only call after an explicit user deletion. No quota cleanup invokes this method. */
    fun remove(sessionId: String) = synchronized(maintenanceGuard) {
        val directory = sessionDirectory(sessionId)
        synchronized(guard) {
            check(live?.let { it.initial.id == sessionId && it.done.count != 0L } != true) { "无法删除正在录制的会话" }
        }
        check(directory.deleteRecursively()) { "删除会话失败" }
        for (name in listOf("$sessionId.zip", "$sessionId.zip.partial")) {
            val file = File(exports, name)
            check(!file.exists() || file.delete()) { "删除导出文件失败" }
        }
    }

    private fun enqueueLine(file: String, text: String): Boolean {
        var failed: Live? = null
        val accepted = synchronized(guard) {
            val session = live ?: return@synchronized false
            if (!session.accepting) return@synchronized false
            if (session.queue.offer(Work.Line(file, text))) true else {
                session.accepting = false
                session.rejectedCommands++
                if (session.error == null) {
                    session.error = "STORAGE_QUEUE_FULL: $file"
                    failed = session
                }
                false
            }
        }
        failed?.let { notifyError(checkNotNull(it.error)) }
        return accepted
    }

    private fun fail(session: Live, message: String) {
        val notify = synchronized(guard) {
            session.accepting = false
            if (session.error == null) { session.error = message; true } else false
        }
        if (notify) notifyError(message)
    }

    private fun notifyError(message: String) {
        // A UI callback must never kill the writer and strand the finish barrier.
        try { onError(message) } catch (_: Exception) { /* Original failure remains in summary. */ }
    }

    private fun writerLoop(session: Live) {
        while (true) {
            when (val work = session.queue.take()) {
                is Work.End -> { finalizeSession(session, work); return }
                else -> try {
                    // Continue draining admitted work after admission errors (e.g. queue full).
                    // IO failure is retained even if subsequent writes happen to succeed.
                    if (session.ioFailed) continue
                    when (work) {
                        is Work.Pcm -> {
                            val bytes = ByteArray(work.samples.size * 2)
                            work.samples.forEachIndexed { index, sample ->
                                bytes[index * 2] = sample.toByte()
                                bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
                            }
                            session.wav.write(bytes)
                            session.writtenSamples += work.samples.size
                        }
                        is Work.Line -> {
                            checkNotNull(session.streams[work.file]).write((work.text + "\n").toByteArray(Charsets.UTF_8))
                            if (work.file == "recognition.jsonl") session.frames++
                            if (work.file == "marks.jsonl") session.marks++
                        }
                        is Work.End -> error("handled above")
                    }
                } catch (failure: Exception) {
                    session.ioFailed = true
                    fail(session, "STORAGE_IO_ERROR: ${failure.message}")
                }
            }
        }
    }

    private fun finalizeSession(session: Live, end: Work.End) {
        var result = session.initial
        try {
            for (stream in session.streams.values) {
                try { stream.flush(); stream.fd.sync() } catch (failure: Exception) {
                    fail(session, "FLUSH_ERROR: ${failure.message}")
                } finally { try { stream.close() } catch (failure: Exception) { fail(session, "CLOSE_ERROR: ${failure.message}") } }
            }
            for (name in session.streams.keys) truncateIncompleteLine(File(session.initial.directory, name))
            session.frames = countLines(File(session.initial.directory, "recognition.jsonl"))
            session.marks = countLines(File(session.initial.directory, "marks.jsonl"))
            try {
                // Actual file length is authoritative, including a partial OS write before failure.
                val bytes = (session.wav.length() - 44).coerceAtLeast(0).let { it - it % 2 }
                session.wav.setLength(44 + bytes)
                session.writtenSamples = bytes / 2
                writeWavHeader(session.wav, bytes)
                session.wav.fd.sync()
            } catch (failure: Exception) { fail(session, "WAV_FINALIZE_ERROR: ${failure.message}") }
            finally { try { session.wav.close() } catch (failure: Exception) { fail(session, "WAV_CLOSE_ERROR: ${failure.message}") } }
            if (session.writtenSamples != session.acceptedSamples) fail(session,
                "PCM_PERSISTENCE_MISMATCH: accepted=${session.acceptedSamples}, written=${session.writtenSamples}")
            val engineLoss = end.engine?.let { it.status.name == "ERROR" || it.droppedFrames > 0 || it.droppedSamples > 0 || it.overflowCount > 0 ||
                it.rejectedBeforeReadyFrames > 0 || it.processedSamples != it.submittedSamples ||
                it.submittedSamples != session.acceptedSamples || it.frameCount != session.frames || it.queueOccupancy != 0 } ?: false
            val status = when {
                session.error != null -> "ERROR"
                engineLoss || end.reason !in NORMAL_REASONS -> "INTERRUPTED"
                else -> "COMPLETED"
            }
            result = session.initial.copy(endedAtMillis = System.currentTimeMillis(), status = status,
                sampleCount = session.writtenSamples, frameCount = session.frames, markCount = session.marks,
                error = session.error, reason = end.reason)
            saveInfo(result)
            writeAtomic(File(result.directory, "summary.json"), json(summary(result) + mapOf(
                "acceptedSamples" to session.acceptedSamples, "rejectedCommands" to session.rejectedCommands,
                "engineLossDetected" to engineLoss, "engineSnapshot" to engineMap(end.engine),
            )))
            writeManifest(result)
            check(File(result.directory, ".active").delete()) { "无法移除录制标记" }
        } catch (failure: Exception) {
            fail(session, "SESSION_FINALIZE_ERROR: ${failure.message}")
            result = result.copy(status = "ERROR", endedAtMillis = System.currentTimeMillis(),
                sampleCount = session.writtenSamples, frameCount = session.frames, markCount = session.marks,
                error = session.error, reason = end.reason)
            // Preserve .active so next launch can salvage files; never claim a committed archive.
        } finally {
            // A failure before header finalization must not leave an old writer's file handles open.
            session.streams.values.forEach { try { it.close() } catch (_: Exception) { /* Prior failure retained. */ } }
            try { session.wav.close() } catch (_: Exception) { /* Prior failure retained. */ }
            session.result = result
            session.done.countDown()
        }
    }

    private fun recoverInterruptedSessions() {
        for (directory in sessions.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) && File(it, ".active").exists() }) {
            val previous = if (File(directory, "session.properties").exists()) readInfo(directory)
                else SessionInfo(directory.name, directory.lastModified(), null, "ACTIVE", 0, 0, null, directory)
            val truncated = linkedMapOf<String, Long>()
            for (name in listOf("recognition.jsonl", "marks.jsonl", "events.jsonl")) {
                val file = File(directory, name)
                if (!file.exists()) writeAtomic(file, "")
                truncated[name] = truncateIncompleteLine(file)
            }
            val wav = File(directory, "audio.wav")
            val sampleCount = RandomAccessFile(wav, "rw").use {
                val bytes = (it.length() - 44).coerceAtLeast(0).let { size -> (size - size % 2).coerceAtMost(MAX_SAMPLES * 2) }
                it.setLength(44 + bytes); writeWavHeader(it, bytes); it.fd.sync(); bytes / 2
            }
            if (!File(directory, "metadata.json").exists()) writeAtomic(File(directory, "metadata.json"), json(mapOf(
                "schemaVersion" to 1, "sessionId" to directory.name, "metadataMissingAfterInterruptedStart" to true)))
            val info = previous.copy(status = "INTERRUPTED", endedAtMillis = System.currentTimeMillis(),
                sampleCount = sampleCount, frameCount = countLines(File(directory, "recognition.jsonl")),
                markCount = countLines(File(directory, "marks.jsonl")), reason = "PROCESS_INTERRUPTED")
            saveInfo(info)
            File(directory, "events.jsonl").appendText(json(mapOf("kind" to "RECOVERED_INTERRUPTED_SESSION",
                "wallTimeMillis" to info.endedAtMillis, "truncatedJsonlBytes" to truncated)) + "\n")
            writeAtomic(File(directory, "summary.json"), json(summary(info) + mapOf(
                "recovered" to true, "captureComplete" to false, "engineSnapshot" to null,
                "truncatedJsonlBytes" to truncated, "recoveryNote" to "Only durable bytes recovered; pending writer/engine queues are unknown",
            )))
            writeManifest(info)
            check(File(directory, ".active").delete()) { "中断会话标记无法清理" }
        }
    }

    private fun sessionDirectory(id: String): File {
        require(validId(id)) { "无效会话 ID" }
        val file = File(sessions, id)
        check(file.canonicalFile.parentFile == sessions.canonicalFile && file.isDirectory) { "会话不存在" }
        return file
    }

    private fun saveInfo(info: SessionInfo) {
        val properties = Properties().apply {
            setProperty("id", info.id); setProperty("startedAtMillis", info.startedAtMillis.toString())
            setProperty("endedAtMillis", info.endedAtMillis?.toString() ?: "")
            setProperty("status", info.status); setProperty("sampleCount", info.sampleCount.toString())
            setProperty("frameCount", info.frameCount.toString()); setProperty("markCount", info.markCount.toString())
            setProperty("error", info.error.orEmpty()); setProperty("reason", info.reason.orEmpty())
        }
        val target = File(info.directory, "session.properties")
        val temp = File(info.directory, "session.properties.tmp")
        FileOutputStream(temp).use { properties.store(it, "QuickTest durable session index"); it.fd.sync() }
        replaceFile(temp, target)
    }

    private fun readInfo(directory: File): SessionInfo {
        val data = Properties().apply { File(directory, "session.properties").inputStream().use { load(it) } }
        check(data.getProperty("id") == directory.name) { "会话 ID 与目录不符" }
        return SessionInfo(directory.name, data.getProperty("startedAtMillis").toLong(),
            data.getProperty("endedAtMillis").toLongOrNull(), data.getProperty("status"),
            data.getProperty("sampleCount").toLong(), data.getProperty("frameCount").toLong(),
            data.getProperty("error").ifEmpty { null }, directory, data.getProperty("markCount", "0").toLong(),
            data.getProperty("reason", "").ifEmpty { null })
    }

    private fun writeManifest(info: SessionInfo) = writeAtomic(File(info.directory, "manifest.json"), manifestText(info.directory, info))

    private fun manifestText(directory: File, info: SessionInfo): String = json(mapOf(
        "schemaVersion" to 1, "sessionId" to info.id, "status" to info.status,
        "hashAlgorithm" to "SHA-256", "files" to EVIDENCE_FILES.map { name ->
            val file = File(directory, name)
            check(file.isFile) { "缺少证据文件：$name" }
            mapOf("path" to name, "bytes" to file.length(), "sha256" to sha256(file))
        },
    ))

    companion object {
        private val OWNERS = ConcurrentHashMap<String, CountDownLatch>()
        private class Ownership(val key: String, val done: CountDownLatch, val file: RandomAccessFile, val lock: FileLock) {
            fun release() {
                try { if (lock.isValid) lock.release() } finally {
                    try { file.close() } finally { OWNERS.remove(key, done); done.countDown() }
                }
            }
        }

        private fun acquireOwnership(root: File): Ownership {
            check(root.isDirectory || root.mkdirs()) { "无法创建存储根目录" }
            val key = root.canonicalPath
            val done = CountDownLatch(1)
            while (true) {
                val existing = OWNERS.putIfAbsent(key, done) ?: break
                existing.await()
            }
            var file: RandomAccessFile? = null
            try {
                file = RandomAccessFile(File(root, ".owner.lock"), "rw")
                return Ownership(key, done, file, file.channel.lock())
            } catch (failure: Exception) {
                try { file?.close() } finally { OWNERS.remove(key, done); done.countDown() }
                throw failure
            }
        }

        const val SAMPLE_RATE = 48_000
        const val MAX_SECONDS = 60
        const val MAX_SAMPLES = 48_000L * MAX_SECONDS
        private val NORMAL_REASONS = setOf("USER_ENDED", "MAX_DURATION", "MAX_DURATION_REACHED")
        private val EVIDENCE_FILES = listOf("audio.wav", "metadata.json", "recognition.jsonl", "marks.jsonl", "events.jsonl", "summary.json", "session.properties")
        private fun validId(id: String): Boolean = id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))

        private fun summary(info: SessionInfo): Map<String, Any?> = mapOf(
            "schemaVersion" to 1, "sessionId" to info.id, "status" to info.status,
            "startedAtMillis" to info.startedAtMillis, "endedAtMillis" to info.endedAtMillis,
            "sampleCount" to info.sampleCount, "frameCount" to info.frameCount, "markCount" to info.markCount,
            "durationSeconds" to info.durationSeconds, "reason" to info.reason, "error" to info.error,
            "captureComplete" to (info.status == "COMPLETED"), "accuracyVerdict" to "NOT_ASSESSED",
        )

        private fun engineMap(value: RecognitionEngineSnapshot?): Map<String, Any?>? = value?.let { mapOf(
            "status" to it.status.name, "message" to it.message, "modelSha256" to it.modelSha256,
            "frameCount" to it.frameCount, "computeMs" to it.computeMs, "modelLoadMs" to it.modelLoadMs,
            "accuracyValidated" to it.accuracyValidated, "queueOccupancy" to it.queueOccupancy,
            "queueCapacity" to it.queueCapacity, "maxQueueOccupancy" to it.maxQueueOccupancy,
            "droppedFrames" to it.droppedFrames, "droppedSamples" to it.droppedSamples,
            "overflowCount" to it.overflowCount, "rejectedBeforeReadyFrames" to it.rejectedBeforeReadyFrames,
            "submittedBlocks" to it.submittedBlocks, "processedBlocks" to it.processedBlocks,
            "submittedSamples" to it.submittedSamples, "processedSamples" to it.processedSamples,
            "sessionId" to it.sessionId,
        ) }

        private fun writeWavHeader(file: RandomAccessFile, dataBytes: Long) {
            file.seek(0)
            file.writeBytes("RIFF"); file.writeInt(Integer.reverseBytes((36 + dataBytes).toInt()))
            file.writeBytes("WAVEfmt "); file.writeInt(Integer.reverseBytes(16))
            file.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            file.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            file.writeInt(Integer.reverseBytes(SAMPLE_RATE)); file.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2))
            file.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            file.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            file.writeBytes("data"); file.writeInt(Integer.reverseBytes(dataBytes.toInt()))
            file.seek(44 + dataBytes)
        }

        private fun writeAtomic(target: File, content: String) {
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            replaceFile(temp, target)
        }

        private fun replaceFile(from: File, to: File) {
            // renameTo atomically replaces a file on Android/Linux; Windows JVM requires removal.
            if (from.renameTo(to)) return
            check(!to.exists() || to.delete()) { "无法替换：${to.name}" }
            check(from.renameTo(to)) { "无法提交：${to.name}" }
        }

        private fun truncateIncompleteLine(file: File): Long = RandomAccessFile(file, "rw").use {
            val oldLength = it.length()
            var length = oldLength
            while (length > 0) { it.seek(length - 1); if (it.readByte().toInt() == 10) break; length-- }
            if (length != oldLength) it.setLength(length)
            it.fd.sync()
            oldLength - length
        }

        private fun countLines(file: File): Long = file.useLines { it.count().toLong() }
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val read = stream.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }

        /** Small standards-compliant encoder, also usable in plain JVM tests without Android JSON. */
        private fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                for (character in value) when (character) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
                }
                append('"')
            }
            is Boolean -> value.toString()
            is Float -> if (value.isFinite()) value.toString() else "null"
            is Double -> if (value.isFinite()) value.toString() else "null"
            is Number -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { json(it.key.toString()) + ":" + json(it.value) }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            else -> error("Unsupported metadata type: ${value.javaClass.name}")
        }
    }
}
