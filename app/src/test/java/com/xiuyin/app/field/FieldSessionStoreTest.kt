package com.xiuyin.app.field

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class FieldSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(limits: FieldLimits = FieldLimits()) = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"), limits)
    private fun start(store: FieldSessionStore) = store.start("TEST DATA: storage contract", mapOf("appVersion" to "test", "gitCommit" to "fixture"), mapOf("sampleRate" to 48000))
    @Test fun zipContainsEightRealFilesRawVectorsAndUserLabelWithoutGroundTruth() {
        val store = store(); val session = start(store)
        val vector = List(88) { it / 100.0 }
        store.append("audio-events.jsonl", mapOf("type" to "RecognitionFrame", "rawOnsetScores" to vector, "rawFrameScores" to vector, "frameIndex" to 7, "audioStreamId" to 2, "finalCandidates" to listOf(60)))
        store.append("app.log", mapOf("message" to "UserFeedback", "feedbackLabel" to FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG.name, "groundTruth" to null))
        store.end("TEST_COMPLETE")
        val zip = store.export(session.id)
        assertNull(store.sessions().single().exportedAtMillis)
        ZipFile(zip).use { archive ->
            FieldSessionStore.REQUIRED_FILES.forEach { assertNotNull(it, archive.getEntry(it)) }
            val records = archive.getInputStream(archive.getEntry("audio-events.jsonl")).reader().readLines().map(FieldJson::objectValue)
            assertEquals(vector, records.single()["rawOnsetScores"])
            val summary = FieldJson.objectValue(archive.getInputStream(archive.getEntry("session-summary.json")).reader().readText())
            assertEquals(false, summary["statisticsAreAccuracy"])
            assertNull(summary["groundTruth"])
        }
        store.markExported(session.id)
        assertNotNull(store.sessions().single().exportedAtMillis)
    }
    @Test fun unexportedSessionIsNotRemovedAtCapacityButExportedOldSessionCanBe() {
        val store = store(FieldLimits(maxSessions = 1)); val first = start(store); store.end("DONE")
        assertThrows(IllegalStateException::class.java) { start(store) }
        assertEquals(first.id, store.sessions().single().id)
        store.export(first.id); store.markExported(first.id)
        val second = start(store)
        assertNotEquals(first.id, second.id)
        assertFalse(File(temporary.root, "sessions/${first.id}").exists())
    }
    @Test fun activeCannotBeClearedOrMarkedCompletelyExported() {
        val store = store(); val first = start(store)
        assertThrows(IllegalStateException::class.java) { store.clear(setOf(first.id)) }
        store.export(first.id)
        assertThrows(IllegalStateException::class.java) { store.markExported(first.id) }
        assertEquals(first.id, store.active()?.id)
    }
    @Test fun activeSnapshotCannotBeMarkedExportedAfterSessionEnds() {
        val store = store(); val session = start(store)
        store.export(session.id)
        store.end("DONE")
        assertThrows(IllegalStateException::class.java) { store.markExported(session.id) }
        assertNull(store.sessions().single().exportedAtMillis)
        store.export(session.id); store.markExported(session.id)
        assertNotNull(store.sessions().single().exportedAtMillis)
    }
    @Test fun endedSessionStillRecordsRealExportEvents() {
        val store = store(); val session = start(store); store.end("DONE")
        store.appendToSession(session.id, "app.log", mapOf("message" to "ExportStart"))
        store.export(session.id)
        store.appendToSession(session.id, "app.log", mapOf("message" to "ExportSuccess"))
        ZipFile(store.export(session.id)).use { archive ->
            val log = archive.getInputStream(archive.getEntry("app.log")).reader().readText()
            assertTrue(log.contains("ExportStart")); assertTrue(log.contains("ExportSuccess"))
        }
    }
    @Test fun reopeningRecoversIncompleteAndPreservesPartialTailForAudit() {
        val initial = store(); val session = start(initial)
        initial.append("audio-events.jsonl", mapOf("frameIndex" to 9)); initial.checkpoint()
        File(temporary.root, "sessions/${session.id}/audio-events.jsonl").appendText("{\"interrupted\":")
        val restored = store()
        assertEquals(FieldSessionStatus.INCOMPLETE, restored.sessions().single().status)
        assertNull(restored.active())
        assertNull(restored.sessions().single().endedAtMillis)
        assertEquals("{\"interrupted\":", File(temporary.root, "sessions/${session.id}/audio-events.jsonl.recovered-tail").readText())
        ZipFile(restored.export(session.id)).use { assertNotNull(it.getEntry("audio-events.jsonl.recovered-tail")) }
    }
    @Test fun corruptManifestFailsVisiblyWithoutDeletingOriginal() {
        val store = store(); val session = start(store)
        val manifest = File(temporary.root, "sessions/${session.id}/manifest.json")
        manifest.writeText("{broken")
        assertThrows(IllegalStateException::class.java) { store() }
        assertEquals("{broken", manifest.readText())
    }
    @Test fun corruptionInCompletedJsonLineIsNotSilentlyTruncated() {
        val store = store(); val session = start(store)
        val records = File(temporary.root, "sessions/${session.id}/audio-events.jsonl")
        records.appendText("{broken}\n")
        assertThrows(IllegalStateException::class.java) { store() }
        assertEquals("{broken}\n", records.readText())
    }
    @Test fun repeatedLockedFramesCountOneWrongEventAndOneLockTransition() {
        val store = store(); val session = start(store)
        repeat(4) { store.append("score-following.jsonl", mapOf("audioStreamId" to 1, "frameIndex" to it, "decision" to "WRONG", "decisionEventId" to "one-strike", "confirmedDecision" to true, "lockState" to true)) }
        store.append("score-following.jsonl", mapOf("audioStreamId" to 1, "frameIndex" to 5, "decision" to "CORRECT", "decisionEventId" to "correction", "confirmedDecision" to true, "lockState" to false))
        store.end("DONE"); store.export(session.id)
        val summary = FieldJson.objectValue(File(temporary.root, "sessions/${session.id}/session-summary.json").readText())
        val counts = summary["counts"] as Map<*, *>
        assertEquals(1L, counts["WRONG"]); assertEquals(1L, counts["LOCK"]); assertEquals(1L, counts["CORRECT"])
    }
    @Test fun lowStorageRefusesNewRecordAndFaultIsDurable() {
        val store = store(FieldLimits(maxBytes = 160_000)); val session = start(store)
        assertThrows(IllegalStateException::class.java) { store.append("app.log", mapOf("message" to "x".repeat(170_000))) }
        store.markFault("QueueOverflow test: no silent loss")
        val manifest = FieldJson.objectValue(File(temporary.root, "sessions/${session.id}/manifest.json").readText())
        assertEquals(true, manifest["hasErrors"])
        assertTrue((manifest["pauseReason"] as String).contains("QueueOverflow"))
        assertTrue(store.totalBytes() <= 160_000)
    }
    @Test fun clipCountCapDoesNotDeleteUnexportedAudioAndWavKeepsActualSamples() {
        val store = store(FieldLimits(maxClips = 1)); val session = start(store)
        val pcm = shortArrayOf(123, -456, 789)
        val clip = store.saveClip(FieldClip(48000, pcm), mapOf("consentGranted" to true))
        assertThrows(IllegalStateException::class.java) { store.saveClip(FieldClip(48003, pcm), emptyMap()) }
        val bytes = File(temporary.root, "sessions/${session.id}/audio-clips/$clip.wav").readBytes()
        assertEquals(50, bytes.size)
        assertEquals(123.toShort(), java.nio.ByteBuffer.wrap(bytes, 44, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short)
        assertEquals(1, store.totalClips())
    }
    @Test fun unavailableSpaceMeasurementReturnsZeroAndRejectsSessionCreation() {
        val failing = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            availableBytes = { throw java.io.IOException("TEST DATA unavailable filesystem measurement") })
        assertEquals(0L, failing.freeBytes())
        assertThrows(IllegalStateException::class.java) { start(failing) }
        assertTrue(failing.sessions().isEmpty())
    }
    @Test fun spaceMeasurementIsReadAgainInsteadOfCachingEarlierFreeSpace() {
        var available = 10_000_000L
        val measured = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"), availableBytes = { available })
        assertEquals(available, measured.freeBytes())
        available = -1L
        assertEquals(0L, measured.freeBytes())
        assertThrows(IllegalStateException::class.java) { start(measured) }
    }
    @Test fun jsonCodecRejectsNonFiniteNumbersAndPreservesEscapedUserText() {
        val value = mapOf("message" to "line\n\"WRONG\"\\\t", "nullValue" to null, "vector" to listOf(.2, .5))
        assertEquals(value, FieldJson.objectValue(FieldJson.encode(value)))
        assertThrows(IllegalArgumentException::class.java) { FieldJson.encode(mapOf("bad" to Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { FieldJson.decode("{\"x\":1,\"x\":2}") }
    }
    @Test fun contextualOldLogsDoNotCountAsCurrentModelRestartsOrFeedback() {
        val disk = store(); val session = start(disk)
        disk.append("app.log", mapOf("message" to "ModelLoading", "beforeSessionStart" to true))
        disk.append("app.log", mapOf("message" to "ModelLoading"))
        disk.append("app.log", mapOf("message" to "UserFeedback", "feedbackLabel" to "USER_SAYS_SYSTEM_WRONG", "beforeSessionStart" to true))
        disk.end("TEST DATA completed")
        ZipFile(disk.export(session.id)).use { zip ->
            val summary = FieldJson.objectValue(zip.getInputStream(zip.getEntry("session-summary.json")).reader().readText())
            val counts = summary["counts"] as Map<*, *>
            assertEquals(1L, (counts["ModelLoading"] as Number).toLong())
            assertEquals(0L, (counts["ModelRestarts"] as Number).toLong())
            assertNull(counts["USER_SAYS_SYSTEM_WRONG"])
        }
    }
    @Test fun sessionStartingWithReadyModelCountsLaterLoadAsRestart() {
        val disk = store()
        val session = disk.start("TEST DATA ready session", emptyMap(), emptyMap(), mapOf("initialModelState" to "READY"))
        disk.append("app.log", mapOf("message" to "ModelLoading"))
        disk.end("TEST DATA completed")
        ZipFile(disk.export(session.id)).use { zip ->
            val summary = FieldJson.objectValue(zip.getInputStream(zip.getEntry("session-summary.json")).reader().readText())
            val counts = summary["counts"] as Map<*, *>
            assertEquals(1L, (counts["ModelRestarts"] as Number).toLong())
        }
    }
    @Test fun burstBatchesRetainAllEvidenceWithoutRepeatedSpaceQueriesOrDirectoryScans() {
        var queries = 0
        val disk = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            availableBytes = { queries++; 100_000_000L }, monotonicNanos = { 0L })
        val session = start(disk)
        val baselineQueries = queries
        val baselineScans = disk.storageDiagnostics()["quotaDirectoryScanCount"]
        val vector = List(88) { it / 100.0 }
        repeat(200) { index ->
            disk.appendBatch(listOf(
                "audio-events.jsonl" to mapOf("frameIndex" to index, "rawOnsetScores" to vector, "rawFrameScores" to vector),
                "octave-diagnostics.jsonl" to mapOf("frameIndex" to index, "lowerNote" to 48, "upperCandidate" to 60)))
        }
        assertEquals(baselineQueries, queries)
        assertEquals(baselineScans, disk.storageDiagnostics()["quotaDirectoryScanCount"])
        val raw = File(temporary.root, "sessions/${session.id}/audio-events.jsonl").readLines().map(FieldJson::objectValue)
        assertEquals(200, raw.size)
        assertTrue(raw.all { it["rawOnsetScores"] == vector && it["rawFrameScores"] == vector })
        assertEquals(200, File(temporary.root, "sessions/${session.id}/octave-diagnostics.jsonl").readLines().size)
    }
    @Test fun measuredBudgetSubtractsActualAppendBytesAndFailsClosedBeforeNextProbe() {
        var queries = 0
        val disk = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            availableBytes = { queries++; 1_450_000L }, monotonicNanos = { 0L })
        val session = start(disk)
        val budget = disk.spaceBudgetBytes()
        val bytes = disk.totalBytes()
        disk.append("audio-events.jsonl", mapOf("message" to "first retained record"))
        assertEquals(disk.totalBytes() - bytes, budget - disk.spaceBudgetBytes())
        var accepted = 0
        var rejected = false
        for (index in 0 until 20) {
            val before = disk.totalBytes()
            try {
                disk.append("audio-events.jsonl", mapOf("index" to index, "payload" to "a".repeat(20_000)))
                accepted++
            } catch (_: IllegalStateException) {
                rejected = true
                assertEquals(before, disk.totalBytes())
                break
            }
        }
        assertTrue(rejected)
        assertTrue(accepted > 0)
        assertEquals(1, queries)
        assertEquals(accepted + 1, File(temporary.root, "sessions/${session.id}/audio-events.jsonl").readLines().size)
    }
    @Test fun expiredSpaceBudgetRechecksAndDoesNotWriteWhenMeasurementFails() {
        var clock = 0L
        var readable = true
        var queries = 0
        val disk = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            availableBytes = { queries++; if (readable) 10_000_000L else throw java.io.IOException("TEST DATA measurement failure") },
            monotonicNanos = { clock })
        val session = start(disk)
        disk.append("audio-events.jsonl", mapOf("frameIndex" to 1))
        val before = disk.totalBytes()
        readable = false
        clock = 1_000_000_000L
        assertThrows(IllegalStateException::class.java) { disk.append("audio-events.jsonl", mapOf("frameIndex" to 2)) }
        assertEquals(2, queries)
        assertEquals(0L, disk.spaceBudgetBytes())
        assertEquals(before, disk.totalBytes())
        assertEquals(1, File(temporary.root, "sessions/${session.id}/audio-events.jsonl").readLines().size)
    }
    @Test fun trackedUsageMatchesDiskAfterAtomicReplacementClipsExportReplacementAndClear() {
        val disk = store()
        val session = start(disk)
        fun assertUsage() { assertEquals(temporary.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, disk.totalBytes()) }
        assertUsage()
        disk.append("audio-events.jsonl", mapOf("frameIndex" to 1)); assertUsage()
        disk.updateDevice(mapOf("description" to "TEST DATA".repeat(200))); assertUsage()
        disk.updateDevice(mapOf("description" to "short")); assertUsage()
        disk.saveClip(FieldClip(0, shortArrayOf(1, 2, 3)), emptyMap()); assertUsage()
        assertEquals(1, disk.totalClips())
        disk.end("DONE"); assertUsage()
        disk.export(session.id); assertUsage()
        disk.export(session.id); assertUsage()
        disk.markExported(session.id); assertUsage()
        disk.clear(setOf(session.id)); assertUsage()
        assertEquals(0, disk.totalClips())
    }
    @Test fun quotaPausePreservesUnexportedSessionAndReservedSpaceAllowsBothZipGenerations() {
        val limit = 600_000L
        val disk = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            FieldLimits(maxBytes = limit), availableBytes = { 20_000_000L }, monotonicNanos = { 0L })
        val session = start(disk)
        val random = java.util.Random(219L)
        var accepted = 0
        var rejected = false
        for (index in 0 until 200) {
            val payload = CharArray(8000) { (33 + random.nextInt(90)).toChar() }.concatToString()
            try { disk.append("audio-events.jsonl", mapOf("frameIndex" to index, "payload" to payload)); accepted++ }
            catch (_: IllegalStateException) { rejected = true; break }
        }
        assertTrue(rejected); assertTrue(accepted > 0)
        assertTrue((disk.storageDiagnostics()["exportReserveBytes"] as Long) > 0)
        disk.markFault("TEST DATA quota pause")
        disk.end("CAPACITY_PAUSE")
        disk.withExportReserve {
            disk.appendToSession(session.id, "app.log", mapOf("message" to "ExportStart"))
            disk.export(session.id)
            disk.appendToSession(session.id, "app.log", mapOf("message" to "ExportSuccess"))
            ZipFile(disk.export(session.id)).use { zip ->
                assertEquals(accepted, zip.getInputStream(zip.getEntry("audio-events.jsonl")).reader().readLines().size)
                assertTrue(zip.getInputStream(zip.getEntry("app.log")).reader().readText().contains("ExportSuccess"))
            }
        }
        disk.markExported(session.id)
        assertTrue(disk.totalBytes() <= limit)
        assertEquals(session.id, disk.sessions().single().id)
        assertEquals(temporary.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, disk.totalBytes())
    }
    @Test fun reopenedHistoricalSessionsAreFullyIncludedInQuotaAndExportReserve() {
        val initial = store()
        val first = start(initial)
        initial.append("audio-events.jsonl", mapOf("payload" to "x".repeat(40_000)))
        initial.end("DONE")
        val second = start(initial)
        initial.append("audio-events.jsonl", mapOf("payload" to "y".repeat(20_000)))
        initial.checkpoint()
        val reopened = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"),
            availableBytes = { 20_000_000L }, monotonicNanos = { 0L })
        assertEquals(2, reopened.sessions().size)
        assertEquals(FieldSessionStatus.INCOMPLETE, reopened.sessions().single { it.id == second.id }.status)
        assertEquals(temporary.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, reopened.totalBytes())
        val largestFolder = File(temporary.root, "sessions/${first.id}")
        val bytes = largestFolder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val entries = largestFolder.walkTopDown().count { it.isFile }
        val expectedReserve = 2 * (bytes + (bytes + 49) / 50 + entries * 512L + 4096)
        assertEquals(expectedReserve, reopened.storageDiagnostics()["exportReserveBytes"])
        val third = start(reopened)
        assertEquals(3, reopened.sessions().size)
        assertNotEquals(first.id, third.id)
        assertTrue((reopened.storageDiagnostics()["exportReserveBytes"] as Long) >= expectedReserve)
    }
    @Test fun batchQuotaRejectionDoesNotWriteItsEarlierSmallRecord() {
        val disk = store(FieldLimits(maxBytes = 200_000))
        val session = start(disk)
        assertThrows(IllegalStateException::class.java) {
            disk.appendBatch(listOf("audio-events.jsonl" to mapOf("frameIndex" to 1),
                "octave-diagnostics.jsonl" to mapOf("payload" to "x".repeat(300_000))))
        }
        assertEquals("", File(temporary.root, "sessions/${session.id}/audio-events.jsonl").readText())
        assertEquals("", File(temporary.root, "sessions/${session.id}/octave-diagnostics.jsonl").readText())
    }
}