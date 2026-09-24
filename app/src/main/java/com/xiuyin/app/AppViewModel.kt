package com.xiuyin.app

import android.app.Application
import android.os.Looper
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.audio.MicrophoneEngine
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.field.FieldDiagnostics
import com.xiuyin.app.pitch.*
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.data.LogEntry
import com.xiuyin.app.data.PracticePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppState(
    val loading: Boolean = true,
    val databaseReady: Boolean = false,
    val recentPieceId: String? = null,
    val preparation: PracticePreparation? = null,
    val logs: List<LogEntry> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase(application)
    private val fieldDiagnostics = FieldDiagnostics.get(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val audioEngine = MicrophoneEngine(application) { level, message, detail ->
        fieldDiagnostics.recordLog(level, "Audio", message, detail)
        operation("保存音频诊断") {
            val mode = mutableState.value.preparation?.mode?.name ?: "未设置"
            database.appendLog(level, "Audio", message,
                "$detail\nSaved Practice Mode: $mode\nDiagnostic source: AudioRecord input metrics only; score judgments are logged by Practice module")
            refresh()
        }
    }
    val audio = audioEngine.state
    private val mutableRecognition = MutableStateFlow(RecognitionEngineSnapshot())
    val recognition = mutableRecognition.asStateFlow()
    private val mutablePianoFrame = MutableStateFlow<PianoRecognitionFrame?>(null)
    val pianoFrame = mutablePianoFrame.asStateFlow()
    @Volatile private var recognitionListener: ((PianoRecognitionFrame) -> Unit)? = null
    private val pcmGuard = Any()
    private var observePcm = false
    @Volatile private var pcmObserver: ((ShortArray, Int, Long) -> Unit)? = null
    @Volatile private var audioStopped: (() -> Unit)? = null
    private var inputDrainListener: (() -> Unit)? = null
    private var stoppingAudio = false
    @Volatile private var inputFailureListener: ((String) -> Unit)? = null
    @Volatile private var companionCaptureRequested = false
    private var preparedConfig = PitchEvidenceConfig()

    private val pianoEngine: AudioRecognitionEngine = PianoRecognitionEngine(application, onFrame = { frame ->
        synchronized(pcmGuard) {
            if (companionCaptureRequested) {
                val bound = fieldDiagnostics.bindFrameContext(frame)
                fieldDiagnostics.recordRecognition(bound)
                mutablePianoFrame.value = bound
                recognitionListener?.invoke(bound)
            }
        }
    }, onStatus = { snapshot ->
        fieldDiagnostics.recordEngine(snapshot)
        val changed = mutableRecognition.value.status != snapshot.status
        mutableRecognition.value = snapshot
        if (changed) operation("保存识音诊断") {
            database.appendLog(if (snapshot.status == RecognitionEngineStatus.ERROR) "ERROR" else "INFO", "Pitch", snapshot.message,
                "modelSHA256=${snapshot.modelSha256}; frameCount=${snapshot.frameCount}; computeMs=${snapshot.computeMs}; calibratedAccuracy=false")
            refresh()
        }
        if (snapshot.status == RecognitionEngineStatus.ERROR) {
            closeInputAcceptance()
            // Never wait for Main while the recognition engine owns its worker lock.
            viewModelScope.launch(Dispatchers.Main) {
                stopAudio("识音失败，收音已停止")
                inputFailureListener?.invoke(snapshot.message)
            }
        }
    })
    fun setRecognitionListener(listener: ((PianoRecognitionFrame) -> Unit)?) { recognitionListener = listener }
    fun setPcmObserver(listener: ((ShortArray, Int, Long) -> Unit)?, onStopped: (() -> Unit)?) { pcmObserver = listener; audioStopped = onStopped }
    @MainThread
    fun setInputDrainListener(listener: (() -> Unit)?) { inputDrainListener = listener }
    fun setInputFailureListener(listener: ((String) -> Unit)?) { inputFailureListener = listener }

    /** Preparing the model never requests microphone permission or starts capture. */
    fun prepareCompanionModel(config: PitchEvidenceConfig = PitchEvidenceConfig()) {
        if (companionCaptureRequested) return
        val status = pianoEngine.snapshot.status
        if (status == RecognitionEngineStatus.LOADING ||
            (status == RecognitionEngineStatus.READY && preparedConfig == config)) return
        pianoEngine.stop()
        pianoEngine.updateConfig(config)
        preparedConfig = config
        mutablePianoFrame.value = null
        pianoEngine.start()
    }

    /** Hard READY gate, including direct/non-UI callers. Loading cannot enqueue a future start. */
    fun startCompanionAudio(config: PitchEvidenceConfig = PitchEvidenceConfig()): Boolean {
        val field = fieldDiagnostics.state.value
        if (field.activeSession != null && field.error != null) return false
        if (pianoEngine.snapshot.status != RecognitionEngineStatus.READY || preparedConfig != config ||
            audioEngine.state.value.status in setOf(AudioStatus.STARTING, AudioStatus.LISTENING, AudioStatus.STOPPING)) return false
        mutablePianoFrame.value = null
        companionCaptureRequested = true
        synchronized(pcmGuard) { observePcm = true }
        audioEngine.start()
        return audioEngine.state.value.status == AudioStatus.STARTING || audioEngine.state.value.status == AudioStatus.LISTENING
    }

    fun startAudio() {
        val field = fieldDiagnostics.state.value
        if (field.activeSession != null && field.error != null) {
            mutableState.update { it.copy(error = "当前测试会话记录故障；请先结束并导出已有记录，再新建会话") }
            return
        }
        stopAudio("切换音频输入前结束已有模型输入")
        synchronized(pcmGuard) { observePcm = true }
        audioEngine.start()
    }
    private fun closeInputAcceptance() {
        synchronized(pcmGuard) { companionCaptureRequested = false; observePcm = false }
    }
    /** Seal worker input, settle admitted frames on Main, then retire their source identity. */
    @MainThread
    fun stopAudio(reason: String = "用户已停止监听") {
        check(Looper.myLooper() == Looper.getMainLooper()) { "停止输入必须在主线程结算已接收模型帧" }
        if (stoppingAudio) return
        stoppingAudio = true
        try {
            closeInputAcceptance()
            // No engine/PCM lock is held while the Main-owned follower is drained.
            inputDrainListener?.invoke()
        } finally {
            try {
                audioStopped?.invoke()
                fieldDiagnostics.endAudioStream()
                audioEngine.stop(reason)
                pianoEngine.stop()
            } finally { stoppingAudio = false }
        }
    }
    fun audioPermissionDenied() = audioEngine.permissionDenied()

    init {
        audioEngine.setPcmListener { samples, count, first ->
            synchronized(pcmGuard) {
                if (observePcm) {
                    fieldDiagnostics.appendPcm(samples, count, first)
                    pcmObserver?.invoke(samples, count, first)
                }
            }
            if (companionCaptureRequested) pianoEngine.submitPcm(samples, count, first)
        }
        fieldDiagnostics.setFailureListener { message ->
            // Always dispatch: a failure can be raised while the PCM/clip lock is held.
            viewModelScope.launch(Dispatchers.Main) {
                stopAudio("诊断记录失败，实地测试已暂停")
                inputFailureListener?.invoke(message)
            }
        }
        fieldDiagnostics.recordLog("INFO", "App", "App Started", "version=${BuildConfig.VERSION_NAME}; commit=${BuildConfig.GIT_COMMIT}")
        viewModelScope.launch {
            audio.collect { snapshot ->
                fieldDiagnostics.recordAudio(snapshot)
                if (companionCaptureRequested && snapshot.status in setOf(AudioStatus.PERMISSION_DENIED,
                        AudioStatus.UNAVAILABLE, AudioStatus.ERROR, AudioStatus.SILENCED, AudioStatus.STOPPING, AudioStatus.IDLE)) {
                    stopAudio("音频不可用，当前判分已暂停")
                    inputFailureListener?.invoke(snapshot.statusMessage)
                }
            }
        }
        initialize()
    }

    fun initialize() = operation("启动数据库") {
        check(database.healthCheck()) { "SQLite 健康检查失败" }
        database.appendLog("INFO", "App", "修音 V${BuildConfig.VERSION_NAME} 已启动")
        database.appendLog("INFO", "Database", "本地数据库检查通过")
        refresh()
    }

    fun openPiece(id: String) = operation("打开乐谱") {
        database.markOpened(id)
        database.appendLog("INFO", "Library", "打开示例谱：$id")
        refresh()
    }

    fun savePreparation(value: PracticePreparation) {
        mutableState.update { it.copy(saving = true) }
        operation("保存练习设置") {
            database.savePreparation(value)
            database.appendLog("INFO", "Practice", "练习设置已保存：${value.mode.label} / ${value.repeatTarget} 次；保存操作不启动音频")
            refresh()
            mutableState.update { it.copy(saving = false) }
        }
    }

    fun visit(page: String) = operation("记录页面状态") {
        database.appendLog("INFO", "UI", "打开页面：$page")
        refresh()
    }

    fun refreshLogs() = operation("刷新日志") {
        check(database.healthCheck()) { "SQLite 健康检查失败" }
        refresh()
    }

    fun clearLogs() = operation("清空日志") {
        database.clearLogs()
        refresh()
    }


    private fun refresh() {
        val preparation = database.loadPreparation()
        val recent = database.recentPieceId()
        val logs = database.logs()
        mutableState.update {
            it.copy(loading = false, databaseReady = true, recentPieceId = recent,
                preparation = preparation, logs = logs, error = null)
        }
    }

    private fun operation(label: String, block: () -> Unit) {
        viewModelScope.launch {
            try {
                mutex.withLock { withContext(Dispatchers.IO) { block() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("Xiuyin", label, error)
                val message = "$label 失败：${error.message ?: error.javaClass.simpleName}"
                val entry = LogEntry(-System.currentTimeMillis(), System.currentTimeMillis(), "ERROR", "Database", message, error.stackTraceToString())
                mutableState.update {
                    it.copy(loading = false, saving = false, databaseReady = false, error = message, logs = listOf(entry) + it.logs.take(199))
                }
            }
        }
    }

    override fun onCleared() {
        fieldDiagnostics.setFailureListener(null)
        stopAudio("应用控制器关闭，输入已停止")
        pcmObserver = null; audioStopped = null; inputDrainListener = null
        inputFailureListener = null
        recognitionListener = null
        audioEngine.setPcmListener(null)
        pianoEngine.close()
        audioEngine.close()
        database.close()
        super.onCleared()
    }
}
