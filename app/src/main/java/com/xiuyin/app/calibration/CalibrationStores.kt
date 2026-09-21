package com.xiuyin.app.calibration

import com.xiuyin.app.pitch.PitchEvidenceConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal object CalibrationFiles {
    val lock = Any()
    fun root(root: File) {
        if (!root.exists()) check(root.mkdirs()) { "不能创建本地校准目录" }
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "校准目录无效" }
    }
    fun write(file: File, payload: ByteArray) {
        check(!Files.isSymbolicLink(file.toPath())) { "拒绝覆盖文件链接" }
        val temp = File(file.parentFile, ".${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> output.write(MessageDigest.getInstance("SHA-256").digest(payload)); output.write(payload); output.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { if (temp.exists()) check(temp.delete()) { "无法清理临时校准文件" } }
    }
    fun read(file: File, maximum: Int): ByteArray {
        check(!Files.isSymbolicLink(file.toPath()) && file.length() in 33..maximum.toLong()) { "校准文件大小或类型无效" }
        val bytes = file.readBytes(); val payload = bytes.copyOfRange(32, bytes.size)
        check(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload))) { "校准文件校验失败，原数据保留" }
        return payload
    }
    fun encode(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(block) }.toByteArray()
    fun nullable(out: DataOutputStream, value: Double?) { out.writeBoolean(value != null); if (value != null) out.writeDouble(value) }
    fun nullable(input: DataInputStream): Double? = if (input.readBoolean()) input.readDouble() else null
}

class CalibrationProfileStore(private val root: File) {
    fun save(profile: PianoCalibrationProfile) = synchronized(CalibrationFiles.lock) {
        validate(profile); CalibrationFiles.root(root)
        val payload = CalibrationFiles.encode { out ->
            out.writeInt(1); out.writeLong(profile.createdAtMillis); out.writeUTF(profile.deviceName.orEmpty()); out.writeInt(profile.sampleRate)
            out.writeInt(profile.observations); out.writeDouble(profile.observedSeconds)
            listOf(profile.noiseFloorDbfs, profile.meanInputDbfs, profile.peakInputDbfs, profile.meanOnsetScore).forEach { CalibrationFiles.nullable(out, it) }
            out.writeInt(profile.recognitionObservations); out.writeFloat(profile.parameters.onsetThreshold); out.writeFloat(profile.parameters.frameThreshold)
            listOf(profile.latencyMs, profile.reverbSeconds, profile.pedalResidualSeconds).forEach { CalibrationFiles.nullable(out, it) }
        }
        CalibrationFiles.write(File(root, "piano-profile.xcp"), payload)
    }
    fun load(): PianoCalibrationProfile? = synchronized(CalibrationFiles.lock) {
        CalibrationFiles.root(root)
        val file = File(root, "piano-profile.xcp")
        if (!file.exists()) return@synchronized null
        DataInputStream(ByteArrayInputStream(CalibrationFiles.read(file, 32768))).use { input ->
            check(input.readInt() == 1) { "不支持的校准版本，保留原数据" }
            val profile = PianoCalibrationProfile(input.readLong(), input.readUTF().ifBlank { null }, input.readInt(), input.readInt(), input.readDouble(),
                CalibrationFiles.nullable(input), CalibrationFiles.nullable(input), CalibrationFiles.nullable(input), CalibrationFiles.nullable(input),
                input.readInt(), PitchEvidenceConfig(input.readFloat(), input.readFloat()), CalibrationFiles.nullable(input), CalibrationFiles.nullable(input), CalibrationFiles.nullable(input))
            check(input.available() == 0) { "校准文件含额外内容" }; validate(profile); profile
        }
    }
    private fun validate(profile: PianoCalibrationProfile) {
        require(profile.createdAtMillis >= 0 && profile.sampleRate in 8000..192000 && profile.observations > 0 && profile.recognitionObservations >= 0)
        require(profile.observedSeconds.isFinite() && profile.observedSeconds >= 5)
        require(profile.deviceName.orEmpty().length <= 1024)
        require(listOfNotNull(profile.noiseFloorDbfs, profile.meanInputDbfs, profile.peakInputDbfs).all { it.isFinite() && it in -160.0..0.0 })
        require(profile.meanOnsetScore == null || profile.meanOnsetScore.isFinite() && profile.meanOnsetScore in 0.0..1.0)
        require(listOfNotNull(profile.latencyMs, profile.reverbSeconds, profile.pedalResidualSeconds).all { it.isFinite() && it >= 0 })
        // Constructor validation belongs to the recognizer and is reused here.
        PitchEvidenceConfig(profile.parameters.onsetThreshold, profile.parameters.frameThreshold)
    }
}

class MisjudgmentCaseStore(private val root: File, private val maxCases: Int = 200, private val maxBytes: Long = 100L * 1024 * 1024) {
    init { require(maxCases in 1..200 && maxBytes in 1..100L * 1024 * 1024) }
    fun save(context: MisjudgmentContext, clip: PcmClip, consent: Boolean, atMillis: Long = System.currentTimeMillis()): CaseSummary = synchronized(CalibrationFiles.lock) {
        require(consent) { "尚未同意保存短音频，请先在校准页明确勾选" }
        val value = MisjudgmentCase(UUID.randomUUID().toString(), atMillis, context, clip)
        validate(value); CalibrationFiles.root(root)
        val bytes = encode(value)
        require(bytes.size + 32 <= maxBytes) { "当前片段超过案例空间上限" }
        // Validate existing entries before mutation. A corrupt case remains visible, never silently removed.
        val existing = list()
        val file = File(root, "${value.id}.xmc")
        CalibrationFiles.write(file, bytes)
        var count = existing.size + 1
        var total = existing.sumOf { it.bytes } + file.length()
        existing.sortedBy { it.createdAtMillis }.forEach { old ->
            if (count > maxCases || total > maxBytes) {
                val target = checkedFile(old.id)
                check(target.delete()) { "案例已保存，但无法清理最旧案例：${old.id}" }
                count--; total -= old.bytes
            }
        }
        CaseSummary(value.id, atMillis, context, clip.durationSeconds, file.length())
    }
    fun list(): List<CaseSummary> = synchronized(CalibrationFiles.lock) {
        CalibrationFiles.root(root)
        files().map { file ->
            val value = read(file.name.removeSuffix(".xmc"))
            CaseSummary(value.id, value.createdAtMillis, value.context, value.clip.durationSeconds, file.length())
        }.sortedByDescending { it.createdAtMillis }
    }
    fun read(id: String): MisjudgmentCase = synchronized(CalibrationFiles.lock) {
        val file = checkedFile(id)
        DataInputStream(ByteArrayInputStream(CalibrationFiles.read(file, 2 * 1024 * 1024))).use { input ->
            check(input.readInt() == 1) { "不支持的误判案例版本" }
            val storedId = input.readUTF(); val created = input.readLong(); val trigger = CaseTrigger.valueOf(input.readUTF())
            val scoreId = input.readUTF().ifBlank { null }; val measure = input.readInt().let { if (it < 0) null else it }; val beat = CalibrationFiles.nullable(input)
            val expected = List(count(input, 128)) { input.readInt() }.toSet(); val raw = List(count(input, 128)) { input.readInt() }.toSet()
            val notes = List(count(input, 128)) { CaseNoteEvidence(input.readInt(), input.readDouble(), input.readDouble(), input.readBoolean(), input.readBoolean()) }
            val context = MisjudgmentContext(trigger, scoreId, measure, beat, expected, raw, notes, input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean(), input.readUTF())
            val sampleRate = input.readInt(); val first = input.readLong(); val pcm = ShortArray(count(input, 192000 * 5)) { input.readShort() }
            val value = MisjudgmentCase(storedId, created, context, PcmClip(sampleRate, first, pcm))
            check(input.available() == 0 && storedId == id) { "误判案例归属或内容无效" }; validate(value); value
        }
    }
    fun clear(): Int = synchronized(CalibrationFiles.lock) {
        CalibrationFiles.root(root); val files = files()
        files.forEach { file -> check(!Files.isSymbolicLink(file.toPath()) && file.delete()) { "无法清理案例：${file.name}" } }
        files.size
    }
    private fun files() = root.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f-]{36}\\.xmc")) }
    private fun checkedFile(id: String): File {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "案例 ID 无效" }
        CalibrationFiles.root(root); return File(root, "$id.xmc").also { check(!Files.isSymbolicLink(it.toPath())) { "案例不能是文件链接" } }
    }
    private fun validate(value: MisjudgmentCase) {
        require(value.createdAtMillis >= 0 && value.clip.sampleRate in 8000..192000 && value.clip.firstSample >= 0)
        require(value.clip.samples.isNotEmpty() && value.clip.samples.size <= value.clip.sampleRate * 5) { "案例只允许最近最多 5 秒真实音频" }
        val c = value.context
        require(c.measureIndex == null || c.measureIndex >= 0)
        require(c.beat == null || c.beat.isFinite() && c.beat >= 0)
        require((c.expectedMidi + c.rawDetectedMidi).all { it in 0..127 } && c.notes.size <= 128)
        require(c.notes.all { it.midi in 0..127 && it.onsetScore.isFinite() && it.onsetScore in 0.0..1.0 && it.frameScore.isFinite() && it.frameScore in 0.0..1.0 })
        require(listOf(c.scoreId.orEmpty(), c.pedalState, c.finalDecision, c.practiceMode, c.modelDescription).all { it.length <= 2048 })
    }
    private fun encode(value: MisjudgmentCase): ByteArray = CalibrationFiles.encode { out ->
        out.writeInt(1); out.writeUTF(value.id); out.writeLong(value.createdAtMillis)
        val c = value.context; out.writeUTF(c.trigger.name); out.writeUTF(c.scoreId.orEmpty()); out.writeInt(c.measureIndex ?: -1); CalibrationFiles.nullable(out, c.beat)
        out.writeInt(c.expectedMidi.size); c.expectedMidi.sorted().forEach(out::writeInt)
        out.writeInt(c.rawDetectedMidi.size); c.rawDetectedMidi.sorted().forEach(out::writeInt)
        out.writeInt(c.notes.size); c.notes.forEach { out.writeInt(it.midi); out.writeDouble(it.onsetScore); out.writeDouble(it.frameScore); out.writeBoolean(it.onset); out.writeBoolean(it.active) }
        out.writeUTF(c.pedalState); out.writeUTF(c.finalDecision); out.writeUTF(c.practiceMode); out.writeBoolean(c.userDisagrees); out.writeUTF(c.modelDescription)
        out.writeInt(value.clip.sampleRate); out.writeLong(value.clip.firstSample); out.writeInt(value.clip.samples.size); value.clip.samples.forEach { out.writeShort(it.toInt()) }
    }
    private fun count(input: DataInputStream, max: Int): Int = input.readInt().also { require(it in 0..max) { "案例数据长度无效" } }
}

