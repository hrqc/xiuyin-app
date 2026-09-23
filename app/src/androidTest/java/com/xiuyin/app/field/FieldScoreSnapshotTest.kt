package com.xiuyin.app.field

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.practice.CompanionMode
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.ScoreFollower
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreJsonCodec
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScorePitch
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime
import com.xiuyin.app.score.ScoreTimelineCompiler
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA for exact storage/reproduction contracts, never an OMR or pitch-accuracy result. */
@RunWith(AndroidJUnit4::class)
class FieldScoreSnapshotTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceHash = "a".repeat(64)
    private fun note(id: String, offset: BeatFraction, duration: BeatFraction, pitch: ScorePitch?, staff: Int = 1, tuplet: Boolean = false) =
        ScoreEvent(id, offset, duration, pitch, staff, "1", false, if (staff == 2) "half" else "quarter", 0,
            emptySet(), 0, 0, tupletActual = if (tuplet) 3 else null, tupletNormal = if (tuplet) 2 else null)
    private fun score(): DigitalScore {
        val clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4))
        val first = ScoreMeasure("m18", "18", ScoreTime(2, 4), 0, clefs, listOf(
            note("e1", BeatFraction.ZERO, BeatFraction.of(2, 3), ScorePitch("E", 0, 4), tuplet = true),
            note("e2", BeatFraction.of(2, 3), BeatFraction.of(2, 3), ScorePitch("F", 0, 4), tuplet = true),
            note("r1", BeatFraction.of(4, 3), BeatFraction.of(2, 3), null, tuplet = true),
            note("l1", BeatFraction.ZERO, BeatFraction.of(2), ScorePitch("C", 0, 3), staff = 2),
        ), false, declaredStaves = 2)
        val second = ScoreMeasure("m19", "19", ScoreTime(2, 4), 0, clefs, listOf(
            note("e3", BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("D", 0, 4)),
            note("r2", BeatFraction.of(1), BeatFraction.of(1), null),
            note("l2", BeatFraction.ZERO, BeatFraction.of(2), ScorePitch("D", 0, 3), staff = 2),
        ), false, declaredStaves = 2)
        return DigitalScore(sourceHash, "TEST DATA snapshot contract", listOf(ScorePart("P1", "Piano", listOf(first, second))),
            listOf(ScoreIssue("test-source", "TEST DATA; musical accuracy is not under test")), ScoreSource("OMR_IMAGE", sourceHash, 123))
    }
    private fun request(score: DigitalScore = score(), config: PracticeConfig = PracticeConfig(CompanionMode.STOP_ON_ERROR,
        0, null, 3, true, PracticeHand.LEFT)) = FieldScoreSnapshot.Request("score-local-id", 7, score, config, CompanionPhase.PAUSED, 1)
    private fun metadata(request: FieldScoreSnapshot.Request) = mapOf("scoreId" to request.scoreId,
        "revisionGeneration" to request.revisionGeneration.toString(), "sourceSha256" to request.score.source.sha256,
        "scoreTitle" to "TEST DATA snapshot contract")
    private fun store(root: File, limits: FieldLimits = FieldLimits()) = FieldSessionStore(File(root, "sessions"), File(root, "exports"),
        limits, availableBytes = { android.os.StatFs(it.absolutePath).availableBytes })
    private fun <T> withOwnedDirectory(block: (File) -> T): T {
        val root = File(context.cacheDir, "field-score-snapshot-${UUID.randomUUID()}")
        check(root.mkdir())
        return try { block(root) } finally {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readObject(file: File) = FieldJson.objectValue(file.readText())

    @Test fun exactScoreAndActualLeftHandConfigSurviveZipReopenWithBothHashes() = withOwnedDirectory { root ->
        val original = score().let { s -> s.copy(parts = s.parts.map { p -> p.copy(measures = p.measures.map { it.copy(time = null) }) }) }
        val input = request().copy(originalScore = original)
        val snapshot = FieldScoreSnapshot.capture(input)
        // Callers cannot mutate the captured byte arrays after a request has been serialized.
        snapshot.files().first().second.fill(0)
        val first = store(root)
        val session = first.start("TEST DATA complete score snapshot", mapOf("appVersion" to "test"), emptyMap(), metadata(input), snapshot)
        val folder = File(root, "sessions/${session.id}")
        assertEquals("AVAILABLE", (readObject(File(folder, "manifest.json"))["scoreSnapshot"] as Map<*, *>)["status"])
        assertEquals(input.score, ScoreJsonCodec.decode(File(folder, "score.json").readBytes()))
        first.append("app.log", mapOf("message" to "SnapshotEvidence", "accuracyValidated" to false))
        first.end("TEST_COMPLETE")
        val reopened = store(root)
        repeat(2) {
            ZipFile(reopened.export(session.id)).use { zip ->
                assertEquals(FieldSessionStore.REQUIRED_FILES.toSet() + setOf("score.json", "practice-context.json"),
                    zip.entries().asSequence().map { it.name }.toSet())
                val scoreBytes = zip.getInputStream(zip.getEntry("score.json")).use { it.readBytes() }
                val contextBytes = zip.getInputStream(zip.getEntry("practice-context.json")).use { it.readBytes() }
                val manifest = FieldJson.objectValue(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
                val descriptor = manifest["scoreSnapshot"] as Map<*, *>
                val captured = FieldJson.objectValue(contextBytes.toString(Charsets.UTF_8))
                val restored = ScoreJsonCodec.decode(scoreBytes)
                assertEquals(input.score, restored)
                assertEquals(sha(scoreBytes), descriptor["scoreSha256"])
                assertEquals(sha(contextBytes), descriptor["practiceContextSha256"])
                assertEquals(sha(ScoreJsonCodec.encode(original)), captured["originalScoreSha256"])
                assertEquals(sourceHash, captured["sourceSha256"])
                assertEquals(7L, captured["revisionGeneration"])
                assertEquals("PAUSED", captured["practicePhaseAtCapture"])
                assertEquals(1L, captured["cursorAtCapture"])
                assertEquals(2L, captured["measureCount"])
                assertEquals(2L, captured["targetCount"])
                val config = captured["practiceConfig"] as Map<*, *>
                assertEquals("LEFT", config["hand"])
                assertEquals("STOP_ON_ERROR", config["mode"])
                assertEquals(true, config["reviewAcknowledged"])
                assertEquals(0L, config["startMeasure"])
                assertNull(config["endMeasure"])
                assertEquals(1L, config["resolvedEndMeasure"])
                assertNull(config["tempoBpm"])
                assertEquals(false, captured["accuracyValidated"])
                assertNull(captured["groundTruth"])
                assertEquals(2, restored.parts.single().measures.flatMap { m -> m.events }.count { e -> e.isRest })
                val follower = ScoreFollower(ScoreTimelineCompiler.compile(restored), input.config)
                assertEquals(listOf(setOf(48), setOf(50)), follower.targets.map { t -> t.notes.map { n -> n.midi }.toSet() })
                FieldScoreSnapshot.verifyArchive(zip, manifest)
            }
        }
        assertTrue(reopened.totalBytes() <= FieldLimits().maxBytes)
    }

    @Test fun legacySessionKeepsAllLogBytesAndExportsWithoutInventingSnapshot() = withOwnedDirectory { root ->
        val first = store(root)
        val session = first.start("TEST DATA old session", emptyMap(), emptyMap())
        first.append("app.log", mapOf("message" to "LegacyEvidence"))
        first.end("TEST_COMPLETE")
        val folder = File(root, "sessions/${session.id}")
        val manifestFile = File(folder, "manifest.json")
        manifestFile.writeText(FieldJson.encode(readObject(manifestFile) - "scoreSnapshot"))
        val originalLog = File(folder, "app.log").readBytes()
        val reopened = store(root)
        ZipFile(reopened.export(session.id)).use { zip ->
            assertNull(zip.getEntry("score.json")); assertNull(zip.getEntry("practice-context.json"))
            assertArrayEquals(originalLog, zip.getInputStream(zip.getEntry("app.log")).use { it.readBytes() })
            val manifest = FieldJson.objectValue(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
            assertEquals("UNAVAILABLE", (manifest["scoreSnapshot"] as Map<*, *>)["status"])
        }
    }

    @Test fun wrongScoreRevisionOrSourceBindingCannotCreateSession() = withOwnedDirectory { root ->
        val input = request()
        val snapshot = FieldScoreSnapshot.capture(input)
        val disk = store(root)
        for ((key, value) in listOf("scoreId" to "other-id", "revisionGeneration" to "8", "sourceSha256" to "b".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                disk.start("TEST DATA wrong binding", emptyMap(), emptyMap(), metadata(input) + (key to value), snapshot)
            }
        }
        assertNull(disk.active()); assertTrue(disk.sessions().isEmpty())
    }

    @Test fun snapshotAndTwoExportReservesAreAccountedBeforeAnyNewSessionIsCreated() = withOwnedDirectory { root ->
        val input = request(score().copy(issues = List(80) { ScoreIssue("large-$it", "TEST DATA " + "x".repeat(1000)) }))
        val snapshot = FieldScoreSnapshot.capture(input)
        val limits = FieldLimits(maxBytes = snapshot.byteCount + 100_000)
        val disk = store(root, limits)
        val preserved = disk.start("TEST DATA preserve unexported history", emptyMap(), emptyMap())
        disk.end("TEST_COMPLETE")
        assertThrows(IllegalStateException::class.java) {
            disk.start("TEST DATA insufficient snapshot reserve", emptyMap(), emptyMap(), metadata(input), snapshot)
        }
        assertEquals(listOf(preserved.id), disk.sessions().map { it.id })
        assertNull(disk.active())
        assertTrue(disk.totalBytes() <= limits.maxBytes)
        assertEquals(1, File(root, "sessions").listFiles().orEmpty().count { it.isDirectory })
    }

    @Test fun corruptedSnapshotRefusesExportAndPreservesOriginalEvidence() = withOwnedDirectory { root ->
        val input = request()
        val disk = store(root)
        val session = disk.start("TEST DATA corruption check", emptyMap(), emptyMap(), metadata(input), FieldScoreSnapshot.capture(input))
        disk.end("TEST_COMPLETE")
        val folder = File(root, "sessions/${session.id}")
        val file = File(folder, "score.json")
        val corrupted = file.readBytes().also { it[it.lastIndex] = ' '.code.toByte() }
        file.writeBytes(corrupted)
        val log = File(folder, "app.log").readBytes()
        assertThrows(IllegalStateException::class.java) { disk.export(session.id) }
        assertArrayEquals(corrupted, file.readBytes())
        assertArrayEquals(log, File(folder, "app.log").readBytes())
        assertFalse(File(root, "exports/field-${session.id}.zip").exists())
    }

    @Test fun snapshotRejectsUnreproducibleHandCursorOriginalAndOversizedScore() {
        val input = request()
        assertThrows(IllegalArgumentException::class.java) { FieldScoreSnapshot.capture(input.copy(cursor = 3)) }
        val oneStaff = input.score.copy(parts = input.score.parts.map { p -> p.copy(measures = p.measures.map { m ->
            m.copy(declaredStaves = 1, clefs = m.clefs.filter { it.staff == 1 }, events = m.events.filter { it.staff == 1 })
        }) })
        assertThrows(IllegalArgumentException::class.java) { FieldScoreSnapshot.capture(input.copy(score = oneStaff)) }
        val otherOriginal = input.score.copy(id = "b".repeat(64), source = input.score.source.copy(sha256 = "b".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { FieldScoreSnapshot.capture(input.copy(originalScore = otherOriginal)) }
        val oversized = input.score.copy(issues = List(300) { ScoreIssue("huge-$it", "x".repeat(16_384)) })
        val error = assertThrows(IllegalArgumentException::class.java) { FieldScoreSnapshot.capture(input.copy(score = oversized)) }
        assertTrue(error.message.orEmpty().contains("4 MiB"))
        val snapshot = FieldScoreSnapshot.capture(input)
        val json = FieldJson.objectValue(snapshot.files().single { it.first == "practice-context.json" }.second.toString(Charsets.UTF_8))
        assertNull(json["originalScoreSha256"]); assertEquals("UNAVAILABLE", json["originalScoreStatus"])
    }

    @Test fun failedIoPreparationDoesNotInvokeAudioStartCallback() = runBlocking {
        val models = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            FieldTestViewModel(context.applicationContext as Application).also { models.put("field-snapshot-test", it) }
        }
        var audioCallbackCalled = false
        suspend fun awaitIdle() {
            val deadline = System.currentTimeMillis() + 30_000
            while (viewModel.operation.value.busy && System.currentTimeMillis() < deadline) delay(25)
            check(!viewModel.operation.value.busy) { "实地操作没有返回" }
        }
        try {
            awaitIdle()
            val input = request().copy(revisionGeneration = -1)
            withContext(Dispatchers.Main) {
                viewModel.start("TEST DATA rejected snapshot", metadata(input), input) { audioCallbackCalled = true; true }
            }
            awaitIdle()
            assertFalse(audioCallbackCalled)
            assertTrue(viewModel.operation.value.error.orEmpty().contains("标识无效"))
        } finally { withContext(Dispatchers.Main) { models.clear() } }
    }
}
