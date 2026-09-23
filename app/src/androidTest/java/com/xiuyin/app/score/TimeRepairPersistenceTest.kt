package com.xiuyin.app.score

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA uses isolated UUID revision files, never rewrites imported user originals or metadata. */
class TimeRepairPersistenceTest {
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val revisionRoot get() = File(application.filesDir, "score-revisions")
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun idle(model: ScoreWorkbenchViewModel) = runBlocking { withTimeout(10_000) { model.state.first { !it.busy } } }

    @Test fun explicitFillKeepsAnnotationsAndEventsAndSurvivesNewViewModelWithUndoRedo() {
        val id = UUID.randomUUID().toString()
        val original = score()
        val originalBytes = ScoreJsonCodec.encode(original)
        val event = original.parts.single().measures.first().events.first().id
        val verified = setOf(event)
        val fingerings = mapOf(event to 3)
        val revisions = ScoreRevisionStore(revisionRoot)
        revisions.load(id, original)
        val annotated = revisions.save(id, 0L, original, verified, fingerings)
        val stores = mutableListOf<ViewModelStore>()
        fun model(): ScoreWorkbenchViewModel {
            lateinit var result: ScoreWorkbenchViewModel
            main {
                val store = ViewModelStore().also { stores += it }
                result = ScoreWorkbenchViewModel(application)
                store.put("test-data-time-repair", result)
                result.open(metadata(id, original), original)
            }
            idle(result); assertNull(result.state.value.error)
            return result
        }
        try {
            val first = model()
            main { first.fillMissingScoreTime(id, annotated.generation, 2, 4) }
            idle(first); assertNull(first.state.value.error)
            val saved = requireNotNull(first.state.value.revision)
            assertEquals(annotated.generation + 1, saved.generation)
            assertEquals(listOf(ScoreTime(2, 4), ScoreTime(2, 4), ScoreTime(3, 4), ScoreTime(6, 8)), saved.current.parts.single().measures.map { it.time })
            original.parts.single().measures.zip(saved.current.parts.single().measures).forEach { (before, after) ->
                assertEquals("仅拍号字段可改变", before.copy(time = after.time), after)
            }
            assertEquals(verified, saved.verifiedEventIds)
            assertEquals(fingerings, saved.fingerings)
            assertArrayEquals(originalBytes, ScoreJsonCodec.encode(original))
            main { stores.forEach { it.clear() }; stores.clear() }
            val reopened = model()
            assertEquals(saved, reopened.state.value.revision)
            main { reopened.undo() }
            idle(reopened); assertNull(reopened.state.value.error)
            val undone = requireNotNull(reopened.state.value.revision)
            assertEquals(original, undone.current)
            assertEquals(verified, undone.verifiedEventIds)
            assertEquals(fingerings, undone.fingerings)
            assertEquals(2, ScoreStructureTools.missingTimeMeasures(undone.current).size)
            main { reopened.redo() }
            idle(reopened); assertNull(reopened.state.value.error)
            val cold = ScoreRevisionStore(revisionRoot).load(id, original)
            assertEquals(saved.current, cold.current)
            assertEquals(verified, cold.verifiedEventIds)
            assertEquals(fingerings, cold.fingerings)
            assertArrayEquals(originalBytes, ScoreJsonCodec.encode(original))
        } finally {
            main { stores.forEach { it.clear() } }
            revisions.delete(id)
        }
    }

    @Test fun staleRevisionAndOtherScoreRequestsAreRejectedWithoutWritingTheCurrentScore() {
        val firstId = UUID.randomUUID().toString()
        val otherId = UUID.randomUUID().toString()
        val original = score()
        val store = ViewModelStore()
        lateinit var model: ScoreWorkbenchViewModel
        try {
            main { model = ScoreWorkbenchViewModel(application); store.put("test-data-stale-dialog", model); model.open(metadata(firstId, original), original) }
            idle(model); assertNull(model.state.value.error)
            main { model.verifySelected() }
            idle(model); assertNull(model.state.value.error)
            val confirmed = requireNotNull(model.state.value.revision)
            assertEquals(1L, confirmed.generation)
            main { model.fillMissingScoreTime(firstId, 0, 2, 4) }
            idle(model)
            assertTrue(model.state.value.error.orEmpty().contains("旧拍号窗口未应用"))
            assertEquals(confirmed, model.state.value.revision)
            assertEquals(confirmed, ScoreRevisionStore(revisionRoot).load(firstId, original))
            main { model.open(metadata(otherId, original), original) }
            idle(model); assertNull(model.state.value.error)
            val otherBefore = requireNotNull(model.state.value.revision)
            main { model.fillMissingScoreTime(firstId, otherBefore.generation, 3, 4) }
            idle(model)
            assertTrue(model.state.value.error.orEmpty().contains("旧拍号窗口未应用"))
            assertEquals(otherId, model.state.value.scoreId)
            assertEquals(otherBefore, model.state.value.revision)
            assertEquals(otherBefore, ScoreRevisionStore(revisionRoot).load(otherId, original))
            assertEquals(confirmed, ScoreRevisionStore(revisionRoot).load(firstId, original))
        } finally {
            main { store.clear() }
            ScoreRevisionStore(revisionRoot).delete(firstId)
            ScoreRevisionStore(revisionRoot).delete(otherId)
        }
    }

    private fun metadata(id: String, score: DigitalScore) = ScoreMetadata(
        scoreId = id, sourceId = score.source.sha256, originalTitle = "TEST DATA 补拍号持久化", customTitle = null,
        temporaryTitle = null, aliases = emptyList(), tags = emptyList(), sourceFileName = null, composer = null,
        workNumber = null, importedAt = 1L, lastPracticedAt = null, favorite = false, pageCount = null,
        timeSignatures = emptyList(), keySignatures = emptyList(), sourceType = "MusicXML", recognitionComplete = false,
        hasReviewIssues = true, firstMeasureSummary = null)

    private fun score(): DigitalScore {
        val times = listOf(null, ScoreTime(4, 3), ScoreTime(3, 4), ScoreTime(6, 8))
        val measures = times.mapIndexed { index, time ->
            val note = ScoreEvent("n$index", BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("C", 0, 4),
                1, "1", false, "quarter", 0, emptySet(), index, 0, confidence = 0.7)
            val rest = note.copy(id = "r$index", pitch = null, offset = BeatFraction.of(1))
            ScoreMeasure("m$index", (index + 1).toString(), time, 0, listOf(ScoreClef(1, "G", 2)), listOf(note, rest), false)
        }
        val hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        return DigitalScore(hash, "TEST DATA 续页与合法变拍号", listOf(ScorePart("p1", "钢琴", measures)),
            emptyList(), ScoreSource("MusicXML", hash, 1))
    }
}
