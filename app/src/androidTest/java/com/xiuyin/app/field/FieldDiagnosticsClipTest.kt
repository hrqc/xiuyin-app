package com.xiuyin.app.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Synthetic PCM verifies privacy/timing plumbing only; it does not claim recognition accuracy. */
@RunWith(AndroidJUnit4::class)
class FieldDiagnosticsClipTest {
    @Test fun consentShortClipAndStreamResetAreEnforcedByProductionWriter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = FieldDiagnostics.get(context)
        diagnostics.refresh()
        check(diagnostics.state.value.activeSession == null) { "不覆盖用户活动会话" }
        diagnostics.setClipConsent(false)
        val session = diagnostics.startSession("TEST DATA clip privacy contract")
        try {
            var first = 0L
            fun appendTenths(count: Int) { repeat(count) { val pcm = ShortArray(4800) { 123 }; diagnostics.appendPcm(pcm, pcm.size, first); first += pcm.size } }
            appendTenths(30)
            diagnostics.requestClip("TEST_WITHOUT_CONSENT", 2.5)
            appendTenths(20)
            diagnostics.refresh()
            assertEquals(0, diagnostics.state.value.activeSession?.clipCount)
            diagnostics.setClipConsent(true)
            val source = diagnostics.bindFrameContext(com.xiuyin.app.pitch.PianoRecognitionFrame(0, 4.0, 5.0, emptySet(), emptySet(), emptyList(),
                com.xiuyin.app.pitch.RecognitionState.SILENCE, false, 0.0, FloatArray(88), FloatArray(88)))
            val sourceContext = mapOf("fixture" to "TEST DATA constant PCM", "fieldSessionId" to source.fieldSessionId.orEmpty(),
                "audioStreamId" to source.audioStreamId.toString(), "engineSessionId" to source.engineSessionId.toString())
            diagnostics.requestClip("TEST_WITH_CONSENT", 4.0, sourceContext)
            appendTenths(10)
            diagnostics.refresh()
            assertEquals(1, diagnostics.state.value.activeSession?.clipCount)
            diagnostics.endAudioStream()
            diagnostics.requestClip("STALE_PREVIOUS_STREAM", 4.0, sourceContext)
            diagnostics.recordFeedback(FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG, mapOf("fixture" to "TEST DATA"), null)
            diagnostics.refresh()
            assertEquals(1, diagnostics.state.value.activeSession?.clipCount)
            diagnostics.endSession("TEST_COMPLETE")
            val file = diagnostics.exportSession(session.id)
            ZipFile(file).use { zip ->
                val clips = zip.entries().asSequence().filter { it.name.endsWith(".wav") }.toList()
                assertEquals(1, clips.size)
                assertEquals(44L + 4.5 * 48000 * 2, clips.single().size.toDouble(), 0.0)
                val metadata = zip.entries().asSequence().single { it.name.startsWith("audio-clips/") && it.name.endsWith(".json") }
                val value = FieldJson.objectValue(zip.getInputStream(metadata).reader().readText())
                assertEquals(true, value["consentGranted"])
                assertEquals(3.0, value["actualPreSeconds"])
                assertEquals(1.5, value["actualPostSeconds"])
                assertNull(value["groundTruth"])
                assertEquals("UNLABELED", value["defaultLabel"])
            }
            assertTrue(File(context.filesDir, "field-sessions/${session.id}/app.log").readText().contains("USER_SAYS_SYSTEM_WRONG"))
        } finally {
            diagnostics.setClipConsent(false)
            if (diagnostics.state.value.activeSession?.id == session.id) diagnostics.endSession("TEST_CLEANUP")
            diagnostics.clearSessions(setOf(session.id))
        }
    }
    @Test fun realRecorderEntryPointsTriggerAutomaticClipsAndRejectOldFrameProvenance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = FieldDiagnostics.get(context)
        diagnostics.refresh()
        check(diagnostics.state.value.activeSession == null) { "不覆盖用户活动会话" }
        diagnostics.setClipConsent(true)
        val session = diagnostics.startSession("TEST DATA automatic diagnostic triggers", mapOf("inputSource" to "SYNTHETIC_STORAGE_TEST"))
        try {
            var first = 0L
            fun appendTenths(count: Int) { repeat(count) { val pcm = ShortArray(4800) { 123 }; diagnostics.appendPcm(pcm, pcm.size, first); first += pcm.size } }
            fun frame(index: Long, ambiguous: Boolean) = com.xiuyin.app.pitch.PianoRecognitionFrame(
                frameIndex = index, audioTimeSeconds = 4.0, availableAudioTimeSeconds = 4.5,
                onsetMidi = setOf(60, 72), activeMidi = setOf(60, 72), notes = emptyList(),
                state = com.xiuyin.app.pitch.RecognitionState.UNCERTAIN, clipped = false, computeMs = 1.0,
                rawOnsetScores = FloatArray(88) { .25f }, rawFrameScores = FloatArray(88) { .25f },
                ambiguousOctaveMidi = if (ambiguous) setOf(60, 72) else emptySet())
            diagnostics.recordEngine(com.xiuyin.app.pitch.RecognitionEngineSnapshot(status = com.xiuyin.app.pitch.RecognitionEngineStatus.RUNNING, sessionId = 777))
            appendTenths(45)
            val old = diagnostics.bindFrameContext(frame(125, true))
            diagnostics.recordRecognition(old)
            appendTenths(10)
            diagnostics.refresh()
            assertEquals("AMBIGUOUS entry point must save one actual authorized clip", 1, diagnostics.state.value.activeSession?.clipCount)
            diagnostics.recordRecognition(old.copy(frameIndex = 126))
            diagnostics.refresh()
            assertEquals(1, diagnostics.state.value.activeSession?.clipCount)

            diagnostics.endAudioStream(); first = 0
            diagnostics.recordEngine(com.xiuyin.app.pitch.RecognitionEngineSnapshot(status = com.xiuyin.app.pitch.RecognitionEngineStatus.RUNNING, sessionId = 778))
            appendTenths(45)
            diagnostics.recordRecognition(old)
            val current = diagnostics.bindFrameContext(frame(125, false))
            diagnostics.recordRecognition(current)
            diagnostics.recordFollowing(FieldFollowingTrace(current.frameIndex, current.audioTimeSeconds,
                expectedMeasure = "1", expectedBeat = 0.0, expectedNotes = setOf(60), detectedNotes = setOf(60, 72),
                previousScorePosition = "0", currentScorePosition = "0", decision = "WRONG", practiceState = "LOCKED",
                locked = true, reason = "TEST DATA: diagnostic lock event", confirmedDecision = true, decisionEventId = "test-lock",
                fieldSessionId = current.fieldSessionId, audioStreamId = current.audioStreamId, engineSessionId = current.engineSessionId))
            appendTenths(10)
            diagnostics.refresh()
            assertEquals("New lock entry point must save its own clip", 2, diagnostics.state.value.activeSession?.clipCount)
            diagnostics.endSession("TEST_COMPLETE")
            ZipFile(diagnostics.exportSession(session.id)).use { zip ->
                val metadata = zip.entries().asSequence().filter { it.name.startsWith("audio-clips/") && it.name.endsWith(".json") }
                    .map { FieldJson.objectValue(zip.getInputStream(it).reader().readText()) }.toList()
                assertEquals(setOf("AMBIGUOUS_OCTAVE", "SUSPECTED_FALSE_STOP"), metadata.map { it["reason"] }.toSet())
                val frames = zip.getInputStream(zip.getEntry("audio-events.jsonl")).reader().readLines()
                    .map(FieldJson::objectValue).filter { it["type"] == "RecognitionFrame" }
                assertEquals(3, frames.size) // two valid old-stream frames, one current frame; stale replay rejected.
                assertEquals(2, frames.map { it["audioStreamId"] }.toSet().size)
                assertTrue(zip.getInputStream(zip.getEntry("app.log")).reader().readText().contains("StaleFrameRejected"))
            }
        } finally {
            diagnostics.setClipConsent(false)
            if (diagnostics.state.value.activeSession?.id == session.id) diagnostics.endSession("TEST_CLEANUP")
            diagnostics.clearSessions(setOf(session.id))
        }
    }
}