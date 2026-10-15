package com.xiuyin.app.field

import android.os.StatFs
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** TEST DATA log replay only: no microphone, inference, musical accuracy or live-session acceptance. */
@RunWith(AndroidJUnit4::class)
class FieldLargeLogRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val streams = listOf("app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl")
    private val fixtureSha = "e24371655ba03e15ca105f3e66c9e94d6cd3874d29cbc3aa2d430fd796798c23"

    @Test fun completeAndExportTwicePreservesEveryReplayedRecordWithinBudget() {
        val runId = UUID.randomUUID().toString()
        val owned = File(context.cacheDir, "field-large-log-replay-$runId")
        check(owned.mkdir())
        val evidence = File(context.getExternalFilesDir(null) ?: context.filesDir, "field-large-log-replay/$runId")
        check(evidence.mkdirs())
        val report = linkedMapOf<String, Any?>("schemaVersion" to 1, "runId" to runId,
            "status" to "STARTED", "testKind" to "TEST DATA storage replay, not recording or pitch accuracy",
            "statisticsAreAccuracy" to false, "version" to BuildConfig.VERSION_NAME,
            "gitCommit" to BuildConfig.GIT_COMMIT, "buildNumber" to BuildConfig.VERSION_CODE,
            "buildTime" to BuildConfig.BUILD_TIME, "ownedCacheDirectory" to owned.absolutePath,
            "stageBudgetMs" to 30_000, "operationWindowBudgetMs" to 90_000)
        val durations = linkedMapOf<String, Double>()
        report["stageDurationMs"] = durations
        var operationStarted: Long? = null
        try {
            val argument = InstrumentationRegistry.getArguments().getString("fieldLargeLogFixture")
            val source = argument?.let {
                val fixed = File(context.cacheDir, "field-log-replay-fixtures/failure-system-saved.zip").canonicalFile
                require(File(it).isAbsolute && File(it).canonicalFile == fixed) { "Fixture must be the explicit fixed app-private path" }
                require(fixed.isFile && fixed.length() == 11_588_709L)
                require(digest(fixed) == fixtureSha) { "Fixture SHA mismatch; no fallback to small fixture" }
                fixed
            }
            report["fixtureKind"] = if (source == null) "TEST DATA small built-in fixture" else "REAL FAILED LOG REPLAY"
            report["sourceZipSha256"] = source?.let { fixtureSha }
            report["sourceZipBytes"] = source?.length()
            val store = FieldSessionStore(File(owned, "sessions"), File(owned, "exports"),
                availableBytes = { StatFs(it.absolutePath).availableBytes })
            val session = store.start("TEST DATA isolated log recovery replay",
                mapOf("appVersion" to BuildConfig.VERSION_NAME, "gitCommit" to BuildConfig.GIT_COMMIT),
                mapOf("testData" to true, "sourceZipSha256" to source?.let { fixtureSha }),
                mapOf("initialModelState" to "READY", "testData" to "true"))
            report["sessionId"] = session.id
            val folder = File(owned, "sessions/${session.id}")
            store.markFault("TEST DATA replay preserves a prior diagnostic failure; not a new recording")
            val expected = File(owned, "expected").also { check(it.mkdir()) }
            streams.forEach { File(folder, it).copyTo(File(expected, it)) }
            val lineCounts = linkedMapOf<String, Int>()
            var fullFitFrames = 0
            val fixtureStarted = SystemClock.elapsedRealtimeNanos()
            fun replay(name: String, records: Sequence<Map<String, Any?>>) {
                var count = 0
                val pending = mutableListOf<Pair<String, Map<String, Any?>>>()
                File(expected, name).bufferedWriter(Charsets.UTF_8, 8192).use { writer ->
                    // Preserve the genuine start/fault prefix of this newly created test session.
                    File(folder, name).reader(Charsets.UTF_8).use { it.copyTo(writer) }
                    records.forEach { record ->
                        val fits = (record["singlePitchReference"] as? Map<*, *>)?.get("fits") as? List<*>
                        if (fits != null) { assertEquals("Full fit list must never be shortened", 88, fits.size); fullFitFrames++ }
                        val rebound = record + ("sessionId" to session.id)
                        // All values, including all fit rows, survive; only the top-level identity changes.
                        writer.write(FieldJson.encode(rebound)); writer.newLine()
                        pending += name to record
                        count++
                        if (pending.size == 16) { store.appendBatch(pending); pending.clear() }
                    }
                    if (pending.isNotEmpty()) store.appendBatch(pending)
                }
                lineCounts[name] = count
                assertEquals("Replay differs: $name", digest(File(expected, name)), digest(File(folder, name)))
            }
            var expectedCounts: Map<String, Any?>
            if (source != null) {
                ZipFile(source).use { zip ->
                    val manifest = FieldJson.objectValue(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
                    assertEquals("123e88c4-33e7-499a-84dd-a1d0469049ec", manifest["sessionId"])
                    assertEquals(true, manifest["hasErrors"])
                    report["sourceSessionId"] = manifest["sessionId"]
                    report["sourceFault"] = manifest["pauseReason"]
                    streams.forEach { name ->
                        zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { reader ->
                            replay(name, reader.lineSequence().filter { it.isNotBlank() }.map { FieldJson.objectValue(it) })
                        }
                    }
                    store.updatePerformance(FieldJson.objectValue(zip.getInputStream(zip.getEntry("performance.json")).reader().use { it.readText() }))
                    val summary = FieldJson.objectValue(zip.getInputStream(zip.getEntry("session-summary.json")).reader().use { it.readText() })
                    expectedCounts = FieldJson.objectValue(FieldJson.encode(summary.getValue("counts")))
                }
                assertEquals(mapOf("app.log" to 52, "audio-events.jsonl" to 4552, "octave-diagnostics.jsonl" to 0,
                    "score-following.jsonl" to 2886), lineCounts)
                assertEquals(2867, fullFitFrames)
            } else {
                replay("app.log", sequenceOf(mapOf("message" to "ModelLoading", "testData" to true)))
                replay("audio-events.jsonl", (0..2).asSequence().map { frame -> mapOf("type" to "RecognitionFrame",
                    "frameIndex" to frame, "finalCandidates" to emptyList<Int>(), "testData" to true,
                    "singlePitchReference" to mapOf("fits" to (21..108).map { midi ->
                        listOf(midi, midi, 440.0, 440.0, 1.0e-5, 0.0, 0.25, 0.75, 0.0, 1)
                    })) })
                replay("octave-diagnostics.jsonl", emptySequence())
                replay("score-following.jsonl", sequenceOf(mapOf("decision" to "UNCERTAIN", "reason" to "TEST DATA")))
                expectedCounts = mapOf("recognizedFrames" to 3L, "UNCERTAIN_EPISODE" to 1L, "ModelLoading" to 1L, "ModelRestarts" to 1L)
                assertEquals(3, fullFitFrames)
            }
            report["fixtureReplayMs"] = (SystemClock.elapsedRealtimeNanos() - fixtureStarted) / 1e6
            report["sourceRecordCounts"] = lineCounts
            report["complete88FitFrames"] = fullFitFrames
            report["identityTransform"] = "Public append API rebinds top-level sessionId only; all JSON values retained and canonically encoded"
            report["scope"] = "Four complete source streams and performance; source score attachments are not replayed; new snapshot explicitly UNAVAILABLE"

            fun <T> timed(name: String, block: () -> T): T {
                val began = SystemClock.elapsedRealtimeNanos()
                try { return block() } finally { durations[name] = (SystemClock.elapsedRealtimeNanos() - began) / 1e6 }
            }
            operationStarted = SystemClock.elapsedRealtimeNanos()
            val ended = timed("end") { store.end("TEST_DATA_LOG_REPLAY_ENDED") }
            assertEquals(FieldSessionStatus.COMPLETED, ended?.status)
            assertNull(store.active())
            assertTrue("end exceeded 30 seconds: $durations", durations.getValue("end") <= 30_000)
            // The only new stream record from end() must be the actual end marker, after the exact expected prefix.
            val beforeEndBytes = File(expected, "app.log").length()
            File(folder, "app.log").inputStream().use { actual ->
                File(expected, "app.log").inputStream().use { prior ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = prior.read(buffer); if (n < 0) break
                        val actualBytes = ByteArray(n)
                        java.io.DataInputStream(actual).readFully(actualBytes)
                        assertArrayEquals(buffer.copyOf(n), actualBytes)
                    }
                }
                val tail = actual.reader(Charsets.UTF_8).readText()
                val marker = FieldJson.objectValue(tail.trimEnd())
                assertEquals("FieldSessionEnd", marker["message"])
                assertEquals("TEST_DATA_LOG_REPLAY_ENDED", marker["reason"])
                assertEquals(session.id, marker["sessionId"])
                assertTrue(marker["timestamp"] is Number)
                assertEquals(beforeEndBytes + tail.toByteArray(Charsets.UTF_8).size, File(folder, "app.log").length())
                File(expected, "app.log").appendText(tail, Charsets.UTF_8)
            }
            fun verifyArchive(file: File): Map<String, Any?> = ZipFile(file).use { zip ->
                FieldSessionStore.REQUIRED_FILES.forEach { assertNotNull("Missing $it", zip.getEntry(it)) }
                val hashes = streams.associateWith { name ->
                    val hash = zip.getInputStream(zip.getEntry(name)).use { digest(it) }
                    assertEquals("Archive lost or changed records: $name", digest(File(expected, name)), hash)
                    assertEquals(File(expected, name).length(), zip.getEntry(name).size)
                    hash
                }
                val manifest = FieldJson.objectValue(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
                assertEquals(session.id, manifest["sessionId"])
                assertEquals("COMPLETED", manifest["status"])
                assertEquals(true, manifest["hasErrors"])
                assertEquals(false, manifest["statisticsAreAccuracy"])
                assertEquals("UNAVAILABLE", (manifest["scoreSnapshot"] as Map<*, *>)["status"])
                val summary = FieldJson.objectValue(zip.getInputStream(zip.getEntry("session-summary.json")).reader().use { it.readText() })
                assertEquals(expectedCounts, summary["counts"])
                assertEquals(true, summary["hasErrors"])
                assertEquals(digest(File(folder, "performance.json")), zip.getInputStream(zip.getEntry("performance.json")).use { digest(it) })
                mapOf("bytes" to file.length(), "sha256" to digest(file), "streamSha256" to hashes,
                    "counts" to summary["counts"], "hasErrors" to true)
            }
            val first = timed("export1") { store.export(session.id) }
            report["export1"] = verifyArchive(first)
            assertTrue("export1 exceeded 30 seconds: $durations", durations.getValue("export1") <= 30_000)
            val second = timed("export2") { store.export(session.id) }
            report["operationWindowMs"] = (SystemClock.elapsedRealtimeNanos() - operationStarted) / 1e6
            report["export2"] = verifyArchive(second)
            assertTrue("export2 exceeded 30 seconds: $durations", durations.getValue("export2") <= 30_000)
            assertTrue("operation window exceeded 90 seconds", (report["operationWindowMs"] as Double) <= 90_000)
            if (source != null) assertEquals("Source fixture must remain unchanged", fixtureSha, digest(source))
            check(owned.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(owned.deleteRecursively())
            report["status"] = "PASSED_STORAGE_REPLAY_ONLY"
        } catch (error: Throwable) {
            report["status"] = "FAILED"
            report["error"] = error.stackTraceToString()
            throw error
        } finally {
            if (!report.containsKey("operationWindowMs")) operationStarted?.let {
                report["operationWindowMs"] = (SystemClock.elapsedRealtimeNanos() - it) / 1e6
            }
            File(evidence, "result.json").writeText(FieldJson.encode(report), Charsets.UTF_8)
            // Failed replay artifacts stay in the owned cache; live store and source fixture are never touched.
        }
    }

    private fun digest(file: File): String = file.inputStream().use { digest(it) }
    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
