package com.xiuyin.app.omr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** TEST DATA: actual Android image decode and orientation, not musical recognition acceptance. */
@RunWith(AndroidJUnit4::class)
class OmrImagePreparationInstrumentedTest {
    @Test fun ordinaryJpegAndPngRetainExactInputBytesAndDimensions() {
        listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG).forEach { format ->
            val bytes = fixture(320, 240, format)
            val frozen = bytes.copyOf()
            val result = OmrImagePreparation.prepare(bytes)
            assertArrayEquals(frozen, bytes)
            assertArrayEquals(bytes, result.bytes)
            assertEquals("ORIGINAL_BYTES_UNCHANGED", result.diagnostics.getString("method"))
            assertEquals(320, result.diagnostics.getInt("outputWidth"))
            assertEquals(240, result.diagnostics.getInt("outputHeight"))
        }
    }

    @Test fun realTwelveMegapixelDecodeProducesBoundedInferenceCopyAndKeepsOriginal() {
        val bytes = fixture(4032, 3024, Bitmap.CompressFormat.JPEG)
        val frozen = bytes.copyOf()
        val result = OmrImagePreparation.prepare(bytes)
        assertArrayEquals(frozen, bytes)
        assertEquals(4032, result.diagnostics.getInt("sourceWidth"))
        assertEquals(3024, result.diagnostics.getInt("sourceHeight"))
        assertEquals(2, result.diagnostics.getInt("decodeSampleSize"))
        assertEquals("image/png", result.diagnostics.getString("outputMimeType"))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
        try {
            assertEquals(2016, decoded.width)
            assertEquals(1512, decoded.height)
            assertTrue(decoded.width.toLong() * decoded.height <= 8_000_000L)
        } finally { decoded.recycle() }
    }

    @Test fun allExifOrientationsMoveActualPixelsAndPreserveSourceBytes() {
        // Clockwise corners: top-left red, top-right green, bottom-right blue, bottom-left yellow.
        val expected = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(2, 3, 0, 1),
            listOf(3, 2, 1, 0), listOf(0, 3, 2, 1), listOf(3, 0, 1, 2), listOf(2, 1, 0, 3), listOf(1, 2, 3, 0))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (orientation in 1..8) {
            val source = File(context.cacheDir, "TEST-DATA-orientation-${UUID.randomUUID()}.jpg")
            try {
                source.writeBytes(fixture(320, 240, Bitmap.CompressFormat.JPEG))
                ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val bytes = source.readBytes()
                val frozen = bytes.copyOf()
                val prepared = OmrImagePreparation.prepare(bytes)
                assertArrayEquals(frozen, source.readBytes())
                assertArrayEquals(frozen, bytes)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size))
                try {
                    assertEquals(if (orientation >= 5) 240 else 320, bitmap.width)
                    assertEquals(if (orientation >= 5) 320 else 240, bitmap.height)
                    val pixels = listOf(bitmap.getPixel(20, 20), bitmap.getPixel(bitmap.width - 21, 20),
                        bitmap.getPixel(bitmap.width - 21, bitmap.height - 21), bitmap.getPixel(20, bitmap.height - 21))
                    expected[orientation - 1].forEachIndexed { i, corner -> assertColor(COLORS[corner], pixels[i]) }
                    assertEquals(orientation != 1, prepared.diagnostics.getBoolean("exifApplied"))
                } finally { bitmap.recycle() }
            } finally { check(source.delete() || !source.exists()) }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun actualWebpDecoderIsSupportedWithoutPretendingToRecognizeMusic() {
        val bytes = fixture(320, 240, Bitmap.CompressFormat.WEBP)
        val result = OmrImagePreparation.prepare(bytes)
        assertArrayEquals(bytes, result.bytes)
        assertEquals("image/webp", result.diagnostics.getString("sourceMimeType"))
        assertEquals("UNVERIFIED", result.diagnostics.getString("musicAccuracy"))
    }

    @Test fun invalidOrTinyImagesFailWithActionableErrors() {
        val corrupt = assertThrows(IllegalArgumentException::class.java) { OmrImagePreparation.prepare("not an image".toByteArray()) }
        assertTrue(corrupt.message.orEmpty().contains("JPG / PNG"))
        val tiny = assertThrows(IllegalArgumentException::class.java) { OmrImagePreparation.prepare(fixture(120, 120, Bitmap.CompressFormat.PNG)) }
        assertTrue(tiny.message.orEmpty().contains("160×160"))
        val oversized = assertThrows(IllegalArgumentException::class.java) { OmrImagePreparation.prepare(ByteArray(20 * 1024 * 1024 + 1)) }
        assertTrue(oversized.message.orEmpty().contains("20 MiB"))
    }

    private fun fixture(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val midX = width / 2f; val midY = height / 2f
            listOf(floatArrayOf(0f, 0f, midX, midY), floatArrayOf(midX, 0f, width.toFloat(), midY),
                floatArrayOf(midX, midY, width.toFloat(), height.toFloat()), floatArrayOf(0f, midY, midX, height.toFloat())).forEachIndexed { i, rect ->
                paint.color = COLORS[i]; canvas.drawRect(rect[0], rect[1], rect[2], rect[3], paint)
            }
            return ByteArrayOutputStream().also { check(bitmap.compress(format, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 15)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 15)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 15)
    }
    companion object { private val COLORS = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW) }
}
