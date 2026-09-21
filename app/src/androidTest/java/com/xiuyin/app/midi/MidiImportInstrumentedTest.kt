package com.xiuyin.app.midi

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.score.ScoreJsonCodec
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MidiImportInstrumentedTest {
    // This explicit SMF protocol fixture is test data, never presented as a recognition result.
    private fun fixture(): ByteArray = listOf(
        0x4d,0x54,0x68,0x64, 0,0,0,6, 0,0, 0,1, 1,0xe0,
        0x4d,0x54,0x72,0x6b, 0,0,0,13,
        0,0x90,60,80, 0x83,0x60,0x80,60,0, 0,0xff,0x2f,0,
    ).map(Int::toByte).toByteArray()

    private fun freshContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.filesDir, "midi-validation-runs/${UUID.randomUUID()}")
        check(directory.mkdirs())
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }

    @Test fun immutableOriginalSurvivesFreshServiceAndExportsCurrentScore() {
        val context = freshContext(); val service = MidiImportService(context)
        val result = service.importBytes(fixture())
        assertFalse(result.reusedExisting)
        assertEquals("MIDI", result.score.source.format)
        assertTrue(fixture().contentEquals(result.originalMidiFile.readBytes()))
        val reopened = MidiImportService(context).load(result.sourceId)
        assertTrue(ScoreJsonCodec.encode(result.score).contentEquals(ScoreJsonCodec.encode(reopened.score)))
        assertTrue(service.importBytes(fixture()).reusedExisting)
        val destination = File(context.filesDir, "export.mid")
        val exported = service.exportCurrent(Uri.fromFile(destination), result.score, 90)
        assertTrue(exported.bytes.contentEquals(destination.readBytes()))
        val parsed = MidiCodec.parse(destination.readBytes())
        assertEquals(60, parsed.notes.single().midi)
        assertEquals(parsed.ppq.toLong(), parsed.notes.single().offTick)
        assertEquals(listOf(result.sourceId), MidiImportService(context).inspect().ids)
        assertTrue(service.delete(result.sourceId))
        assertFalse(service.delete(result.sourceId))
        assertTrue(destination.isFile) // deleting an archive never removes independently exported files.
    }

    @Test fun corruptedArchiveIsVisibleAndNotSilentlyReplaced() {
        val service = MidiImportService(freshContext()); val result = service.importBytes(fixture())
        val originalJson = result.originalScoreFile.readBytes()
        result.originalMidiFile.appendBytes(byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { service.load(result.sourceId) }
        assertThrows(IllegalArgumentException::class.java) { service.importBytes(fixture()) }
        assertTrue(originalJson.contentEquals(result.originalScoreFile.readBytes()))
        assertTrue(service.delete(result.sourceId)) // explicitly requested deletion also works on corrupt archives.
    }

    @Test fun unsafeDeleteAndPendingArchivesDoNotEscapeOrMasqueradeAsSuccess() {
        val context = freshContext(); val service = MidiImportService(context)
        val result = service.importBytes(fixture())
        assertThrows(IllegalArgumentException::class.java) { service.delete("../unrelated") }
        val unrelated = File(context.filesDir, "unrelated.txt").apply { writeText("preserve") }
        val link = File(result.sourceDirectory, "external")
        Files.createSymbolicLink(link.toPath(), unrelated.toPath())
        assertThrows(IllegalArgumentException::class.java) { service.delete(result.sourceId) }
        assertEquals("preserve", unrelated.readText())
        assertTrue(result.originalMidiFile.isFile) // all paths are validated before any delete.
        val pending = File(context.filesDir, "midi-sources/.pending-test").apply { mkdir() }
        File(pending, "failure.txt").writeText("deliberate test failure evidence")
        val inventory = service.inspect()
        assertTrue(inventory.issues.any { it.contains(".pending-test") })
        assertFalse(inventory.ids.contains(".pending-test"))
        Files.delete(link.toPath())
        assertTrue(service.delete(result.sourceId))
        assertTrue(pending.isDirectory)
    }
}
