package com.xiuyin.app.field

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppViewModel
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.pitch.*
import com.xiuyin.app.practice.*
import com.xiuyin.app.score.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * TEST DATA: actual fixed PCM and production model; no artificial frame.
 * E2 is frozen reference group 1. The crop is an engineering fixture, not independent musical acceptance.
 * Private App microphone/Activity.onStop are not exercised; the actual App.stopAudio hook is.
 */
class FieldCompletionBoundaryTest {
    @Test fun realE2CompletionStopsInputAndPreservesUnevaluatedTailWithoutRepeatingJudgment(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext as Application
        val runId = UUID.randomUUID().toString()
        val outputRoot = File(requireNotNull(context.getExternalFilesDir(null)), "field-completion-boundary/$runId")
        check(outputRoot.mkdirs())
        val resultFile = File(outputRoot, "result.json")
        val result = JSONObject().put("schemaVersion", 1).put("runId", runId).put("startedAtMillis", System.currentTimeMillis())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("chainPassed", false).put("accuracyValidated", false)
            .put("scope", "TEST DATA frozen reference E2 terminal lifecycle; original independent music FAILED is unchanged")
            .put("privateMicrophoneInjected", false).put("activityBackgroundTransitionExercised", false)
        resultFile.writeText(result.toString(2))
        val wav = File(requireNotNull(InstrumentationRegistry.getArguments().getString("octaveBaselineWav")))
        require(wav.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertEquals(WAV_SHA, sha(wav.readBytes()))
        val allPcm = pcm(wav.readBytes())
        assertEquals(1_274_880, allPcm.size)
        val input = allPcm.copyOfRange(FIRST_SAMPLE, END_SAMPLE)
        assertEquals(144_000, input.size)
        assertEquals(SUBSET_SHA, sha(pcmBytes(input)))
        val reference = File(wav.parentFile, "octave-regression-timeline.json")
        assertEquals(REFERENCE_SHA, sha(reference.readBytes()))
        val referenceJson = JSONObject(reference.readText())
        assertEquals(WAV_SHA, referenceJson.getString("sha256"))
        val group = referenceJson.getJSONArray("groups").getJSONObject(1)
        assertEquals(setOf(40), notes(group.getJSONArray("expectedMidi")))
        val sourceDefinition = JSONObject().put("scope", "TEST DATA derived reference excerpt")
            .put("ownedTestNonce", runId).put("referenceSha256", REFERENCE_SHA).put("referenceGroupIndex", 1)
            .put("expectedMidi", JSONArray(listOf(40))).put("wavSha256", WAV_SHA)
            .put("firstSourceSample", FIRST_SAMPLE).put("exclusiveEndSourceSample", END_SAMPLE).put("subsetPcmSha256", SUBSET_SHA)
        val definitionBytes = sourceDefinition.toString(2).toByteArray(Charsets.UTF_8)
        File(outputRoot, "reference-source.json").writeBytes(definitionBytes)
        reference.copyTo(File(outputRoot, "original-reference.json"))
        val sourceId = sha(definitionBytes)
        val title = "TEST DATA E2 completion $runId"
        val score = score(sourceId, definitionBytes.size, title)
        val config = PracticeConfig(CompanionMode.STOP_ON_ERROR)
        val diagnostics = FieldDiagnostics.get(context)
        diagnostics.refresh()
        check(diagnostics.state.value.activeSession == null) { "Do not replace any existing Field session" }
        diagnostics.setClipConsent(false)
        val ownedMetadata = ScoreMetadataDatabase(context).use { database ->
            assertNull(database.findBySourceId(sourceId, includeDeleted = true))
            database.ensureImported(sourceId, ScoreImportMetadata(originalTitle = title))
        }
        val scoreId = ownedMetadata.scoreId
        result.put("scoreId", scoreId).put("sourceSha256", sourceId).put("referenceGroupIndex", 1)
            .put("firstSourceSample", FIRST_SAMPLE).put("exclusiveEndSourceSample", END_SAMPLE)
            .put("inputSha256", WAV_SHA).put("subsetPcmSha256", SUBSET_SHA).put("subsetSamples", input.size)
        val viewModels = ViewModelStore()
        lateinit var practice: PracticeViewModel
        lateinit var host: AppViewModel
        var engine: PianoRecognitionEngine? = null
        var sessionId: String? = null
        val delivered = mutableListOf<PianoRecognitionFrame>()
        val callbackFailure = AtomicReference<Throwable?>()
        var completionCalls = 0
        var drainCalls = 0
        var completedChecks = false
        var primaryFailure: Throwable? = null
        var completionFrame = -1L
        var countersAtCompletion: List<Int>? = null
        try {
            main {
                practice = PracticeViewModel(context); host = AppViewModel(context)
                viewModels.put("TEST DATA terminal-practice", practice); viewModels.put("TEST DATA terminal-host", host)
                host.setInputDrainListener { drainCalls++; practice.drainPendingInputFrames() }
                host.setPcmObserver(null) { practice.invalidateInputFrames() }
                practice.setInputCompletedListener {
                    assertEquals("Completion notification must be unique", 1, ++completionCalls)
                    assertEquals(CompanionPhase.COMPLETED, practice.state.value.phase)
                    assertEquals(1, practice.state.value.cursor)
                    assertEquals(1, practice.state.value.correct)
                    assertFalse("Completion callback precedes actual asynchronous report save", practice.state.value.busy)
                    countersAtCompletion = counters(practice.state.value)
                    host.stopAudio("TEST DATA actual automatic completion") // synchronously re-enters drain
                    assertEquals(countersAtCompletion, counters(practice.state.value))
                    assertEquals(1, completionCalls)
                    engine?.stop() // independent PCM engine is also stopped; no further samples are supplied
                }
                assertTrue(practice.open(scoreId, title, score, 0, "actual frozen PCM and bundled model"))
            }
            awaitCondition("View models ready") { !practice.state.value.busy && !host.state.value.loading }
            assertNull(practice.state.value.error); assertTrue(host.state.value.databaseReady)
            main { practice.start(config) }
            assertEquals(CompanionPhase.RUNNING, practice.state.value.phase)
            val actualEngine = PianoRecognitionEngine(context, { emitted ->
                try {
                    val frame = diagnostics.bindFrameContext(emitted)
                    diagnostics.recordRecognition(frame)
                    synchronized(delivered) { delivered.add(frame.copy(rawOnsetScores = frame.rawOnsetScores.copyOf(), rawFrameScores = frame.rawFrameScores.copyOf())) }
                    practice.accept(frame)
                } catch (error: Throwable) { callbackFailure.compareAndSet(null, error) }
            }, diagnostics::recordEngine)
            engine = actualEngine
            actualEngine.start()
            awaitEngine(actualEngine, diagnostics) { actualEngine.snapshot.status == RecognitionEngineStatus.READY }
            val snapshot = FieldScoreSnapshot.capture(FieldScoreSnapshot.Request(scoreId, 0, score, config, CompanionPhase.RUNNING, 0, score))
            sessionId = diagnostics.startSession("TEST DATA real E2 terminal tail", mapOf("scoreId" to scoreId,
                "revisionGeneration" to "0", "sourceSha256" to sourceId, "inputSource" to "RECORDED_REGRESSION_SUBSET",
                "inputSha256" to WAV_SHA, "subsetPcmSha256" to SUBSET_SHA, "referenceGroupIndex" to "1",
                "referenceSha256" to REFERENCE_SHA, "firstSourceSample" to FIRST_SAMPLE.toString(),
                "exclusiveEndSourceSample" to END_SAMPLE.toString(), "subsetSamples" to input.size.toString(),
                "subsetTimeOrigin" to "Rebased firstInputSample=0; source sample equals 91200 plus local sample index"), snapshot).id
            result.put("sessionId", sessionId)
            val sequential = PracticeSession(scoreId, title, 0, ScoreTimelineCompiler.compile(score), config,
                System.currentTimeMillis(), "TEST DATA sequential execution of actual frames")
            val evaluatedReasons = linkedMapOf<Long, String>()
            main {
                val before = practice.state.value
                val workerFailure = AtomicReference<Throwable?>()
                val worker = Thread({
                    try {
                        var submitted = 0
                        while (submitted < input.size) {
                            val target = minOf(input.size, if (submitted == 0) 60_000 else submitted + 24_576)
                            while (submitted < target) {
                                val count = minOf(4096, target - submitted)
                                val block = input.copyOfRange(submitted, submitted + count)
                                diagnostics.appendPcm(block, count, submitted.toLong())
                                actualEngine.submitPcm(block, count, submitted.toLong()); submitted += count
                            }
                            awaitEngine(actualEngine, diagnostics) { actualEngine.snapshot.processedSamples == submitted.toLong() }
                        }
                        runBlocking { diagnostics.refresh() }
                    } catch (error: Throwable) { workerFailure.set(error) }
                }, "TEST DATA real E2 while Main blocked").apply { isDaemon = true }
                worker.start(); worker.join(20_000)
                if (worker.isAlive) { worker.interrupt(); worker.join(2_000); error("Bounded actual PCM worker timed out") }
                workerFailure.get()?.let { throw AssertionError("Actual PCM replay failed", it) }
                callbackFailure.get()?.let { throw AssertionError("Actual callback failed", it) }
                assertEquals(before, practice.state.value)
                val pending = synchronized(delivered) { delivered.toList() }
                assertEquals(80, pending.size); assertEquals(80L, actualEngine.snapshot.frameCount)
                assertEquals(0L, actualEngine.snapshot.overflowCount); assertEquals(0L, actualEngine.snapshot.droppedFrames)
                assertEquals(0L, actualEngine.snapshot.rejectedBeforeReadyFrames)
                for (frame in pending) {
                    if (sequential.phase == CompanionPhase.COMPLETED) break // never reuse lastUpdate for terminal frames
                    val update = sequential.accept(observe(frame))
                    evaluatedReasons[frame.frameIndex] = update.reason
                    if (sequential.phase == CompanionPhase.COMPLETED) {
                        assertEquals(PracticeDecision.CORRECT, update.decision)
                        assertTrue(update.evidence.any { it.decision == PracticeDecision.CORRECT && it.expectedMidi == setOf(40) })
                        completionFrame = frame.frameIndex
                    }
                }
                assertTrue("Actual E2 must complete before a nonempty real tail; do not replace the target or fake a frame", completionFrame in 0L..78L)
                practice.drainPendingInputFrames()
                assertEquals(1, completionCalls); assertEquals(1, drainCalls)
                assertEquals(sequential.confirmedInputs, practice.state.value.confirmedInputs)
                assertEquals(sequential.correct, practice.state.value.correct); assertEquals(sequential.wrong, practice.state.value.wrong)
                assertEquals(countersAtCompletion, counters(practice.state.value))
            }
            awaitCondition("Actual practice report save") { !practice.state.value.busy }
            assertNull(practice.state.value.error)
            instrumentation.waitForIdleSync()
            main { practice.drainPendingInputFrames() }
            assertEquals(1, completionCalls); assertEquals(1, drainCalls)
            assertEquals(countersAtCompletion, counters(practice.state.value))
            val saved = requireNotNull(practice.state.value.latestReport)
            assertTrue(saved.completed); assertEquals(1, saved.correct)
            assertEquals(sequential.confirmedInputs, saved.confirmedInputs)
            assertEquals(sequential.wrong, saved.wrong)
            assertEquals(sequential.report(saved.endedAtMillis).evidence, saved.evidence)
            val reports = PracticeReportStore(File(context.filesDir, "practice-reports"))
            assertEquals(saved, reports.load(scoreId).single())
            assertEquals(saved.endedAtMillis, ScoreMetadataDatabase(context).use { it.get(scoreId)!!.lastPracticedAt })
            File(context.filesDir, "practice-reports/$scoreId/${saved.id}.xpr").copyTo(File(outputRoot, "actual-practice-report.xpr"))
            diagnostics.refresh(); assertNull(diagnostics.state.value.error)
            diagnostics.endSession("TEST DATA ACTUAL E2 AUTOMATIC COMPLETION")
            val actualZip = diagnostics.exportSession(requireNotNull(sessionId))
            val zipFile = File(outputRoot, "field-completion-export.zip")
            actualZip.copyTo(zipFile)
            assertEquals(sha(actualZip.readBytes()), sha(zipFile.readBytes()))
            ZipFile(zipFile).use { zip ->
                val raw = rows(zip, "audio-events.jsonl").filter { it.optString("type") == "RecognitionFrame" }
                val following = rows(zip, "score-following.jsonl")
                val normalStates = following.filter { !it.optBoolean("confirmedDecision", false) }
                val judgments = following.filter { it.optBoolean("confirmedDecision", false) }
                assertEquals(80, raw.size); assertEquals(80, normalStates.size)
                val byKey = normalStates.groupBy(::key)
                assertEquals(raw.map(::key).toSet(), byKey.keys)
                assertEquals(80, byKey.size); assertEquals(1, raw.map { key(it).take(2) }.toSet().size)
                val original = synchronized(delivered) { delivered.associateBy { it.frameIndex } }
                val contextJson = JSONObject(zip.getInputStream(zip.getEntry("practice-context.json")).reader().use { it.readText() })
                assertEquals(1, contextJson.getInt("targetCount"))
                assertEquals(scoreId, contextJson.getString("scoreId"))
                assertEquals(sourceId, contextJson.getString("sourceSha256"))
                var terminalCount = 0
                raw.forEach { record ->
                    val frameIndex = record.getLong("frameIndex")
                    val source = original.getValue(frameIndex)
                    val state = byKey.getValue(key(record)).single()
                    assertEquals(source.fieldSessionId, record.getString("sessionId"))
                    assertEquals(source.audioStreamId, record.getLong("audioStreamId"))
                    assertEquals(source.engineSessionId, record.getLong("engineSessionId"))
                    assertEquals(source.onsetMidi, notes(record.getJSONArray("finalCandidates")))
                    listOf("rawOnsetScores" to source.rawOnsetScores, "rawFrameScores" to source.rawFrameScores).forEach { (name, vector) ->
                        val values = record.getJSONArray(name); assertEquals(88, values.length())
                        repeat(88) { assertEquals(vector[it].toDouble(), values.getDouble(it), 1e-7) }
                    }
                    if (frameIndex <= completionFrame) {
                        assertFalse(state.has("processingDisposition"))
                        assertEquals(evaluatedReasons.getValue(frameIndex), state.getString("reason"))
                        assertTrue(state.getDouble("followingLatencyMs") >= 0)
                    } else {
                        terminalCount++
                        assertEquals("NOT_EVALUATED", state.getString("processingDisposition"))
                        assertEquals("NOT_EVALUATED", state.getString("decision"))
                        assertEquals("PRACTICE_COMPLETED", state.getString("reason"))
                        assertEquals("COMPLETED", state.getString("practiceState")); assertFalse(state.getBoolean("lockState"))
                        assertEquals("1", state.getString("previousScorePosition")); assertEquals("1", state.getString("currentScorePosition"))
                        assertEquals(source.onsetMidi, notes(state.getJSONArray("observedCandidates")))
                        assertEquals(emptySet<Int>(), notes(state.getJSONArray("expectedNotes")))
                        assertEquals(emptySet<Int>(), notes(state.getJSONArray("detectedNotes")))
                        assertFalse(state.getBoolean("countsAsMusicalEvaluation")); assertFalse(state.getBoolean("confirmedDecision"))
                        listOf("expectedMeasure", "expectedBeat", "followingLatencyMs", "decisionEventId", "evidenceAudioTimeSeconds", "recoveryEvent")
                            .forEach { name -> assertTrue("Terminal disposition must not copy an earlier evaluation: $name", state.isNull(name)) }
                    }
                }
                assertEquals(79 - completionFrame.toInt(), terminalCount); assertTrue(terminalCount > 0)
                val completion = normalStates.single { it.getLong("frameIndex") == completionFrame }
                assertEquals("CORRECT", completion.getString("decision")); assertEquals("COMPLETED", completion.getString("practiceState"))
                assertEquals("0", completion.getString("previousScorePosition")); assertEquals("1", completion.getString("currentScorePosition"))
                assertTrue(judgments.any { key(it) == key(completion) && it.getString("decision") == "CORRECT" && it.getString("practiceState") == "COMPLETED" })
                assertFalse(judgments.any { it.getLong("frameIndex") > completionFrame })
                assertEquals(1, judgments.count { it.getString("decision") == "CORRECT" })
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
                val sessionStartedAt = manifest.getLong("startedAtMillis")
                val rejectedLogs = rows(zip, "app.log").filter {
                    it.optString("message") in setOf("StaleFollowingRejected", "StaleDispositionRejected", "StaleFrameRejected", "FollowingProcessingFailure")
                }.filterNot {
                    val timestamp = it.opt("timestamp")
                    // Only explicitly copied, time-proven pre-session context is exempt.
                    it.opt("beforeSessionStart") == true && (timestamp is Long || timestamp is Int) &&
                        (timestamp as Number).toLong() in 1..sessionStartedAt
                }
                assertTrue("Current-session following failures: ${JSONArray(rejectedLogs)}", rejectedLogs.isEmpty())
                assertEquals(sessionId, manifest.getString("sessionId")); assertEquals("COMPLETED", manifest.getString("status"))
                assertEquals(BuildConfig.GIT_COMMIT, manifest.getString("gitCommit"))
                result.put("rawFrames", 80).put("evaluatedFrames", completionFrame + 1).put("notEvaluatedFrames", terminalCount)
                    .put("completionFrame", completionFrame).put("confirmedInputs", saved.confirmedInputs).put("correct", saved.correct)
                    .put("wrongBeforeCompletion", saved.wrong).put("newConfirmationsAfterCompletion", 0)
            }
            result.put("zipSha256", sha(zipFile.readBytes())).put("completionCallbacks", completionCalls).put("nestedStopDrainCalls", drainCalls)
            completedChecks = true
        } catch (error: Throwable) { primaryFailure = error; result.put("error", error.stackTraceToString()); throw error }
        finally {
            var cleanupFailure: Throwable? = null
            fun note(error: Throwable) { cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error } }
            try { engine?.close() } catch (error: Throwable) { note(error) }
            try { if (sessionId != null && diagnostics.state.value.activeSession?.id == sessionId) diagnostics.endSession("TEST DATA TERMINAL TEST FAILED") }
            catch (error: Throwable) { note(error) }
            try { main {
                if (completionCalls > 0 && practice.state.value.busy) {
                    error("Report save has not settled; preserve owned metadata and reports for diagnosis")
                }
                host.setInputDrainListener(null); host.setPcmObserver(null, null)
                practice.setInputCompletedListener(null)
                viewModels.clear()
            } } catch (error: Throwable) { note(error) }
            try {
                check(cleanupFailure == null) { "Cleanup did not settle; owned metadata/report remain for diagnosis" }
                // Only the exact UUID score created by this test; no user score/source/Field archive is removed.
                ScoreMetadataDatabase(context).use { database ->
                    val current = requireNotNull(database.get(scoreId, includeDeleted = true))
                    require(current.sourceId == sourceId && current.originalTitle == title)
                    val reportsRoot = File(context.filesDir, "practice-reports").canonicalFile
                    val owned = File(reportsRoot, scoreId)
                    require(owned.canonicalFile.parentFile == reportsRoot && !Files.isSymbolicLink(owned.toPath()))
                    if (owned.exists()) check(owned.deleteRecursively())
                    assertEquals(1, database.writableDatabase.delete("score_metadata", "score_id = ? AND source_id = ?", arrayOf(scoreId, sourceId)))
                }
                result.put("ownedMetadataAndPracticeReportsCleaned", true)
            } catch (error: Throwable) { note(error) }
            cleanupFailure?.let { result.put("cleanupError", it.stackTraceToString()); primaryFailure?.addSuppressed(it) }
            result.put("chainPassed", completedChecks && primaryFailure == null && cleanupFailure == null).put("endedAtMillis", System.currentTimeMillis())
            resultFile.writeText(result.toString(2))
            instrumentation.addResults(android.os.Bundle().apply {
                putString("FIELD_COMPLETION_BOUNDARY_RESULT", resultFile.absolutePath)
            })
            println("FIELD_COMPLETION_BOUNDARY_RESULT=${resultFile.absolutePath}")
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun counters(state: CompanionState) = listOf(state.confirmedInputs, state.correct, state.wrong, state.missed, state.cursor)
    private fun key(row: JSONObject) = listOf(row.getLong("audioStreamId"), row.getLong("engineSessionId"), row.getLong("frameIndex"))
    private fun notes(array: JSONArray) = (0 until array.length()).map { array.getInt(it) }.toSet()
    private fun rows(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
    private fun observe(frame: PianoRecognitionFrame): RecognitionObservation {
        val notes = frame.notes.associateBy { it.midi }
        fun evidence(midi: Int) = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
        return RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds, frame.onsetMidi.mapNotNull(::evidence), frame.activeMidi.mapNotNull(::evidence),
            frame.state != RecognitionState.WARMING_UP, frame.clipped, frame.uncertainMidi, frame.ambiguousOctaveMidi)
    }
    private fun score(sourceId: String, bytes: Int, title: String) = DigitalScore(sourceId, title, listOf(ScorePart("p", "Piano", listOf(
        ScoreMeasure("m1", "1", ScoreTime(1, 4), 0, listOf(ScoreClef(1, "F", 4)), listOf(
            ScoreEvent("e1", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(40), 1, "1", false, "quarter", 0, emptySet(), 0, 0)), false)
    ))), emptyList(), ScoreSource("TEST DATA frozen-reference E2 excerpt", sourceId, bytes))
    private fun awaitEngine(engine: PianoRecognitionEngine, diagnostics: FieldDiagnostics, predicate: () -> Boolean) = awaitCondition("Actual model") {
        check(engine.snapshot.status !in setOf(RecognitionEngineStatus.ERROR, RecognitionEngineStatus.CLOSED)) { engine.snapshot.message }
        check(diagnostics.state.value.error == null) { diagnostics.state.value.error.orEmpty() }; predicate()
    }
    private fun awaitCondition(label: String, predicate: () -> Boolean) { val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "$label timed out" }; Thread.sleep(10) } }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun pcm(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12; var format = false
        while (offset + 8 <= bytes.size) { val tag = String(bytes, offset, 4, Charsets.US_ASCII); val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            if (tag == "fmt ") format = length >= 16 && buffer.getShort(offset + 8).toInt() == 1 && buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 48_000 && buffer.getShort(offset + 22).toInt() == 16
            if (tag == "data") { require(format && length % 2 == 0); return ShortArray(length / 2) { buffer.getShort(offset + 8 + it * 2) } }; offset += 8 + length + length % 2 }
        error("No PCM data")
    }
    private fun pcmBytes(samples: ShortArray) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val FIRST_SAMPLE = 91_200
        private const val END_SAMPLE = 235_200
        private const val WAV_SHA = "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"
        private const val REFERENCE_SHA = "cdfc2bccc23a8bfe0a23dbbfe8a6a4e76561a31cff383ff0a2930e359a1fd099"
        private const val SUBSET_SHA = "7626fdab2b11cc0ff6cedab8a6f70f60dc4239fc72026f7ae5f8ddf551cd4508"
    }
}
