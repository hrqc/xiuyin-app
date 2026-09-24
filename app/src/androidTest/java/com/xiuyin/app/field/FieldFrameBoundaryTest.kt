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
 * TEST DATA: actual frozen website PCM and bundled model, with deliberately blocked Main scheduling.
 * The independent engine feeds the production Field/Practice components; App.stopAudio exercises its
 * real drain hook. This does not inject PCM into App's private microphone or certify phone accuracy.
 */
class FieldFrameBoundaryTest {
    @Test fun realRawQueuedOnMainIsActuallyFollowedBeforePauseBackgroundAndFieldEnd(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext as Application
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "field-frame-boundary-result.json")
        val result = JSONObject().put("startedAtMillis", System.currentTimeMillis())
            .put("gitCommit", BuildConfig.GIT_COMMIT).put("chainPassed", false).put("accuracyValidated", false)
            .put("privateMicrophoneInjected", false).put("activityBackgroundTransitionExercised", false)
            .put("scope", "TEST DATA frozen website PCM -> real model -> queued PracticeViewModel -> actual App.stopAudio drain hook -> Field ZIP; no private microphone injection or physical accuracy claim")
        output.writeText(result.toString(2))
        val wav = File(requireNotNull(InstrumentationRegistry.getArguments().getString("octaveBaselineWav")))
        require(wav.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertEquals(WAV_SHA, sha(wav.readBytes()))
        val wholePcm = pcm(wav.readBytes())
        assertEquals(1_274_880, wholePcm.size)
        val input = wholePcm.copyOfRange(0, 144_000)
        val reference = File(wav.parentFile, "octave-regression-timeline.json")
        assertEquals(REFERENCE_SHA, sha(reference.readBytes()))
        val referenceJson = JSONObject(reference.readText())
        assertEquals(WAV_SHA, referenceJson.getString("sha256"))
        val groups = referenceJson.getJSONArray("groups")
        val score = referenceScore(List(groups.length()) { index ->
            val notes = groups.getJSONObject(index).getJSONArray("expectedMidi")
            List(notes.length()) { notes.getInt(it) }.toSet()
        }, reference.length().toInt())
        val scoreId = UUID.randomUUID().toString()
        val config = PracticeConfig(CompanionMode.FREE)
        val control = PracticeSession(scoreId, score.title, 0, ScoreTimelineCompiler.compile(score), config,
            System.currentTimeMillis(), "TEST DATA independent sequential execution of the same actual frames")
        val diagnostics = FieldDiagnostics.get(context)
        diagnostics.refresh()
        check(diagnostics.state.value.activeSession == null) { "TEST DATA must not replace another Field session" }
        diagnostics.setClipConsent(false)
        val viewModels = ViewModelStore()
        lateinit var practice: PracticeViewModel
        lateinit var host: AppViewModel
        var drainCalls = 0
        val boundaries = JSONArray()
        val frames = mutableListOf<PianoRecognitionFrame>()
        val expected = linkedMapOf<SourceKey, ExpectedFollowing>()
        val callbackFailure = AtomicReference<Throwable?>()
        var sessionId: String? = null
        var engine: PianoRecognitionEngine? = null
        var completedChecks = false
        var primaryFailure: Throwable? = null
        try {
            main {
                practice = PracticeViewModel(context)
                host = AppViewModel(context)
                viewModels.put("TEST DATA boundary-practice", practice)
                viewModels.put("TEST DATA boundary-host", host)
                host.setInputDrainListener { drainCalls++; practice.drainPendingInputFrames() }
                host.setPcmObserver(null) { practice.invalidateInputFrames() }
                assertTrue(practice.open(scoreId, score.title, score, 0, "bundled production model; frozen PCM"))
            }
            awaitCondition("TEST DATA view models ready") { !practice.state.value.busy && !host.state.value.loading }
            assertNull(practice.state.value.error)
            assertTrue(host.state.value.databaseReady)
            main { practice.start(config) }
            assertEquals(CompanionPhase.RUNNING, practice.state.value.phase)
            val actualEngine = PianoRecognitionEngine(context, { emitted ->
                try {
                    val bound = diagnostics.bindFrameContext(emitted)
                    diagnostics.recordRecognition(bound)
                    synchronized(frames) { frames.add(bound) }
                    practice.accept(bound)
                } catch (error: Throwable) { callbackFailure.compareAndSet(null, error) }
            }, diagnostics::recordEngine)
            engine = actualEngine
            actualEngine.start()
            awaitEngine(actualEngine, diagnostics) { actualEngine.snapshot.status == RecognitionEngineStatus.READY }
            val snapshot = FieldScoreSnapshot.capture(FieldScoreSnapshot.Request(scoreId, 0, score, config,
                CompanionPhase.RUNNING, 0, score))
            sessionId = diagnostics.startSession("TEST DATA Main queue lifecycle boundary", mapOf(
                "scoreId" to scoreId, "revisionGeneration" to "0", "sourceSha256" to score.source.sha256,
                "inputSource" to "RECORDED_REGRESSION", "inputSha256" to WAV_SHA,
                "scope" to "TEST DATA actual model/component replay, not microphone injection or music accuracy"), snapshot).id
            result.put("sessionId", sessionId).put("inputSha256", WAV_SHA).put("referenceSha256", REFERENCE_SHA)
                .put("subsetFirstSample", 0).put("subsetSamplesPerStream", input.size).put("subsetPcmSha256", sha(pcmBytes(input)))

            listOf("USER_PAUSE", "BACKGROUND_STOP", "FIELD_END").forEachIndexed { round, reason ->
                if (round > 0) {
                    main { practice.resume(); control.resume() }
                    actualEngine.start()
                    awaitEngine(actualEngine, diagnostics) { actualEngine.snapshot.status == RecognitionEngineStatus.READY }
                }
                val first = synchronized(frames) { frames.size }
                val before = practice.state.value
                val workerFailure = AtomicReference<Throwable?>()
                // Main owns this callback until stopAudio returns. Worker-produced accept jobs cannot
                // run on Main first; this deterministically exercises the real observed failure window.
                main {
                    val worker = Thread({
                        try {
                            var submitted = 0
                            while (submitted < input.size) {
                                val target = minOf(input.size, if (submitted == 0) 60_000 else submitted + 24_576)
                                while (submitted < target) {
                                    val count = minOf(4096, target - submitted)
                                    val block = input.copyOfRange(submitted, submitted + count)
                                    diagnostics.appendPcm(block, count, submitted.toLong())
                                    actualEngine.submitPcm(block, count, submitted.toLong())
                                    submitted += count
                                }
                                awaitEngine(actualEngine, diagnostics) { actualEngine.snapshot.processedSamples == submitted.toLong() }
                            }
                            runBlocking { diagnostics.refresh() } // raw is actually durable before Main drains
                        } catch (error: Throwable) { workerFailure.set(error) }
                    }, "TEST DATA real PCM while Main blocked").apply { isDaemon = true }
                    worker.start()
                    worker.join(20_000)
                    if (worker.isAlive) {
                        worker.interrupt(); worker.join(2_000)
                        error("Actual PCM worker did not finish inside the bounded Main-blocking window")
                    }
                    workerFailure.get()?.let { throw AssertionError("Actual PCM replay failed", it) }
                    callbackFailure.get()?.let { throw AssertionError("Actual model callback failed", it) }
                    assertEquals("Following cannot have run while Main was blocked", before, practice.state.value)
                    val pending = synchronized(frames) { frames.drop(first) }
                    assertEquals("Actual 3-second processor output", 80, pending.size)
                    assertEquals(80L, actualEngine.snapshot.frameCount)
                    assertEquals(0L, actualEngine.snapshot.overflowCount)
                    assertEquals(0L, actualEngine.snapshot.droppedFrames)
                    assertEquals(0L, actualEngine.snapshot.rejectedBeforeReadyFrames)
                    pending.forEach { frame ->
                        val previous = control.follower.cursor
                        val target = control.follower.targets.getOrNull(previous)
                        val expectedNotes = control.follower.expectedMidi()
                        val measure = control.follower.measureIndex()
                        val update = control.accept(observe(frame))
                        check(control.phase != CompanionPhase.COMPLETED) { "Fixture must keep a live follower at this boundary" }
                        val key = sourceKey(frame)
                        check(expected.put(key, ExpectedFollowing(previous, control.follower.cursor, measure + 1,
                            target?.startBeat?.toDouble(), expectedNotes, update.detectedMidi, update.decision.name,
                            control.phase.name, control.follower.locked, update.reason)) == null)
                    }
                    val previousDrains = drainCalls
                    // Production App boundary must invoke its hook before audioStopped invalidation
                    // and Field.endAudioStream. The independent replay engine already processed PCM.
                    host.stopAudio("TEST DATA $reason")
                    assertEquals(previousDrains + 1, drainCalls)
                    assertEquals(control.follower.cursor, practice.state.value.cursor)
                    assertEquals(control.confirmedInputs, practice.state.value.confirmedInputs)
                    assertEquals(control.correct, practice.state.value.correct)
                    assertEquals(control.wrong, practice.state.value.wrong)
                    assertEquals(control.lastUpdate.reason, practice.state.value.reason)
                    assertNull(practice.state.value.error)
                    actualEngine.stop()
                    practice.pause(); control.pause()
                    assertEquals(CompanionPhase.PAUSED, practice.state.value.phase)
                    boundaries.put(JSONObject().put("reason", reason).put("frames", pending.size)
                        .put("audioStreamId", pending.first().audioStreamId).put("engineSessionId", pending.first().engineSessionId)
                        .put("firstFrame", pending.first().frameIndex).put("lastFrame", pending.last().frameIndex)
                        .put("actualCursorAfterDrain", practice.state.value.cursor).put("confirmedInputs", practice.state.value.confirmedInputs))
                }
                val paused = practice.state.value
                instrumentation.waitForIdleSync() // formerly scheduled jobs must not duplicate a drained frame
                assertEquals(paused, practice.state.value)
                diagnostics.refresh()
                assertNull(diagnostics.state.value.error)
            }
            assertEquals(3, drainCalls)
            diagnostics.endSession("TEST DATA FIELD_END AFTER ACTUAL DRAIN")
            val exported = diagnostics.exportSession(requireNotNull(sessionId))
            val destination = File(output.parentFile, "field-frame-boundary-export.zip")
            exported.copyTo(destination, overwrite = true)
            assertEquals(sha(exported.readBytes()), sha(destination.readBytes()))
            ZipFile(destination).use { zip ->
                val raw = rows(zip, "audio-events.jsonl").filter { it.optString("type") == "RecognitionFrame" }
                val allFollowing = rows(zip, "score-following.jsonl")
                val following = allFollowing.filter { !it.optBoolean("confirmedDecision", false) }
                assertEquals(240, raw.size)
                assertEquals(240, following.size)
                assertEquals(raw.size, raw.map(::sourceKey).toSet().size)
                val grouped = following.groupBy(::sourceKey)
                assertEquals(raw.map(::sourceKey).toSet(), grouped.keys)
                assertEquals(expected.keys, grouped.keys)
                raw.forEach { row ->
                    assertEquals(88, row.getJSONArray("rawOnsetScores").length())
                    assertEquals(88, row.getJSONArray("rawFrameScores").length())
                    val key = sourceKey(row)
                    val ordinary = grouped.getValue(key).single()
                    val actual = expected.getValue(key)
                    assertEquals(actual.previous.toString(), ordinary.getString("previousScorePosition"))
                    assertEquals(actual.current.toString(), ordinary.getString("currentScorePosition"))
                    assertEquals(actual.measure.toString(), ordinary.getString("expectedMeasure"))
                    assertEquals(actual.beat, if (ordinary.isNull("expectedBeat")) null else ordinary.getDouble("expectedBeat"))
                    assertEquals(actual.expected, notes(ordinary.getJSONArray("expectedNotes")))
                    assertEquals(actual.detected, notes(ordinary.getJSONArray("detectedNotes")))
                    assertEquals(actual.decision, ordinary.getString("decision"))
                    assertEquals(actual.phase, ordinary.getString("practiceState"))
                    assertEquals(actual.locked, ordinary.getBoolean("lockState"))
                    assertEquals(actual.reason, ordinary.getString("reason"))
                    assertTrue(ordinary.getDouble("followingLatencyMs") >= 0)
                }
                val exportedManifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().use { it.readText() })
                val sessionStartedAt = exportedManifest.getLong("startedAtMillis")
                val rejectedLogs = rows(zip, "app.log").filter {
                    it.optString("message") in setOf("StaleFollowingRejected", "StaleFrameRejected")
                }.filterNot {
                    val timestamp = it.opt("timestamp")
                    // Only explicitly copied, time-proven pre-session context is exempt.
                    it.opt("beforeSessionStart") == true && (timestamp is Long || timestamp is Int) &&
                        (timestamp as Number).toLong() in 1..sessionStartedAt
                }
                assertTrue("Current-session rejected associations: ${JSONArray(rejectedLogs)}", rejectedLogs.isEmpty())
                assertEquals(sessionId, exportedManifest.getString("sessionId"))
                assertEquals(BuildConfig.GIT_COMMIT, exportedManifest.getString("gitCommit"))
                assertEquals("COMPLETED", exportedManifest.getString("status"))
            }
            result.put("boundaries", boundaries).put("rawFrames", 240).put("ordinaryFollowingFrames", 240)
                .put("zipSha256", sha(destination.readBytes()))
            completedChecks = true
        } catch (error: Throwable) {
            primaryFailure = error
            result.put("error", error.stackTraceToString())
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try { engine?.close() } catch (error: Throwable) { cleanupFailure = error }
            try {
                if (sessionId != null && diagnostics.state.value.activeSession?.id == sessionId) {
                    diagnostics.endSession("TEST DATA BOUNDARY TEST FAILED")
                }
            } catch (error: Throwable) { cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error } }
            try { main { viewModels.clear() } }
            catch (error: Throwable) { cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error } }
            cleanupFailure?.let { error ->
                result.put("cleanupError", error.stackTraceToString())
                primaryFailure?.addSuppressed(error)
            }
            result.put("chainPassed", completedChecks && primaryFailure == null && cleanupFailure == null)
                .put("endedAtMillis", System.currentTimeMillis())
            output.writeText(result.toString(2))
            // Keep this owned Field session and diagnostic ZIP; never delete user data or the failed UI ZIP.
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private data class SourceKey(val stream: Long, val engine: Long, val frame: Long)
    private data class ExpectedFollowing(val previous: Int, val current: Int, val measure: Int, val beat: Double?,
        val expected: Set<Int>, val detected: Set<Int>, val decision: String, val phase: String, val locked: Boolean, val reason: String)
    private fun sourceKey(frame: PianoRecognitionFrame) = SourceKey(requireNotNull(frame.audioStreamId), requireNotNull(frame.engineSessionId), frame.frameIndex)
    private fun sourceKey(row: JSONObject) = SourceKey(row.getLong("audioStreamId"), row.getLong("engineSessionId"), row.getLong("frameIndex"))
    private fun notes(values: JSONArray) = (0 until values.length()).map { values.getInt(it) }.toSet()
    private fun rows(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().use { input ->
        input.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
    }
    private fun observe(frame: PianoRecognitionFrame): RecognitionObservation {
        val notes = frame.notes.associateBy { it.midi }
        fun evidence(midi: Int) = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
        return RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds, frame.onsetMidi.mapNotNull(::evidence),
            frame.activeMidi.mapNotNull(::evidence), frame.state != RecognitionState.WARMING_UP,
            frame.clipped, frame.uncertainMidi, frame.ambiguousOctaveMidi)
    }
    private fun referenceScore(groups: List<Set<Int>>, byteCount: Int): DigitalScore {
        val measures = groups.mapIndexed { index, pitches ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)),
                pitches.sorted().mapIndexed { noteIndex, midi ->
                    ScoreEvent("e${index}_$noteIndex", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi),
                        1, "1", noteIndex > 0, "quarter", 0, emptySet(), index * 10 + noteIndex, 0)
                }, false)
        }
        return DigitalScore(REFERENCE_SHA, "TEST DATA Field lifecycle reference", listOf(ScorePart("p", "Piano", measures)),
            emptyList(), ScoreSource("independent-web-action-reference", REFERENCE_SHA, byteCount))
    }
    private fun awaitEngine(engine: PianoRecognitionEngine, diagnostics: FieldDiagnostics, predicate: () -> Boolean) =
        awaitCondition("Real engine replay") {
            check(engine.snapshot.status !in setOf(RecognitionEngineStatus.ERROR, RecognitionEngineStatus.CLOSED)) { engine.snapshot.message }
            check(diagnostics.state.value.error == null) { diagnostics.state.value.error.orEmpty() }
            predicate()
        }
    private fun awaitCondition(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "$label timed out" }; Thread.sleep(10) }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun pcm(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12
        var validFormat = false
        while (offset + 8 <= bytes.size) {
            val tag = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            if (tag == "fmt ") validFormat = length >= 16 && buffer.getShort(offset + 8).toInt() == 1 &&
                buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 48_000 && buffer.getShort(offset + 22).toInt() == 16
            if (tag == "data") { require(validFormat && length % 2 == 0); return ShortArray(length / 2) { buffer.getShort(offset + 8 + it * 2) } }
            offset += 8 + length + length % 2
        }
        error("No PCM data")
    }
    private fun pcmBytes(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buffer ->
        samples.forEach { buffer.putShort(it) }
    }.array()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val WAV_SHA = "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"
        private const val REFERENCE_SHA = "cdfc2bccc23a8bfe0a23dbbfe8a6a4e76561a31cff383ff0a2930e359a1fd099"
    }
}
