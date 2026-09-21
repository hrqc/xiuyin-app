package com.xiuyin.app.score

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite in a uniquely named test DB. Never writes the user's score_metadata.db or xiuyin.db. */
@RunWith(AndroidJUnit4::class)
class ScoreMetadataDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var database: ScoreMetadataDatabase

    @Before fun openIsolatedDatabase() {
        name = "metadata-test-${UUID.randomUUID()}.db"
        database = ScoreMetadataDatabase(context, name)
    }
    @After fun removeOnlyTestDatabase() {
        database.close()
        check(name.startsWith("metadata-test-") && name.endsWith(".db"))
        context.deleteDatabase(name)
    }

    @Test fun unnamedImportUsesDurableCounterAndPersistsAfterReopen() {
        val first = database.ensureImported(source(1), importedAt = 10)
        assertEquals("未命名乐谱 001", first.displayTitle)
        UUID.fromString(first.scoreId)
        assertNull(first.lastPracticedAt)
        assertNull(first.pageCount)
        database.close()
        database = ScoreMetadataDatabase(context, name)
        assertEquals(first, database.get(first.scoreId))
        val second = database.ensureImported(source(2), importedAt = 20)
        assertEquals("未命名乐谱 002", second.displayTitle)
        assertNotEquals(first.scoreId, second.scoreId)
        assertEquals(2, database.list().size)
    }

    @Test fun namedImportAndRenamingNeverReplaceOriginalOrConsumeUnnamedNumber() {
        val named = database.ensureImported(source(1), ScoreImportMetadata(originalTitle = " 原谱题名 ", composer = "作者"), 10)
        assertNull(named.temporaryTitle)
        val edited = database.rename(named.scoreId, " 周三左手练习 ")
        assertEquals("周三左手练习", edited.displayTitle)
        assertEquals("原谱题名", edited.originalTitle)
        assertEquals("原谱题名", database.rename(named.scoreId, "  ").displayTitle)
        assertEquals("未命名乐谱 001", database.ensureImported(source(2), importedAt = 20).displayTitle)
    }

    @Test fun idempotentReimportPreservesAllUserMetadataAndOriginalTimestamp() {
        val first = database.ensureImported(source(1), ScoreImportMetadata(sourceFileName = "IMG_001.xml"), 10)
        database.update(first.scoreId, "作业", listOf("599-12"), listOf("难点"))
        database.setFavorite(first.scoreId, true)
        val expected = database.get(first.scoreId)
        val again = database.ensureImported(source(1), ScoreImportMetadata(originalTitle = "不能覆盖"), 999)
        assertEquals(expected, again)
        assertEquals(1, database.list().size)
        assertNull(again.originalTitle)
        assertEquals(10L, again.importedAt)
        assertTrue(again.favorite)
    }

    @Test fun editWritesAreAtomicAndDoNotLoseFavoriteOrPracticeSummary() {
        val first = database.ensureImported(source(1), importedAt = 10)
        database.setFavorite(first.scoreId, true)
        database.markPracticed(first.scoreId, 50)
        val edited = database.update(first.scoreId, " 周三 ", listOf(" 作业 ", "作业"), listOf(" 左手 "))
        assertTrue(edited.favorite)
        assertEquals(50L, edited.lastPracticedAt)
        assertEquals(listOf("作业"), edited.aliases)
        assertThrows(IllegalArgumentException::class.java) { database.update(first.scoreId, "changed", listOf("x".repeat(101)), listOf("bad")) }
        assertEquals(edited, database.get(first.scoreId))
        assertEquals(50L, database.markPracticed(first.scoreId, 40).lastPracticedAt)
    }

    @Test fun sameDisplayNameUsesDistinctUuidAndSearchesAfterReopen() {
        val first = database.ensureImported(source(1), importedAt = 10)
        val second = database.ensureImported(source(2), importedAt = 20)
        database.update(first.scoreId, "练习曲1", listOf("599-12"), listOf("本周"))
        database.update(second.scoreId, "练习曲1", listOf("周三作业"), listOf("左手"))
        database.close()
        database = ScoreMetadataDatabase(context, name)
        assertEquals(2, queryScoreMetadata(database.list(), "练习曲1").size)
        assertEquals(first.scoreId, queryScoreMetadata(database.list(), "599").single().scoreId)
        assertEquals(second.scoreId, queryScoreMetadata(database.list(), tag = "左手").single().scoreId)
    }

    @Test fun deletionTombstoneBlocksScanResurrectionAndRetriesUntilFileConfirmation() {
        val first = database.ensureImported(source(1), importedAt = 10)
        database.rename(first.scoreId, "已命名")
        database.markPracticed(first.scoreId, 30)
        val deleted = database.markDeleted(first.scoreId, deletedAt = 40)
        assertEquals(30L, deleted.lastPracticedAt)
        assertTrue(database.list().isEmpty())
        assertNull(database.findBySourceId(first.sourceId))
        assertEquals(listOf(deleted), database.pendingSourceDeletions())
        database.close()
        database = ScoreMetadataDatabase(context, name)
        assertThrows(ScoreDeletedException::class.java) { database.ensureImported(source(1)) }
        database.confirmSourceDeleted(first.scoreId)
        assertTrue(database.pendingSourceDeletions().isEmpty())
        assertTrue(database.get(first.scoreId, includeDeleted = true)!!.sourceFileDeleted)
        assertEquals("未命名乐谱 002", database.ensureImported(source(2)).displayTitle)
        val restored = database.ensureImported(source(1), restoreDeleted = true)
        assertEquals(first.scoreId, restored.scoreId)
        assertEquals("已命名", restored.displayTitle)
        assertNull(restored.deletedAt)
        assertFalse(restored.sourceFileDeleted)
    }

    @Test fun deletionWithPracticeOptionClearsOnlyAssociatedSummary() {
        val first = database.ensureImported(source(1))
        val second = database.ensureImported(source(2))
        database.markPracticed(first.scoreId, 30)
        database.markPracticed(second.scoreId, 40)
        val deleted = database.markDeleted(first.scoreId, deletePracticeRecords = true, deletedAt = 50)
        assertNull(deleted.lastPracticedAt)
        assertEquals(40L, database.get(second.scoreId)!!.lastPracticedAt)
        assertThrows(IllegalStateException::class.java) { database.confirmSourceDeleted(second.scoreId) }
    }

    @Test fun concurrentImportsPreserveUniqueIdentifiersAndSequence() {
        database.list() // Create the schema before the deliberately parallel write attempts.
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..8).map { index -> Callable {
                ScoreMetadataDatabase(context, name).use { it.ensureImported(source(index), importedAt = index.toLong()) }
            } }).map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(8, results.map { it.scoreId }.toSet().size)
            assertEquals((1..8).map { "未命名乐谱 ${it.toString().padStart(3, '0')}" }.toSet(), results.map { it.temporaryTitle }.toSet())
            assertEquals(8, database.list().size)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidInputDoesNotCreateRowsOrTouchOriginalDatabase() {
        assertThrows(IllegalArgumentException::class.java) { ScoreMetadataDatabase(context, "xiuyin.db") }
        assertThrows(IllegalArgumentException::class.java) { database.ensureImported("../bad") }
        assertThrows(IllegalArgumentException::class.java) { database.ensureImported(source(1), importedAt = -1) }
        assertThrows(IllegalArgumentException::class.java) { database.ensureImported(source(1), ScoreImportMetadata(pageCount = 0)) }
        assertTrue(database.list().isEmpty())
        assertEquals("未命名乐谱 001", database.ensureImported(source(1)).displayTitle)
    }

    private fun source(number: Int): String = number.toString(16).padStart(64, '0')
}