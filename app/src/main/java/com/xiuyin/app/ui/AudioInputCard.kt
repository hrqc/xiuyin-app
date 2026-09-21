package com.xiuyin.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.audio.FinalDecision
import com.xiuyin.app.audio.OnsetState
import java.util.Locale

/** Read-only presentation of engine evidence; all recording actions belong to the parent. */
@Composable
fun AudioInputCard(
    audio: AudioSnapshot,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val captureActive = audio.status in setOf(
        AudioStatus.STARTING, AudioStatus.LISTENING, AudioStatus.STOPPING, AudioStatus.SILENCED,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("麦克风检查", style = MaterialTheme.typography.titleLarge)
            Text(
                audioStatusLabel(audio),
                style = MaterialTheme.typography.titleMedium,
                color = when (audio.status) {
                    AudioStatus.ERROR -> MaterialTheme.colorScheme.error
                    AudioStatus.LISTENING -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.testTag("audio_status"),
            )
            Text(audio.statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (audio.status == AudioStatus.PERMISSION_DENIED) {
                Text(
                    "麦克风权限未允许。可重新申请；如果系统不再弹出授权框，请打开系统权限设置。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onOpenPermissionSettings) { Text("打开系统权限设置") }
            }
            if (captureActive) {
                OutlinedButton(
                    onClick = onStopAudio,
                    enabled = audio.status != AudioStatus.STOPPING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("audio_stop"),
                ) { Text(if (audio.status == AudioStatus.STOPPING) "正在停止…" else "停止监听") }
            } else {
                Button(
                    onClick = onStartAudio,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("audio_start"),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("开始麦克风检查") }
            }
            Text(
                "主动开始后才申请权限。声音仅在本机即时处理，不保存录音；退出音频页面或进入后台会停止监听。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("输入数据 · 最近一帧", style = MaterialTheme.typography.titleMedium)
            AudioMetric("输入设备", audio.deviceName ?: "尚未选择输入设备", "audio_device")
            AudioMetric("输入电平", audioMetricNumber(audio.inputLevelDbfs, "dBFS"), "audio_input_level")
            audio.inputLevelDbfs?.takeIf { it.isFinite() }?.let { level ->
                LinearProgressIndicator(
                    progress = { ((level.coerceIn(-80.0, 0.0) + 80.0) / 80.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().semantics {
                        stateDescription = "输入电平 ${audioMetricNumber(level, "dBFS")}"
                    },
                    color = if (audio.clipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
            AudioMetric("估计底噪", audioMetricNumber(audio.noiseFloorDbfs, "dBFS"), "audio_noise_floor")
            if (audio.clipped) {
                Text(
                    "输入过强，出现削波。请适当远离钢琴或减小输入响度；当前信号不用于确认起音。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AudioMetric("起音状态", onsetStateLabel(audio.onsetState), "audio_onset_state")
            AudioMetric("候选起音次数", if (audio.totalFrames > 0) audio.onsetCount.toString() else "尚未采集", "audio_onset_count")
            AudioMetric("起音证据分（0–1）", audioMetricNumber(audio.onsetConfidence, "", 2), "audio_onset_confidence")
            Text(
                "证据分只描述输入信号的起音迹象，不是音符识别准确率，也不计入连续正确次数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AudioMetric("检测音符", detectedNotesLabel(audio), "audio_detected_notes")
            AudioMetric("演奏判断", finalDecisionLabel(audio.decision), "audio_final_decision")
            Text(
                "此卡片只检查收音与候选起音；音符识别与跟谱请从乐谱工作台开始陪练。暂无法判断时不锁定位置，也不增加或清零正确次数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioMetric(label: String, value: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
    }
}

internal fun audioStatusLabel(audio: AudioSnapshot): String = when (audio.status) {
    AudioStatus.IDLE -> "⚪ 未运行 · 尚未开始采集"
    AudioStatus.STARTING -> "🟡 正在启动 · 等待有效输入"
    AudioStatus.LISTENING -> if (audio.totalFrames > 0 && audio.capturedAtNanos != null) {
        "🟢 正常 · 正在采集输入"
    } else {
        "🟡 等待输入 · 尚无有效帧"
    }
    AudioStatus.STOPPING -> "🟡 正在停止 · 释放麦克风"
    AudioStatus.PERMISSION_DENIED -> "🟡 麦克风权限未允许"
    AudioStatus.UNAVAILABLE -> "🟡 麦克风不可用"
    AudioStatus.ERROR -> "🔴 音频采集错误"
    AudioStatus.SILENCED -> "🟡 系统已静音 · 无法判断输入"
}

internal fun onsetStateLabel(state: OnsetState): String = when (state) {
    OnsetState.WARMING_UP -> "等待输入与底噪预热"
    OnsetState.SILENCE -> "静音或低于检测阈值"
    OnsetState.ATTACK -> "候选起音 · 已获多帧证据"
    OnsetState.SUSTAIN -> "持续输入 · 不重复累计起音"
    OnsetState.UNCERTAIN -> "起音不确定 · 等待后续证据"
}

internal fun finalDecisionLabel(decision: FinalDecision): String = when (decision) {
    FinalDecision.CORRECT -> "正确（CORRECT）"
    FinalDecision.WRONG -> "错误（WRONG）"
    FinalDecision.MISSED -> "漏音（MISSED）"
    FinalDecision.UNCERTAIN -> "暂无法判断（UNCERTAIN）"
}

internal fun detectedNotesLabel(audio: AudioSnapshot): String =
    if (audio.notes.isEmpty()) {
        "暂无（输入诊断不进行音符判定）"
    } else {
        audio.notes.joinToString("、") { note -> "MIDI ${note.midi}" }
    }

internal fun audioMetricNumber(value: Double?, unit: String, decimals: Int = 1): String =
    value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.ROOT, "%.${decimals}f", it) + if (unit.isEmpty()) "" else " $unit"
    } ?: "尚未采集"