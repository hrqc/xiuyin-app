package com.xiuyin.app.field

import com.xiuyin.app.practice.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** TEST DATA: optional diagnosis must round-trip without changing the existing ZIP contract. */
class ScoreAssistanceExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun optionalThreeLayerEvidenceCoexistsWithLegacyRowsAndRawPendingInSameZip() {
        val store = FieldSessionStore(File(temporary.root, "sessions"), File(temporary.root, "exports"))
        val session = store.start("TEST DATA score assistance", mapOf("appVersion" to "test"), mapOf("sampleRate" to 48000))
        val frame = ScoreAssistanceTraceTest.frame(0, setOf(60, 72)).copy(pendingOnsetMidi = setOf(64))
        val context = ScoreAssistanceContext("score", 5, PracticeHand.RIGHT, 0, 3, CompanionMode.STOP_ON_ERROR,
            FollowerContextSnapshot(0, FollowerPositionState.INITIAL_UNLOCATED, setOf(60), listOf("target"), 0, 0.0))
        val trace = ScoreAssistanceTrace.observe(frame, context).withDecision(FollowerUpdate(0, decision = PracticeDecision.UNCERTAIN,
            detectedMidi = setOf(60, 72), reason = "TEST DATA ambiguity"))
        store.append("score-following.jsonl", mapOf("frameIndex" to 0, "decision" to "UNCERTAIN", "scoreAssistance" to trace.toLogRecord()))
        store.append("score-following.jsonl", mapOf("frameIndex" to 1, "decision" to "UNCERTAIN"))
        store.append("audio-events.jsonl", mapOf("type" to "RecognitionFrame", "frameIndex" to 0, "pendingOnsetMidi" to listOf(64)))
        store.end("TEST_COMPLETE")
        ZipFile(store.export(session.id)).use { archive ->
            FieldSessionStore.REQUIRED_FILES.forEach { assertNotNull(archive.getEntry(it)) }
            val rows = archive.getInputStream(archive.getEntry("score-following.jsonl")).reader().readLines().map(FieldJson::objectValue)
            assertEquals(2, rows.size)
            assertFalse(rows[1].containsKey("scoreAssistance"))
            val nested = rows[0]["scoreAssistance"] as Map<*, *>
            assertEquals(false, nested["assistanceAppliedToDecision"])
            assertEquals(false, nested["countsAsMusicalEvidence"])
            val audio = nested["independentAudio"] as Map<*, *>
            assertEquals(listOf(60L, 72L), audio["onsetMidi"])
            assertEquals(listOf(64L), audio["pendingOnsetMidi"])
            assertEquals(88, (audio["rawOnsetScores"] as List<*>).size)
            val relation = nested["scoreRelation"] as Map<*, *>
            assertEquals(listOf(72L), relation["observedExtraMidi"])
            assertEquals(false, relation["positionPriorEligible"])
            assertEquals("UNCERTAIN", (nested["finalDecision"] as Map<*, *>)["decision"])
            val raw = FieldJson.objectValue(archive.getInputStream(archive.getEntry("audio-events.jsonl")).reader().readText().trim())
            assertEquals(listOf(64L), raw["pendingOnsetMidi"])
            val summary = FieldJson.objectValue(archive.getInputStream(archive.getEntry("session-summary.json")).reader().readText())
            assertEquals(false, summary["statisticsAreAccuracy"])
        }
    }
}
