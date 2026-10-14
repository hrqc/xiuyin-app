package com.xiuyin.quicktest

import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.pitch.RecognitionState
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickTestStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finishDrainsCopiesInputWritesPcm16AndExportsVerifiedEvidence() {
        val root = temporary.newFolder("complete")
        QuickTestStore(root).use { store ->
            val started = store.start(mapOf("version" to "test", "modelSHA" to "independent-model-id",
                "device" to mapOf("model" to "JVM"), "note" to "琴\n\"C4\""))
            val samples = shortArrayOf(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE)
            assertTrue(store.appendPcm(samples, samples.size, 0))
            samples.fill(123)
            val raw = FloatArray(88) { .25f }
            assertTrue(store.appendFrame(frame(raw)))
            raw.fill(.99f)
            assertTrue(store.mark("听起来不对", 3))
            assertTrue(store.appendEvent("MICROPHONE", mapOf("source" to "UNPROCESSED")))
            val ended = store.finish("USER_ENDED", healthy(5, 1))
            assertEquals("COMPLETED", ended.status)
            assertEquals(5, ended.sampleCount)
            assertEquals(1, ended.frameCount)
            assertEquals(1, ended.markCount)
            assertEquals(ended, store.finish("APP_BACKGROUND"))
            assertFalse(store.appendPcm(shortArrayOf(1), 1, 5))
            val wav = File(ended.directory, "audio.wav").readBytes()
            assertWav(wav, 5)
            val actual = ByteBuffer.wrap(wav, 44, 10).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(listOf<Short>(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE), List(5) { actual.short })
            val recognition = File(ended.directory, "recognition.jsonl").readText()
            assertTrue(recognition.contains("\"rawOnsetScores\":[0.25"))
            assertFalse(recognition.contains("0.99"))
            assertTrue(File(ended.directory, "marks.jsonl").readText().contains("\"sample\":3"))
            val zipFile = store.export(started.id)
            ZipFile(zipFile).use { zip ->
                assertEquals(8, zip.size())
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                for (name in listOf("audio.wav", "metadata.json", "recognition.jsonl", "marks.jsonl",
                    "events.jsonl", "summary.json", "session.properties")) {
                    val bytes = zip.getInputStream(zip.getEntry(name)).readBytes()
                    assertTrue(manifest.contains(sha(bytes)))
                    assertTrue(manifest.contains("\"path\":\"$name\""))
                }
            }
        }
        QuickTestStore(root).use { reopened ->
            assertEquals("COMPLETED", reopened.listSessions().single().status)
            assertTrue(reopened.export(reopened.listSessions().single().id).isFile)
        }
    }

    @Test fun gapIsExplicitErrorAndDoesNotCreateFakeContinuousPcm() {
        val errors = mutableListOf<String>()
        QuickTestStore(temporary.newFolder("gap"), { errors.add(it) }).use { store ->
            store.start(emptyMap())
            assertTrue(store.appendPcm(shortArrayOf(10, 20), 2, 0))
            assertFalse(store.appendPcm(shortArrayOf(30), 1, 3))
            assertFalse(store.appendPcm(shortArrayOf(40), 1, 2))
            val ended = store.finish("USER_ENDED", healthy(2))
            assertEquals("ERROR", ended.status)
            assertEquals(2, ended.sampleCount)
            assertTrue(ended.error.orEmpty().contains("PCM_DISCONTINUITY"))
            assertEquals(1, errors.size)
            assertWav(File(ended.directory, "audio.wav").readBytes(), 2)
        }
    }

    @Test fun boundedQueueRejectsWithoutBlockingAndPreservesAdmittedPrefix() {
        var task: Runnable? = null
        val errors = mutableListOf<String>()
        QuickTestStore(temporary.newFolder("queue"), { errors.add(it) }, 1, { task = it }).use { store ->
            store.start(emptyMap())
            assertTrue(store.appendPcm(shortArrayOf(11, 22), 2, 0))
            assertFalse(store.appendPcm(shortArrayOf(33), 1, 2))
            assertTrue(errors.single().startsWith("STORAGE_QUEUE_FULL"))
            Thread(checkNotNull(task)).start()
            val ended = store.finish("USER_ENDED", healthy(2))
            assertEquals("ERROR", ended.status)
            assertEquals(2, ended.sampleCount)
            assertTrue(File(ended.directory, "summary.json").readText().contains("\"rejectedCommands\":1"))
        }
    }

    @Test fun maximumDurationIsExactlySixtySecondsAndNeverSilentlyTruncates() {
        QuickTestStore(temporary.newFolder("limit")).use { store ->
            store.start(emptyMap())
            val data = ShortArray(QuickTestStore.MAX_SAMPLES.toInt()) { (it % 123).toShort() }
            assertTrue(store.appendPcm(data, data.size, 0))
            val ended = store.finish("MAX_DURATION", healthy(data.size.toLong()))
            assertEquals("COMPLETED", ended.status)
            assertEquals(60.0, ended.durationSeconds, 0.0)
            assertWav(File(ended.directory, "audio.wav").readBytes(), data.size.toLong())
            store.start(emptyMap())
            assertFalse(store.appendPcm(ShortArray(data.size + 1), data.size + 1, 0))
            val rejected = store.finish("USER_ENDED")
            assertEquals("ERROR", rejected.status)
            assertEquals(0, rejected.sampleCount)
            assertTrue(rejected.error.orEmpty().contains("MAX_DURATION_EXCEEDED"))
        }
    }

    @Test fun interruptedCaptureRecoversOnlyDurableBytesAndFixesHeader() {
        val root = temporary.newFolder("recovery")
        val info = QuickTestStore(root).use { store ->
            store.start(emptyMap())
            assertTrue(store.appendPcm(shortArrayOf(1, 2, 3), 3, 0))
            assertTrue(store.appendFrame(frame()))
            store.finish("USER_ENDED", healthy(3, 1))
        }
        // Simulate exactly the disk state of a killed process: active marker, stale header,
        // one partial PCM byte and a non-newline JSON tail. No worker is still alive.
        File(info.directory, ".active").writeText(info.id)
        RandomAccessFile(File(info.directory, "audio.wav"), "rw").use {
            it.seek(40); it.writeInt(0)
            it.seek(it.length()); it.writeByte(7)
        }
        File(info.directory, "recognition.jsonl").appendText("{\"frameIndex\":999")
        QuickTestStore(root).use { store ->
            val recovered = store.listSessions().single()
            assertEquals("INTERRUPTED", recovered.status)
            assertEquals("PROCESS_INTERRUPTED", recovered.reason)
            assertEquals(3, recovered.sampleCount)
            assertEquals(1, recovered.frameCount)
            assertWav(File(recovered.directory, "audio.wav").readBytes(), 3)
            assertFalse(File(recovered.directory, "recognition.jsonl").readText().contains("999"))
            assertTrue(File(recovered.directory, "summary.json").readText().contains("\"captureComplete\":false"))
            assertTrue(store.export(recovered.id).isFile)
        }
    }

    @Test fun engineLossAndBackgroundCannotMasqueradeAsCompletedRecognition() {
        QuickTestStore(temporary.newFolder("incomplete")).use { store ->
            store.start(emptyMap())
            store.appendPcm(shortArrayOf(1), 1, 0)
            val loss = store.finish("USER_ENDED", healthy(1).copy(droppedSamples = 1, droppedFrames = 1))
            assertEquals("INTERRUPTED", loss.status)
            assertTrue(File(loss.directory, "summary.json").readText().contains("\"engineLossDetected\":true"))
            store.start(emptyMap())
            val background = store.finish("APP_BACKGROUND", healthy(0))
            assertEquals("INTERRUPTED", background.status)
            assertEquals("APP_BACKGROUND", background.reason)
        }
    }

    @Test fun closeFinishesAsInterruptedBeforeSuccessorMayRecoverRoot() {
        val root = temporary.newFolder("lifecycle")
        val store = QuickTestStore(root)
        store.start(emptyMap())
        store.appendPcm(shortArrayOf(1, 2), 2, 0)
        store.close()
        val completed = CountDownLatch(1)
        var successor: QuickTestStore.SessionInfo? = null
        Thread {
            QuickTestStore(root).use { successor = it.listSessions().single() }
            completed.countDown()
        }.start()
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertEquals("APP_CLOSED", successor?.reason)
        assertEquals("INTERRUPTED", successor?.status)
        assertEquals(2L, successor?.sampleCount)
    }

    @Test fun changedEvidenceCannotBeSilentlyRehashedDuringExport() {
        QuickTestStore(temporary.newFolder("tamper")).use { store ->
            store.start(emptyMap())
            val info = store.finish("USER_ENDED", healthy(0))
            File(info.directory, "marks.jsonl").appendText("{}\n")
            try { store.export(info.id); fail("changed evidence must fail export") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("SHA256")) }
        }
    }

    @Test fun invalidRaw88IsVisibleAndSessionIdsCannotEscapeRoot() {
        QuickTestStore(temporary.newFolder("invalid")).use { store ->
            store.start(emptyMap())
            assertFalse(store.appendFrame(frame(FloatArray(87))))
            val ended = store.finish("USER_ENDED")
            assertEquals("ERROR", ended.status)
            assertTrue(ended.error.orEmpty().contains("INVALID_RAW88"))
            try { store.export("../outside"); fail("path traversal must be rejected") }
            catch (expected: IllegalArgumentException) { assertNotNull(expected.message) }
            val properties = Properties().apply { File(ended.directory, "session.properties").inputStream().use { load(it) } }
            assertEquals("ERROR", properties.getProperty("status"))
        }
    }

    private fun frame(raw: FloatArray = FloatArray(88)) = PianoRecognitionFrame(
        1, 0.1, 1.0, setOf(60), setOf(60), emptyList(), RecognitionState.ATTACK,
        false, 10.0, raw, FloatArray(88), uncertainMidi = setOf(72), candidateRejections = mapOf(72 to "harmonic"),
    )

    private fun healthy(samples: Long, frames: Long = 0) = RecognitionEngineSnapshot(
        status = RecognitionEngineStatus.RUNNING, submittedBlocks = if (samples == 0L) 0 else 1,
        processedBlocks = if (samples == 0L) 0 else 1, submittedSamples = samples, processedSamples = samples, frameCount = frames,
    )

    private fun assertWav(bytes: ByteArray, samples: Long) {
        assertEquals(44 + samples * 2, bytes.size.toLong())
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 8, buffer.getInt(4))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(48_000, buffer.getInt(24))
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(samples * 2, buffer.getInt(40).toLong())
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
