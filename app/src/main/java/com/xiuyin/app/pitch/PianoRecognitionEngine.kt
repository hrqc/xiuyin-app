package com.xiuyin.app.pitch

import android.content.Context
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous model worker; capture never waits for neural inference. */
class PianoRecognitionEngine(context: Context,
    private val onFrame: (PianoRecognitionFrame) -> Unit,
    private val onStatus: (RecognitionEngineSnapshot) -> Unit = {},
) : AudioRecognitionEngine {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "XiuyinPianoModel") }
    @Volatile private var queue = ArrayBlockingQueue<Block>(96)
    private val generation = AtomicLong()
    private val guard = Any()
    @Volatile private var closed = false
    private var config = PitchEvidenceConfig()
    @Volatile override var snapshot = RecognitionEngineSnapshot(); private set

    override fun start() = synchronized(guard) {
        check(!closed) { "识音引擎已关闭" }
        if (snapshot.status in setOf(RecognitionEngineStatus.LOADING, RecognitionEngineStatus.WARMING_UP, RecognitionEngineStatus.RUNNING)) return@synchronized
        val sessionConfig = config
        val session = generation.incrementAndGet()
        queue.clear()
        val sessionQueue = ArrayBlockingQueue<Block>(96)
        queue = sessionQueue
        publish(RecognitionEngineSnapshot(RecognitionEngineStatus.LOADING, "正在校验并加载本地钢琴模型"))
        executor.execute {
            try {
                PianoModel.fromAssets(appContext).use { model ->
                    if (generation.get() != session) return@use
                    val processor = PianoRecognitionProcessor(model, sessionConfig) { frame ->
                        synchronized(guard) { if (generation.get() == session && !closed) onFrame(frame) }
                    }
                    publishCurrent(session, RecognitionEngineSnapshot(RecognitionEngineStatus.WARMING_UP, "等待1.12秒连续PCM；准确率未验证"))
                    while (!closed && generation.get() == session) {
                        val block = sessionQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        if (generation.get() != session || closed) break
                        processor.accept(block.samples, block.samples.size, block.firstInputSample)
                        if (processor.frameCount > 0) publishCurrent(session, RecognitionEngineSnapshot(
                            RecognitionEngineStatus.RUNNING, "本地模型已运行；手机琴房准确率未验证；踏板UNKNOWN",
                            processor.frameCount, processor.computeMs))
                    }
                }
            } catch (error: Exception) { fail(session, error) }
              catch (error: LinkageError) { fail(session, error) }
        }
    }

    override fun updateConfig(config: PitchEvidenceConfig) = synchronized(guard) {
        check(!closed && snapshot.status in setOf(RecognitionEngineStatus.IDLE, RecognitionEngineStatus.ERROR)) { "校准配置只能在识音停止时更新，下次会话生效" }
        this.config = config
    }

    /** Caller owns samples; copying is bounded to one 1024-sample block, never persisted. */
    override fun submitPcm(samples: ShortArray, count: Int, firstInputSample: Long) {
        require(count in 1..samples.size && count <= 4096 && firstInputSample >= 0)
        val status = snapshot.status
        if (closed || status !in setOf(RecognitionEngineStatus.LOADING, RecognitionEngineStatus.WARMING_UP, RecognitionEngineStatus.RUNNING)) return
        if (!queue.offer(Block(samples.copyOf(count), firstInputSample))) {
            val session = generation.get()
            fail(session, IllegalStateException("识音处理跟不上采集，队列已满；已暂停，不能用丢帧结果判断错音"))
        }
    }
    override fun stop() = synchronized(guard) {
        if (!closed) { generation.incrementAndGet(); queue.clear(); publish(RecognitionEngineSnapshot(message = "识音已停止")) }
    }
    override fun close() = synchronized(guard) {
        if (!closed) {
            closed = true; generation.incrementAndGet(); queue.clear()
            publish(RecognitionEngineSnapshot(RecognitionEngineStatus.CLOSED, "识音已关闭"))
            executor.shutdown()
        }
    }
    private fun fail(session: Long, error: Throwable) = synchronized(guard) {
        if (generation.compareAndSet(session, session + 1) && !closed) {
            queue.clear()
            Log.e("XiuyinPiano", "真实识音停止", error)
            publish(RecognitionEngineSnapshot(RecognitionEngineStatus.ERROR, "识音错误：${error.message ?: error.javaClass.simpleName}"))
        }
    }
    private fun publishCurrent(session: Long, value: RecognitionEngineSnapshot) = synchronized(guard) {
        if (generation.get() == session && !closed) publish(value)
    }
    private fun publish(value: RecognitionEngineSnapshot) {
        snapshot = value
        try { onStatus(value) } catch (error: Exception) { Log.e("XiuyinPiano", "识音状态回调失败", error) }
    }
    private data class Block(val samples: ShortArray, val firstInputSample: Long)
}