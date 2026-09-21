package com.xiuyin.app.evaluation

import android.app.Activity
import android.app.Instrumentation
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.xiuyin.app.audio.OnsetAnalyzer
import com.xiuyin.app.audio.OnsetState
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit offline evaluation only, never discovered as a JUnit test or shipped in the app APK.
 * Decoding an actual room recording does not validate microphone capture or piano recognition.
 * PCM is kept only in bounded decoder/frame buffers; output files contain scalar metrics only.
 */
class RoomRecordingRunner : Instrumentation() {
    private var runnerArguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        runnerArguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val started = SystemClock.elapsedRealtimeNanos()
        val input = File(targetContext.cacheDir, INPUT_NAME)
        val csv = File(targetContext.cacheDir, CSV_NAME)
        val output = File(targetContext.cacheDir, REPORT_NAME)
        val report = baseReport()
        var executorPassed = false
        var csvCreated = false
        try {
            val runId = runnerArguments.getString("runId").orEmpty()
            val expectedHash = runnerArguments.getString("expectedSha256").orEmpty().lowercase()
            val expectedAppSha = runnerArguments.getString("expectedAppSha").orEmpty().lowercase()
            require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) { "runId 格式无效" }
            require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "expectedSha256 必须为完整 SHA256" }
            require(expectedAppSha.matches(Regex("[a-f0-9]{40}"))) { "expectedAppSha 必须为完整目标 App Git SHA" }
            report.put("runId", runId).put("expectedSourceSha256", expectedHash).put("expectedAppSha", expectedAppSha)
            // Read the installed target APK. Importing BuildConfig would inline this test APK's build constants.
            val targetBuild = Class.forName("com.xiuyin.app.BuildConfig", false, targetContext.classLoader)
            val appCommit = targetBuild.getField("GIT_COMMIT").get(null) as String
            report.put("appVersion", targetBuild.getField("VERSION_NAME").get(null))
                .put("appCommit", appCommit).put("appTagAtBuild", targetBuild.getField("GIT_TAG").get(null))
                .put("appIdentitySource", "reflection via installed targetContext.classLoader; not inlined test constants")
            require(appCommit == expectedAppSha) { "已安装目标 App SHA 与指定基线不一致" }
            require(Files.isRegularFile(input.toPath(), LinkOption.NOFOLLOW_LINKS)) { "缺少固定本地 MP4 输入，或输入不是普通文件" }
            require(input.length() in 1..MAX_INPUT_BYTES) { "实录输入必须大于零且不超过 50 MiB" }
            report.put("sourceBytes", input.length())
            val actualHash = sha256(input)
            report.put("sourceSha256", actualHash)
            require(actualHash == expectedHash) { "实录 SHA256 与已核对源文件不一致" }
            csv.bufferedWriter(Charsets.UTF_8).use { writer ->
                csvCreated = true
                writer.appendLine("run_id,app_commit,source_sha256,frame_index,timestamp_ns,sample_count,input_dbfs,noise_floor_dbfs,onset_state,onset_evidence,onset_count,clipped,processing_ns")
                decode(input, writer, runId, actualHash, appCommit, report, started)
            }
            executorPassed = true
        } catch (error: Exception) {
            report.put("error", error.message ?: error.javaClass.simpleName)
                .put("errorStack", error.stackTraceToString())
        } finally {
            // This fixed cache file was copied expressly for this run. No source media or user data is deleted.
            val inputDeleted = try {
                Files.deleteIfExists(input.toPath())
                !Files.exists(input.toPath(), LinkOption.NOFOLLOW_LINKS)
            } catch (error: Exception) {
                report.put("cleanupError", error.stackTraceToString())
                false
            }
            if (csvCreated) {
                try {
                    // Partial evidence belongs to failed runs too; never lose the first decoder error.
                    report.put("framesCsvSha256", sha256(csv))
                } catch (error: Exception) {
                    report.put("framesCsvHashError", error.stackTraceToString())
                    executorPassed = false
                }
            }
            report.put("inputCacheDeleted", inputDeleted)
            if (!inputDeleted) executorPassed = false
            report.put("executorStatus", if (executorPassed) "passed" else "failed")
                .put("elapsedWallMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                .put("reportFile", REPORT_NAME)
                .put("framesFile", if (csvCreated) CSV_NAME else JSONObject.NULL)
            try {
                output.writeText(report.toString(2), Charsets.UTF_8)
            } catch (error: Exception) {
                executorPassed = false
                report.put("reportWriteError", error.stackTraceToString())
            }
            val result = Bundle().apply {
                // Full packet timelines stay in the report file, below bounded report-memory limits.
                // Do not send a multi-megabyte JSON through Binder's instrumentation result Bundle.
                putString("stream", JSONObject().put("runId", report.opt("runId"))
                    .put("executorStatus", if (executorPassed) "passed" else "failed")
                    .put("functionalAcceptance", "failed").put("error", report.opt("error") ?: JSONObject.NULL)
                    .put("reportFile", REPORT_NAME).toString(2))
                putString("executorStatus", if (executorPassed) "passed" else "failed")
                putString("functionalAcceptance", "failed")
                putString("reportFile", REPORT_NAME)
                putString("framesFile", CSV_NAME)
            }
            // RESULT_OK means the evaluation executor finished; it never means the product passed.
            finish(if (executorPassed) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    private fun baseReport() = JSONObject()
        .put("schemaVersion", 1)
        .put("evaluationType", "actual_room_recording_offline_decode_and_envelope_only")
        .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT))
        .put("functionalAcceptance", "failed")
        .put("functionalReasons", JSONArray(listOf("图片 OMR 尚未实现", "音高及多音识别尚未实现", "乐谱跟随及错音判断尚未实现")))
        .put("microphoneHardwareValidation", "未验证：离线解码不会经过 AudioRecord 或手机麦克风")
        .put("recognizedNotes", JSONObject.NULL)
        .put("pitchAccuracy", JSONObject.NULL)
        .put("onsetAccuracy", JSONObject.NULL)
        .put("falseStopRate", JSONObject.NULL)
        .put("scoreFollowingAccuracy", JSONObject.NULL)
        .put("audioLatencyMs", JSONObject.NULL)
        .put("musicalDecision", JSONObject.NULL)
        .put("recognitionEvaluation", "未执行：本入口仅调用生产 OnsetAnalyzer；不调用音符/正确性模型，相关字段为 null")
        .put("onsetEvidenceMeaning", "生产 OnsetAnalyzer 的包络启发式分数，不是音符或准确率")
        .put("timestampMeaning", "首个解码 PCM PTS 为锚点，加完整连续解码样本数/实际采样率，取帧末端；不按抖动的容器包时长伸缩PCM，不使用计算墙钟")
        .put("containerTimestampMeaning", "原始压缩包PTS与每个解码块PTS完整记录；相邻PTS减前块样本时长为残差，不直接等同采集缺口；另核对累计采样时钟漂移与全轨时长")
        .put("limits", JSONObject().put("maxInputBytes", MAX_INPUT_BYTES).put("maxAudioDurationUs", MAX_DURATION_US)
            .put("decodeDeadlineSeconds", 180).put("maxPcmTimestampDiscontinuityUs", MAX_PTS_GAP_US)
            .put("maxDurationErrorUs", MAX_DURATION_ERROR_US).put("maxTimelineEntries", MAX_TIMELINE_ENTRIES))

    private fun decode(
        input: File,
        csv: BufferedWriter,
        runId: String,
        hash: String,
        appCommit: String,
        report: JSONObject,
        started: Long,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var pipeline: PcmPipeline? = null
        var decodeFailure: Exception? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val audioTracks = (0 until extractor.trackCount).filter { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(audioTracks.size == 1) { "需要且仅支持一个明确音轨，实际 ${audioTracks.size} 个" }
            val track = audioTracks.single()
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            require(mime == "audio/mp4a-latm") { "本评测入口仅支持真实 AAC 音轨，实际 $mime" }
            require(inputFormat.containsKey(MediaFormat.KEY_DURATION)) { "音轨缺少可核对的时长" }
            val declaredDurationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
            require(declaredDurationUs in 1..MAX_DURATION_US) { "音轨时长超出 10 分钟评测上限" }
            report.put("audioTrackIndex", track).put("inputFormat", inputFormat.toString())
                .put("declaredAudioDurationUs", declaredDurationUs)
            extractor.selectTrack(track)
            // Ask for integer PCM explicitly. Output format is still checked, never assumed to match.
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            report.put("decoderName", decoder.name)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            codecStarted = true
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var compressedPackets = 0L
            val packetTimeline = JSONArray()
            report.put("compressedPacketTimelineColumns", JSONArray(listOf("packetIndex", "ptsUs", "encodedBytes")))
                .put("compressedPacketTimeline", packetTimeline)
            var lastInputPts = -1L
            val formats = JSONArray()
            report.put("outputFormats", formats)
            while (!outputEnded) {
                check(SystemClock.elapsedRealtimeNanos() - started < DECODE_DEADLINE_NS) { "实录解码超过 180 秒，评测失败" }
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(inputIndex)) { "解码输入缓冲不可用" }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val pts = extractor.sampleTime
                            require(compressedPackets < MAX_TIMELINE_ENTRIES) { "压缩包时间轴超过安全条数上限" }
                            packetTimeline.put(JSONArray(listOf(compressedPackets, pts, size)))
                            require(pts >= 0 && pts >= lastInputPts && pts <= MAX_DURATION_US) { "压缩音轨 PTS 无效或逆序" }
                            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不支持加密音轨" }
                            lastInputPts = pts
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            compressedPackets++
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actualFormat = decoder.outputFormat
                        formats.put(actualFormat.toString())
                        if (pipeline == null) pipeline = PcmPipeline(actualFormat, csv, runId, hash, appCommit)
                        else pipeline.requireSameFormat(actualFormat)
                    }
                    outputIndex >= 0 -> {
                        try {
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val activePipeline = pipeline ?: PcmPipeline(decoder.outputFormat, csv, runId, hash, appCommit).also {
                                    pipeline = it
                                    formats.put(decoder.outputFormat.toString())
                                }
                                val buffer = checkNotNull(decoder.getOutputBuffer(outputIndex)) { "解码输出缓冲不可用" }
                                require(bufferInfo.offset >= 0 && bufferInfo.size <= buffer.capacity() - bufferInfo.offset) { "解码输出范围无效" }
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                activePipeline.accept(buffer.slice().order(ByteOrder.nativeOrder()), bufferInfo.presentationTimeUs)
                            }
                            outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            val actualPipeline = checkNotNull(pipeline) { "解码未输出 PCM" }
            actualPipeline.finish()
            val metrics = actualPipeline.metrics()
            report.put("audioMetrics", metrics).put("compressedPackets", compressedPackets)
                .put("inputEndOfStream", inputEnded).put("outputEndOfStream", outputEnded)
            require(inputEnded && outputEnded && compressedPackets > 0) { "音轨未完整解码至 EOS" }
            require(actualPipeline.totalSamples > 0) { "解码样本数为零" }
            val durationErrorUs = abs(actualPipeline.durationUs - declaredDurationUs)
            report.put("durationDifferenceUs", durationErrorUs)
            require(durationErrorUs <= MAX_DURATION_ERROR_US) { "解码 PCM 时长与原音轨差异超过 0.15 秒" }
        } catch (error: Exception) {
            decodeFailure = error
            throw error
        } finally {
            pipeline?.let { report.put("audioMetrics", it.metrics()) }
            val cleanupErrors = JSONArray()
            if (codecStarted) try { codec?.stop() } catch (error: Exception) { cleanupErrors.put(error.stackTraceToString()) }
            try { codec?.release() } catch (error: Exception) { cleanupErrors.put(error.stackTraceToString()) }
            try { extractor.release() } catch (error: Exception) { cleanupErrors.put(error.stackTraceToString()) }
            report.put("decoderCleanupErrors", cleanupErrors)
            if (cleanupErrors.length() > 0 && decodeFailure == null) error("解码资源释放失败：$cleanupErrors")
        }
    }

    private class PcmPipeline(
        format: MediaFormat,
        private val csv: BufferedWriter,
        private val runId: String,
        private val hash: String,
        private val appCommit: String,
    ) {
        private val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val encoding = encoding(format)
        private val encodingReported = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        private val analyzer = OnsetAnalyzer(sampleRate)
        private val frame = ShortArray(FRAME_SAMPLES)
        private var filled = 0
        private var frames = 0L
        private var partialFrames = 0L
        private var firstPtsUs: Long? = null
        private var expectedNextPtsUs: Long? = null
        private var previousChunkPtsUs: Long? = null
        private val decodedTimeline = JSONArray()
        private var decodedChunks = 0L
        private var lastSampleEndNanos = 0L
        private var lastFrameNanos = -1L
        private var onsetCount = 0
        private var analyzerOnsets = 0
        private var clippedFrames = 0L
        private var clippedChannelSamples = 0L
        private var ptsResidualsOverOneMs = 0L
        private var maxPtsClockDriftUs = 0L
        private var minimumPtsResidualUs = 0L
        private var maximumPtsResidualUs = 0L
        private var maxPtsGapUs = 0L
        private var minLevel = Double.POSITIVE_INFINITY
        private var maxLevel = Double.NEGATIVE_INFINITY
        private var sumLevel = 0.0
        private val stateCounts = OnsetState.entries.associateWith { 0L }.toMutableMap()
        private val processingNs = ArrayList<Long>()
        var totalSamples = 0L
            private set
        val durationUs: Long get() = totalSamples * 1_000_000L / sampleRate

        init {
            require(channels in 1..2) { "仅支持单声道或双声道 PCM，实际 $channels" }
            require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "解码器输出不是 PCM16：$encoding" }
        }

        fun requireSameFormat(format: MediaFormat) {
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate &&
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels && encoding(format) == encoding) {
                "解码中途 PCM 格式变化，不能拼接为同一评测序列"
            }
        }

        fun accept(buffer: ByteBuffer, ptsUs: Long) {
            require(ptsUs in 0..MAX_DURATION_US) { "PCM PTS 无效" }
            require(buffer.remaining() % (channels * 2) == 0) { "PCM 数据未按声道/16 位样本对齐" }
            val chunkSamples = buffer.remaining() / (channels * 2)
            require(chunkSamples > 0) { "PCM 块不含有效样本" }
            require(totalSamples + chunkSamples <= sampleRate.toLong() * 600L) { "PCM 样本超出 10 分钟限制" }
            require(decodedChunks < MAX_TIMELINE_ENTRIES) { "解码块时间轴超过安全条数上限" }
            val anchor = firstPtsUs ?: ptsUs.also { firstPtsUs = it }
            val previousEnd = expectedNextPtsUs
            val gapUs = if (previousEnd == null) 0L else ptsUs - previousEnd
            val sampleClockPtsUs = anchor + totalSamples * 1_000_000L / sampleRate
            val driftUs = ptsUs - sampleClockPtsUs
            // Preserve the actual codec/container time even when a following assertion fails.
            decodedTimeline.put(JSONArray(listOf(decodedChunks, ptsUs, totalSamples, chunkSamples, gapUs, driftUs)))
            decodedChunks++
            maxPtsGapUs = maxOf(maxPtsGapUs, abs(gapUs))
            maxPtsClockDriftUs = maxOf(maxPtsClockDriftUs, abs(driftUs))
            minimumPtsResidualUs = minOf(minimumPtsResidualUs, gapUs)
            maximumPtsResidualUs = maxOf(maximumPtsResidualUs, gapUs)
            if (abs(gapUs) > 1_000) ptsResidualsOverOneMs++
            require(previousChunkPtsUs == null || ptsUs >= checkNotNull(previousChunkPtsUs)) { "PCM 原始块 PTS 逆序" }
            require(abs(gapUs) <= MAX_PTS_GAP_US) { "PCM 包时间残差超过 0.5 秒：$gapUs 微秒" }
            require(abs(driftUs) <= MAX_PTS_GAP_US) { "容器 PTS 与累计采样时钟漂移超过 0.5 秒：$driftUs 微秒" }
            // AAC decodes a fixed number of samples per access unit even when MP4 packet durations jitter.
            // Feed every decoded sample exactly once at its true sample rate; do not insert/drop/reorder PCM,
            // reset warm-up, or merge real microphone fragments based on container scheduling metadata.
            repeat(chunkSamples) {
                var sum = 0
                repeat(channels) {
                    val sample = buffer.short.toInt()
                    sum += sample
                    if (abs(sample) >= OnsetAnalyzer.CLIP_SAMPLE_THRESHOLD) clippedChannelSamples++
                }
                // Deterministic arithmetic-mean downmix; no gain, silence padding or resampling.
                frame[filled++] = (sum / channels).toShort()
                totalSamples++
                lastSampleEndNanos = anchor * 1_000L + totalSamples * 1_000_000_000L / sampleRate
                if (filled == FRAME_SAMPLES) emitFrame()
            }
            expectedNextPtsUs = ptsUs + chunkSamples * 1_000_000L / sampleRate
            previousChunkPtsUs = ptsUs
        }

        fun finish() = emitFrame()

        private fun emitFrame() {
            if (filled == 0) return
            require(lastSampleEndNanos > lastFrameNanos) { "分析帧时间戳必须严格递增" }
            val before = SystemClock.elapsedRealtimeNanos()
            val result = analyzer.process(frame, filled, lastSampleEndNanos)
            val processing = SystemClock.elapsedRealtimeNanos() - before
            processingNs.add(processing)
            onsetCount += result.onsetCount - analyzerOnsets
            analyzerOnsets = result.onsetCount
            stateCounts[result.onsetState] = stateCounts.getValue(result.onsetState) + 1L
            if (result.clipped) clippedFrames++
            if (filled < FRAME_SAMPLES) partialFrames++
            minLevel = minOf(minLevel, result.inputLevelDbfs)
            maxLevel = maxOf(maxLevel, result.inputLevelDbfs)
            sumLevel += result.inputLevelDbfs
            frames++
            csv.appendLine(listOf(runId, appCommit, hash, frames, lastSampleEndNanos, filled,
                result.inputLevelDbfs, result.noiseFloorDbfs ?: "", result.onsetState.name,
                result.onsetConfidence ?: "", onsetCount, result.clipped, processing).joinToString(","))
            lastFrameNanos = lastSampleEndNanos
            filled = 0
        }

        fun metrics(): JSONObject {
            val ordered = processingNs.sorted()
            return JSONObject().put("sampleRate", sampleRate).put("channelCount", channels).put("pcmEncoding", encoding)
                .put("pcmEncodingReportedByDecoder", encodingReported)
                .put("pcmEncodingIfOmitted", "MediaCodec 原始音频默认 PCM16，且本次 configure 已明确请求 PCM16")
                .put("downmix", if (channels == 1) "mono unchanged" else "arithmetic mean of two channels; no gain")
                .put("frameSamples", FRAME_SAMPLES).put("monoSamples", totalSamples).put("pcmDurationUs", durationUs)
                .put("firstPcmPtsUs", firstPtsUs ?: JSONObject.NULL).put("lastSampleEndNanos", lastSampleEndNanos)
                .put("analysisFrames", frames).put("partialFrames", partialFrames).put("pendingSamples", filled)
                .put("onsetCount", onsetCount).put("clippedFrames", clippedFrames).put("clippedChannelSamples", clippedChannelSamples)
                .put("decodedChunks", decodedChunks).put("ptsResidualsOverOneMs", ptsResidualsOverOneMs)
                .put("minimumPtsResidualUs", minimumPtsResidualUs).put("maximumPtsResidualUs", maximumPtsResidualUs)
                .put("maximumAbsolutePtsGapUs", maxPtsGapUs).put("maximumAbsolutePtsClockDriftUs", maxPtsClockDriftUs)
                .put("analyzerClock", "first PCM PTS + complete decoded sample count / actual sample rate; no inserted or dropped samples")
                .put("decodedChunkTimelineColumns", JSONArray(listOf("chunkIndex", "originalPtsUs", "firstMonoSample", "sampleCount", "adjacentPtsResidualUs", "sampleClockDriftUs")))
                .put("decodedChunkTimeline", decodedTimeline)
                .put("states", JSONObject(stateCounts.mapKeys { it.key.name }))
                .put("minimumInputDbfs", if (frames > 0) minLevel else JSONObject.NULL)
                .put("maximumInputDbfs", if (frames > 0) maxLevel else JSONObject.NULL)
                .put("meanFrameInputDbfs", if (frames > 0) sumLevel / frames else JSONObject.NULL)
                .put("processingMeanMs", if (ordered.isNotEmpty()) ordered.average() / 1_000_000.0 else JSONObject.NULL)
                .put("processingP95Ms", if (ordered.isNotEmpty()) ordered[((ordered.size - 1) * 0.95).toInt()] / 1_000_000.0 else JSONObject.NULL)
                .put("processingMaxMs", if (ordered.isNotEmpty()) ordered.last() / 1_000_000.0 else JSONObject.NULL)
                .put("processingMeaning", "仅 OnsetAnalyzer CPU 墙钟耗时，不包含采集延迟、解码、音高识别或跟谱")
        }

        private fun encoding(format: MediaFormat): Int = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val INPUT_NAME = "room-recording-input.mp4"
        const val REPORT_NAME = "room-recording-report.json"
        const val CSV_NAME = "room-recording-frames.csv"
        const val MAX_INPUT_BYTES = 50L * 1_024L * 1_024L
        const val MAX_DURATION_US = 600_000_000L
        const val MAX_PTS_GAP_US = 500_000L
        const val MAX_DURATION_ERROR_US = 150_000L
        const val DECODE_DEADLINE_NS = 180_000_000_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val FRAME_SAMPLES = 1_024
        const val MAX_TIMELINE_ENTRIES = 120_000L
    }
}