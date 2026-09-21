package com.xiuyin.app.omr

import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OmrImportBudgetInstrumentedTest {
    @Test fun multiplePdfDocumentsShareOnePageBudgetAndKeepOriginals() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.filesDir, "omr-budget-tests/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        // Blank PDF fixture tests only admission limits, never music recognition.
        val pdfs = (0..1).map { index ->
            File(directory, "test-$index.pdf").also { file ->
                val pdf = PdfDocument()
                try {
                    repeat(11) { page ->
                        val info = PdfDocument.PageInfo.Builder(80, 100, page + 1).create()
                        val image = pdf.startPage(info); pdf.finishPage(image)
                    }
                    file.outputStream().use(pdf::writeTo)
                } finally { pdf.close() }
            }
        }
        val originalBytes = pdfs.map { it.readBytes() }
        val failure = assertThrows(IllegalArgumentException::class.java) { OmrImportService(context).importDocuments(pdfs.map(Uri::fromFile)) }
        assertTrue(failure.message.orEmpty().contains("累计超过 20 页"))
        pdfs.forEachIndexed { i, file -> assertTrue(originalBytes[i].contentEquals(file.readBytes())) }
        assertTrue(OmrImportService(context).listSourceIds().isEmpty())
    }
}
