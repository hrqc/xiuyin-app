package com.xiuyin.app.pitch

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** TEST DATA: silence and explicit loading/inference delays test lifecycle, never piano accuracy.
 * The successful factory always constructs the actual bundled model and returns its actual outputs.
 */
class PianoReadyGateInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun loadingRejectsPcmWithoutQueueingAndReadyStartsANewCompleteStream() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val frames = AtomicInteger()
        val engine = PianoRecognitionEngine(modelFactory = {
            loading.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "TEST DATA loading gate timeout" }
            observedModel(PianoModel.fromAssets(context), closed)
        }, onFrame = { frames.incrementAndGet() })
        try {
            engine.start()
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            assertEquals(RecognitionEngineStatus.LOADING, engine.snapshot.status)
            repeat(120) { engine.submitPcm(ShortArray(1024), 1024, it * 1024L) }
            val rejected = engine.snapshot
            assertEquals(0, rejected.queueOccupancy)
            assertEquals(0L, rejected.submittedSamples)
            assertEquals(120L, rejected.rejectedBeforeReadyFrames)
            assertEquals(120L, rejected.droppedFrames)
            assertEquals(122_880L, rejected.droppedSamples)
            assertEquals(0L, rejected.overflowCount)
            assertEquals(0, frames.get())
            release.countDown()
            await { engine.snapshot.status == RecognitionEngineStatus.READY }
            assertNotNull(engine.snapshot.modelLoadMs)
            assertTrue(engine.snapshot.modelLoadMs!! > 0)
            // Early samples never reach the processor. The authorized capture starts anew at zero.
            feed(engine, 60_000)
            await { engine.snapshot.processedSamples == 60_000L }
            assertEquals(RecognitionEngineStatus.RUNNING, engine.snapshot.status)
            assertEquals(32L, engine.snapshot.frameCount)
            assertEquals(32, frames.get())
            assertEquals(0, engine.snapshot.queueOccupancy)
            engine.stop()
            assertEquals(RecognitionEngineStatus.IDLE, engine.snapshot.status)
            assertEquals(120L, engine.snapshot.rejectedBeforeReadyFrames)
            assertEquals(120L, engine.snapshot.droppedFrames)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(32, frames.get())
        } finally { release.countDown(); engine.close() }
    }

    @Test fun overflowPublishesErrorImmediatelyAndStopKeepsLossCountersWithoutOldFrames() {
        val inferEntered = CountDownLatch(1)
        val releaseInfer = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val frames = AtomicInteger()
        val errorCallback = AtomicReference<RecognitionEngineSnapshot?>()
        val engine = PianoRecognitionEngine(modelFactory = {
            val actual = PianoModel.fromAssets(context)
            object : PianoInferenceModel {
                override fun infer(samples16k: FloatArray): PianoModel.Prediction {
                    inferEntered.countDown()
                    check(releaseInfer.await(30, TimeUnit.SECONDS)) { "TEST DATA inference gate timeout" }
                    return actual.infer(samples16k)
                }
                override fun close() { try { actual.close() } finally { closed.countDown() } }
            }
        }, onFrame = { frames.incrementAndGet() }, onStatus = {
            if (it.status == RecognitionEngineStatus.ERROR) errorCallback.set(it)
        })
        try {
            engine.start()
            await { engine.snapshot.status == RecognitionEngineStatus.READY }
            feed(engine, 60_000)
            assertTrue(inferEntered.await(10, TimeUnit.SECONDS))
            repeat(PianoRecognitionEngine.QUEUE_CAPACITY + 1) {
                engine.submitPcm(ShortArray(4096), 4096, 60_000L + it * 4096L)
            }
            val failed = engine.snapshot
            assertEquals(RecognitionEngineStatus.ERROR, failed.status)
            assertSame(failed, errorCallback.get()) // Callback is synchronous with overflow, not a future frame.
            assertEquals(1L, failed.overflowCount)
            assertEquals(PianoRecognitionEngine.QUEUE_CAPACITY, failed.maxQueueOccupancy)
            assertEquals(0, failed.queueOccupancy)
            assertTrue(failed.droppedFrames >= PianoRecognitionEngine.QUEUE_CAPACITY + 2L)
            assertEquals(failed.submittedBlocks + 1, failed.processedBlocks + failed.droppedFrames)
            assertEquals(failed.submittedSamples + 4096, failed.processedSamples + failed.droppedSamples)
            engine.stop()
            assertEquals(RecognitionEngineStatus.IDLE, engine.snapshot.status)
            assertEquals(failed.overflowCount, engine.snapshot.overflowCount)
            assertEquals(failed.droppedSamples, engine.snapshot.droppedSamples)
            assertEquals(failed.droppedFrames, engine.snapshot.droppedFrames)
            releaseInfer.countDown()
            assertTrue(closed.await(15, TimeUnit.SECONDS))
            assertEquals("In-flight real inference must not publish after ERROR/stop", 0, frames.get())
        } finally { releaseInfer.countDown(); engine.close() }
    }

    @Test fun stopWhileLoadingClosesActualModelWithoutLateReadyOrFrames() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val readyCount = AtomicInteger()
        val frames = AtomicInteger()
        val engine = PianoRecognitionEngine(modelFactory = {
            loading.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "TEST DATA loading gate timeout" }
            observedModel(PianoModel.fromAssets(context), closed)
        }, onFrame = { frames.incrementAndGet() }, onStatus = {
            if (it.status == RecognitionEngineStatus.READY) readyCount.incrementAndGet()
        })
        try {
            engine.start()
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            engine.stop()
            release.countDown()
            assertTrue(closed.await(30, TimeUnit.SECONDS))
            assertEquals(RecognitionEngineStatus.IDLE, engine.snapshot.status)
            assertEquals(0, readyCount.get())
            assertEquals(0, frames.get())
        } finally { release.countDown(); engine.close() }
    }

    @Test fun failedModelConstructionPublishesErrorAndNeverReady() {
        val readyCount = AtomicInteger()
        val failure = AtomicReference<RecognitionEngineSnapshot?>()
        val engine = PianoRecognitionEngine(modelFactory = {
            throw IllegalStateException("TEST DATA injected model construction failure")
        }, onFrame = { fail("No model exists, so no frame may be emitted") }, onStatus = {
            if (it.status == RecognitionEngineStatus.READY) readyCount.incrementAndGet()
            if (it.status == RecognitionEngineStatus.ERROR) failure.set(it)
        })
        try {
            engine.start()
            await { failure.get() != null }
            assertEquals(RecognitionEngineStatus.ERROR, engine.snapshot.status)
            assertTrue(engine.snapshot.message.contains("TEST DATA"))
            assertNotNull(engine.snapshot.modelLoadMs)
            assertEquals(0, readyCount.get())
            assertEquals(0L, engine.snapshot.submittedBlocks)
        } finally { engine.close() }
    }

    private fun observedModel(actual: PianoModel, closed: CountDownLatch) = object : PianoInferenceModel {
        override fun infer(samples16k: FloatArray) = actual.infer(samples16k)
        override fun close() { try { actual.close() } finally { closed.countDown() } }
    }

    private fun feed(engine: PianoRecognitionEngine, samples: Int) {
        var offset = 0
        while (offset < samples) {
            val size = minOf(4096, samples - offset)
            engine.submitPcm(ShortArray(size), size, offset.toLong())
            offset += size
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Real engine lifecycle wait timed out" }
            Thread.sleep(10)
        }
    }
}