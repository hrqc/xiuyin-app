package com.xiuyin.quicktest

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.audio.MicrophoneEngine
import com.xiuyin.app.pitch.PianoModel
import com.xiuyin.app.pitch.PianoRecognitionEngine
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class QuickPhase { PREPARING, READY, STARTING, RECORDING, SAVING, SAVED, ERROR }

data class QuickUiState(
    val phase: QuickPhase = QuickPhase.PREPARING,
    val message: String = "正在准备，请稍候…",
    val error: String? = null,
    val elapsedSeconds: Double = 0.0,
    val receivedSamples: Long = 0,
    val modelFrames: Long = 0,
    val latestNotes: String = "等待弹琴",
    val exportFile: File? = null,
    val sessions: List<QuickTestStore.SessionInfo> = emptyList(),
    val markedProblems: Int = 0,
)

/** This collection app never supplies a score or expected note to recognition. */
class QuickTestViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(QuickUiState())
    val state = mutableState.asStateFlow()
    private val mutableModel = MutableStateFlow(RecognitionEngineSnapshot())
    val modelState = mutableModel.asStateFlow()
    private val inputGuard = Any()
    private val samples = AtomicLong()
    @Volatile private var acceptingPcm = false
    @Volatile private var activeSession: String? = null
    @Volatile private var store: QuickTestStore? = null
    private var foreground = false
    private var stopRequested = false
    private var startingToken = 0L
    private val root = File(application.filesDir, "quicktest")
    private val engine = PianoRecognitionEngine(application, ::onFrame, ::onModel)
    private val microphone = MicrophoneEngine(application) { level, message, detail ->
        store?.appendEvent("MICROPHONE", mapOf("level" to level, "message" to message, "detail" to detail))
    }
    val audioState = microphone.state

    init {
        microphone.setPcmListener(::onPcm)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store = QuickTestStore(root, onError = { message -> viewModelScope.launch { failCapture(message) } })
                }
                refreshSessions()
                engine.start()
            } catch (error: CancellationException) { store?.close(); throw error }
            catch (error: Exception) { showError("准备失败：${error.message}") }
        }
        viewModelScope.launch {
            audioState.collect { audio ->
                if (activeSession != null) {
                    if (audio.status == AudioStatus.LISTENING && mutableState.value.phase == QuickPhase.STARTING) {
                        mutableState.update { it.copy(phase = QuickPhase.RECORDING, message = "正在录音，请弹琴。最长60秒。") }
                    }
                    if (audio.status in setOf(AudioStatus.ERROR, AudioStatus.UNAVAILABLE, AudioStatus.SILENCED, AudioStatus.PERMISSION_DENIED)) {
                        failCapture(audio.statusMessage)
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(150)
                if (activeSession != null) {
                    val count = samples.get()
                    mutableState.update { it.copy(receivedSamples = count, elapsedSeconds = count / 48_000.0) }
                }
            }
        }
    }

    fun onForeground(visible: Boolean) {
        foreground = visible
        if (!visible) {
            startingToken++
            if (mutableState.value.phase in setOf(QuickPhase.STARTING, QuickPhase.RECORDING)) finish("APP_BACKGROUND")
        }
    }

    fun prepare() {
        if (activeSession != null || mutableState.value.phase == QuickPhase.SAVING) return
        if (store == null) { showError("测试目录未就绪，请退出并重新打开应用。"); return }
        mutableState.update { it.copy(phase = QuickPhase.PREPARING, error = null, message = "正在准备，请稍候…") }
        engine.stop()
        engine.start()
    }

    fun permissionDenied() {
        mutableState.update { it.copy(error = "没有麦克风权限，未录音。请允许权限后再点开始。") }
    }

    fun start(comment: String = "") {
        if (!foreground || activeSession != null || mutableState.value.phase !in setOf(QuickPhase.READY, QuickPhase.SAVED)) return
        if (engine.snapshot.status != RecognitionEngineStatus.READY) { prepare(); return }
        val token = ++startingToken
        stopRequested = false
        mutableState.update { it.copy(phase = QuickPhase.STARTING, message = "正在创建测试记录…", error = null,
            exportFile = null, latestNotes = "等待弹琴", receivedSamples = 0, elapsedSeconds = 0.0, modelFrames = 0, markedProblems = 0) }
        viewModelScope.launch {
            try {
                val owner = checkNotNull(store)
                val session = withContext(Dispatchers.IO) {
                    check(StatFs(root.absolutePath).availableBytes >= 64L * 1024 * 1024) { "手机空间不足，请留出至少64MB后重试。" }
                    owner.start(mapOf(
                        "appVersion" to BuildConfig.VERSION_NAME, "buildNumber" to BuildConfig.VERSION_CODE,
                        "gitCommit" to BuildConfig.GIT_COMMIT, "buildTime" to BuildConfig.BUILD_TIME,
                        "modelSha256" to PianoModel.MODEL_SHA256,
                        "manufacturer" to Build.MANUFACTURER, "phoneModel" to Build.MODEL,
                        "androidRelease" to Build.VERSION.RELEASE, "androidSdk" to Build.VERSION.SDK_INT,
                        "userComment" to comment.take(500), "commentIsUserAnnotationNotVerifiedTruth" to true,
                        "recordingConsent" to "User explicitly tapped start after local recording notice",
                        "testScope" to "FREE_AUDIO_COLLECTION_NO_SCORE_NO_ACCURACY_GRADING",
                        "sampleRate" to 48_000, "channels" to 1, "bitsPerSample" to 16,
                        "maximumSeconds" to 60, "pedalState" to "NOT_VERIFIED", "networkUpload" to false,
                    ))
                }
                activeSession = session.id
                samples.set(0)
                if (token != startingToken || !foreground || stopRequested || engine.snapshot.status != RecognitionEngineStatus.READY) {
                    stopRequested = false
                    finish("START_CANCELLED")
                    return@launch
                }
                synchronized(inputGuard) { acceptingPcm = true }
                microphone.start()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (activeSession != null) failCapture("开始失败：${error.message}")
                else showError("开始失败：${error.message}")
            }
        }
    }

    private fun onPcm(pcm: ShortArray, count: Int, first: Long) {
        synchronized(inputGuard) {
            if (!acceptingPcm) return
            val remaining = MAX_SAMPLES - samples.get()
            val accepted = minOf(count.toLong(), remaining).toInt()
            if (accepted <= 0) return
            val owner = store ?: return
            if (!owner.appendPcm(pcm, accepted, first)) {
                acceptingPcm = false
                viewModelScope.launch { failCapture("录音写入失败，已停止；已有数据会保留。") }
                return
            }
            samples.addAndGet(accepted.toLong())
            engine.submitPcm(pcm, accepted, first)
            if (samples.get() >= MAX_SAMPLES) {
                acceptingPcm = false
                viewModelScope.launch { finish("MAX_DURATION") }
            }
        }
    }

    private fun onFrame(frame: PianoRecognitionFrame) {
        val session = activeSession ?: return
        store?.appendFrame(frame)
        viewModelScope.launch {
            if (activeSession != session) return@launch
            mutableState.update { current ->
                current.copy(modelFrames = frame.frameIndex + 1,
                    latestNotes = if (frame.activeMidi.isNotEmpty()) frame.activeMidi.sorted().joinToString(" · ", transform = ::noteName)
                    else if (frame.onsetMidi.isNotEmpty()) frame.onsetMidi.sorted().joinToString(" · ", transform = ::noteName)
                    else "等待弹琴")
            }
        }
    }

    private fun onModel(snapshot: RecognitionEngineSnapshot) {
        viewModelScope.launch {
            mutableModel.value = snapshot
            if (snapshot.status == RecognitionEngineStatus.READY && mutableState.value.phase == QuickPhase.PREPARING) {
                mutableState.update { it.copy(phase = QuickPhase.READY, message = "准备好了，点开始测试。") }
            }
            if (snapshot.status == RecognitionEngineStatus.ERROR) {
                if (activeSession != null) failCapture(snapshot.message) else showError(snapshot.message)
            }
        }
    }

    fun markProblem() {
        if (mutableState.value.phase != QuickPhase.RECORDING) return
        if (store?.mark("用户标记：这里有问题；不是已验证误识别", samples.get()) == true) {
            mutableState.update { it.copy(markedProblems = it.markedProblems + 1, message = "已记下这个位置，继续弹即可。") }
        }
    }

    private fun failCapture(message: String) {
        val snapshot = engine.snapshot
        store?.appendEvent("CAPTURE_ERROR", mapOf("message" to message,
            "engineStatus" to snapshot.status.name, "engineMessage" to snapshot.message))
        mutableState.update { it.copy(error = message) }
        if (activeSession != null) finish("CAPTURE_ERROR") else if (mutableState.value.phase != QuickPhase.SAVING) showError(message)
    }

    fun finish(reason: String = "USER_ENDED") {
        if (mutableState.value.phase == QuickPhase.SAVING) return
        if (activeSession == null) {
            if (mutableState.value.phase == QuickPhase.STARTING) { stopRequested = true; startingToken++ }
            return
        }
        mutableState.update { it.copy(phase = QuickPhase.SAVING, message = "正在保存录音并打包，请稍候…") }
        synchronized(inputGuard) { acceptingPcm = false }
        microphone.stop("本次测试已结束")
        viewModelScope.launch {
            var finalReason = reason
            try {
                val drained = withTimeoutOrNull(15_000) {
                    while (true) {
                        val e = engine.snapshot
                        val micDone = audioState.value.status !in setOf(AudioStatus.STARTING, AudioStatus.LISTENING, AudioStatus.STOPPING)
                        if (micDone && (e.status == RecognitionEngineStatus.ERROR || (e.processedBlocks == e.submittedBlocks && e.queueOccupancy == 0))) break
                        delay(20)
                    }
                    true
                } == true
                if (!drained) {
                    finalReason = "DRAIN_TIMEOUT"
                    mutableState.update { it.copy(error = "结束时处理未完成，已保留部分数据，请仍将数据包发回。") }
                }
                val finalAudio = audioState.value
                store?.appendEvent("AUDIO_END", mapOf("status" to finalAudio.status.name,
                    "audioSource" to finalAudio.actualAudioSourceName, "route" to finalAudio.actualInputRoute,
                    "sourceDevice" to finalAudio.deviceName, "clippedAtEnd" to finalAudio.clipped))
                val beforeStop = engine.snapshot
                store?.appendEvent("RECOGNITION_END", mapOf("statusBeforeStop" to beforeStop.status.name,
                    "messageBeforeStop" to beforeStop.message, "frameCount" to beforeStop.frameCount,
                    "submittedSamples" to beforeStop.submittedSamples, "processedSamples" to beforeStop.processedSamples))
                // Stop synchronously closes the model callback guard before sealing the writer.
                engine.stop()
                val finalEngine = engine.snapshot
                val owner = checkNotNull(store)
                val (info, file) = withContext(Dispatchers.IO) {
                    val info = owner.finish(finalReason, finalEngine)
                    info to owner.export(info.id)
                }
                activeSession = null
                refreshSessions()
                mutableState.update { it.copy(phase = QuickPhase.SAVED, exportFile = file,
                    receivedSamples = samples.get(), elapsedSeconds = samples.get() / 48_000.0,
                    error = info.error ?: it.error,
                    message = if (info.status == "COMPLETED") "数据包已准备好，点分享数据包发给我。"
                    else "本次提前结束，已有数据已保存，可以分享。") }
                engine.start()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                activeSession = null
                engine.stop()
                refreshSessions()
                showError("打包未完成：${error.message}。已有录音保留，请从最近测试重新导出。")
            }
        }
    }

    fun selectSession(id: String) {
        if (activeSession != null || mutableState.value.phase in setOf(QuickPhase.STARTING, QuickPhase.SAVING)) return
        mutableState.update { it.copy(phase = QuickPhase.SAVING, message = "正在读取数据包…", error = null) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { checkNotNull(store).export(id) }
                mutableState.update { it.copy(phase = QuickPhase.SAVED, exportFile = file, message = "数据包已准备好，可以分享或保存。") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError("读取失败：${error.message}") }
        }
    }

    fun saveTo(uri: Uri, path: String? = state.value.exportFile?.absolutePath) {
        if (path == null || activeSession != null || mutableState.value.phase == QuickPhase.SAVING) return
        mutableState.update { it.copy(phase = QuickPhase.SAVING, message = "正在保存到你选的位置…", error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = File(path).canonicalFile
                    check(file.parentFile == File(root, "exports").canonicalFile && file.isFile) { "数据包已失效，请从最近测试重新导出。" }
                    val resolver = getApplication<Application>().contentResolver
                    val output = checkNotNull(resolver.openOutputStream(uri, "wt")) { "保存位置无法写入。" }
                    output.use { dest -> file.inputStream().use { it.copyTo(dest) } }
                    val back = checkNotNull(resolver.openInputStream(uri)) { "无法读回文件，尚未验证保存，请重试。" }
                    val actual = back.use { stream -> val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest() }
                    val expected = file.inputStream().use { stream -> val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest() }
                    check(actual.contentEquals(expected)) { "保存后的文件校验不一致，请换位置重试。" }
                }
                mutableState.update { it.copy(phase = QuickPhase.SAVED, message = "保存成功并校验一致。把这个ZIP文件发给我即可。") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.update { it.copy(phase = QuickPhase.SAVED, error = "保存失败：${error.message}，本机原数据包仍保留。") }
            }
        }
    }

    fun reportShareProblem(message: String) { mutableState.update { it.copy(error = message) } }

    private suspend fun refreshSessions() {
        try {
            val sessions = withContext(Dispatchers.IO) { store?.listSessions().orEmpty() }
            mutableState.update { it.copy(sessions = sessions) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutableState.update { it.copy(error = "历史列表读取失败：${error.message}。本次数据包仍可单独保存或分享。") }
        }
    }

    private fun showError(message: String) {
        mutableState.update { it.copy(phase = QuickPhase.ERROR, error = message, message = "没有继续录音，请检查提示后重试。") }
    }

    override fun onCleared() {
        synchronized(inputGuard) { acceptingPcm = false }
        microphone.close()
        engine.close()
        store?.close()
        super.onCleared()
    }

    companion object {
        const val MAX_SAMPLES = 48_000L * 60
        fun noteName(midi: Int): String = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    }
}
