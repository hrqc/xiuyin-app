package com.xiuyin.app.field

import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.ui.ModelReadyStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FieldSettingsScreen(onOpenField: () -> Unit, onMonitor: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text("在琴房里留下可复查的证据", style = MaterialTheme.typography.titleMedium)
        BuildIdentity()
        Button(onOpenField, modifier = Modifier.fillMaxWidth().testTag("open_field_test")) { Text("开发 / 实地测试") }
        OutlinedButton(onMonitor, modifier = Modifier.fillMaxWidth()) { Text("开发者监控与日志") }
        Text("所有测试数据保存在本机。只有你选择分享或保存时，才交给所选应用或位置。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FieldTestScreen(
    state: FieldDiagnosticsState,
    operation: FieldOperationState,
    audio: AudioSnapshot,
    recognition: RecognitionEngineSnapshot,
    frame: PianoRecognitionFrame?,
    companion: CompanionState,
    microphonePermission: Boolean,
    builtInMicrophoneAvailable: Boolean,
    onStart: (String, Boolean) -> Unit,
    onStopAudio: () -> Unit,
    onEndSession: () -> Unit,
    onRetryModel: () -> Unit,
    onConsent: (Boolean) -> Unit,
    onFeedback: (FieldFeedbackLabel) -> Unit,
    onExport: (String) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onClear: (Set<String>) -> Unit,
    onRefresh: () -> Unit,
    onChooseScore: () -> Unit,
    onPractice: () -> Unit,
    onPermissionSettings: () -> Unit,
    onBack: () -> Unit,
    appDataAvailable: Boolean = true,
    appDataError: String? = null,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var reviewed by rememberSaveable(companion.scoreId, companion.revisionGeneration) { mutableStateOf(false) }
    var clearId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.busy || operation.busy
    val session = state.activeSession
    val listening = audio.status in setOf(AudioStatus.STARTING, AudioStatus.LISTENING)
    val validScore = companion.timeline != null && companion.timeline.measures.isNotEmpty() && companion.score != null && !companion.busy
    val canStart = appDataAvailable && !busy && (session == null || state.error == null) && validScore && recognition.status == RecognitionEngineStatus.READY &&
        builtInMicrophoneAvailable && state.storageWritable && state.freeSpaceBytes >= 10L * 1024 * 1024
    Column(Modifier.fillMaxWidth().testTag("field_content").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("实地测试", style = MaterialTheme.typography.headlineMedium)
        Text(if (session != null) "FIELD TEST MODE · 会话已开启" else "尚未开启实地测试会话", modifier = Modifier.testTag("field_mode"), color = MaterialTheme.colorScheme.primary)
        Text("屏幕仅在此页面位于前台时保持唤醒。进入后台会停止收音；返回后需要手动继续。", style = MaterialTheme.typography.bodySmall)
        BuildIdentity()
        if (!appDataAvailable) {
            Text("本地数据未就绪 · 当前仅可恢复诊断", modifier = Modifier.testTag("field_database_unavailable"), color = MaterialTheme.colorScheme.error)
            Text(appDataError ?: "数据库正在初始化，请稍后再开始。", color = MaterialTheme.colorScheme.error)
            Text("已禁止新开收音与正式测试。已有会话仍可导出、保存或确认清理。", style = MaterialTheme.typography.bodySmall)
        }
        ModelReadyStatus(recognition, onRetryModel)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("开始前检查", style = MaterialTheme.typography.titleMedium)
                Text("数字乐谱：${if (validScore) companion.title else "未就绪，请先从乐谱工作台打开陪练"}")
                Text("麦克风权限：${if (microphonePermission) "已授权" else "未授权，点击开始时请求"}")
                Text("内置麦克风：${if (builtInMicrophoneAvailable) "系统报告可用" else "系统未报告可用设备"}")
                Text("测试目录：${if (state.storageWritable) "可写" else "尚未通过可写检查"} · 剩余 ${bytes(state.freeSpaceBytes)}")
                if (!validScore) OutlinedButton(onChooseScore) { Text("选择数字乐谱") }
                if (audio.status == AudioStatus.PERMISSION_DENIED) OutlinedButton(onPermissionSettings) { Text("打开麦克风权限设置") }
                OutlinedButton(onRefresh, enabled = !busy) { Text("重新检查存储") }
            }
        }
        if (session == null) OutlinedTextField(label, { label = it.take(80) }, label = { Text("现场备注（可选）") }, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(state.clipConsent, onConsent, enabled = !busy, modifier = Modifier.testTag("field_clip_consent"))
            Text("我同意异常或反馈时保存前 3 秒、后 1.5 秒的短音频到本机", modifier = Modifier.padding(top = 10.dp))
        }
        Text("默认只保留内存环形缓冲，不保存整段录音。不勾选也能保存文字标签和识别证据；短片段不足时如实记录。", style = MaterialTheme.typography.bodySmall)
        if (companion.timeline?.issues?.isNotEmpty() == true && companion.phase !in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED, CompanionPhase.PAUSED)) {
            Row { Checkbox(reviewed, { reviewed = it }); Text("我已校对当前谱面的来源和结构警告", Modifier.padding(top = 10.dp)) }
        }
        if (session != null && state.error != null) Text("当前会话记录故障，已禁止继续收音。请结束并导出已有记录，再开启新会话。", color = MaterialTheme.colorScheme.error)
        if (!listening) Button(onClick = { onStart(label, reviewed) }, enabled = canStart,
            modifier = Modifier.fillMaxWidth().testTag("field_start")) { Text(if (session == null) "开启实地测试" else "继续现场收音") }
        else OutlinedButton(onStopAudio, modifier = Modifier.fillMaxWidth().testTag("field_stop_audio")) { Text("暂停现场收音") }
        if (session != null) OutlinedButton(onEndSession, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("field_end")) { Text("结束实地测试会话") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("真实现场状态", style = MaterialTheme.typography.titleMedium)
                Text("会话：${session?.id ?: "无"}", modifier = Modifier.testTag("field_session"))
                Text("麦克风：${audio.status.name} · ${audio.statusMessage}")
                Text("实际输入：${audio.actualInputRoute ?: "尚未采集"} · ${audio.actualAudioSourceName ?: "未测得音源"}")
                Text("输入设备：${audio.deviceName ?: "系统未提供名称"} · ${if (audio.totalFrames > 0) "采样率" else "计划采样率"} ${audio.sampleRate} Hz · 已采集 ${audio.totalFrames} 帧")
                Text("电平：${number(audio.inputLevelDbfs)} dBFS · 底噪：${number(audio.noiseFloorDbfs)} dBFS")
                Text("练习：${companion.phase.name} · LOCKED=${companion.locked}", modifier = Modifier.testTag("field_lock"))
                Text("位置：${if (validScore) "第 ${companion.measureIndex + 1} 小节 · 起音位置 ${companion.cursor + 1}" else "无有效谱面"}")
                Text("目标 MIDI：${companion.expectedMidi.sorted().joinToString().ifEmpty { "暂无" }} · 输入 MIDI：${companion.detectedMidi.sorted().joinToString().ifEmpty { "暂无" }}")
                Text("当前决策：${companion.decision.name} · ${companion.reason}")
                Text("模型会话：${recognition.sessionId} · 实际推理帧 ${recognition.frameCount} · 队列 ${recognition.queueOccupancy}/${recognition.queueCapacity}")
                Text("丢弃 PCM 块 ${recognition.droppedFrames} · 溢出 ${recognition.overflowCount} · 推理耗时 ${number(recognition.computeMs)} ms")
                frame?.octaveDiagnostics?.take(4)?.forEach { item -> Text("八度 ${item.lowerMidi}/${item.upperMidi}：${item.finalState.name}\n${item.reason}", style = MaterialTheme.typography.bodySmall) }
                Text("模型 SHA256：${recognition.modelSha256}", style = MaterialTheme.typography.bodySmall)
                Text("现场演奏准确率、误停率：尚未完成真人琴房验证。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("给这次判断留下反馈", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onFeedback(FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG) }, enabled = session != null && state.error == null && !busy,
            modifier = Modifier.fillMaxWidth().testTag("field_feedback_dispute")) { Text("我弹对了") }
        OutlinedButton({ onFeedback(FieldFeedbackLabel.USER_CONFIRMS_WRONG_NOTE) }, enabled = session != null && state.error == null && !busy,
            modifier = Modifier.fillMaxWidth().testTag("field_feedback_confirm")) { Text("这里确实弹错了") }
        Text("反馈是待复核的人工标签，不代表准确率，也不会解锁或移动谱面。未标注的事件保持 UNLABELED。", style = MaterialTheme.typography.bodySmall)
        if (validScore) OutlinedButton(onPractice, enabled = !busy) { Text("查看实时陪练谱面") }
        operation.error?.let { Text(it, modifier = Modifier.testTag("field_operation_error"), color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        (operation.message ?: state.message)?.let { Text(it, modifier = Modifier.testTag("field_message"), color = MaterialTheme.colorScheme.primary) }
        Text("测试包与存储", style = MaterialTheme.typography.titleMedium)
        Text("实际占用 ${bytes(state.totalBytes)} · ${state.clipCount} 个短片段 · ${state.sessions.size} 个会话")
        Text("上限 200 MiB / 300 个片段 / 20 个会话。只自动清理最旧且已保存导出的会话；未导出内容需要你确认。", style = MaterialTheme.typography.bodySmall)
        if (session != null) Button({ onExport(session.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("field_export_current")) { Text("一键导出日志 ZIP") }
        if (operation.exportedFile != null) {
            Text("已生成 ${operation.exportedFile.name} · ${bytes(operation.exportedFile.length())}")
            Button(onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("field_save_zip")) { Text("保存 ZIP 到文件") }
            OutlinedButton(onShare, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("field_share_zip")) { Text("分享测试 ZIP") }
        }
        if (state.sessions.isEmpty()) Text("尚无测试会话；不会生成空白成功报告。")
        state.sessions.sortedByDescending { it.startedAtMillis }.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.label.ifBlank { "现场测试" }, style = MaterialTheme.typography.titleSmall)
                    Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.startedAtMillis))} · ${item.status.name}")
                    Text("${item.id} · ${bytes(item.bytes)} · ${item.clipCount} 个片段", style = MaterialTheme.typography.bodySmall)
                    if (item.status == FieldSessionStatus.INCOMPLETE) Text("上次未正常结束，已有记录可恢复导出。", color = MaterialTheme.colorScheme.error)
                    Text(if (item.exportedAtMillis == null) "未确认保存导出" else "已保存导出")
                    OutlinedButton({ onExport(item.id) }, enabled = !busy, modifier = Modifier.testTag("field_export_${item.id}")) { Text("一键导出此会话 ZIP") }
                    if (item.id != session?.id) TextButton({ clearId = item.id }, enabled = !busy, modifier = Modifier.testTag("field_clear_${item.id}")) { Text("清理此会话") }
                }
            }
        }
        OutlinedButton(onBack, enabled = !busy) { Text("返回设置") }
    }
    clearId?.let { id ->
        val item = state.sessions.firstOrNull { it.id == id }
        AlertDialog(onDismissRequest = { clearId = null }, title = { Text("确认清理测试会话") },
            text = { Text("删除此会话的本地日志、短音频和 ZIP。${if (item?.exportedAtMillis == null) "该会话尚未确认保存导出，请先保留所需测试包。" else "请确认已经保留需要的测试包。"}") },
            confirmButton = { TextButton({ clearId = null; onClear(setOf(id)) }, modifier = Modifier.testTag("field_clear_confirm")) { Text("确认清理") } },
            dismissButton = { TextButton({ clearId = null }, modifier = Modifier.testTag("field_clear_cancel")) { Text("取消") } })
    }
}

@Composable
private fun BuildIdentity() {
    Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nGit Commit：${BuildConfig.GIT_COMMIT}\nField Tag：${BuildConfig.FIELD_TAG}\nGit Tag at Build：${BuildConfig.GIT_TAG}\nBuild Time：${BuildConfig.BUILD_TIME}",
        modifier = Modifier.testTag("field_build_identity"), style = MaterialTheme.typography.bodySmall)
    Text("设备：${Build.MANUFACTURER} / ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodySmall)
    Text("Field Tag 为本版交付目标；Git Tag at Build 是构建时的真实标签。最终交付清单核验标签与提交的对应关系。", style = MaterialTheme.typography.bodySmall)
}
private fun bytes(value: Long): String = String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0)
private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "未测得"