package com.xiuyin.app.field

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.pitch.*
import com.xiuyin.app.practice.*
import com.xiuyin.app.score.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/** Actual recorded regression PCM and shipped piano model. Test button labels are not musical truth. */
class FieldSessionReplayTest {
    @Test fun productionPianoFramesAreExportedWithFeedbackAndClips(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val resultFile = File(external, "field-replay-result.json")
        val result = JSONObject().put("startedAtMillis", System.currentTimeMillis())
            .put("chainPassed", false).put("accuracyValidated", false).put("gitCommit", BuildConfig.GIT_COMMIT)
            .put("scope", "TEST DATA actual DotIAM recorded regression -> production model/follower/diagnostic ZIP; not phone microphone accuracy")
        resultFile.writeText(result.toString(2))
        val wav = File(requireNotNull(InstrumentationRegistry.getArguments().getString("octaveBaselineWav")))
        assertTrue(wav.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val inputSha = sha(wav.readBytes())
        assertEquals("a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20", inputSha)
        val pcm = pcm(wav)
        assertEquals(1274880, pcm.size)
        val referenceFile = File(wav.parentFile, "octave-regression-timeline.json")
        assertEquals("cdfc2bccc23a8bfe0a23dbbfe8a6a4e76561a31cff383ff0a2930e359a1fd099", sha(referenceFile.readBytes()))
        val reference = JSONObject(referenceFile.readText()); assertEquals(inputSha, reference.getString("sha256"))
        val entries = reference.getJSONArray("groups")
        val groups = (0 until entries.length()).map { index ->
            val notes = entries.getJSONObject(index).getJSONArray("expectedMidi")
            (0 until notes.length()).map { notes.getInt(it) }.toSet()
        }
        val follower = ScoreFollower(ScoreTimelineCompiler.compile(referenceScore(groups)), PracticeConfig(mode = CompanionMode.STOP_ON_ERROR))
        val diagnostics = FieldDiagnostics.get(context)
        diagnostics.refresh()
        check(diagnostics.state.value.activeSession == null) { "Test will not overwrite an existing session" }
        diagnostics.setClipConsent(true)
        val session = diagnostics.startSession("TEST DATA recorded DotIAM field ZIP", mapOf("inputSource" to "RECORDED_REGRESSION",
            "inputSha256" to inputSha, "timing" to "paced offline file replay, not hardware microphone latency", "feedbackLabels" to "TEST DATA button actions, not verified truth"))
        result.put("sessionId", session.id).put("inputSha256", inputSha).put("inputSamples", pcm.size)
        val frames = AtomicLong(); val ambiguous = AtomicLong(); var saysCorrect = false; var saysWrong = false
        val engine = PianoRecognitionEngine(context, { rawFrame ->
            val frame = diagnostics.bindFrameContext(rawFrame)
            diagnostics.recordRecognition(frame)
            val before = follower.cursor; val previouslyLocked = follower.locked
            val expected = follower.expectedMidi(); val measure = follower.measureIndex()
            val target = follower.targets.getOrNull(before)
            val notes = frame.notes.associateBy { it.midi }
            fun observed(midi: Int) = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
            val start = SystemClock.elapsedRealtimeNanos()
            val update = follower.accept(RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds,
                frame.onsetMidi.mapNotNull(::observed), frame.activeMidi.mapNotNull(::observed),
                clipped = frame.clipped, uncertainMidi = frame.uncertainMidi, ambiguousOctaveMidi = frame.ambiguousOctaveMidi))
            diagnostics.recordFollowing(FieldFollowingTrace(frame.frameIndex, frame.audioTimeSeconds,
                (measure + 1).toString(), target?.startBeat?.toDouble(), expected, update.detectedMidi,
                before.toString(), follower.cursor.toString(), update.decision.name,
                if (follower.locked) "LOCKED" else "RUNNING", follower.locked, update.reason,
                if (previouslyLocked && !follower.locked) "UNLOCK_AFTER_RECOGNIZED_CORRECTION" else null,
                (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0,
                fieldSessionId = frame.fieldSessionId, audioStreamId = frame.audioStreamId, engineSessionId = frame.engineSessionId))
            update.evidence.forEach { evidence ->
                diagnostics.recordFollowing(FieldFollowingTrace(frame.frameIndex, frame.audioTimeSeconds,
                    (evidence.measureIndex + 1).toString(), follower.targets.getOrNull(evidence.targetIndex)?.startBeat?.toDouble(),
                    evidence.expectedMidi, evidence.detectedMidi, before.toString(), follower.cursor.toString(), evidence.decision.name,
                    if (follower.locked) "LOCKED" else "RUNNING", follower.locked, evidence.reason,
                    decisionEventId = "test:${frame.frameIndex}:${evidence.targetIndex}:${evidence.decision}",
                    confirmedDecision = true, evidenceAudioTimeSeconds = evidence.audioTimeSeconds,
                    fieldSessionId = frame.fieldSessionId, audioStreamId = frame.audioStreamId, engineSessionId = frame.engineSessionId))
            }
            if (frame.ambiguousOctaveMidi.isNotEmpty()) {
                assertEquals(PracticeDecision.UNCERTAIN, update.decision)
                assertEquals("Ambiguity cannot introduce a new LOCK", previouslyLocked, follower.locked)
                assertTrue(update.evidence.isEmpty()); assertEquals(before, follower.cursor)
                ambiguous.incrementAndGet()
            }
            if (!saysCorrect && frame.audioTimeSeconds >= 4.0) {
                diagnostics.recordFeedback(FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG, mapOf("source" to "TEST DATA button operation", "fieldSessionId" to session.id, "audioStreamId" to frame.audioStreamId.toString(), "engineSessionId" to frame.engineSessionId.toString(), "frameIndex" to frame.frameIndex.toString()), frame.audioTimeSeconds)
                saysCorrect = true
            }
            if (!saysWrong && frame.audioTimeSeconds >= 9.0) {
                diagnostics.recordFeedback(FieldFeedbackLabel.USER_CONFIRMS_WRONG_NOTE, mapOf("source" to "TEST DATA button operation", "fieldSessionId" to session.id, "audioStreamId" to frame.audioStreamId.toString(), "engineSessionId" to frame.engineSessionId.toString(), "frameIndex" to frame.frameIndex.toString()), frame.audioTimeSeconds)
                saysWrong = true
            }
            assertEquals("Continuous real frame sequence", frames.getAndIncrement(), frame.frameIndex)
        }, diagnostics::recordEngine)
        try {
            engine.start(); await(engine, diagnostics) { engine.snapshot.status == RecognitionEngineStatus.READY }
            var submitted = 0L; var blocks = 0L; var target = 60000L
            while (submitted < pcm.size) {
                while (submitted < target) {
                    val count = minOf(4096L, target - submitted).toInt()
                    val block = pcm.copyOfRange(submitted.toInt(), submitted.toInt() + count)
                    diagnostics.appendPcm(block, count, submitted)
                    engine.submitPcm(block, count, submitted)
                    submitted += count; blocks++
                }
                await(engine, diagnostics) { engine.snapshot.processedSamples == submitted && engine.snapshot.processedBlocks == blocks }
                diagnostics.refresh() // Drain incremental writes before the next batch; never discard logs to meet the gate.
                target = minOf(submitted + 24576, pcm.size.toLong())
            }
            val snapshot = engine.snapshot
            assertEquals(816L, frames.get()); assertEquals(816L, snapshot.frameCount)
            assertTrue("Frozen regression must exercise real octave ambiguity", ambiguous.get() > 0)
            assertEquals(0L, snapshot.overflowCount); assertEquals(0L, snapshot.droppedFrames)
            assertEquals(0, snapshot.queueOccupancy); assertEquals(1274880L, snapshot.processedSamples)
            result.put("submittedSamples", submitted).put("processedSamples", snapshot.processedSamples)
                .put("frames", frames.get()).put("ambiguousFrames", ambiguous.get()).put("overflowCount", snapshot.overflowCount)
                .put("lockedAtEnd", follower.locked).put("scorePositionAtEnd", follower.cursor)
            engine.stop(); assertEquals(RecognitionEngineStatus.IDLE, engine.snapshot.status)
            diagnostics.endSession("TEST DATA REPLAY FINISHED")
            val zip = diagnostics.exportSession(session.id)
            ZipFile(zip).use { archive ->
                val events = archive.getInputStream(archive.getEntry("audio-events.jsonl")).bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.map(::JSONObject)
                val recognized = events.filter { it.optString("type") == "RecognitionFrame" }
                assertEquals(816, recognized.size)
                recognized.forEach { assertEquals(88, it.getJSONArray("rawOnsetScores").length()); assertEquals(88, it.getJSONArray("rawFrameScores").length()) }
                val following = archive.getInputStream(archive.getEntry("score-following.jsonl")).bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.map(::JSONObject)
                assertEquals((0L..815L).toSet(), following.map { it.getLong("frameIndex") }.toSet())
                val logs = archive.getInputStream(archive.getEntry("app.log")).bufferedReader().use { it.readText() }
                assertTrue(logs.contains("USER_SAYS_SYSTEM_WRONG")); assertTrue(logs.contains("USER_CONFIRMS_WRONG_NOTE"))
                val clips = archive.entries().asSequence().filter { it.name.endsWith(".wav") }.toList()
                assertTrue("Consent must result in actual bounded audio, not an empty ZIP", clips.isNotEmpty())
                clips.forEach { entry ->
                    val meta = JSONObject(archive.getInputStream(archive.getEntry(entry.name.removeSuffix(".wav") + ".json")).bufferedReader().use { it.readText() })
                    val bytes = archive.getInputStream(entry).use { it.readBytes() }
                    val clip = pcmBytes(bytes); val first = meta.getLong("firstSample").toInt()
                    assertTrue(clip.size <= 240000)
                    assertArrayEquals("Exported clip must be byte-equivalent to original input samples", pcm.copyOfRange(first, first + clip.size), clip)
                }
                result.put("clipCount", clips.size)
                val summary = JSONObject(archive.getInputStream(archive.getEntry("session-summary.json")).bufferedReader().use { it.readText() })
                assertFalse(summary.getBoolean("statisticsAreAccuracy")); assertFalse(summary.getBoolean("hasErrors"))
            }
            val exported = File(external, "field-replay-export.zip")
            zip.copyTo(exported, overwrite = true)
            assertEquals(sha(zip.readBytes()), sha(exported.readBytes()))
            result.put("zipSha256", sha(exported.readBytes())).put("statusAfterStop", engine.snapshot.status.name).put("chainPassed", true)
            // Keep this uniquely identified test session/private export for the actual rollback preservation rehearsal.
        } catch (error: Throwable) {
            result.put("error", error.stackTraceToString()); throw error
        } finally {
            engine.close()
            if (diagnostics.state.value.activeSession?.id == session.id) diagnostics.endSession("TEST DATA REPLAY FAILED")
            diagnostics.setClipConsent(false)
            resultFile.writeText(result.toString(2))
        }
    }
    private fun await(engine: PianoRecognitionEngine, diagnostics: FieldDiagnostics, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(diagnostics.state.value.error == null) { diagnostics.state.value.error.orEmpty() }
            check(SystemClock.elapsedRealtime() < deadline) { "Real model replay timed out: ${engine.snapshot}" }
            Thread.sleep(10)
        }
        check(diagnostics.state.value.error == null) { diagnostics.state.value.error.orEmpty() }
    }
    private fun referenceScore(groups: List<Set<Int>>): DigitalScore {
        val measures = groups.mapIndexed { index, pitches ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), pitches.sorted().mapIndexed { noteIndex, midi ->
                ScoreEvent("e${index}_$noteIndex", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi), 1, "1", noteIndex > 0, "quarter", 0, emptySet(), index * 10 + noteIndex, 0)
            }, false)
        }
        return DigitalScore("test-reference", "TEST DATA independent website reference", listOf(ScorePart("p", "Piano", measures)), emptyList(), ScoreSource("independent-web-action-reference", "DotIAM frozen regression", 1))
    }
    private fun pcm(file: File): ShortArray = pcmBytes(file.readBytes())
    private fun pcmBytes(bytes: ByteArray): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12; var valid = false
        while (offset + 8 <= bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII); val length = input.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            if (type == "fmt ") valid = length >= 16 && input.getShort(offset + 8).toInt() == 1 && input.getShort(offset + 10).toInt() == 1 && input.getInt(offset + 12) == 48000 && input.getShort(offset + 22).toInt() == 16
            if (type == "data") { require(valid && length % 2 == 0); return ShortArray(length / 2) { input.getShort(offset + 8 + it * 2) } }
            offset += 8 + length + length % 2
        }
        error("No valid PCM data")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
