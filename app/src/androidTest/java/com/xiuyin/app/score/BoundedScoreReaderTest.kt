package com.xiuyin.app.score

import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual local ContentResolver, OS files and OS pipes; no mock provider or simulated score result. */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
class BoundedScoreReaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun allReaderAndCleanupSlotsAreReleased() = runBlocking {
        withTimeout(5_000) { while (BoundedScoreReader.activeRequests > 0) delay(10) }
    }

    @Test
    fun actualLocalContentResolverReturnsTheOriginalBytes() = runBlocking {
        val bytes = "<?xml version=\"1.0\"?><source>TEST DATA 原始文件字节</source>".toByteArray()
        val resolver = context.contentResolver
        val displayName = "xiuyin-reader-test-${UUID.randomUUID()}.xml"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/xml")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiuyinTests")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val document = BoundedScoreReader.readDocument(context, uri)
            assertArrayEquals(bytes, document.bytes)
            org.junit.Assert.assertEquals(displayName, document.sourceFileName)
            assertArrayEquals(bytes, BoundedScoreReader.read(context, uri))
        } finally { resolver.delete(uri, null, null) }
    }

    @Test
    fun sourceAssetOffsetAndLengthAreRespectedAndDescriptorCloses() = runBlocking {
        val prefix = "prefix".toByteArray()
        val body = "真实范围 TEST DATA".toByteArray()
        val file = temporaryFile(prefix + body + "suffix".toByteArray())
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val result = BoundedScoreReader.readDescriptor(5_000) {
                AssetFileDescriptor(fd, prefix.size.toLong(), body.size.toLong())
            }
            assertArrayEquals(body, result)
            assertFalse(fd.fileDescriptor.valid())
        } finally { fd.close(); check(file.delete()) }
    }

    @Test
    fun nonContentUrisAreRejectedBeforeProviderAccess() = runBlocking {
        for (uri in listOf("file:///data/data/private.xml", "https://example.invalid/score.xml")) {
            val error = captureFailure { BoundedScoreReader.read(context, Uri.parse(uri)) }
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun oversizedAndEmptyRealFilesFailWithoutLeakingDescriptors() = runBlocking {
        for (size in listOf(0, BoundedScoreReader.MAX_BYTES + 1)) {
            val file = temporaryFile(ByteArray(size))
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val error = captureFailure {
                    BoundedScoreReader.readDescriptor(5_000) { AssetFileDescriptor(fd, 0, size.toLong()) }
                }
                assertTrue(error is IOException)
                assertFalse(fd.fileDescriptor.valid())
            } finally { fd.close(); check(file.delete()) }
        }
    }

    @Test
    fun unknownLengthCannotBypassTheSizeLimit() = runBlocking {
        val file = temporaryFile(ByteArray(BoundedScoreReader.MAX_BYTES + 1))
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val error = captureFailure {
                BoundedScoreReader.readDescriptor(5_000) { AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
            }
            assertTrue(error is IOException && error.message.orEmpty().contains("2 MiB"))
            assertFalse(fd.fileDescriptor.valid())
        } finally { fd.close(); check(file.delete()) }
    }

    @Test
    fun exactlyTwoMiBIsAcceptedWithUnknownLength() = runBlocking {
        val bytes = ByteArray(BoundedScoreReader.MAX_BYTES) { (it % 127).toByte() }
        val file = temporaryFile(bytes)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val result = BoundedScoreReader.readDescriptor(5_000) { AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
            assertArrayEquals(bytes, result)
            assertFalse(fd.fileDescriptor.valid())
        } finally { fd.close(); check(file.delete()) }
    }
    @Test
    fun pipeThatStopsWritingTimesOutAndClosesItsReadHandle() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val cancellation = AtomicReference<CancellationSignal?>()
        val start = System.nanoTime()
        try {
            val error = captureFailure {
                BoundedScoreReader.readDescriptor(300) { signal ->
                    cancellation.set(signal)
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                }
            }
            assertTrue(error is IOException)
            assertTrue(error.message.orEmpty().contains("超时"))
            assertTrue("Caller must not wait on stalled read", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
            withTimeout(5_000) {
                while (pipe[0].fileDescriptor.valid() || cancellation.get()?.isCanceled != true) delay(10)
            }
        } finally { pipe.forEach { it.close() } }
    }

    @Test
    fun coroutineCancellationClosesActualPipeWithoutWaitingForEof() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val opened = CountDownLatch(1)
        val cancellation = AtomicReference<CancellationSignal?>()
        val job = launch {
            BoundedScoreReader.readDescriptor(5_000) { signal ->
                cancellation.set(signal)
                opened.countDown()
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        }
        try {
            withTimeout(5_000) { while (opened.count > 0) delay(10) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            withTimeout(5_000) {
                while (pipe[0].fileDescriptor.valid() || cancellation.get()?.isCanceled != true) delay(10)
            }
        } finally { job.cancelAndJoin(); pipe.forEach { it.close() } }
    }

    @Test
    fun descriptorReturnedAfterTimeoutIsClosedAndCannotBecomeLateSuccess() = runBlocking {
        val file = temporaryFile("late TEST DATA".toByteArray())
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val releaseOpen = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val result = async {
            captureFailure {
                BoundedScoreReader.readDescriptor(300) {
                    entered.countDown()
                    check(releaseOpen.await(5, TimeUnit.SECONDS))
                    AssetFileDescriptor(fd, 0, file.length())
                }
            }
        }
        try {
            withTimeout(5_000) { while (entered.count > 0) delay(10) }
            val error = withTimeout(5_000) { result.await() }
            assertTrue(error is IOException && error.message.orEmpty().contains("超时"))
            releaseOpen.countDown()
            withTimeout(5_000) { while (fd.fileDescriptor.valid()) delay(10) }
        } finally { releaseOpen.countDown(); result.cancelAndJoin(); fd.close(); check(file.delete()) }
    }

    @Test
    fun twoBlockedOpensDoNotCreateUnboundedAdditionalWorkers() = runBlocking {
        val entered = CountDownLatch(2)
        val releaseOpen = CountDownLatch(1)
        val jobs = (0 until 2).map {
            async {
                captureFailure {
                    BoundedScoreReader.readDescriptor(5_000) {
                        entered.countDown()
                        check(releaseOpen.await(5, TimeUnit.SECONDS))
                        null
                    }
                }
            }
        }
        try {
            withTimeout(5_000) { while (entered.count > 0) delay(10) }
            val error = captureFailure { BoundedScoreReader.readDescriptor(300) { error("Must not open a third provider") } }
            assertTrue(error is IOException)
            assertTrue(error.message.orEmpty().contains("未结束读取"))
        } finally {
            releaseOpen.countDown()
            jobs.forEach { it.await() }
        }
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File(context.cacheDir, "bounded-score-reader-test-${UUID.randomUUID()}.bin").apply { writeBytes(bytes) }

    private suspend fun captureFailure(action: suspend () -> ByteArray): Exception = try {
        action()
        throw AssertionError("Expected a read failure")
    } catch (error: Exception) { error }
}