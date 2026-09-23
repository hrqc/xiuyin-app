package com.xiuyin.app.pitch

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Real production asynchronous engine replay of the separately frozen user-video AAC decode.
 * No microphone capture, expected note names, score filtering or musical-accuracy assertions.
 * Keep OctaveBaselineTraceTest and its original input unchanged.
 */
class PhoneVideoTraceTest {
    @Test fun userVideoAudioTraversesProductionEngineWithCompleteFrameTrace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val metadataFile = File(root, "phone-video-metadata.json")
        val framesFile = File(root, "phone-video-frames.jsonl")
        val statusFile = File(root, "phone-video-status.jsonl")
        val startedAtMillis = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        val metadata = JSONObject()
            .put("schemaVersion", 3).put("runId", UUID.randomUUID().toString())
            .put("startedAtMillis", startedAtMillis).put("stage", "INPUT_VALIDATION")
            .put("chainPassed", false).put("traceComplete", false).put("accuracyValidated", false)
            .put("scope", "new user video AAC decoded WAV -> production asynchronous PianoRecognitionEngine; passive file replay, not original microphone PCM or musical acceptance")
            .put("inputProvenance", "USER_VIDEO_AAC_DECODED_PCM_NOT_NATIVE_MICROPHONE_PCM")
            .put("sourceVideoExpectedSha256", EXPECTED_VIDEO_SHA256).put("sourceVideoVerifiedOnDevice", false)
            .put("sourceVideoDurationSeconds", 15.633333).put("sourceAudioDeclaredSamples", 747_562)
            .put("timeAxis", "WAV_DECODED_SAMPLE_INDEX_48000_HZ")
            .put("videoTimeAlignment", "NOT_ASSUMED_EQUAL_SEE_EXTERNAL_AAC_PTS_TO_PCM_MAPPING")
            .put("replayPacing", "BOUNDED_CHUNKS_WAIT_FOR_ENGINE_DRAIN_NOT_REAL_TIME_THROUGHPUT_TEST")
            .put("microphoneCaptureTested", false).put("scoreFollowingTested", false)
            .put("fieldTestZipTested", false).put("noteGroundTruth", "NONE_NO_GUESSED_NOTE_LABELS")
            .put("phonePianoRoomAccuracy", "UNVERIFIED").put("endToEndLiveLatency", JSONObject.NULL)
            .put("expectedInputBytes", EXPECTED_BYTES).put("expectedConvertedSamples16k", EXPECTED_CONVERTED_SAMPLES)
            .put("expectedIncompleteTailSamples16k", EXPECTED_TAIL_SAMPLES)
            .put("expectedInputSha256", EXPECTED_SHA256).put("expectedSamples", EXPECTED_SAMPLES)
            .put("expectedFrames", EXPECTED_FRAMES).put("expectedWindows", EXPECTED_WINDOWS)
            .put("framesFile", framesFile.name).put("statusFile", statusFile.name)
        // Invalidate the old success first, even when arguments or fixture validation fail.
        metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
        val trace = TraceCapture()
        var engine: PianoRecognitionEngine? = null
        var submitted = 0L
        var submittedBlocks = 0L
        var failure: Throwable? = null
        try {
            framesFile.writeText("", Charsets.UTF_8)
            statusFile.writeText("", Charsets.UTF_8)
            val args = InstrumentationRegistry.getArguments()
            val path = requireNotNull(args.getString("phoneVideoWav")) {
                "新用户视频回放缺少 phoneVideoWav；禁止跳过或以合成音频替代"
            }
            val expectedSha = requireNotNull(args.getString("phoneVideoExpectedSha256")) {
                "新用户视频回放缺少 phoneVideoExpectedSha256；必须显式指定冻结输入身份"
            }
            assertEquals("新视频回放必须使用独立冻结输入，不能替换旧八度基线或临时猜测音名", EXPECTED_SHA256, expectedSha)
            val wav = authorizedFile(context, path)
            val inputSha = wav.inputStream().use(::sha256)
            metadata.put("inputSha256", inputSha).put("inputBytes", wav.length())
            assertEquals("新视频派生 WAV 字节数改变", EXPECTED_BYTES, wav.length())
            assertEquals("冻结 WAV 的 SHA256 不匹配", expectedSha, inputSha)
            val layout = readLayout(wav)
            metadata.put("inputSamples", layout.samples).put("sampleRate", 48_000)
                .put("channels", 1).put("pcmBits", 16).put("inputSeconds", layout.samples / 48_000.0)
            assertEquals("冻结 WAV 样本数改变", EXPECTED_SAMPLES, layout.samples)
            val modelSha = context.assets.open(PianoModel.MODEL_ASSET).use(::sha256)
            metadata.put("modelSha256", modelSha).put("modelAsset", PianoModel.MODEL_ASSET)
                .put("expectedModelSha256", PianoModel.MODEL_SHA256)
            assertEquals("实际模型资源 SHA256 不匹配", PianoModel.MODEL_SHA256, modelSha)
            metadata.put("stage", "REPLAY_RUNNING")
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            val runningEngine = PianoRecognitionEngine(context, trace::acceptFrame, trace::acceptStatus)
            engine = runningEngine
            runningEngine.start()
            awaitEngine(runningEngine) { runningEngine.snapshot.status == RecognitionEngineStatus.READY }
            metadata.put("readyObservedBeforeFirstPcm", true)
            assertEquals(0L, runningEngine.snapshot.submittedSamples)

            RandomAccessFile(wav, "r").use { input ->
                input.seek(layout.dataOffset)
                val bytes = ByteArray(4096 * 2)
                val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val samples = ShortArray(4096)
                var target = minOf(60_000L, layout.samples)
                while (submitted < layout.samples) {
                    while (submitted < target) {
                        val count = minOf(samples.size.toLong(), target - submitted).toInt()
                        input.readFully(bytes, 0, count * 2)
                        repeat(count) { samples[it] = littleEndian.getShort(it * 2) }
                        runningEngine.submitPcm(samples, count, submitted)
                        submitted += count
                        submittedBlocks++
                    }
                    val converted = if (submitted < Streaming48kTo16k.TAP_COUNT) 0L else
                        1L + (submitted - Streaming48kTo16k.TAP_COUNT) / Streaming48kTo16k.FACTOR
                    val windows = if (converted < PianoModel.INPUT_SAMPLES) 0L else
                        1L + (converted - PianoModel.INPUT_SAMPLES) / PianoRecognitionProcessor.STEP_SAMPLES
                    val expectedFrames = if (windows == 0L) 0L else PianoModel.FRAME_COUNT +
                        (windows - 1) * (PianoRecognitionProcessor.STEP_SAMPLES / PianoModel.HOP_SAMPLES)
                    awaitEngine(runningEngine) {
                        runningEngine.snapshot.frameCount >= expectedFrames &&
                            runningEngine.snapshot.processedBlocks >= submittedBlocks &&
                            runningEngine.snapshot.processedSamples >= submitted
                    }
                    target = minOf(submitted + 24_576, layout.samples)
                }
                assertEquals("真实 data 区块必须全部读取", layout.dataOffset + layout.samples * 2, input.filePointer)
            }
            assertEquals(EXPECTED_SAMPLES, submitted)
            val convertedSamples = 1L + (submitted - Streaming48kTo16k.TAP_COUNT) / Streaming48kTo16k.FACTOR
            val completeWindows = 1L + (convertedSamples - PianoModel.INPUT_SAMPLES) / PianoRecognitionProcessor.STEP_SAMPLES
            val tailSamples = convertedSamples - (PianoModel.INPUT_SAMPLES + (completeWindows - 1) * PianoRecognitionProcessor.STEP_SAMPLES)
            assertEquals(EXPECTED_CONVERTED_SAMPLES, convertedSamples)
            assertEquals(EXPECTED_WINDOWS.toLong(), completeWindows)
            assertEquals(EXPECTED_TAIL_SAMPLES, tailSamples)
            metadata.put("convertedSamples16k", convertedSamples).put("incompleteTailSamples16k", tailSamples)
            assertEquals(EXPECTED_FRAMES, runningEngine.snapshot.frameCount)
            assertEquals(submittedBlocks, runningEngine.snapshot.submittedBlocks)
            assertEquals(submitted, runningEngine.snapshot.submittedSamples)
            assertEquals(0L, runningEngine.snapshot.rejectedBeforeReadyFrames)
            assertEquals(0L, runningEngine.snapshot.droppedSamples)
            assertEquals(submittedBlocks, runningEngine.snapshot.processedBlocks)
            assertEquals(submitted, runningEngine.snapshot.processedSamples)
            assertEquals(0L, runningEngine.snapshot.droppedFrames)
            assertEquals(0L, runningEngine.snapshot.overflowCount)
            assertEquals(0, runningEngine.snapshot.queueOccupancy)
            assertEquals(RecognitionEngineStatus.RUNNING, runningEngine.snapshot.status)
            val captured = trace.frames()
            assertEquals(EXPECTED_FRAMES, captured.size.toLong())
            assertEquals(EXPECTED_WINDOWS, captured.map { it.availableAudioTimeSeconds }.distinct().size)
            metadata.put("replayAssertionsPassed", true)
        } catch (error: Throwable) {
            failure = error
        } finally {
            val beforeStop = engine?.snapshot
            try {
                engine?.stop()
                if (engine != null) assertEquals("回放结束必须真正停止识音", RecognitionEngineStatus.IDLE, engine.snapshot.status)
                metadata.put("statusBeforeStop", beforeStop?.status?.name ?: "NOT_STARTED")
                    .put("statusAfterStop", engine?.snapshot?.status?.name ?: "NOT_STARTED")
                    .put("engineFrames", beforeStop?.frameCount ?: 0L)
                    .put("engineModelSha256", beforeStop?.modelSha256 ?: JSONObject.NULL)
                    .put("processedSamples", beforeStop?.processedSamples ?: 0L)
                    .put("processedBlocks", beforeStop?.processedBlocks ?: 0L)
                    .put("queueOccupancy", beforeStop?.queueOccupancy ?: 0)
                    .put("maxQueueOccupancy", beforeStop?.maxQueueOccupancy ?: 0)
                    .put("queueCapacity", beforeStop?.queueCapacity ?: PianoRecognitionEngine.QUEUE_CAPACITY)
                    .put("droppedFrames", beforeStop?.droppedFrames ?: 0L)
                    .put("droppedSamples", beforeStop?.droppedSamples ?: 0L)
                    .put("overflowCount", beforeStop?.overflowCount ?: 0L)
                    .put("rejectedBeforeReadyFrames", beforeStop?.rejectedBeforeReadyFrames ?: 0L)
                    .put("modelLoadMs", beforeStop?.modelLoadMs ?: JSONObject.NULL)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally {
                // stop() shares the engine callback guard: no later frame can race serialization.
                try { engine?.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            try {
                val captured = trace.frames()
                val hashes = writeFrames(framesFile, captured)
                val statuses = trace.statusCounts()
                val statusEvents = trace.statusEvents()
                writeStatuses(statusFile, statusEvents)
                val complete = failure == null && metadata.optBoolean("replayAssertionsPassed") &&
                    metadata.optString("statusAfterStop") == RecognitionEngineStatus.IDLE.name &&
                    submitted == EXPECTED_SAMPLES && captured.size.toLong() == EXPECTED_FRAMES &&
                    beforeStop != null && beforeStop.processedBlocks == submittedBlocks && beforeStop.processedSamples == submitted &&
                    beforeStop.droppedFrames == 0L && beforeStop.droppedSamples == 0L && beforeStop.overflowCount == 0L &&
                    beforeStop.rejectedBeforeReadyFrames == 0L && beforeStop.submittedBlocks == submittedBlocks &&
                    beforeStop.submittedSamples == submitted && metadata.optBoolean("readyObservedBeforeFirstPcm")
                metadata.put("stage", if (complete) "TRACE_COMPLETE" else "FAILED")
                    .put("chainPassed", complete).put("traceComplete", complete)
                    .put("submittedSamples", submitted).put("submittedBlocks", submittedBlocks)
                    .put("runningPublications", trace.runningPublications())
                    .put("allSubmittedBlocksProcessed", complete)
                    .put("drainEvidence", "Engine processedBlocks and processedSamples account for every consumed PCM block including the incomplete-window tail; status callbacks are not processing counters")
                    .put("statusPublicationCounts", JSONObject(statuses))
                    .put("statusEventCount", statusEvents.size)
                    .put("statusFileSha256", statusFile.inputStream().use(::sha256))
                    .put("statusTraceMeaning", "Every delivered production snapshot including queue occupancy and submitted/processed counters; publication count is not a processing counter")
                    .put("statusAfterClose", engine?.snapshot?.status?.name ?: "NOT_STARTED")
                    .put("frames", captured.size).put("windows", captured.map { it.availableAudioTimeSeconds }.distinct().size)
                    .put("firstFrameIndex", captured.firstOrNull()?.frameIndex ?: JSONObject.NULL)
                    .put("lastFrameIndex", captured.lastOrNull()?.frameIndex ?: JSONObject.NULL)
                    .put("acceptedOnsets", captured.sumOf { it.onsetMidi.size })
                    .put("onsetFrames", captured.count { it.onsetMidi.isNotEmpty() })
                    .put("uncertainFrames", captured.count { it.state == RecognitionState.UNCERTAIN })
                    .put("uncertainCandidates", captured.sumOf { it.uncertainMidi.size })
                    .put("clippedFrames", captured.count { it.clipped })
                    .put("rawSequenceSha256", hashes.rawSequence)
                    .put("deterministicSequenceSha256", hashes.deterministicSequence)
                    .put("framesFileSha256", framesFile.inputStream().use(::sha256))
                    .put("rawSequenceHashEncoding", "Per frame: int64 frameIndex then float32 rawOnsetScores[88] then float32 rawFrameScores[88], all big endian; concatenate frames in order; SHA-256")
                    .put("deterministicSequenceHashEncoding", "UTF-8 JSONL with recursively sorted object keys and LF after each frame; Android JSONObject number formatting; omit computeMs only")
                    .put("scoresMeaning", "Unmodified engine sigmoid activation scores, not logits or calibrated probabilities; index 0 = MIDI 21, index 87 = MIDI 108")
                    .put("tailPolicy", "All real PCM blocks processed; incomplete final inference window is not zero-padded and produces no invented frames")
                    .put("unavailableDiagnostics", JSONArray(listOf("independentOnsetScore", "octaveAmbiguityScore", "measuredNoteOffsets", "reliablePedalState", "scoreFollowing", "lockReason", "nativeMicrophonePcm", "musicalGroundTruth")))
                    .put("pitchAccuracy", JSONObject.NULL).put("falseStopRate", JSONObject.NULL)
                    .put("elapsedWallMs", SystemClock.elapsedRealtime() - started)
                    .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
                // The success flag is only written after the current trace was flushed and hashed.
                metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
                metadata.put("stage", "OUTPUT_FAILED").put("chainPassed", false).put("traceComplete", false)
                    .put("failure", failure.stackTraceToString())
                try { metadataFile.writeText(metadata.toString(2), Charsets.UTF_8) } catch (writeError: Throwable) {
                    failure.addSuppressed(writeError)
                }
            }
        }
        failure?.let { throw it }
        check(metadata.getBoolean("traceComplete")) { "新视频逐帧输出不完整" }
    }

    /** Own only detached copies; never invoke the engine or perform I/O under this capture lock. */
    private class TraceCapture {
        private val captured = ArrayList<PianoRecognitionFrame>()
        private val publications = linkedMapOf<String, Long>()
        private val statusTrace = ArrayList<CapturedStatus>()

        @Synchronized fun acceptFrame(frame: PianoRecognitionFrame) {
            check(frame.frameIndex == captured.size.toLong()) { "实际模型帧不连续：${captured.size} -> ${frame.frameIndex}" }
            val previous = captured.lastOrNull()
            check(frame.audioTimeSeconds.isFinite() && frame.availableAudioTimeSeconds.isFinite())
            check(frame.audioTimeSeconds >= 0.0 && frame.availableAudioTimeSeconds >= frame.audioTimeSeconds)
            check(previous == null || frame.audioTimeSeconds > previous.audioTimeSeconds &&
                frame.availableAudioTimeSeconds >= previous.availableAudioTimeSeconds)
            check(frame.computeMs.isFinite() && frame.computeMs >= 0.0)
            check(frame.rawOnsetScores.size == PianoModel.PIANO_KEYS && frame.rawFrameScores.size == PianoModel.PIANO_KEYS)
            check(frame.rawOnsetScores.all { it.isFinite() && it in 0f..1f })
            check(frame.rawFrameScores.all { it.isFinite() && it in 0f..1f })
            captured.add(frame.copy(
                onsetMidi = frame.onsetMidi.toSet(), activeMidi = frame.activeMidi.toSet(), notes = frame.notes.toList(),
                rawOnsetScores = frame.rawOnsetScores.copyOf(), rawFrameScores = frame.rawFrameScores.copyOf(),
                uncertainMidi = frame.uncertainMidi.toSet(), candidateRejections = frame.candidateRejections.toMap(),
                octaveDiagnostics = frame.octaveDiagnostics.toList(), ambiguousOctaveMidi = frame.ambiguousOctaveMidi.toSet(),
            ))
        }

        @Synchronized fun acceptStatus(snapshot: RecognitionEngineSnapshot) {
            publications[snapshot.status.name] = (publications[snapshot.status.name] ?: 0L) + 1
            statusTrace.add(CapturedStatus(SystemClock.elapsedRealtime(), snapshot.copy()))
        }
        @Synchronized fun runningPublications(): Long = publications[RecognitionEngineStatus.RUNNING.name] ?: 0L
        @Synchronized fun statusCounts(): Map<String, Long> = publications.toMap()
        @Synchronized fun statusEvents(): List<CapturedStatus> = statusTrace.toList()
        @Synchronized fun frames(): List<PianoRecognitionFrame> = captured.toList()
    }

    private data class CapturedStatus(val callbackElapsedRealtimeMs: Long, val snapshot: RecognitionEngineSnapshot)

    private fun writeStatuses(file: File, statuses: List<CapturedStatus>) {
        file.bufferedWriter(Charsets.UTF_8).use { output ->
            statuses.forEachIndexed { index, event ->
                val s = event.snapshot
                val row = JSONObject().put("publicationIndex", index)
                    .put("callbackElapsedRealtimeMs", event.callbackElapsedRealtimeMs)
                    .put("status", s.status.name).put("message", s.message).put("sessionId", s.sessionId)
                    .put("frameCount", s.frameCount).put("computeMs", s.computeMs ?: JSONObject.NULL)
                    .put("modelSha256", s.modelSha256).put("accuracyValidated", s.accuracyValidated)
                    .put("modelLoadMs", s.modelLoadMs ?: JSONObject.NULL)
                    .put("queueOccupancy", s.queueOccupancy).put("queueCapacity", s.queueCapacity)
                    .put("maxQueueOccupancy", s.maxQueueOccupancy)
                    .put("submittedBlocks", s.submittedBlocks).put("submittedSamples", s.submittedSamples)
                    .put("processedBlocks", s.processedBlocks).put("processedSamples", s.processedSamples)
                    .put("droppedFrames", s.droppedFrames).put("droppedSamples", s.droppedSamples)
                    .put("overflowCount", s.overflowCount).put("rejectedBeforeReadyFrames", s.rejectedBeforeReadyFrames)
                output.write(canonicalJson(row)); output.write("\n")
            }
        }
    }

    private data class TraceHashes(val rawSequence: String, val deterministicSequence: String)

    private fun writeFrames(file: File, frames: List<PianoRecognitionFrame>): TraceHashes {
        val rawDigest = MessageDigest.getInstance("SHA-256")
        val deterministicDigest = MessageDigest.getInstance("SHA-256")
        val rawBytes = ByteBuffer.allocate(8 + PianoModel.PIANO_KEYS * 2 * 4).order(ByteOrder.BIG_ENDIAN)
        file.bufferedWriter(Charsets.UTF_8).use { output ->
            frames.forEach { frame ->
                rawBytes.clear()
                rawBytes.putLong(frame.frameIndex)
                frame.rawOnsetScores.forEach { rawBytes.putFloat(it) }
                frame.rawFrameScores.forEach { rawBytes.putFloat(it) }
                rawDigest.update(rawBytes.array())
                val row = frameJson(frame)
                val deterministicLine = canonicalJson(row) + "\n"
                deterministicDigest.update(deterministicLine.toByteArray(Charsets.UTF_8))
                row.put("computeMs", frame.computeMs)
                output.write(canonicalJson(row))
                output.write("\n")
            }
        }
        return TraceHashes(hex(rawDigest.digest()), hex(deterministicDigest.digest()))
    }

    private fun frameJson(frame: PianoRecognitionFrame): JSONObject {
        val rejections = JSONObject()
        frame.candidateRejections.toSortedMap().forEach { (midi, reason) -> rejections.put(midi.toString(), reason) }
        return JSONObject().put("frameIndex", frame.frameIndex).put("audioTimeSeconds", frame.audioTimeSeconds)
            .put("availableAudioTimeSeconds", frame.availableAudioTimeSeconds)
            .put("state", frame.state.name).put("clipped", frame.clipped)
            .put("onsetMidi", JSONArray(frame.onsetMidi.sorted())).put("activeMidi", JSONArray(frame.activeMidi.sorted()))
            .put("rawOnsetScores", JSONArray(frame.rawOnsetScores.toList()))
            .put("rawFrameScores", JSONArray(frame.rawFrameScores.toList()))
            .put("uncertainMidi", JSONArray(frame.uncertainMidi.sorted())).put("candidateRejections", rejections)
            .put("ambiguousOctaveMidi", JSONArray(frame.ambiguousOctaveMidi.sorted()))
            .put("octaveDiagnostics", JSONArray(frame.octaveDiagnostics.map { d ->
                JSONObject().put("lowerMidi", d.lowerMidi).put("upperMidi", d.upperMidi)
                    .put("lowerOnsetScore", d.lowerOnsetScore).put("upperOnsetScore", d.upperOnsetScore)
                    .put("lowerFrameScore", d.lowerFrameScore).put("upperFrameScore", d.upperFrameScore)
                    .put("temporalOffsetSeconds", d.temporalOffsetSeconds ?: JSONObject.NULL)
                    .put("lowerFundamentalAmplitude", d.lowerFundamentalAmplitude ?: JSONObject.NULL)
                    .put("upperFundamentalAmplitude", d.upperFundamentalAmplitude ?: JSONObject.NULL)
                    .put("upperAttackEnergyRise", d.upperAttackEnergyRise ?: JSONObject.NULL)
                    .put("lowerAttackEnergyRise", d.lowerAttackEnergyRise ?: JSONObject.NULL)
                    .put("harmonicPrior", d.harmonicPrior ?: JSONObject.NULL)
                    .put("independentOnsetScore", d.independentOnsetScore ?: JSONObject.NULL)
                    .put("scoreContextWeight", d.scoreContextWeight ?: JSONObject.NULL)
                    .put("finalConfidence", d.finalConfidence ?: JSONObject.NULL)
                    .put("finalState", d.finalState.name).put("reason", d.reason)
            }))
            .put("pedal", frame.pedalState).put("offset", JSONObject.NULL).put("offsetSupported", frame.offsetSupported)
            .put("modelWindowMs", frame.modelWindowMs)
            .put("notes", JSONArray(frame.notes.sortedBy { it.midi }.map { note ->
                JSONObject().put("midi", note.midi).put("onsetScore", note.onsetScore)
                    .put("frameScore", note.frameScore).put("onset", note.onset).put("active", note.active)
            }))
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        is String -> JSONObject.quote(value)
        else -> error("不可序列化的真实帧字段：${value.javaClass.name}")
    }

    private data class WavLayout(val dataOffset: Long, val samples: Long)

    private fun readLayout(file: File): WavLayout = RandomAccessFile(file, "r").use { input ->
        fun fourCc(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun unsignedInt(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun unsignedShort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        require(input.length() >= 44 && fourCc() == "RIFF") { "缺少有效 WAV RIFF" }
        require(unsignedInt() + 8 == input.length() && fourCc() == "WAVE") { "WAV 长度或类型无效" }
        var validFormat = false
        var result: WavLayout? = null
        while (input.filePointer + 8 <= input.length()) {
            val type = fourCc(); val length = unsignedInt(); val offset = input.filePointer
            require(length <= input.length() - offset) { "WAV 区块越界" }
            if (type == "fmt ") {
                require(length >= 16 && !validFormat)
                val pcm = unsignedShort(); val channels = unsignedShort(); val rate = unsignedInt()
                val bytesPerSecond = unsignedInt(); val alignment = unsignedShort(); val bits = unsignedShort()
                validFormat = pcm == 1 && channels == 1 && rate == 48_000L &&
                    bytesPerSecond == 96_000L && alignment == 2 && bits == 16
                require(validFormat) { "新视频派生音轨必须为 48kHz 单声道 16-bit PCM" }
            } else if (type == "data") {
                require(validFormat && length > 0 && length % 2 == 0L && result == null)
                result = WavLayout(offset, length / 2)
            }
            val next = offset + length + length % 2
            require(next <= input.length())
            input.seek(next)
        }
        require(input.filePointer == input.length()) { "WAV 存在不完整尾部区块" }
        requireNotNull(result) { "缺少真实 PCM data 区块" }
    }

    private fun authorizedFile(context: Context, path: String): File {
        val file = File(path).canonicalFile
        val expectedFile = File(context.filesDir, "phone-video-20260923.wav").canonicalFile
        require(file == expectedFile && file.isFile && file.length() == EXPECTED_BYTES) {
            "新视频输入必须是此 App 私有 files/phone-video-20260923.wav，且字节数与冻结派生 WAV 一致"
        }
        return file
    }

    private fun awaitEngine(engine: PianoRecognitionEngine, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(SystemClock.elapsedRealtime() < deadline) { "真实新视频异步回放等待超时：${engine.snapshot}" }
            Thread.sleep(10)
        }
        check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        private const val EXPECTED_SHA256 = "777ff0c774277532ae61754afd4aea345d71e3be03c1e264fbf817ae50d9e858"
        private const val EXPECTED_VIDEO_SHA256 = "ae9a491bbf22ba24fff861c00bb36a84f9b31ca51e612c25bbb1d9e11448e727"
        private const val EXPECTED_BYTES = 1_501_262L
        private const val EXPECTED_CONVERTED_SAMPLES = 250_156L
        private const val EXPECTED_TAIL_SAMPLES = 2_860L
        private const val EXPECTED_SAMPLES = 750_592L
        private const val EXPECTED_FRAMES = 480L
        private const val EXPECTED_WINDOWS = 29
    }
}
