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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA: source archive tests use labeled XML bytes, not OMR or recognition results. */
@RunWith(AndroidJUnit4::class)
class ScoreSourceStoreTest {
    private lateinit var root: File
    private lateinit var store: ScoreSourceStore

    @Before
    fun createIsolatedArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "score-store-test-${UUID.randomUUID()}")
        store = ScoreSourceStore(root)
    }

    @After
    fun removeOnlyIsolatedTestArchive() {
        check(root.name.startsWith("score-store-test-"))
        check(root.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        // Delete links themselves; never traverse their targets.
        if (root.isDirectory && !Files.isSymbolicLink(root.toPath())) {
            root.listFiles()?.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) Files.delete(child.toPath())
                else check(child.deleteRecursively())
            }
        }
        Files.deleteIfExists(root.toPath())
    }

    @Test
    fun emptyArchiveHasNoInventedSources() {
        assertTrue(store.inspect().ids.isEmpty())
        assertTrue(store.inspect().issues.isEmpty())
    }

    @Test
    fun savedOriginalBytesSurviveNewStoreInstance() {
        val bytes = xml("原始谱 TEST DATA")
        val saved = store.save(bytes)
        assertTrue(saved.id.matches(Regex("[a-f0-9]{64}")))
        assertEquals(listOf(saved.id), store.listIds())
        val reopened = ScoreSourceStore(root).load(saved.id)
        assertArrayEquals(bytes, reopened.bytes)
        assertArrayEquals(bytes, File(root, "${saved.id}.musicxml").readBytes())
        assertTrue(store.inspect().issues.isEmpty())
    }

    @Test
    fun duplicateImportIsIdempotentAndDifferentBytesStaySeparate() {
        val first = store.save(xml("first"))
        val duplicate = store.save(xml("first"))
        val second = store.save(xml("second"))
        assertEquals(first.id, duplicate.id)
        assertEquals(setOf(first.id, second.id), store.listIds().toSet())
        assertOnlySourcesAndArchiveLock(setOf(first.id, second.id))
    }

    @Test
    fun emptyOrOversizedInputIsRejectedWithoutCreatingHalfFile() {
        assertThrows(IllegalArgumentException::class.java) { store.save(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            store.save(ByteArray(ScoreSourceStore.MAX_SOURCE_BYTES + 1))
        }
        assertTrue(store.inspect().ids.isEmpty())
        assertTrue(store.inspect().issues.isEmpty())
    }

    @Test
    fun maximumIndividualSizeIsAcceptedExactly() {
        val bytes = ByteArray(ScoreSourceStore.MAX_SOURCE_BYTES) { (it % 127).toByte() }
        val saved = store.save(bytes)
        assertArrayEquals(bytes, store.load(saved.id).bytes)
        assertOnlySourcesAndArchiveLock(setOf(saved.id))
    }

    @Test
    fun pathTraversalAndMalformedIdsCannotReadOutsideArchive() {
        listOf("../outside", "..", "", "A".repeat(64), "a".repeat(63), "/tmp/score").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.load(invalid) }
        }
        assertTrue(store.inspect().ids.isEmpty())
    }

    @Test
    fun corruptSourceIsVisibleAndDoesNotHideHealthySource() {
        val healthy = store.save(xml("healthy"))
        val damaged = store.save(xml("damaged"))
        File(root, "${damaged.id}.musicxml").writeText("damaged bytes TEST DATA")
        assertEquals(2, store.inspect().ids.size)
        val error = assertThrows(ScoreStorageException::class.java) { store.load(damaged.id) }
        assertTrue(error.message!!.contains("校验失败"))
        assertArrayEquals(healthy.bytes, store.load(healthy.id).bytes)
    }

    @Test
    fun reimportDoesNotOverwriteExistingCorruptSourceWithSameId() {
        val bytes = xml("original")
        val saved = store.save(bytes)
        val file = File(root, "${saved.id}.musicxml")
        val corruptBytes = "corrupt bytes TEST DATA".toByteArray()
        file.writeBytes(corruptBytes)
        assertThrows(ScoreStorageException::class.java) { store.save(bytes) }
        assertArrayEquals(corruptBytes, file.readBytes())
        assertOnlySourcesAndArchiveLock(setOf(saved.id))
    }

    @Test
    fun interruptedImportAndUnexpectedPathsRemainVisibleWithoutDeletingThem() {
        val saved = store.save(xml("retained"))
        val pending = File(root, ".pending-interrupted.tmp").apply { writeText("partial TEST DATA") }
        val unknown = File(root, "unexpected.xml").apply { writeText("TEST DATA") }
        val directory = File(root, "unexpected-directory").apply { mkdir() }
        val inventory = ScoreSourceStore(root).inspect()
        assertEquals(listOf(saved.id), inventory.ids)
        assertEquals(3, inventory.issues.size)
        assertTrue(inventory.issues.any { it.contains("未完成导入") })
        assertTrue(pending.exists() && unknown.exists() && directory.exists())
        assertArrayEquals(saved.bytes, store.load(saved.id).bytes)
    }

    @Test
    fun sourceCountLimitPreservesExistingFilesAndAllowsDuplicate() {
        val originals = (0 until ScoreSourceStore.MAX_SOURCES).map { store.save(xml("piece-$it")) }
        assertThrows(ScoreStorageException::class.java) { store.save(xml("one-more")) }
        assertEquals(ScoreSourceStore.MAX_SOURCES, store.listIds().size)
        assertEquals(originals.first().id, store.save(originals.first().bytes).id)
        assertArrayEquals(originals.last().bytes, store.load(originals.last().id).bytes)
        assertTrue(store.inspect().issues.isEmpty())
    }

    @Test
    fun totalSpaceLimitIncludesRetainedTemporaryFiles() {
        store.inspect()
        val interrupted = File(root, ".pending-large.tmp")
        RandomAccessFile(interrupted, "rw").use { it.setLength(ScoreSourceStore.MAX_TOTAL_BYTES) }
        val error = assertThrows(ScoreStorageException::class.java) { store.save(xml("over-quota")) }
        assertTrue(error.message!!.contains("100 MiB"))
        assertTrue(store.listIds().isEmpty())
        assertTrue(interrupted.exists())
        assertEquals(ScoreSourceStore.MAX_TOTAL_BYTES, interrupted.length())
    }

    @Test
    fun concurrentInstancesPublishOneCompleteSource() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val bytes = xml("concurrent TEST DATA")
            val results = executor.invokeAll((0 until 12).map {
                Callable { ScoreSourceStore(root).save(bytes) }
            }).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.id }.toSet().size)
            assertEquals(1, store.listIds().size)
            assertTrue(store.inspect().issues.isEmpty())
            assertOnlySourcesAndArchiveLock(setOf(results.first().id))
            assertArrayEquals(bytes, store.load(results.first().id).bytes)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun symlinkSourceIsNotReadOrReplaced() {
        val saved = store.save(xml("original"))
        val original = File(root, "${saved.id}.musicxml")
        val retained = File(root, "retained-original.xml")
        assertTrue(original.renameTo(retained))
        Files.createSymbolicLink(original.toPath(), retained.toPath())
        assertFalse(store.listIds().contains(saved.id))
        assertTrue(store.inspect().issues.any { it.contains("非普通文件") })
        assertThrows(ScoreStorageException::class.java) { store.load(saved.id) }
        assertThrows(ScoreStorageException::class.java) { store.save(saved.bytes) }
        assertTrue(Files.isSymbolicLink(original.toPath()))
        assertArrayEquals(saved.bytes, retained.readBytes())
    }

    @Test
    fun nonDirectoryRootCannotBeReplacedWithArchive() {
        root.writeText("existing local data TEST DATA")
        assertThrows(Exception::class.java) { store.save(xml("new")) }
        assertEquals("existing local data TEST DATA", root.readText())
    }

    @Test fun confirmedDeletionTargetsOnlyTheNamedSource() {
        val first = store.save(xml("delete TEST DATA"))
        val keep = store.save(xml("keep TEST DATA"))
        assertTrue(store.delete(first.id))
        assertFalse(store.delete(first.id))
        assertArrayEquals(keep.bytes, store.load(keep.id).bytes)
        assertEquals(listOf(keep.id), store.listIds())
        assertThrows(IllegalArgumentException::class.java) { store.delete("../outside") }
    }

    @Test fun corruptedSourceCanBeExplicitlyDeletedWithoutAffectingOtherFiles() {
        val source = store.save(xml("corrupt TEST DATA"))
        val path = File(root, "${source.id}.musicxml")
        path.writeText("damaged TEST DATA")
        assertTrue(store.delete(source.id))
        assertFalse(path.exists())
        assertTrue(store.listIds().isEmpty())
    }

    @Test fun directoryAtLockPathBlocksMutationsWithoutHidingHealthySources() {
        val saved = store.save(xml("retained TEST DATA"))
        val lockFile = File(root, ScoreSourceStore.ARCHIVE_LOCK)
        assertTrue(lockFile.delete())
        assertTrue(lockFile.mkdir())
        assertThrows(ScoreStorageException::class.java) { store.save(xml("another TEST DATA")) }
        assertThrows(ScoreStorageException::class.java) { store.delete(saved.id) }
        assertTrue(store.inspect().issues.any { it.contains(ScoreSourceStore.ARCHIVE_LOCK) })
        assertArrayEquals(saved.bytes, store.load(saved.id).bytes)
        assertTrue(lockFile.isDirectory)
    }

    @Test fun nonemptyLockFileIsVisibleAndNeverTruncated() {
        val saved = store.save(xml("retained TEST DATA"))
        val lockFile = File(root, ScoreSourceStore.ARCHIVE_LOCK)
        lockFile.writeText("unexpected existing data TEST DATA")
        assertThrows(ScoreStorageException::class.java) { store.save(xml("another TEST DATA")) }
        assertThrows(ScoreStorageException::class.java) { store.delete(saved.id) }
        assertTrue(store.inspect().issues.any { it.contains("归档锁文件内容异常") })
        assertEquals("unexpected existing data TEST DATA", lockFile.readText())
        assertArrayEquals(saved.bytes, store.load(saved.id).bytes)
    }

    private fun assertOnlySourcesAndArchiveLock(ids: Set<String>) {
        val expected = ids.map { "$it.musicxml" }.toSet() + ScoreSourceStore.ARCHIVE_LOCK
        assertEquals(expected, root.listFiles()!!.map { it.name }.toSet())
        val lockFile = File(root, ScoreSourceStore.ARCHIVE_LOCK)
        assertTrue(Files.isRegularFile(lockFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(0L, lockFile.length())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".pending-") })
    }
    private fun xml(title: String): ByteArray =
        "<?xml version=\"1.0\"?><score-partwise><work><work-title>$title</work-title></work></score-partwise>".toByteArray(Charsets.UTF_8)
}