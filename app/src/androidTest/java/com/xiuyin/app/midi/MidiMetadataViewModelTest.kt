package com.xiuyin.app.midi

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.score.ScoreImportViewModel
import com.xiuyin.app.score.ScoreMetadata
import com.xiuyin.app.score.ScoreMetadataDatabase
import com.xiuyin.app.score.queryScoreMetadata
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SMF parsing, ViewModel and database; explicit protocol TEST DATA, no recognition mock. */
@RunWith(AndroidJUnit4::class)
class MidiMetadataViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun declaredMidiTitleSurvivesExplicitImportAndArchiveDiscovery() = exerciseTitles("MIDI原始曲名")

    @Test fun absentMidiTitleKeepsTemporaryNamingOnBothPaths() = exerciseTitles(null)

    private fun exerciseTitles(titlePrefix: String?) {
        val store = ViewModelStore()
        val service = MidiImportService(context)
        val ownedSources = mutableListOf<String>()
        val documents = mutableListOf<File>()
        lateinit var model: ScoreImportViewModel
        var created = false
        try {
            instrumentation.runOnMainSync {
                model = ScoreImportViewModel(context.applicationContext as Application)
                store.put("midi-title-regression", model)
                created = true
            }
            await(model)
            for (discovery in listOf(false, true)) {
                val title = titlePrefix?.let { "$it ${UUID.randomUUID()}" }
                val bytes = fixture(title)
                val sourceId = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                ownedSources += sourceId
                if (discovery) {
                    val imported = service.importBytes(bytes)
                    assertEquals(title.orEmpty(), imported.score.title)
                    assertNull(ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId) })
                    instrumentation.runOnMainSync { model.reload() }
                } else {
                    val file = File(context.cacheDir, "midi-title-${UUID.randomUUID()}.mid")
                    documents += file; file.writeBytes(bytes)
                    instrumentation.runOnMainSync { model.importMidi(Uri.fromFile(file)) }
                }
                await(model)
                val metadata = requireNotNull(ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId) })
                assertEquals("MIDI", metadata.sourceType)
                assertEquals(title, metadata.originalTitle)
                assertNull(metadata.customTitle)
                if (title == null) {
                    assertTrue(metadata.displayTitle.matches(Regex("未命名乐谱 \\d{3,}")))
                    if (!discovery) assertEquals(metadata.scoreId, model.state.value.namingMetadata?.scoreId)
                } else {
                    assertEquals(title, metadata.displayTitle)
                    assertNull(metadata.temporaryTitle)
                    assertEquals(listOf(metadata.scoreId), queryScoreMetadata(listOf(metadata), title).map(ScoreMetadata::scoreId))
                    if (!discovery) assertNull(model.state.value.namingMetadata)
                }
                assertEquals(title.orEmpty(), service.load(sourceId).score.title)
                instrumentation.runOnMainSync { model.reload() }
                await(model)
                assertEquals(metadata, ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId) })
            }
        } finally {
            // Only UUID-marked sources created by this test are removed; no database/library reset.
            if (created) {
                ownedSources.forEach { sourceId ->
                    val metadata = ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId) }
                    if (metadata != null) {
                        instrumentation.runOnMainSync { model.deleteScore(metadata.scoreId, false) }
                        await(model)
                    } else if (service.listSourceIds().contains(sourceId)) service.delete(sourceId)
                }
            }
            documents.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun await(model: ScoreImportViewModel) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (model.state.value.importing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertFalse("MIDI 元数据操作超时", model.state.value.importing)
        assertNull(model.state.value.error)
    }

    private fun fixture(title: String?): ByteArray {
        val track = ByteArrayOutputStream()
        fun textMeta(type: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size < 128)
            track.write(byteArrayOf(0, 0xff.toByte(), type.toByte(), bytes.size.toByte()))
            track.write(bytes)
        }
        if (title != null) textMeta(3, title)
        textMeta(1, "TEST DATA ${UUID.randomUUID()}") // unique raw source; never a declared track title.
        track.write(byteArrayOf(0, 0x90.toByte(), 60, 80, 0x83.toByte(), 0x60, 0x80.toByte(), 60, 0, 0, 0xff.toByte(), 0x2f, 0))
        return ByteArrayOutputStream().apply {
            val output = DataOutputStream(this)
            output.writeBytes("MThd"); output.writeInt(6); output.writeShort(0); output.writeShort(1); output.writeShort(480)
            output.writeBytes("MTrk"); output.writeInt(track.size()); output.write(track.toByteArray())
        }.toByteArray()
    }

}
