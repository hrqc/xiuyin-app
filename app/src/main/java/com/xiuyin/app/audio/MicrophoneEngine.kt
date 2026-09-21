package com.xiuyin.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real, ephemeral built-in-microphone PCM capture. No permission dialog, playback, file,
 * network, note inference or correctness judgement is performed by this class.
 * The UI must request permission and call start only after an explicit user action.
 */
class MicrophoneEngine(
    context: Context,
    private val onEvent: (level: String, message: String, detail: String) -> Unit = { _, _, _ -> },
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val guard = Any()
    private val captureMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AudioSnapshot())
    val state: StateFlow<AudioSnapshot> = mutableState.asStateFlow()
    private var generation = 0L
    private var worker: Job? = null
    private var closed = false
    private val releaseFailure = AtomicReference<Throwable?>(null)
    private val pcmListener = AtomicReference<((ShortArray, Int, Long) -> Unit)?>(null)

    /** Borrowed PCM, valid only during callback. Consumer must copy promptly and never block capture. */
    fun setPcmListener(listener: ((ShortArray, Int, Long) -> Unit)?) { pcmListener.set(listener) }

    fun start() {
        synchronized(guard) {
            if (closed || worker?.isActive == true) return
            if (!hasPermission()) {
                mutableState.value = inactive(AudioStatus.PERMISSION_DENIED, "麦克风权限未授予；未启动采集")
                scope.launch { event("WARNING", "麦克风权限未授予；未启动采集") }
                return
            }
            val session = ++generation
            mutableState.value = AudioSnapshot(status = AudioStatus.STARTING, statusMessage = "正在连接内置麦克风")
            worker = scope.launch {
                try {
                    captureMutex.withLock {
                        if (!isCurrent(session)) return@withLock
                        releaseFailure.get()?.let { throw IllegalStateException("上次音频资源释放失败，请重新打开 App", it) }
                        val counters = CaptureCounters()
                        var rebuilds = 0
                        while (isCurrent(session)) {
                            try {
                                capture(session, counters)
                                break
                            } catch (dead: DeadAudioService) {
                                if (rebuilds++ >= 1) throw dead
                                publish(session) { it.copy(status = AudioStatus.STARTING, statusMessage = "音频服务中断，正在重新连接") }
                                event("WARNING", "音频服务失效，释放后重建一次", dead.stackTraceToString())
                                delay(100)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (denied: SecurityException) {
                    fail(session, AudioStatus.PERMISSION_DENIED, "麦克风权限不可用，采集已停止", denied)
                } catch (unavailable: InputUnavailable) {
                    fail(session, AudioStatus.UNAVAILABLE, unavailable.message ?: "内置麦克风不可用", unavailable)
                } catch (error: Exception) {
                    fail(session, AudioStatus.ERROR, "音频采集失败：${error.message ?: error.javaClass.simpleName}", error)
                }
            }
        }
    }

    fun stop(reason: String = "已停止") = requestStop(reason, closing = false)

    fun permissionDenied() {
        requestStop("麦克风权限被拒绝", closing = false, finalStatus = AudioStatus.PERMISSION_DENIED)
        scope.launch { event("WARNING", "用户未授予麦克风权限；不进行音频判断") }
    }

    fun close() = requestStop("麦克风引擎已关闭", closing = true)

    private fun requestStop(reason: String, closing: Boolean, finalStatus: AudioStatus = AudioStatus.IDLE) {
        synchronized(guard) {
            if (closed) return
            if (closing) closed = true
            val target = worker
            if (target == null || target.isCompleted) {
                ++generation
                worker = null
                mutableState.value = if (releaseFailure.get() == null) inactive(finalStatus, reason)
                else inactive(AudioStatus.ERROR, "麦克风释放失败，请重新打开 App")
                if (closing) scope.cancel()
                return
            }
            if (mutableState.value.status == AudioStatus.STOPPING && !closing && finalStatus == AudioStatus.IDLE) return
            val stoppingGeneration = ++generation
            mutableState.value = inactive(AudioStatus.STOPPING, "正在释放麦克风")
            target.cancel()
            scope.launch {
                target.join()
                var didStop = false
                var transitionDetail = ""
                synchronized(guard) {
                    if (generation == stoppingGeneration) {
                        worker = null
                        val failure = releaseFailure.get()
                        val beforeStatus = mutableState.value.status
                        mutableState.value = if (failure == null) inactive(finalStatus, reason)
                        else inactive(AudioStatus.ERROR, "麦克风释放失败，请重新打开 App")
                        transitionDetail = audioTransition(beforeStatus, mutableState.value.status)
                        didStop = true
                    }
                }
                if (didStop) event("INFO", reason, transitionDetail)
                if (closing) scope.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission") // Checked at start and again immediately before construction.
    private suspend fun capture(session: Long, counters: CaptureCounters) {
        if (!hasPermission()) throw SecurityException("RECORD_AUDIO permission missing")
        val manager = audioManager ?: throw InputUnavailable("系统没有音频管理服务")
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: throw InputUnavailable("未发现内置麦克风；本版不使用外接麦克风")
        val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val sourceLabel = if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION，可能经系统处理"
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw InputUnavailable("设备不支持 48000 Hz 单声道 PCM16 采集（$minBytes）")
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBytes * 2, FRAME_SAMPLES * 2 * 4))
            .build()
        val silencedByCallback = AtomicBoolean(false)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    configs.orEmpty().firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                        ?.let { silencedByCallback.set(it.isClientSilenced) }
                }
            }
        }
        var callbackRegistered = false
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw InputUnavailable("AudioRecord 初始化失败")
            if (!record.setPreferredDevice(input)) throw InputUnavailable("系统未接受内置麦克风路由请求，采集未启动")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record.registerAudioRecordingCallback(Executor { command -> command.run() }, callback)
                callbackRegistered = true
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(session)) return
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw InputUnavailable("麦克风未进入录音状态")
            val routeDeadline = SystemClock.elapsedRealtimeNanos() + ROUTE_TIMEOUT_NANOS
            var routed = record.routedDevice
            while (routed == null && SystemClock.elapsedRealtimeNanos() < routeDeadline) {
                delay(10)
                routed = record.routedDevice
            }
            val confirmedInput = routed ?: throw InputUnavailable("无法确认当前录音设备，采集已停止")
            if (confirmedInput.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) throw InputUnavailable("实际路由不是内置麦克风，采集已停止")
            val startingStatus = mutableState.value.status
            event(
                "INFO", "已启动内置麦克风：${confirmedInput.productName}；$sourceLabel；48000 Hz / 1024 样本帧",
                "captureSession=$session\npreferredDeviceId=${input.id}\n" +
                    "routedDeviceId=${confirmedInput.id}\nroutedDeviceType=TYPE_BUILTIN_MIC\n" +
                    "audioRecordState=${record.recordingState}\naudioSource=$sourceLabel\n" +
                    "sampleRate=${record.sampleRate}\nframeSamples=$FRAME_SAMPLES\n" +
                    "audioLatencyMs=null\n" + audioTransition(startingStatus, startingStatus),
            )
            val samples = ShortArray(FRAME_SAMPLES)
            var filled = 0
            var analyzer = OnsetAnalyzer(SAMPLE_RATE)
            var analyzerOnsets = 0
            var lastSilenced = false
            var lastEmit = 0L
            var lastData = SystemClock.elapsedRealtimeNanos()
            while (isCurrent(session)) {
                currentCoroutineContext().ensureActive()
                val currentRoute = record.routedDevice
                if (currentRoute == null || currentRoute.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    throw InputUnavailable("内置麦克风已断开或路由发生变化，采集已停止")
                }
                if (!hasPermission()) throw SecurityException("RECORD_AUDIO permission revoked")
                val count = record.read(samples, filled, FRAME_SAMPLES - filled, AudioRecord.READ_NON_BLOCKING)
                if (count == AudioRecord.ERROR_DEAD_OBJECT) throw DeadAudioService()
                if (count < 0) throw IllegalStateException("AudioRecord.read 返回错误 $count")
                if (count == 0) {
                    if (SystemClock.elapsedRealtimeNanos() - lastData > NO_DATA_TIMEOUT_NANOS) {
                        throw InputUnavailable("麦克风长时间未提供 PCM 数据，采集已停止")
                    }
                    delay(5)
                    continue
                }
                lastData = SystemClock.elapsedRealtimeNanos()
                filled += count
                if (filled < FRAME_SAMPLES) continue
                filled = 0
                val timestamp = maxOf(lastData, counters.lastTimestamp + 1)
                counters.lastTimestamp = timestamp
                counters.frames++
                val silenced = manager.isMicrophoneMute || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    record.activeRecordingConfiguration?.isClientSilenced ?: silencedByCallback.get()
                } else false
                if (silenced != lastSilenced) {
                    event(if (silenced) "WARNING" else "INFO", if (silenced) "系统已静音麦克风输入，暂停音频判断" else "系统恢复麦克风输入，重新估计噪声底")
                    analyzer = OnsetAnalyzer(SAMPLE_RATE)
                    analyzerOnsets = 0
                }
                lastSilenced = silenced
                if (!silenced) {
                    try { pcmListener.get()?.invoke(samples, FRAME_SAMPLES, (counters.frames - 1) * FRAME_SAMPLES) }
                    catch (error: Exception) {
                        pcmListener.set(null)
                        event("ERROR", "PCM识音回调失败，已断开识音消费；音频诊断继续", error.stackTraceToString())
                    }
                }
                val processingStart = SystemClock.elapsedRealtimeNanos()
                val analysis = if (silenced) null else analyzer.process(samples, FRAME_SAMPLES, timestamp)
                val processingMs = if (analysis == null) null else (SystemClock.elapsedRealtimeNanos() - processingStart) / 1_000_000.0
                if (analysis != null && analysis.onsetCount > analyzerOnsets) {
                    counters.onsets += analysis.onsetCount - analyzerOnsets
                    analyzerOnsets = analysis.onsetCount
                    if (counters.onsets <= 3 || counters.onsets % 10 == 0) {
                        val snapshotStatus = mutableState.value.status
                        event(
                            "INFO", "能量起音候选累计 ${counters.onsets} 次；本条为包络诊断，识音见独立Pitch Engine",
                            "captureSession=$session\n" +
                                "timestampNanos=$timestamp\n" +
                                "timestampMeaning=PCM 帧读取完成的单调时间；不是校准硬件采样时间\n" +
                                "inputLevelDbfs=${analysis.inputLevelDbfs}\n" +
                                "noiseFloorDbfs=${analysis.noiseFloorDbfs}\n" +
                                "onsetState=${analysis.onsetState}\n" +
                                "onsetConfidence=${analysis.onsetConfidence}\n" +
                                "onsetConfidenceMeaning=能量起音启发式证据分数；不是识音准确率\n" +
                                "onsetCount=${counters.onsets}\n" +
                                "rawDetectedNotes=null\nnoteInference=本包络诊断不包含独立Pitch Engine的识别结果\n" +
                                "decision=UNCERTAIN\ndecisionConfidence=null\n" +
                                audioTransition(snapshotStatus, snapshotStatus),
                        )
                    }
                }
                if (timestamp - lastEmit >= STATE_INTERVAL_NANOS) {
                    lastEmit = timestamp
                    publish(session) {
                        AudioSnapshot(
                            status = if (silenced) AudioStatus.SILENCED else AudioStatus.LISTENING,
                            statusMessage = if (silenced) "系统正在静音输入；无法判断演奏" else "正在监听内置麦克风（$sourceLabel）",
                            deviceName = currentRoute.productName.toString().ifBlank { "内置麦克风（设备 ${currentRoute.id}）" }, totalFrames = counters.frames,
                            capturedAtNanos = timestamp, inputLevelDbfs = analysis?.inputLevelDbfs,
                            noiseFloorDbfs = analysis?.noiseFloorDbfs,
                            onsetState = analysis?.onsetState ?: OnsetState.UNCERTAIN,
                            onsetConfidence = analysis?.onsetConfidence, onsetCount = counters.onsets,
                            clipped = analysis?.clipped ?: false, processingMs = processingMs,
                            // No hardware/end-to-end latency measurement is available in this version.
                            audioLatencyMs = null,
                        )
                    }
                }
            }
        } finally {
            if (callbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { record.unregisterAudioRecordingCallback(callback) } catch (error: RuntimeException) {
                    event("WARNING", "取消音频状态回调失败", error.stackTraceToString())
                }
            }
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (error: RuntimeException) {
                event("WARNING", "停止音频设备时出现异常，继续释放资源", error.stackTraceToString())
            } finally {
                try { record.release() } catch (error: RuntimeException) {
                    releaseFailure.set(error)
                    event("ERROR", "释放音频资源失败", error.stackTraceToString())
                    throw error
                }
            }
        }
    }

    private fun hasPermission() = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isCurrent(session: Long): Boolean = synchronized(guard) { !closed && generation == session }

    private fun publish(session: Long, update: (AudioSnapshot) -> AudioSnapshot) {
        synchronized(guard) {
            if (!closed && generation == session) mutableState.value = update(mutableState.value)
        }
    }

    private fun fail(session: Long, status: AudioStatus, message: String, error: Exception) {
        var transitionDetail = "AudioStatusTransition=旧采集会话已失效，没有覆盖当前状态"
        publish(session) { prior ->
            transitionDetail = audioTransition(prior.status, status)
            inactive(status, message)
        }
        event("ERROR", message, "captureSession=$session\n$transitionDetail\n${error.stackTraceToString()}")
    }

    private fun inactive(status: AudioStatus, message: String) = mutableState.value.copy(
        status = status, statusMessage = message, inputLevelDbfs = null, noiseFloorDbfs = null,
        onsetState = OnsetState.UNCERTAIN, onsetConfidence = null, clipped = false,
        notes = emptyList(), decision = FinalDecision.UNCERTAIN, decisionConfidence = null,
        processingMs = null, audioLatencyMs = null,
    )

    /** Musical decisions live in the separate practice module; this log reports capture transitions. */
    private fun audioTransition(before: AudioStatus, after: AudioStatus): String =
        "AudioStatusBefore=$before\nAudioStatusAfter=$after\n" +
            "StateBefore=本采集层不持有演奏状态\nStateAfter=本采集层不持有演奏状态"
    private fun event(level: String, message: String, detail: String = "") {
        try { onEvent(level, message, detail) } catch (error: Exception) {
            Log.e("XiuyinAudio", "音频日志回调失败", error)
        }
    }

    private class CaptureCounters(var frames: Long = 0, var onsets: Int = 0, var lastTimestamp: Long = -1)
    private class InputUnavailable(message: String) : IllegalStateException(message)
    private class DeadAudioService : IllegalStateException("AudioRecord.ERROR_DEAD_OBJECT")

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SAMPLES = 1_024
        private const val STATE_INTERVAL_NANOS = 100_000_000L
        private const val ROUTE_TIMEOUT_NANOS = 2_000_000_000L
        private const val NO_DATA_TIMEOUT_NANOS = 3_000_000_000L
    }
}