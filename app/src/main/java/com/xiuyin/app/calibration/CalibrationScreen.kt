package com.xiuyin.app.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiuyin.app.pitch.PitchEvidenceConfig
import java.util.Locale

@Composable
fun CalibrationScreen(
    state: CalibrationState,
    onStartCalibration: () -> Unit,
    onFinishCalibration: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onApplyParameters: (PitchEvidenceConfig) -> Unit,
    onResetDefaults: () -> Unit,
    onClearCases: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var onset by rememberSaveable(state.profile?.parameters) { mutableStateOf((state.profile?.parameters?.onsetThreshold ?: 0.60f).toString()) }
    var frame by rememberSaveable(state.profile?.parameters) { mutableStateOf((state.profile?.parameters?.frameThreshold ?: 0.50f).toString()) }
    var parameterError by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("钢琴环境校准", style = MaterialTheme.typography.headlineSmall)
        Text("先保持安静数秒，再弹 C2、C3、C4、C5、C6，若干三和弦、双手和弦，以及一次延音踏板演奏。未自动验证你是否完成全部示范。")
        Text("平时仅保留最近 5 秒 PCM 在内存，旧样本自动覆盖，退出收音时清空。", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.environmentWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("当前输入：${metric(state.currentInputDbfs)} dBFS · 底噪：${metric(state.currentNoiseDbfs)} dBFS")
        if (state.calibrating) {
            Text("已观察 ${metric(state.measuredSeconds)} 秒 · 输入 ${state.audioObservations} 次 · 识别 ${state.recognitionObservations} 帧")
            Button(onClick = onFinishCalibration, enabled = !state.busy && state.measuredSeconds >= 5) { Text("保存实际校准指标") }
        } else Button(onClick = onStartCalibration, enabled = !state.busy) { Text("重新校准") }
        val profile = state.profile
        if (profile == null) Text("尚无校准 Profile，使用保守默认识别参数") else {
            Text("当前 Profile", style = MaterialTheme.typography.titleMedium)
            Text("设备：${profile.deviceName ?: "系统未提供名称"} · ${profile.sampleRate} Hz\n实际观察 ${metric(profile.observedSeconds)} 秒\n平均输入 ${metric(profile.meanInputDbfs)} dBFS · 峰值 ${metric(profile.peakInputDbfs)} dBFS\n底噪 ${metric(profile.noiseFloorDbfs)} dBFS\n确认起音激活均值 ${metric(profile.meanOnsetScore)}（不是准确率）")
            Text("设备音频延迟：${metric(profile.latencyMs)}\n房间混响：${metric(profile.reverbSeconds)}\n踏板残响：${metric(profile.pedalResidualSeconds)}\n未知项目不生成估值。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(onset, { onset = it }, label = { Text("起音阈值 0.60–0.85") }, singleLine = true)
            OutlinedTextField(frame, { frame = it }, label = { Text("持续证据阈值 0.50–0.75") }, singleLine = true)
            parameterError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                try {
                    val config = PitchEvidenceConfig(requireNotNull(onset.toFloatOrNull()), requireNotNull(frame.toFloatOrNull()))
                    parameterError = null; onApplyParameters(config)
                } catch (_: IllegalArgumentException) { parameterError = "请输入允许范围内的有限数值" }
            }, enabled = !state.busy && !state.calibrating) { Text("应用有界参数（下次启动生效）") }
            OutlinedButton(onClick = onResetDefaults, enabled = !state.busy && !state.calibrating) { Text("恢复默认识别参数") }
            Text("参数不会自动漂移；少量反馈不会训练主模型。提高阈值可能让弱音保持 UNCERTAIN。", style = MaterialTheme.typography.bodySmall)
        }
        Text("本地误判案例", style = MaterialTheme.typography.titleMedium)
        Row {
            Checkbox(state.caseConsent, onConsentChange)
            Text("我同意异常事件或主动反馈时保存最多 5 秒音频及识别证据到本机")
        }
        Text("默认关闭；最多 200 项且总量不超过 100 MiB，超过时清理最旧案例。不会保存整次演奏，不上传。反馈不是校验真值，也不会自动解锁。", style = MaterialTheme.typography.bodySmall)
        Text("当前 ${state.cases.size} 项 · ${metric(state.cases.sumOf { it.bytes } / 1048576.0)} MiB")
        state.cases.take(8).forEach { value ->
            Text("${value.context.trigger} · ${metric(value.durationSeconds)} 秒 · ${value.context.finalDecision}\n用户反馈：${if (value.context.userDisagrees) "认为判断有误（待核查）" else "无"}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { confirmClear = true }, enabled = !state.busy) { Text("一键清理识别调试数据") }
        OutlinedButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清理识别调试数据") },
        text = { Text("删除本机所有误判案例及短音频，保留当前校准 Profile。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClearCases() }) { Text("确认清理") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
private fun metric(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "尚未测得"
