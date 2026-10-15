package com.xiuyin.app.pitch

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.field.toFieldLogRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Complete supplied real recording -> production worker. This is not live-phone accuracy acceptance. */
class ClavierRealWavInstrumentedTest {
    @Test fun completeRealPcmTraversesProductionWorkerWithDiagnosticSideChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("clavierWav")
        assumeTrue("未验证：需要单独用 clavierWav 对两份真实录音各执行一次；跳过不能计为回放通过", path != null)
        val wav = authorizedFile(requireNotNull(path))
        val inputSha = sha256(wav)
        val expectedSamples = requireNotNull(INPUTS[inputSha]) { "不是冻结的两份真实 PCM" }
        val layout = readLayout(wav)
        assertEquals(expectedSamples, layout.samples)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "clavier-replay").apply { mkdirs() }
        val resultFile = File(directory, "$inputSha.result.json")
        val framesFile = File(directory, "$inputSha.frames.jsonl")
        val startedAt = System.currentTimeMillis()
        resultFile.writeText(JSONObject().put("chainPassed", false).put("stage", "REPLAY_STARTED")
            .put("startedAtMillis", startedAt).put("inputSha256", inputSha).toString(2))
        val writer = framesFile.bufferedWriter()
        var frames = 0L
        var aligned = 0L
        var warmup = 0L
        var nullBest = 0L
        var fullFitFrames = 0L
        var submitted = 0L
        var lastWindowEnd = -1L
        var uniqueWindows = 0L
        var referenceComputeMs = 0.0
        var referenceMaxComputeMs = 0.0
        val engine = PianoRecognitionEngine(context, onFrame = { frame ->
            check(frames == frame.frameIndex) { "模型帧不连续：$frames -> ${frame.frameIndex}" }
            val reference = requireNotNull(frame.singlePitchReference)
            when (reference.status) {
                ClavierReferenceStatus.WARMING_UP -> warmup++
                ClavierReferenceStatus.ALIGNED -> {
                    aligned++
                    check(reference.windowEndSeconds!! <= frame.audioTimeSeconds) { "旁证用了未来窗" }
                    check(reference.ageSeconds!! in 0.0..(2048 / 48000.0)) { "旁证窗口已过期" }
                    check(reference.windowStartSample!! + 16384 == reference.windowEndSampleExclusive)
                    if (reference.fits != null) {
                        check(reference.fits.size == 88) { "旁证没有保留全88个fit" }
                        check(reference.fits.map { it.startMidi } == (21..108).toList())
                        fullFitFrames++
                    }
                    if (reference.best == null) nullBest++
                    if (reference.windowEndSampleExclusive != lastWindowEnd) {
                        uniqueWindows++
                        lastWindowEnd = requireNotNull(reference.windowEndSampleExclusive)
                        referenceComputeMs += requireNotNull(reference.computeMs)
                        referenceMaxComputeMs = maxOf(referenceMaxComputeMs, reference.computeMs)
                    }
                }
                else -> error("旁证关键验收失败 ${reference.status}: ${reference.error}")
            }
            writer.appendLine(JSONObject().put("frameIndex", frame.frameIndex)
                .put("audioTimeSeconds", frame.audioTimeSeconds)
                .put("availableAudioTimeSeconds", frame.availableAudioTimeSeconds)
                .put("rawOnsetScores", JSONArray(frame.rawOnsetScores.toList()))
                .put("rawFrameScores", JSONArray(frame.rawFrameScores.toList()))
                .put("onsetMidi", JSONArray(frame.onsetMidi.sorted()))
                .put("activeMidi", JSONArray(frame.activeMidi.sorted()))
                .put("uncertainMidi", JSONArray(frame.uncertainMidi.sorted()))
                .put("state", frame.state.name)
                .put("singlePitchReference", JSONObject(reference.toFieldLogRecord())).toString())
            frames++
        })
        engine.updateConfig(PitchEvidenceConfig(singlePitchReferenceEnabled = true))
        val started = SystemClock.elapsedRealtime()
        var failure: Throwable? = null
        try {
            engine.start()
            awaitEngine(engine) { engine.snapshot.status == RecognitionEngineStatus.READY }
            RandomAccessFile(wav, "r").use { input ->
                input.seek(layout.dataOffset)
                val bytes = ByteArray(4096 * 2)
                val littleEndian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val samples = ShortArray(4096)
                while (submitted < layout.samples) {
                    val count = minOf(samples.size.toLong(), layout.samples - submitted).toInt()
                    input.readFully(bytes, 0, count * 2)
                    repeat(count) { samples[it] = littleEndian.getShort(it * 2) }
                    engine.submitPcm(samples, count, submitted)
                    submitted += count
                    // Test-only backpressure ensures complete-file correctness; no real-time claim.
                    awaitEngine(engine) { engine.snapshot.processedSamples == submitted }
                }
                assertEquals(layout.dataOffset + layout.samples * 2, input.filePointer)
            }
            val safe16k = if (submitted < Streaming48kTo16k.TAP_COUNT) 0L
                else (submitted - Streaming48kTo16k.TAP_COUNT) / Streaming48kTo16k.FACTOR + 1
            val windows = 1L + (safe16k - PianoModel.INPUT_SAMPLES) / PianoRecognitionProcessor.STEP_SAMPLES
            val expectedFrames = 32 + (windows - 1) * 16
            assertEquals(expectedSamples, engine.snapshot.processedSamples)
            assertEquals(expectedFrames, engine.snapshot.frameCount)
            assertEquals(expectedFrames, frames)
            assertEquals(0L, engine.snapshot.droppedFrames)
            assertEquals(0L, engine.snapshot.overflowCount)
            assertTrue(aligned > 0 && warmup > 0 && fullFitFrames > 0)
            assertEquals(RecognitionEngineStatus.RUNNING, engine.snapshot.status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val beforeStop = engine.snapshot
            engine.stop()
            val afterStop = engine.snapshot.status
            writer.close()
            try {
                resultFile.writeText(JSONObject().put("startedAtMillis", startedAt)
                    .put("chainPassed", failure == null && afterStop == RecognitionEngineStatus.IDLE)
                    .put("stage", "REPLAY_FINISHED").put("inputSha256", inputSha)
                    .put("inputSamples", layout.samples).put("submittedSamples", submitted)
                    .put("processedSamples", beforeStop.processedSamples).put("frames", frames)
                    .put("warmupFrames", warmup).put("alignedFrames", aligned)
                    .put("alignedNullBestFrames", nullBest).put("alignedFull88FitFrames", fullFitFrames)
                    .put("distinctAlignedWindows", uniqueWindows)
                    .put("distinctAlignedReferenceComputeMs", referenceComputeMs)
                    .put("referenceMaxComputeMs", referenceMaxComputeMs)
                    .put("overflowCount", beforeStop.overflowCount).put("droppedSamples", beforeStop.droppedSamples)
                    .put("elapsedWallMs", SystemClock.elapsedRealtime() - started)
                    .put("statusBeforeStop", beforeStop.status.name).put("statusAfterStop", afterStop.name)
                    .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
                    .put("framesFile", framesFile.name).put("accuracyValidated", false)
                    .put("liveMicrophoneValidated", false).put("realTimePerformanceValidated", false)
                    .put("scope", "Full real WAV through existing production asynchronous worker; paced offline replay; no expected note labels supplied")
                    .put("tailPolicy", "All PCM submitted; no zero padding of incomplete model/reference windows").toString(2))
            } finally { engine.close() }
        }
    }

    private data class WavLayout(val dataOffset: Long, val samples: Long)

    private fun readLayout(file: File): WavLayout = RandomAccessFile(file, "r").use { input ->
        fun fourCc(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun unsignedInt(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun unsignedShort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        require(input.length() >= 44 && fourCc() == "RIFF")
        require(unsignedInt() + 8 == input.length() && fourCc() == "WAVE")
        var validFormat = false
        var result: WavLayout? = null
        while (input.filePointer + 8 <= input.length()) {
            val type = fourCc(); val length = unsignedInt(); val offset = input.filePointer
            require(length <= input.length() - offset)
            if (type == "fmt ") {
                require(length >= 16)
                val pcm = unsignedShort(); val channels = unsignedShort(); val rate = unsignedInt()
                val bytesPerSecond = unsignedInt(); val alignment = unsignedShort(); val bits = unsignedShort()
                validFormat = pcm == 1 && channels == 1 && rate == 48000L && bytesPerSecond == 96000L && alignment == 2 && bits == 16
                require(validFormat) { "必须为 48kHz 单声道 PCM16" }
            } else if (type == "data") {
                require(validFormat && length > 0 && length % 2 == 0L && result == null)
                result = WavLayout(offset, length / 2)
            }
            val next = offset + length + length % 2
            require(next <= input.length())
            input.seek(next)
        }
        requireNotNull(result)
    }

    private fun authorizedFile(path: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(path).canonicalFile
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
        require(roots.any { file.path.startsWith(it) } && file.isFile && file.length() in 44..64L * 1024 * 1024)
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
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(SystemClock.elapsedRealtime() < deadline) { "真实回放超时：${engine.snapshot}" }
            Thread.sleep(10)
        }
    }

    companion object {
        private val INPUTS = mapOf(
            "753c59bc3005d6c37cbb4a2078938c46d4293b690ae15a61d75203bf2b75280d" to 1_995_776L,
            "66241afbbbe26edfe075b6402f56ca6764944fcd64825ff02ee52b745704a310" to 2_028_544L,
        )
    }
}
