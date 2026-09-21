package com.xiuyin.app.score

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA: real isolated files only; original sources and user databases are never edited. */
@RunWith(AndroidJUnit4::class)
class ScoreRevisionStoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var store: ScoreRevisionStore
    private lateinit var original: DigitalScore
    private lateinit var originalBytes: ByteArray
    private lateinit var scoreId: String

    @Before fun createIsolatedStore() {
        root = File(instrumentation.targetContext.cacheDir, "score-revisions-test-${UUID.randomUUID()}")
        originalBytes = instrumentation.context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
        original = MusicXmlParser().parse(originalBytes)
        scoreId = UUID.randomUUID().toString()
        store = ScoreRevisionStore(root)
    }

    @After fun removeOnlyTestFiles() {
        check(root.parentFile == instrumentation.targetContext.cacheDir && root.name.startsWith("score-revisions-test-"))
        if (Files.isSymbolicLink(root.toPath())) Files.delete(root.toPath())
        else {
            root.listFiles()?.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) Files.delete(child.toPath()) else check(child.deleteRecursively())
            }
            Files.deleteIfExists(root.toPath())
        }
    }

    @Test fun jsonRoundTripPreservesAllCoreFieldsAndExactFractions() {
        val first = event()
        val annotated = changeEvent(original, first.copy(offset = BeatFraction.of(1, 3), duration = BeatFraction.of(7, 6),
            ties = setOf("stop", "start"), sourceIndex = -1, sourceLine = -1, confidence = 0.8125,
            sourceBox = SourceBox(2, 14.25, 28.5, 9.75, 18.0)))
            .copy(title = "TEST DATA · 引号\"与\\路径\n第二行", issues = listOf(ScoreIssue("manual-test", "待确认", ScoreIssueSeverity.ERROR,
                original.parts.first().measures.first().id, first.id)))
        val encoded = ScoreJsonCodec.encode(annotated)
        assertEquals(annotated, ScoreJsonCodec.decode(encoded))
        assertArrayEquals(encoded, ScoreJsonCodec.encode(ScoreJsonCodec.decode(encoded)))
    }

    @Test fun firstLoadDoesNotCreateRevisionOrMutateOriginal() {
        val snapshot = store.load(scoreId, original)
        assertEquals(0L, snapshot.generation)
        assertEquals(original, snapshot.current)
        assertFalse(snapshot.canUndo || snapshot.canRedo)
        assertFalse(revisionFile().exists())
        assertEquals(original, MusicXmlParser().parse(originalBytes))
    }

    @Test fun saveAndColdReopenPersistNotesVerificationAndFingering() {
        store.load(scoreId, original)
        val changed = changed(1)
        val saved = store.save(scoreId, 0, changed, setOf(event().id), mapOf(event().id to 3))
        assertEquals(1L, saved.generation)
        assertTrue(saved.canUndo)
        assertFalse(saved.canRedo)
        assertEquals(saved, ScoreRevisionStore(root).load(scoreId, original))
        assertEquals(original.source.sha256, saved.sourceId)
        assertEquals(original, MusicXmlParser().parse(originalBytes))
    }

    @Test fun undoRedoRestoreWholeStateAndIncrementGeneration() {
        store.load(scoreId, original)
        val edited = store.save(scoreId, 0, changed(1), setOf(event().id), mapOf(event().id to 4))
        val undone = store.undo(scoreId, original, edited.generation)
        assertEquals(2L, undone.generation)
        assertEquals(original, undone.current)
        assertTrue(undone.verifiedEventIds.isEmpty() && undone.fingerings.isEmpty())
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)
        val redone = ScoreRevisionStore(root).redo(scoreId, original, undone.generation)
        assertEquals(3L, redone.generation)
        assertEquals(edited.current, redone.current)
        assertEquals(edited.verifiedEventIds, redone.verifiedEventIds)
        assertEquals(edited.fingerings, redone.fingerings)
    }

    @Test fun identicalSaveDoesNotChangeGenerationOrDestroyRedo() {
        store.load(scoreId, original)
        val saved = store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        val before = revisionFile().readBytes()
        assertEquals(saved, store.save(scoreId, 1, changed(1), emptySet(), emptyMap()))
        assertArrayEquals(before, revisionFile().readBytes())
        val undone = store.undo(scoreId, original, 1)
        assertEquals(undone, store.save(scoreId, 2, original, emptySet(), emptyMap()))
        assertTrue(store.load(scoreId, original).canRedo)
        val replaced = store.save(scoreId, 2, changed(2), emptySet(), emptyMap())
        assertFalse(replaced.canRedo)
        assertThrows(ScoreRevisionException::class.java) { store.redo(scoreId, original, replaced.generation) }
    }

    @Test fun staleGenerationCannotOverwriteAnotherInstanceAndSaveNeedsBinding() {
        val second = ScoreRevisionStore(root)
        assertThrows(ScoreRevisionException::class.java) { second.save(scoreId, 0, changed(1), emptySet(), emptyMap()) }
        store.load(scoreId, original)
        second.load(scoreId, original)
        val saved = store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        assertThrows(ScoreRevisionConflictException::class.java) { second.save(scoreId, 0, changed(2), emptySet(), emptyMap()) }
        assertThrows(ScoreRevisionConflictException::class.java) { second.undo(scoreId, original, 0) }
        assertEquals(saved, second.load(scoreId, original))
    }

    @Test fun competingStoresPermitExactlyOneCompareAndSwap() {
        val stores = List(2) { ScoreRevisionStore(root).also { it.load(scoreId, original) } }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = stores.mapIndexed { index, candidate -> pool.submit(Callable {
                try { candidate.save(scoreId, 0, changed(index + 1), emptySet(), emptyMap()); true }
                catch (_: ScoreRevisionConflictException) { false }
            }) }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertEquals(1L, store.load(scoreId, original).generation)
        } finally { pool.shutdownNow() }
    }

    @Test fun sourceChangesDuplicateIdsAndNonFiniteBoxesCannotOverwriteGoodRevision() {
        store.load(scoreId, original)
        store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        val before = revisionFile().readBytes()
        val variants = listOf(original.copy(id = "b".repeat(64)), original.copy(source = original.source.copy(byteCount = original.source.byteCount + 1)),
            changeEvent(original, event().copy(sourceBox = SourceBox(0, Double.NaN, 0.0, 1.0, 1.0))),
            original.copy(parts = original.parts.mapIndexed { index, part -> if (index != 0) part else part.copy(measures = part.measures.mapIndexed { m, measure ->
                if (m != 0) measure else measure.copy(events = measure.events + measure.events.first())
            }) }))
        variants.forEach { invalid ->
            assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 1, invalid, emptySet(), emptyMap()) }
            assertArrayEquals(before, revisionFile().readBytes())
        }
        assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 1, changed(2), setOf("unknown"), emptyMap()) }
        assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 1, changed(2), emptySet(), mapOf(event().id to 6)) }
    }

    @Test fun corruptionUnknownSchemaAndChangedOriginalAreRejectedWithoutRebuild() {
        store.load(scoreId, original)
        store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        val valid = revisionFile().readBytes()
        val corruptions = listOf("truncated TEST DATA".toByteArray(),
            JSONObject(String(valid, Charsets.UTF_8)).put("schemaVersion", 999).toString().toByteArray(),
            JSONObject(String(valid, Charsets.UTF_8)).put("payloadSha256", "0".repeat(64)).toString().toByteArray())
        corruptions.forEach { corrupt ->
            revisionFile().writeBytes(corrupt)
            assertThrows(ScoreRevisionException::class.java) { store.load(scoreId, original) }
            assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 1, changed(2), emptySet(), emptyMap()) }
            assertArrayEquals(corrupt, revisionFile().readBytes())
        }
        revisionFile().writeBytes(valid)
        assertThrows(ScoreRevisionException::class.java) { store.load(scoreId, original.copy(title = "different parse")) }
        assertArrayEquals(valid, revisionFile().readBytes())
    }

    @Test fun historyHasTwentyStepCapAndReportsTruncation() {
        var state = store.load(scoreId, original)
        repeat(25) { index -> state = store.save(scoreId, state.generation, changed(index + 1), emptySet(), emptyMap()) }
        assertTrue(state.historyTruncated)
        var undone = 0
        while (state.canUndo) { state = store.undo(scoreId, original, state.generation); undone++ }
        assertEquals(ScoreRevisionStore.MAX_HISTORY, undone)
        assertEquals(changed(5), state.current)
        assertTrue(state.canRedo)
        assertEquals(45L, state.generation)
    }

    @Test fun totalBudgetRefusesSaveAndLeavesExistingBytes() {
        store.load(scoreId, original)
        store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        val before = revisionFile().readBytes()
        RandomAccessFile(File(root, "quota-test.bin"), "rw").use { it.setLength(ScoreRevisionStore.MAX_TOTAL_BYTES) }
        assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 1, changed(2), emptySet(), emptyMap()) }
        assertArrayEquals(before, revisionFile().readBytes())
    }

    @Test fun explicitDeleteRemovesOnlyChosenRevisionIncludingCorruptFile() {
        val secondId = UUID.randomUUID().toString()
        store.load(scoreId, original); store.load(secondId, original)
        store.save(scoreId, 0, changed(1), emptySet(), emptyMap())
        val other = store.save(secondId, 0, changed(2), emptySet(), emptyMap())
        revisionFile().writeText("corrupt TEST DATA")
        assertTrue(store.delete(scoreId))
        assertFalse(store.delete(scoreId))
        assertEquals(other, store.load(secondId, original))
        assertEquals(0L, store.load(scoreId, original).generation)
    }

    @Test fun pathsSymlinksAndNonRegularFilesAreNeverFollowedOrReplaced() {
        listOf("../outside", "", "not-a-uuid", "/tmp/score").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { store.load(bad, original) }
        }
        store.load(scoreId, original)
        val outside = File(root, "outside-sentinel").apply { writeText("sentinel TEST DATA") }
        Files.createSymbolicLink(revisionFile().toPath(), outside.toPath())
        assertThrows(ScoreRevisionException::class.java) { store.load(scoreId, original) }
        assertThrows(ScoreRevisionException::class.java) { store.save(scoreId, 0, changed(1), emptySet(), emptyMap()) }
        assertThrows(ScoreRevisionException::class.java) { store.delete(scoreId) }
        assertEquals("sentinel TEST DATA", outside.readText())
    }

    @Test fun jsonRejectsUnknownFieldsBadTypesAndOversizedOrDeepInput() {
        val encoded = ScoreJsonCodec.encode(original)
        assertThrows(IllegalArgumentException::class.java) { ScoreJsonCodec.decode(JSONObject(String(encoded)).put("unexpected", true).toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ScoreJsonCodec.decode(JSONObject(String(encoded)).put("schemaVersion", "1").toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ScoreJsonCodec.decode(ByteArray(ScoreJsonCodec.MAX_SCORE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreJsonCodec.decode(("[".repeat(40) + "]".repeat(40)).toByteArray()) }
    }

    private fun event() = original.parts.first().measures.first().events.first()
    private fun changed(index: Int) = changeEvent(original, event().copy(voice = "test-voice-$index"))
    private fun changeEvent(score: DigitalScore, replacement: ScoreEvent) = score.copy(parts = score.parts.map { part ->
        part.copy(measures = part.measures.map { measure -> measure.copy(events = measure.events.map { if (it.id == replacement.id) replacement else it }) })
    })
    private fun revisionFile() = File(root, "$scoreId.json")
}