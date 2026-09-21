package com.xiuyin.app.calibration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.PitchEvidenceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CalibrationState(
    val busy: Boolean = false, val calibrating: Boolean = false, val profile: PianoCalibrationProfile? = null,
    val caseConsent: Boolean = false, val cases: List<CaseSummary> = emptyList(),
    val audioObservations: Int = 0, val recognitionObservations: Int = 0, val measuredSeconds: Double = 0.0,
    val currentInputDbfs: Double? = null, val currentNoiseDbfs: Double? = null,
    val environmentWarning: String? = null, val message: String? = null, val error: String? = null,
)

/** Root forwards real PCM and model frames. No microphone is opened and no input is synthesized here. */
class CalibrationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CalibrationState(busy = true))
    val state: StateFlow<CalibrationState> = mutable.asStateFlow()
    private val ring = PcmRingBuffer()
    private val profiles = CalibrationProfileStore(File(application.filesDir, "piano-calibration"))
    private val cases = MisjudgmentCaseStore(File(application.filesDir, "misjudgment-cases"))
    private var measurement = Measurement()
    private var warningFrames = 0
    private var initializing = true
    private val recentOnsets = ArrayDeque<Double>()
    private var recognitionWarning = false
    init { operation({ profiles.load() to cases.list() }) { loaded -> mutable.value = mutable.value.copy(profile = loaded.first, cases = loaded.second) } }
    fun appendPcm(samples: ShortArray, count: Int, firstInputSample: Long) = ring.append(samples, count, firstInputSample)
    fun clearMemory() { ring.clear(); viewModelScope.launch { recentOnsets.clear(); recognitionWarning = false } }
    fun setCaseConsent(consent: Boolean) { mutable.value = mutable.value.copy(caseConsent = consent, message = if (consent) "仅异常事件或主动反馈允许保存最多 5 秒音频；不会保存整次演奏" else "已关闭后续音频案例保存；已有案例可单独清理", error = null) }
    fun startCalibration() {
        if (mutable.value.busy) return
        measurement = Measurement(); warningFrames = 0
        mutable.value = mutable.value.copy(calibrating = true, audioObservations = 0, recognitionObservations = 0, measuredSeconds = 0.0, error = null, message = "请先安静数秒，再按引导演奏；保存需要至少 5 秒真实输入")
    }
    fun cancelCalibration() {
        if (mutable.value.calibrating) mutable.value = mutable.value.copy(calibrating = false, message = "校准已中断，原 Profile 保留；可重新校准")
    }
    fun acceptAudio(snapshot: AudioSnapshot) {
        viewModelScope.launch {
            if (snapshot.status != AudioStatus.LISTENING) {
                if (mutable.value.calibrating && (snapshot.status in setOf(AudioStatus.PERMISSION_DENIED, AudioStatus.UNAVAILABLE, AudioStatus.ERROR, AudioStatus.SILENCED) || (snapshot.status == AudioStatus.STARTING && measurement.inputCount > 0))) {
                    cancelCalibration(); mutable.value = mutable.value.copy(error = "校准输入中断：${snapshot.statusMessage}；原 Profile 保留")
                }
                return@launch
            }
            val profile = mutable.value.profile
            val warning = profile?.environmentWarning(snapshot.noiseFloorDbfs, snapshot.inputLevelDbfs, null)
            warningFrames = if (warning != null) (warningFrames + 1).coerceAtMost(20) else 0
            mutable.value = mutable.value.copy(currentInputDbfs = snapshot.inputLevelDbfs, currentNoiseDbfs = snapshot.noiseFloorDbfs,
                environmentWarning = if (recognitionWarning) "当前环境与上次校准差异较大，建议重新校准。" else if (warningFrames >= 20) warning else null)
            if (!mutable.value.calibrating || snapshot.totalFrames <= measurement.lastAudioFrame) return@launch
            val time = snapshot.capturedAtNanos ?: return@launch
            if (measurement.inputCount > 0 && (measurement.device != snapshot.deviceName || measurement.sampleRate != snapshot.sampleRate)) {
                cancelCalibration(); mutable.value = mutable.value.copy(error = "校准期间输入设备改变，请重新校准"); return@launch
            }
            measurement.device = snapshot.deviceName; measurement.sampleRate = snapshot.sampleRate
            measurement.lastAudioFrame = snapshot.totalFrames
            if (measurement.startNanos == null) measurement.startNanos = time
            measurement.endNanos = time
            snapshot.inputLevelDbfs?.takeIf { it.isFinite() }?.let { measurement.inputSum += it; measurement.inputCount++; measurement.peak = maxOf(measurement.peak ?: it, it) }
            snapshot.noiseFloorDbfs?.takeIf { it.isFinite() }?.let { measurement.noiseSum += it; measurement.noiseCount++ }
            mutable.value = mutable.value.copy(audioObservations = measurement.inputCount, measuredSeconds = measurement.seconds)
        }
    }
    fun acceptRecognition(frame: PianoRecognitionFrame) {
        viewModelScope.launch {
            if (frame.clipped) return@launch
            frame.notes.filter { it.midi in frame.onsetMidi }.forEach { note ->
                recentOnsets.addLast(note.onsetScore.toDouble()); if (recentOnsets.size > 20) recentOnsets.removeFirst()
            }
            val referenceScore = mutable.value.profile?.meanOnsetScore
            if (recentOnsets.size == 20 && referenceScore != null) recognitionWarning = referenceScore - recentOnsets.average() > 0.20
            if (!mutable.value.calibrating || frame.frameIndex <= measurement.lastRecognitionFrame) return@launch
            measurement.lastRecognitionFrame = frame.frameIndex; measurement.recognitionFrames++
            frame.notes.filter { it.midi in frame.onsetMidi }.forEach { measurement.onsetSum += it.onsetScore; measurement.onsetCount++ }
            mutable.value = mutable.value.copy(recognitionObservations = measurement.recognitionFrames)
        }
    }
    fun finishCalibration() {
        if (!mutable.value.calibrating || mutable.value.busy) return
        if (measurement.seconds < 5 || measurement.inputCount == 0) { mutable.value = mutable.value.copy(error = "需要至少 5 秒实际麦克风输入才能保存校准"); return }
        val value = PianoCalibrationProfile(System.currentTimeMillis(), measurement.device, measurement.sampleRate, measurement.inputCount, measurement.seconds,
            if (measurement.noiseCount > 0) measurement.noiseSum / measurement.noiseCount else null,
            measurement.inputSum / measurement.inputCount, measurement.peak,
            if (measurement.onsetCount > 0) measurement.onsetSum / measurement.onsetCount else null,
            measurement.recognitionFrames, mutable.value.profile?.parameters ?: PitchEvidenceConfig())
        mutable.value = mutable.value.copy(calibrating = false)
        operation({ profiles.save(value) }) { mutable.value = mutable.value.copy(profile = value, message = "真实环境指标已保存；延迟、混响和踏板残响尚未测得") }
    }
    fun applyParameters(parameters: PitchEvidenceConfig) {
        val profile = mutable.value.profile
        if (profile == null) { mutable.value = mutable.value.copy(error = "请先完成一次真实环境校准"); return }
        val updated = profile.copy(parameters = parameters)
        operation({ profiles.save(updated) }) { mutable.value = mutable.value.copy(profile = updated, message = "有界识别参数已保存，下次启动识别时生效；未修改模型权重") }
    }
    fun resetDefaults() = applyParameters(PitchEvidenceConfig())
    fun saveCase(context: MisjudgmentContext, audioTimeSeconds: Double?) {
        if (!mutable.value.caseConsent) { mutable.value = mutable.value.copy(error = "尚未同意保存短音频，请在环境校准页明确勾选；反馈不会自动解锁"); return }
        if (audioTimeSeconds == null || !audioTimeSeconds.isFinite() || audioTimeSeconds < 0) {
            mutable.value = mutable.value.copy(error = "缺少当前音频流的错误帧时刻，无法绑定案例音频；反馈未解锁练习"); return
        }
        val clip = ring.snapshotAt(audioTimeSeconds)
        if (clip == null) { mutable.value = mutable.value.copy(error = "错误帧音频已过期或不在最近 5 秒缓存内，未保存无关音频；反馈未解锁练习"); return }
        operation({ cases.save(context, clip, consent = true); cases.list() }) { saved ->
            mutable.value = mutable.value.copy(cases = saved, message = "案例已保存在本机；用户反馈不是校验真值，未自动解锁或训练模型")
        }
    }
    fun clearCases() { operation({ cases.clear() }) { count -> mutable.value = mutable.value.copy(cases = emptyList(), message = "已清理 $count 个本地识别案例") } }
    private fun <T> operation(block: suspend () -> T, onSuccess: (T) -> Unit) {
        if (mutable.value.busy && !initializing) return
        initializing = false
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { onSuccess(withContext(Dispatchers.IO) { block() }) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = mutable.value.copy(error = error.message ?: "校准存储失败")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    override fun onCleared() { ring.clear(); super.onCleared() }
    private class Measurement {
        var device: String? = null; var sampleRate = 48000; var lastAudioFrame = -1L; var lastRecognitionFrame = -1L
        var startNanos: Long? = null; var endNanos = 0L
        var inputSum = 0.0; var inputCount = 0; var peak: Double? = null
        var noiseSum = 0.0; var noiseCount = 0; var onsetSum = 0.0; var onsetCount = 0; var recognitionFrames = 0
        val seconds: Double get() = ((endNanos - (startNanos ?: endNanos)) / 1_000_000_000.0).coerceAtLeast(0.0)
    }
}



