package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA blank page through the real model: failure must remain visible and preserve evidence. */
@RunWith(AndroidJUnit4::class)
class OmrImportFailureEvidenceTest {
    @Test fun blankPageKeepsOriginalAndBoundedFailureWithoutPublishingAFakeScore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "omr-import-failure-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val source = File(directory, "TEST-DATA-blank-page.png")
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val frozen = source.readBytes()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val service = OmrImportService(isolated)
        val error = assertThrows(IllegalArgumentException::class.java) { service.importDocuments(listOf(Uri.fromFile(source))) }
        assertTrue(error.message.orEmpty().contains("五线谱"))
        assertArrayEquals(frozen, source.readBytes())
        assertTrue(service.listSourceIds().isEmpty())
        val pending = File(directory, "omr-sources").listFiles().orEmpty().single()
        assertTrue(pending.name.startsWith(".pending-"))
        assertArrayEquals(frozen, File(pending, "original-000.document").readBytes())
        assertArrayEquals(frozen, File(pending, "page-000.image").readBytes())
        val evidence = JSONObject(File(pending, "failure.json").readText())
        assertEquals("FAILED", evidence.getString("status"))
        assertTrue(evidence.getBoolean("originalDocumentsPreserved"))
        assertTrue(evidence.getBoolean("partialRawPreserved"))
        assertTrue(File(pending, "failure.json").length() <= 64 * 1024)
        assertTrue(File(pending, "failure-raw.json").length() <= 32 * 1024 * 1024)
        assertEquals("FAILED", JSONObject(File(pending, "failure-raw.json").readText()).getString("status"))
        assertFalse(File(pending, "original.json").exists())
        assertTrue(service.inspect().issues.any { it.contains("失败") })
    }
}
