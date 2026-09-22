package com.xiuyin.app.practice

import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.pitch.PianoRecognitionEngine
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePart
import com.xiuyin.app.score.ScoreSource
import com.xiuyin.app.score.ScoreTime
import com.xiuyin.app.score.ScoreTimelineCompiler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual-music gate requires external PCM and independent labels; silence has a separate test name. */
class PracticePcmIntegrationTest {
    @Test fun productionPcmToFollowerAndDurableReport() = runReplay(requireActual = true)
    @Test fun silenceNeverBecomesPracticeAndEngineStops() = runReplay(requireActual = false)
    private fun runReplay(requireActual: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val wavPath = if (requireActual) args.getString("practiceWav") else null
        val expectationPath = if (requireActual) args.getString("practiceTimeline") else null
        val actualReplay = wavPath != null && expectationPath != null
        require(!requireActual || actualReplay) { "音乐门禁缺少 practiceWav/practiceTimeline，禁止用静音冒充实际音乐验收" }
        require((wavPath == null) == (expectationPath == null)) { "真实回放必须同时提供 WAV 与独立预期时间线" }
        val expectation = expectationPath?.let { JSONObject(authorizedFile(it).readText()) }
        val wav = wavPath?.let(::authorizedFile)
        val pcm = wav?.let(::readPcm48kMono) ?: ShortArray(72000)
        val hash = wav?.let { sha(it.readBytes()) }
        if (expectation != null) assertEquals("录音必须与独立标注相同", expectation.getString("sha256"), hash)
        val groups = expectation?.getJSONArray("groups")
        val pitches = if (groups != null) List(groups.length()) { i ->
            val notes = groups.getJSONObject(i).getJSONArray("expectedMidi")
            List(notes.length()) { notes.getInt(it) }.toSet()
        } else listOf(setOf(60))
        val sourceSha = hash ?: "0".repeat(64)
        val score = referenceScore(pitches, sourceSha)
        val good = PracticeSession(UUID.randomUUID().toString(), score.title, 0, ScoreTimelineCompiler.compile(score), PracticeConfig(), 1000, "bundled production model; external PCM replay")
        // A separate stop-on-error session uses the unchanged, independently labelled correct score.
        // No lock alone is insufficient: completeness, correct groups and missing notes are checked too.
        val correctStop = PracticeSession(UUID.randomUUID().toString(), score.title, 0, ScoreTimelineCompiler.compile(score),
            PracticeConfig(CompanionMode.STOP_ON_ERROR), 1000, "bundled production model; external PCM replay")
        val correctStopLocks = AtomicInteger()
        val correctStopTransitions = JSONArray()
        // The controlled wrong-score scenario expects the second independent reference chord first.
        // Actual first-note PCM remains untouched; the next recorded note then provides correction.
        val correction = if (actualReplay && pitches.size > 1) PracticeSession(UUID.randomUUID().toString(), "TEST DATA 故意错位后纠正", 0,
            ScoreTimelineCompiler.compile(referenceScore(pitches.drop(1), sourceSha)), PracticeConfig(CompanionMode.STOP_ON_ERROR), 1000, "bundled production model; external PCM replay") else null
        val lockCount = AtomicInteger(); val recoveryCount = AtomicInteger(); val frames = AtomicInteger()
        val output = File(context.getExternalFilesDir(null), if (requireActual) "practice-replay-result.json" else "practice-silence-smoke.json")
        val events = JSONArray()
        val reportRoot = File(context.cacheDir, "practice-integration-${UUID.randomUUID()}")
        val engine = PianoRecognitionEngine(context, onFrame = { frame ->
            val observation = observe(frame)
            val update = good.accept(observation)
            val previousStopPhase = correctStop.phase
            val previousStopWrong = correctStop.wrong
            val stopUpdate = correctStop.accept(observation)
            if (previousStopPhase != CompanionPhase.LOCKED && correctStop.phase == CompanionPhase.LOCKED) correctStopLocks.incrementAndGet()
            if (previousStopPhase != correctStop.phase || previousStopWrong != correctStop.wrong) {
                correctStopTransitions.put(JSONObject().put("frame", frame.frameIndex).put("audioSeconds", frame.audioTimeSeconds)
                    .put("fromPhase", previousStopPhase.name).put("toPhase", correctStop.phase.name)
                    .put("decision", stopUpdate.decision.name).put("wrong", correctStop.wrong).put("missed", correctStop.missed)
                    .put("rawOnsetMidi", JSONArray(frame.onsetMidi.toList())).put("reason", stopUpdate.reason))
            }
            val wasLocked = correction?.phase == CompanionPhase.LOCKED
            val corrected = correction?.accept(observation)
            if (!wasLocked && correction?.phase == CompanionPhase.LOCKED) lockCount.incrementAndGet()
            if (wasLocked && corrected?.decision == PracticeDecision.CORRECT && correction.phase != CompanionPhase.LOCKED) recoveryCount.incrementAndGet()
            if (update.confirmedInput || frame.onsetMidi.isNotEmpty()) events.put(JSONObject().put("frame", frame.frameIndex).put("audioSeconds", frame.audioTimeSeconds)
                .put("rawOnsetMidi", JSONArray(frame.onsetMidi.toList())).put("uncertainMidi", JSONArray(frame.uncertainMidi.toList())).put("ambiguousOctaveMidi", JSONArray(frame.ambiguousOctaveMidi.toList()))
                .put("decision", update.decision.name).put("cursor", update.cursor).put("reason", update.reason)
                .put("stopDecision", stopUpdate.decision.name).put("stopCursor", stopUpdate.cursor)
                .put("stopPhase", correctStop.phase.name).put("stopReason", stopUpdate.reason))
            frames.incrementAndGet()
        })
        try {
            engine.start()
            awaitEngine(engine) { engine.snapshot.status != RecognitionEngineStatus.LOADING }
            var offset = 0
            var target = minOf(60000, pcm.size)
            while (offset < pcm.size) {
                while (offset < target) {
                    val count = minOf(4096, target - offset)
                    engine.submitPcm(pcm.copyOfRange(offset, offset + count), count, offset.toLong())
                    offset += count
                }
                val safe16k = (offset - 64).coerceAtLeast(0) / 3
                val windows = if (safe16k < 17920) 0 else 1 + (safe16k - 17920) / 8192
                val expectedFrames = if (windows == 0) 0 else 32 + (windows - 1) * 16
                awaitEngine(engine) { engine.snapshot.frameCount >= expectedFrames }
                target = minOf(offset + 24576, pcm.size)
            }
            val report = good.report(2000)
            val stopReport = correctStop.report(2000)
            val stopResult = JSONObject().put("mode", CompanionMode.STOP_ON_ERROR.name)
                .put("correct", stopReport.correct).put("wrong", stopReport.wrong).put("missed", stopReport.missed)
                .put("completed", stopReport.completed).put("confirmedInputs", stopReport.confirmedInputs)
                .put("uncertainFrames", stopReport.uncertainFrames).put("lockedTransitions", correctStopLocks.get())
                .put("finalPhase", correctStop.phase.name).put("transitions", correctStopTransitions)
                .put("evidence", JSONArray(stopReport.evidence.map { item ->
                    JSONObject().put("decision", item.decision.name).put("targetIndex", item.targetIndex)
                        .put("measureIndex", item.measureIndex).put("audioSeconds", item.audioTimeSeconds)
                        .put("expectedMidi", JSONArray(item.expectedMidi.toList())).put("detectedMidi", JSONArray(item.detectedMidi.toList()))
                        .put("evidenceScore", item.evidenceScore).put("reason", item.reason)
                }))
            val result = JSONObject().put("scope", if (actualReplay) "actual website PCM -> production asynchronous engine -> follower -> report; not microphone" else "synthetic silence smoke; not musical accuracy")
                .put("sha256", hash ?: JSONObject.NULL).put("samples", pcm.size).put("frames", frames.get()).put("expectedGroups", pitches.size)
                .put("correct", report.correct).put("wrong", report.wrong).put("missed", report.missed).put("completed", report.completed)
                .put("lockedTransitions", lockCount.get()).put("correctionTransitions", recoveryCount.get()).put("events", events)
                .put("correctReferenceStopOnError", stopResult)
            if (actualReplay) {
                val store = PracticeReportStore(reportRoot)
                store.save(report)
                val restored = PracticeReportStore(reportRoot).load(report.scoreId).single()
                result.put("reportRoundTripEqual", report == restored)
                store.save(stopReport)
                val restoredStop = PracticeReportStore(reportRoot).load(stopReport.scoreId).single()
                stopResult.put("reportRoundTripEqual", stopReport == restoredStop)
                output.writeText(result.toString(2))
                assertTrue("真实击键未完整跟随；请查看 practice-replay-result.json", report.completed)
                assertEquals(pitches.size, report.correct); assertEquals(0, report.wrong); assertEquals(0, report.missed)
                assertTrue("正确参考谱的错音即停未完整跟随；UNCERTAIN 不等于识别正确", stopReport.completed)
                assertEquals(pitches.size, stopReport.correct); assertEquals(0, stopReport.wrong); assertEquals(0, stopReport.missed)
                assertEquals("正确参考谱出现误锁停", 0, correctStopLocks.get())
                assertEquals(stopReport, restoredStop)
                assertTrue("故意错位的目标未触发锁停", lockCount.get() > 0)
                assertTrue("后续真实目标音未解除锁定", recoveryCount.get() > 0)
                assertEquals(report, restored)
            } else {
                output.writeText(result.toString(2))
                assertEquals(0, report.confirmedInputs); assertEquals(0, report.wrong); assertFalse(report.completed)
                assertEquals(0, stopReport.confirmedInputs); assertEquals(0, stopReport.wrong); assertEquals(0, correctStopLocks.get()); assertFalse(stopReport.completed)
            }
        } finally {
            engine.stop(); assertEquals(RecognitionEngineStatus.IDLE, engine.snapshot.status); engine.close()
            // Only this random test-owned cache directory is removed; no app library or reports are touched.
            if (reportRoot.exists()) { check(reportRoot.canonicalFile.parentFile == context.cacheDir.canonicalFile); reportRoot.deleteRecursively() }
        }
    }
    private fun observe(frame: PianoRecognitionFrame): RecognitionObservation {
        val notes = frame.notes.associateBy { it.midi }
        fun entry(midi: Int) = notes[midi]?.let { ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) }
        return RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds, frame.onsetMidi.mapNotNull(::entry), frame.activeMidi.mapNotNull(::entry),
            clipped = frame.clipped, uncertainMidi = frame.uncertainMidi, ambiguousOctaveMidi = frame.ambiguousOctaveMidi)
    }
    private fun referenceScore(groups: List<Set<Int>>, source: String): DigitalScore {
        val measures = groups.mapIndexed { index, pitches ->
            ScoreMeasure("m$index", (index + 1).toString(), ScoreTime(1, 4), 0, listOf(ScoreClef(1, "G", 2)), pitches.sorted().mapIndexed { noteIndex, midi ->
                ScoreEvent("e${index}_$noteIndex", BeatFraction.ZERO, BeatFraction.of(1), ScoreEditing.pitchFromMidi(midi), 1, "1", noteIndex > 0, "quarter", 0, emptySet(), index * 10 + noteIndex, 0)
            }, false)
        }
        return DigitalScore("test-reference", "TEST DATA 独立网站事件参考谱", listOf(ScorePart("p", "Piano", measures)), emptyList(), ScoreSource("independent-web-action-reference", source, 1))
    }
    private fun authorizedFile(path: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(path).canonicalFile
        val allowed = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { it.canonicalPath + File.separator }
        require(allowed.any { file.path.startsWith(it) } && file.isFile && file.length() in 1..64L * 1024 * 1024) { "测试文件必须位于此App私有测试目录且不超过64MiB" }
        return file
    }
    private fun readPcm48kMono(file: File): ShortArray {
        val bytes = file.readBytes(); val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12; var validFormat = false
        while (offset + 8 <= bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII); val length = input.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            if (type == "fmt ") { require(length >= 16); validFormat = input.getShort(offset + 8).toInt() == 1 && input.getShort(offset + 10).toInt() == 1 && input.getInt(offset + 12) == 48000 && input.getShort(offset + 22).toInt() == 16 }
            if (type == "data") { require(validFormat && length % 2 == 0); return ShortArray(length / 2) { input.getShort(offset + 8 + it * 2) } }
            offset += 8 + length + length % 2
        }
        error("缺少有效PCM data区块")
    }
    private fun awaitEngine(engine: PianoRecognitionEngine, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30000
        while (!predicate()) {
            check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "模型回放等待超时：${engine.snapshot}" }
            Thread.sleep(10)
        }
        check(engine.snapshot.status != RecognitionEngineStatus.ERROR) { engine.snapshot.message }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}



