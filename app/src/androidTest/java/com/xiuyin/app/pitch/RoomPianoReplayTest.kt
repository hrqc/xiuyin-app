package com.xiuyin.app.pitch

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real room PCM through the App engine. No score, note truth, accuracy or false-stop claim is supplied. */
class RoomPianoReplayTest {
    @Test fun fullRoomRecordingTraversesProductionAsyncEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "room-piano-replay-result.json")
        val startedAtMillis = System.currentTimeMillis()
        // A missing/invalid fixture must not leave an earlier successful artifact looking current.
        output.writeText(JSONObject().put("chainPassed", false).put("stage", "INPUT_VALIDATION")
            .put("startedAtMillis", startedAtMillis).put("accuracyValidated", false).toString(2))
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("roomPianoWav")) {
            "琴房全文件门禁缺少 roomPianoWav；禁止跳过或以合成音频替代"
        }
        val wav = authorizedFile(path)
        val inputSha = sha256(wav)
        assertEquals("必须使用冻结的真实琴房派生 WAV", EXPECTED_SHA256, inputSha)
        val layout = readLayout(wav)
        assertEquals(EXPECTED_SAMPLES, layout.samples)
        val stats = ReplayStats()
        val engine = PianoRecognitionEngine(context, onFrame = stats::accept)
        val started = SystemClock.elapsedRealtime()
        var submitted = 0L
        var failure: Throwable? = null
        try {
            engine.start()
            awaitEngine(engine) { engine.snapshot.status != RecognitionEngineStatus.LOADING }
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
                        engine.submitPcm(samples, count, submitted)
                        submitted += count
                    }
                    val safe16k = (submitted - 64).coerceAtLeast(0) / 3
                    val windows = if (safe16k < PianoModel.INPUT_SAMPLES) 0L else
                        1L + (safe16k - PianoModel.INPUT_SAMPLES) / PianoRecognitionProcessor.STEP_SAMPLES
                    val frames = if (windows == 0L) 0L else 32 + (windows - 1) * 16
                    awaitEngine(engine) { engine.snapshot.frameCount >= frames }
                    target = minOf(submitted + 24_576, layout.samples)
                }
                assertEquals(layout.dataOffset + layout.samples * 2, input.filePointer)
            }
            assertEquals(EXPECTED_SAMPLES, submitted)
            assertEquals(EXPECTED_FRAMES, engine.snapshot.frameCount)
            val measured = stats.snapshot()
            assertEquals(EXPECTED_FRAMES, measured.getLong("frames"))
            assertEquals(EXPECTED_WINDOWS, measured.getInt("windows"))
            assertEquals(RecognitionEngineStatus.RUNNING, engine.snapshot.status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val beforeStop = engine.snapshot
            engine.stop()
            val afterStop = engine.snapshot.status
            try {
                val result = stats.snapshot().put("startedAtMillis", startedAtMillis).put("stage", "REPLAY_FINISHED")
                    .put("scope", "actual local room WAV -> production asynchronous PianoRecognitionEngine; file replay, not live microphone")
                    .put("inputSha256", inputSha).put("modelSha256", beforeStop.modelSha256)
                    .put("sampleRate", 48000).put("channels", 1).put("pcmBits", 16)
                    .put("inputSamples", layout.samples).put("submittedSamples", submitted)
                    .put("inputSeconds", layout.samples / 48000.0)
                    .put("engineFrames", beforeStop.frameCount).put("statusBeforeStop", beforeStop.status.name)
                    .put("statusAfterStop", afterStop.name).put("elapsedWallMs", SystemClock.elapsedRealtime() - started)
                    .put("chainPassed", failure == null && afterStop == RecognitionEngineStatus.IDLE)
                    .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
                    .put("pitchAccuracy", JSONObject.NULL).put("falseStopRate", JSONObject.NULL)
                    .put("accuracyValidated", false)
                    .put("acceptance", "Only complete input submission, real inference counts and stop lifecycle are checked; no played-note ground truth")
                    .put("tailPolicy", "All input samples submitted; an incomplete final model window is not zero-padded or counted as evaluated")
                output.writeText(result.toString(2))
            } finally { engine.close() }
            assertEquals("回放结束必须真正停止识音", RecognitionEngineStatus.IDLE, afterStop)
        }
    }
    private class ReplayStats {
        private var frames = 0L
        private var acceptedOnsets = 0
        private var onsetFrames = 0
        private var uncertainFrames = 0
        private var acousticUncertainFrames = 0
        private var uncertainCandidates = 0
        private var clippedFrames = 0
        private var lastTime = -1.0
        private var lastAvailable = -1.0
        private val computeMs = ArrayList<Double>()
        private val states = linkedMapOf<String, Int>()
        @Synchronized fun accept(frame: PianoRecognitionFrame) {
            check(frame.frameIndex == frames) { "实际模型帧不连续：期望 $frames，收到 ${frame.frameIndex}" }
            check(frame.audioTimeSeconds > lastTime && frame.availableAudioTimeSeconds >= lastAvailable)
            check(frame.computeMs.isFinite() && frame.computeMs >= 0)
            if (frame.availableAudioTimeSeconds != lastAvailable) computeMs.add(frame.computeMs)
            frames++
            acceptedOnsets += frame.onsetMidi.size
            if (frame.onsetMidi.isNotEmpty()) onsetFrames++
            if (frame.state == RecognitionState.UNCERTAIN) uncertainFrames++
            if (frame.uncertainMidi.isNotEmpty()) acousticUncertainFrames++
            uncertainCandidates += frame.uncertainMidi.size
            if (frame.clipped) clippedFrames++
            states[frame.state.name] = (states[frame.state.name] ?: 0) + 1
            lastTime = frame.audioTimeSeconds
            lastAvailable = frame.availableAudioTimeSeconds
        }
        @Synchronized fun snapshot(): JSONObject {
            val sorted = computeMs.sorted()
            return JSONObject().put("frames", frames).put("windows", computeMs.size)
                .put("windowCountSource", "distinct actual availableAudioTimeSeconds values in engine callbacks")
                .put("acceptedOnsets", acceptedOnsets).put("onsetFrames", onsetFrames)
                .put("uncertainFrames", uncertainFrames).put("acousticUncertainFrames", acousticUncertainFrames)
                .put("uncertainCandidates", uncertainCandidates).put("clippedFrames", clippedFrames)
                .put("states", JSONObject(states.toMap())).put("lastAudioTimeSeconds", lastTime)
                .put("lastAvailableAudioTimeSeconds", lastAvailable)
                .put("computeMsByWindow", JSONArray(computeMs.toList()))
                .put("computeMsMedian", sorted.getOrNull(sorted.size / 2) ?: JSONObject.NULL)
                .put("computeMsMax", sorted.lastOrNull() ?: JSONObject.NULL)
                .put("computeMsTotal", computeMs.sum())
        }
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
                require(length >= 16)
                val pcm = unsignedShort(); val channels = unsignedShort(); val rate = unsignedInt()
                val bytesPerSecond = unsignedInt(); val alignment = unsignedShort(); val bits = unsignedShort()
                validFormat = pcm == 1 && channels == 1 && rate == 48000L && bytesPerSecond == 96000L && alignment == 2 && bits == 16
                require(validFormat) { "琴房回放必须为 48kHz 单声道 16-bit PCM" }
            } else if (type == "data") {
                require(validFormat && length > 0 && length % 2 == 0L && result == null)
                result = WavLayout(offset, length / 2)
            }
            val next = offset + length + length % 2
            require(next <= input.length())
            input.seek(next)
        }
        requireNotNull(result) { "缺少真实 PCM data 区块" }
    }
    private fun authorizedFile(path: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(path).canonicalFile
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
        require(roots.any { file.path.startsWith(it) } && file.isFile && file.length() in 44..64L * 1024 * 1024) {
            "琴房输入必须位于此 App 私有测试目录且不超过 64MiB"
        }
        return file
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
    private fun awaitEngine(engine: PianoRecognitionEngine, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(SystemClock.elapsedRealtime() < deadline) { "真实琴房异步回放等待超时：${engine.snapshot}" }
            Thread.sleep(10)
        }
        check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
    }
    companion object {
        private const val EXPECTED_SHA256 = "89d79abba6e39f7cbbc78759b4220bb2e1dfa31ce174ffa8846f361f7b78e2d1"
        private const val EXPECTED_SAMPLES = 8_418_304L
        private const val EXPECTED_FRAMES = 5472L
        private const val EXPECTED_WINDOWS = 341
    }
}