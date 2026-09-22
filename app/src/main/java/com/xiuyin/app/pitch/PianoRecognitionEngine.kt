package com.xiuyin.app.pitch

import android.content.Context
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The model must be genuinely READY before PCM is accepted. No capture starts in this class. */
class PianoRecognitionEngine internal constructor(
    private val modelFactory: () -> PianoInferenceModel,
    private val onFrame: (PianoRecognitionFrame) -> Unit,
    private val onStatus: (RecognitionEngineSnapshot) -> Unit = {},
) : AudioRecognitionEngine {
    constructor(context: Context, onFrame: (PianoRecognitionFrame) -> Unit,
        onStatus: (RecognitionEngineSnapshot) -> Unit = {}) :
        this({ PianoModel.fromAssets(context.applicationContext) }, onFrame, onStatus)

    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "XiuyinPianoModel") }
    @Volatile private var queue = ArrayBlockingQueue<Block>(QUEUE_CAPACITY)
    private val generation = AtomicLong()
    private val guard = Any()
    @Volatile private var closed = false
    private var config = PitchEvidenceConfig()
    private var inFlight: Block? = null
    private var loadStartedNanos = 0L
    @Volatile override var snapshot = RecognitionEngineSnapshot(); private set

    override fun start() = synchronized(guard) {
        check(!closed) { "识音引擎已关闭" }
        if (snapshot.status in ACTIVE_STATUSES) return@synchronized
        val sessionConfig = config
        val session = generation.incrementAndGet()
        val sessionQueue = ArrayBlockingQueue<Block>(QUEUE_CAPACITY)
        queue = sessionQueue
        inFlight = null
        loadStartedNanos = System.nanoTime()
        publish(RecognitionEngineSnapshot(RecognitionEngineStatus.LOADING,
            "正在校验并加载本地钢琴模型；READY 前禁止收音", sessionId = session))
        executor.execute {
            try {
                modelFactory().use { model ->
                    if (generation.get() != session || closed) return@use
                    val processor = PianoRecognitionProcessor(model, sessionConfig) { frame ->
                        synchronized(guard) { if (generation.get() == session && !closed) onFrame(frame) }
                    }
                    updateCurrent(session) { it.copy(status = RecognitionEngineStatus.READY,
                        message = "本地模型已就绪，可以开始收音；现场准确率未验证",
                        modelLoadMs = (System.nanoTime() - loadStartedNanos) / 1_000_000.0) }
                    while (!closed && generation.get() == session) {
                        // Dequeue and in-flight ownership are atomic with stop/error loss accounting.
                        val block = synchronized(guard) {
                            if (generation.get() != session || closed) null else sessionQueue.poll()?.also {
                                inFlight = it
                                publish(snapshot.copy(queueOccupancy = sessionQueue.size))
                            }
                        }
                        if (block == null) { Thread.sleep(10); continue }
                        processor.accept(block.samples, block.samples.size, block.firstInputSample)
                        updateCurrent(session) {
                            inFlight = null
                            it.copy(status = if (processor.frameCount == 0L) RecognitionEngineStatus.WARMING_UP else RecognitionEngineStatus.RUNNING,
                                message = if (processor.frameCount == 0L) "正在累积1.12秒真实PCM；尚无识音结果" else "本地模型正在运行；手机琴房准确率未验证；踏板UNKNOWN",
                                frameCount = processor.frameCount, computeMs = processor.computeMs,
                                queueOccupancy = sessionQueue.size, processedBlocks = it.processedBlocks + 1,
                                processedSamples = it.processedSamples + block.samples.size)
                        }
                    }
                }
            } catch (error: Exception) { fail(session, error) }
              catch (error: LinkageError) { fail(session, error) }
        }
    }

    override fun updateConfig(config: PitchEvidenceConfig) = synchronized(guard) {
        check(!closed && snapshot.status in setOf(RecognitionEngineStatus.IDLE, RecognitionEngineStatus.ERROR)) {
            "校准配置只能在识音停止时更新，下次会话生效"
        }
        this.config = config
    }

    /** Each accepted block is copied, bounded, and session-owned. Premature PCM is never queued. */
    override fun submitPcm(samples: ShortArray, count: Int, firstInputSample: Long) = synchronized(guard) {
        require(count in 1..samples.size && count <= 4096 && firstInputSample >= 0)
        if (closed) return@synchronized
        if (snapshot.status == RecognitionEngineStatus.LOADING) {
            publish(snapshot.copy(droppedFrames = snapshot.droppedFrames + 1,
                droppedSamples = snapshot.droppedSamples + count,
                rejectedBeforeReadyFrames = snapshot.rejectedBeforeReadyFrames + 1,
                message = "模型仍在加载，已拒收提前到达的PCM；等待READY后重新开始音频流"))
            return@synchronized
        }
        if (snapshot.status !in PCM_STATUSES) return@synchronized
        if (!queue.offer(Block(samples.copyOf(count), firstInputSample))) {
            snapshot = snapshot.copy(overflowCount = snapshot.overflowCount + 1,
                droppedFrames = snapshot.droppedFrames + 1, droppedSamples = snapshot.droppedSamples + count)
            fail(generation.get(), IllegalStateException("识音处理跟不上采集，队列已满；已暂停，不能用丢帧结果判断错音"))
            return@synchronized
        }
        publish(snapshot.copy(
            status = if (snapshot.status == RecognitionEngineStatus.READY) RecognitionEngineStatus.WARMING_UP else snapshot.status,
            message = if (snapshot.status == RecognitionEngineStatus.READY) "正在累积真实PCM，等待首个模型窗口" else snapshot.message,
            submittedBlocks = snapshot.submittedBlocks + 1, submittedSamples = snapshot.submittedSamples + count,
            queueOccupancy = queue.size, maxQueueOccupancy = maxOf(snapshot.maxQueueOccupancy, queue.size)))
    }

    override fun stop() = synchronized(guard) {
        if (!closed) {
            generation.incrementAndGet()
            discardPending()
            publish(snapshot.copy(status = RecognitionEngineStatus.IDLE, message = "识音已停止", queueOccupancy = 0))
        }
    }

    override fun close() = synchronized(guard) {
        if (!closed) {
            closed = true
            generation.incrementAndGet()
            discardPending()
            publish(snapshot.copy(status = RecognitionEngineStatus.CLOSED, message = "识音已关闭", queueOccupancy = 0))
            executor.shutdown()
        }
    }

    /** The caller owns guard. Retain all loss counters when ERROR is followed by stop/close. */
    private fun discardPending() {
        val pending = ArrayList<Block>()
        queue.drainTo(pending)
        inFlight?.let(pending::add)
        inFlight = null
        snapshot = snapshot.copy(queueOccupancy = 0,
            droppedFrames = snapshot.droppedFrames + pending.size,
            droppedSamples = snapshot.droppedSamples + pending.sumOf { it.samples.size.toLong() })
    }

    private fun fail(session: Long, error: Throwable) = synchronized(guard) {
        if (generation.compareAndSet(session, session + 1) && !closed) {
            discardPending()
            Log.e("XiuyinPiano", "真实识音停止", error)
            publish(snapshot.copy(status = RecognitionEngineStatus.ERROR,
                message = "识音错误：${error.message ?: error.javaClass.simpleName}",
                modelLoadMs = snapshot.modelLoadMs ?: (System.nanoTime() - loadStartedNanos) / 1_000_000.0))
        }
    }

    private fun updateCurrent(session: Long, update: (RecognitionEngineSnapshot) -> RecognitionEngineSnapshot) = synchronized(guard) {
        if (generation.get() == session && !closed) publish(update(snapshot))
    }

    private fun publish(value: RecognitionEngineSnapshot) {
        snapshot = value
        try { onStatus(value) } catch (error: Exception) { Log.e("XiuyinPiano", "识音状态回调失败", error) }
    }

    private data class Block(val samples: ShortArray, val firstInputSample: Long)
    companion object {
        const val QUEUE_CAPACITY = 96
        private val PCM_STATUSES = setOf(RecognitionEngineStatus.READY, RecognitionEngineStatus.WARMING_UP, RecognitionEngineStatus.RUNNING)
        private val ACTIVE_STATUSES = PCM_STATUSES + RecognitionEngineStatus.LOADING
    }
}