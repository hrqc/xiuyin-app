package com.xiuyin.app.score

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

data class ReadScoreDocument(val bytes: ByteArray, val sourceFileName: String?)

/**
 * Bounded local ContentResolver reads. Timeout does not await a stalled Binder/open/read call.
 * At most two requests (including cancellation cleanup) occupy workers. A provider or kernel that
 * ignores cancellation may retain a worker until it returns; further requests fail explicitly,
 * instead of creating unbounded threads. No URI permission is persisted and nothing is uploaded.
 */
object BoundedScoreReader {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val TIMEOUT_MILLIS = 15_000L
    private val slots = Semaphore(2)
    internal val activeRequests: Int get() = 2 - slots.availablePermits()
    private val workers = Executors.newFixedThreadPool(2, daemonFactory("score-read"))
    private val closers = Executors.newFixedThreadPool(2, daemonFactory("score-read-close"))
    private val timer = ScheduledThreadPoolExecutor(1, daemonFactory("score-read-deadline")).apply { removeOnCancelPolicy = true }

    suspend fun read(context: Context, uri: Uri): ByteArray = readDocument(context, uri).bytes

    suspend fun readDocument(context: Context, uri: Uri): ReadScoreDocument {
        require(uri.scheme == "content") { "请从系统文件选择器选择本地 content 文件" }
        val resolver = context.applicationContext.contentResolver
        return readSource(TIMEOUT_MILLIS) { signal ->
            val name = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) null
                    else cursor.getString(index).take(256).map { if (it.isISOControl()) '_' else it }.joinToString("").trim().ifBlank { null }
                }
            } catch (_: UnsupportedOperationException) {
                null // Some otherwise readable providers do not offer a metadata query.
            } catch (_: IllegalArgumentException) {
                null // DISPLAY_NAME is optional; do not invent a name from the URI.
            }
            signal.throwIfCanceled()
            OpenedSource(resolver.openAssetFileDescriptor(uri, "r", signal), name)
        }
    }

    /** Test seam uses actual file descriptors/pipes; it does not fabricate provider or score data. */
    internal suspend fun readDescriptor(
        timeoutMillis: Long,
        open: (CancellationSignal) -> AssetFileDescriptor?,
    ): ByteArray = readSource(timeoutMillis) { signal -> OpenedSource(open(signal), null) }.bytes

    private data class OpenedSource(val descriptor: AssetFileDescriptor?, val name: String?)

    private suspend fun readSource(
        timeoutMillis: Long,
        open: (CancellationSignal) -> OpenedSource,
    ): ReadScoreDocument = suspendCancellableCoroutine { continuation ->
        require(timeoutMillis in 1..TIMEOUT_MILLIS) { "读取时限无效" }
        if (!slots.tryAcquire()) {
            continuation.resumeWithException(IOException("已有文件提供者未结束读取或取消；请稍后重试"))
            return@suspendCancellableCoroutine
        }
        val request = Request(continuation, timeoutMillis, open)
        continuation.invokeOnCancellation { request.cancel(null) }
        request.start()
    }

    private class Request(
        private val continuation: CancellableContinuation<ReadScoreDocument>,
        private val timeoutMillis: Long,
        private val open: (CancellationSignal) -> OpenedSource,
    ) {
        private val monitor = Any()
        private val signal = CancellationSignal()
        private val descriptor = AtomicReference<AssetFileDescriptor?>()
        private var completed = false
        private var ioEnded = false
        private var cleanupEnded = true
        private var released = false
        private var timeout: ScheduledFuture<*>? = null

        fun start() {
            val future = timer.schedule({
                cancel(IOException("读取文件超时（${timeoutMillis} ms）；文件提供者未及时响应，请选择本地文件后重试"))
            }, timeoutMillis, TimeUnit.MILLISECONDS)
            synchronized(monitor) {
                timeout = future
                if (completed) future.cancel(false)
            }
            workers.execute { runRead() }
        }

        fun cancel(error: IOException?) {
            synchronized(monitor) {
                if (completed) return
                completed = true
                cleanupEnded = false
                timeout?.cancel(false)
            }
            // Complete the caller before cleanup: remote Binder cancellation can itself stall.
            if (error != null) continuation.resumeWithException(error)
            closers.execute {
                try {
                    closeDescriptor()?.let { closeError ->
                        error?.addSuppressed(closeError)
                        Log.w("Xiuyin", "取消文件读取时关闭句柄失败", closeError)
                    }
                    signal.cancel()
                } catch (cleanupError: Exception) {
                    error?.addSuppressed(cleanupError)
                    Log.w("Xiuyin", "文件提供者取消请求失败", cleanupError)
                } finally {
                    synchronized(monitor) { cleanupEnded = true; releaseIfEnded() }
                }
            }
        }

        private fun runRead() {
            var document: ReadScoreDocument? = null
            var failure: Exception? = null
            try {
                checkNotCancelled()
                val source = open(signal)
                val opened = source.descriptor ?: throw IOException("无法打开文件，请重新选择本地 MusicXML")
                descriptor.set(opened)
                checkNotCancelled()
                document = ReadScoreDocument(readBytes(opened), source.name)
            } catch (error: Exception) {
                failure = error
            } finally {
                val closeError = closeDescriptor()
                if (closeError != null) {
                    val original = failure
                    if (original == null) failure = closeError else original.addSuppressed(closeError)
                }
            }
            val deliver = synchronized(monitor) {
                val pending = !completed
                if (pending) { completed = true; timeout?.cancel(false) }
                ioEnded = true
                releaseIfEnded()
                pending
            }
            if (deliver) {
                val error = failure
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(requireNotNull(document))
            }
        }

        private fun readBytes(source: AssetFileDescriptor): ByteArray {
            val length = source.declaredLength
            if (length > MAX_BYTES) throw IOException("文件超过 2 MiB，请导出较小的未压缩 MusicXML")
            if (length == 0L) throw IOException("MusicXML 文件为空")
            if (source.startOffset > 0) Os.lseek(source.fileDescriptor, source.startOffset, OsConstants.SEEK_SET)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val poll = StructPollfd().apply {
                fd = source.fileDescriptor
                events = (OsConstants.POLLIN or OsConstants.POLLERR or OsConstants.POLLHUP).toShort()
            }
            while (length < 0 || output.size().toLong() < length) {
                checkNotCancelled()
                val count = try {
                    if (Os.poll(arrayOf(poll), 100) == 0) continue
                    if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) throw IOException("文件句柄已失效")
                    val remaining = if (length < 0) MAX_BYTES + 1 - output.size() else minOf(length - output.size(), (MAX_BYTES + 1 - output.size()).toLong()).toInt()
                    Os.read(source.fileDescriptor, buffer, 0, minOf(buffer.size, remaining))
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR || error.errno == OsConstants.EAGAIN) continue
                    throw IOException("本地文件读取失败", error)
                }
                if (count == 0) {
                    if (length >= 0 && output.size().toLong() < length) throw IOException("文件内容提前结束，请重新选择完整文件")
                    break
                }
                if (output.size() + count > MAX_BYTES) throw IOException("文件超过 2 MiB，请导出较小的未压缩 MusicXML")
                output.write(buffer, 0, count)
            }
            if (output.size() == 0) throw IOException("MusicXML 文件为空")
            checkNotCancelled()
            return output.toByteArray()
        }

        private fun checkNotCancelled() {
            synchronized(monitor) { if (completed) throw IOException("文件读取已取消或超时") }
        }

        private fun closeDescriptor(): Exception? = try {
            descriptor.getAndSet(null)?.close()
            null
        } catch (error: Exception) { error }

        /** monitor must be held: stalled IO or cancellation keeps its slot until both return. */
        private fun releaseIfEnded() {
            if (ioEnded && cleanupEnded && !released) { released = true; slots.release() }
        }
    }

    private fun daemonFactory(prefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { task -> Thread(task, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    }
}