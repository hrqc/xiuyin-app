package com.xiuyin.app.monitor

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.calibration.CalibrationState
import com.xiuyin.app.AppState
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.score.ScoreImportState
import com.xiuyin.app.data.LogEntry
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.audio.OnsetState
import com.xiuyin.app.ui.audioMetricNumber
import com.xiuyin.app.ui.audioStatusLabel
import com.xiuyin.app.ui.onsetStateLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(state: AppState, onRefresh: () -> Unit, onClearLogs: () -> Unit, audio: AudioSnapshot, scores: ScoreImportState = ScoreImportState(), recognition: RecognitionEngineSnapshot = RecognitionEngineSnapshot(), pianoFrame: PianoRecognitionFrame? = null, companion: CompanionState = CompanionState(), calibration: CalibrationState = CalibrationState(), onCalibration: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<String?>(null) }
    var frozenLogs by remember { mutableStateOf<List<LogEntry>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }
    var memoryMb by remember { mutableStateOf<Float?>(null) }
    var cpuPercent by remember { mutableStateOf<Float?>(null) }
    var sampledAt by remember { mutableStateOf<Long?>(null) }
    var metricsError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh by rememberUpdatedState(onRefresh)

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var priorCpu: Long? = null
            var priorWall: Long? = null
            while (isActive) {
                currentRefresh()
                try {
                    val sample = withContext(Dispatchers.Default) {
                        val info = Debug.MemoryInfo()
                        Debug.getMemoryInfo(info)
                        Triple(info.totalPss / 1024f, Process.getElapsedCpuTime(), SystemClock.elapsedRealtime())
                    }
                    memoryMb = sample.first
                    val cpuStart = priorCpu
                    val wallStart = priorWall
                    cpuPercent = if (cpuStart != null && wallStart != null && sample.third > wallStart) {
                        (sample.second - cpuStart).toFloat() / (sample.third - wallStart) * 100f
                    } else {
                        null
                    }
                    priorCpu = sample.second
                    priorWall = sample.third
                    sampledAt = System.currentTimeMillis()
                    metricsError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RuntimeException) {
                    metricsError = error.message ?: error.javaClass.simpleName
                    memoryMb = null
                    cpuPercent = null
                }
                delay(5_000)
            }
        }
    }

    val visibleLogs = filterLogs(frozenLogs ?: state.logs, query, selectedLevel)
    val paused = frozenLogs != null
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("开发者监控", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "本机状态每 5 秒刷新 · 音频指标随输入更新",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MonitorCard("版本与数据") {
                MetricRow("当前版本", "v${BuildConfig.VERSION_NAME}")
                MetricRow("Git 基础提交", BuildConfig.GIT_COMMIT)
                MetricRow("构建时工作区", when (BuildConfig.GIT_WORKTREE_STATE) {
                    "CLEAN" -> "已提交 · 工作区干净"
                    "MODIFIED" -> "有未提交修改 · 基础提交不代表全部构建代码"
                    else -> "未知 · 未能验证 Git 工作区"
                })
                MetricRow("Git Tag（构建时）", BuildConfig.GIT_TAG)
                MetricRow("数据位置", "设备本地 SQLite 与私有归档")
                MetricRow("音频保存", "默认仅在内存；短音频案例须本次明确同意后保存")
                MetricRow("当前页面", "开发者监控")
                state.error?.let {
                    Text("错误：$it", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.saving) {
                    Text("刷新状态")
                }
            }
        }
        item {
            MonitorCard("模块运行状态") {
                MetricRow("Frontend / App", "🟢 正常 · 当前页面已运行")
                MetricRow(
                    "Database",
                    when {
                        state.loading -> "🟡 检查中"
                        state.databaseReady -> "🟢 正常 · 最近一次数据库检查通过"
                        state.error != null -> "🔴 错误 · 数据库不可用"
                        else -> "⚪ 未运行 · 尚无检查结果"
                    },
                )
                MetricRow("Audio Engine", audioStatusLabel(audio))
                MetricRow("Pitch Engine", "${recognition.status} · ${recognition.message}")
                MetricRow("Clavier 单音参考", pianoFrame?.singlePitchReference?.let {
                    "${if (recognition.status == RecognitionEngineStatus.RUNNING) "最近参考" else "⚪ 未运行 · 上次参考"} ${it.status.name} · MIDI ${it.best?.midi ?: "暂无"} · ${it.computeMs ?: "未测得"} ms · 仅独立参考\n${it.error.orEmpty()}"
                } ?: "⚪ 未运行 · 尚无参考帧（默认关闭）")
                MetricRow("OMR Engine", if (scores.importing) scores.progress ?: "本地处理中" else if (scores.selectedScore?.source?.format != null && scores.selectedScore.source.format != "MusicXML") "已载入本地识谱结果 · 待人工校对" else "本地模型可用 · 尚无当前图像识别运行")
                MetricRow("Score Engine", when {
                    scores.importing -> "🟡 正在处理 · ${scores.progress.orEmpty()}"
                    scores.error != null || scores.storageIssues.isNotEmpty() -> "🟡 警告 · 请查看数字乐谱页"
                    scores.unreadableScoreCount > 0 -> "🟡 警告 · 部分本地乐谱无法读取"
                    scores.selectedScore?.issues?.isNotEmpty() == true -> "🟡 待确认 · ${scores.selectedScore.issues.size} 处疑点"
                    scores.selectedScore != null -> "🟢 已解析 · 基础结构检查通过"
                    scores.totalScoreCount > 0 -> "🟢 本地来源已读取 · ${scores.totalScoreCount} 份"
                    else -> "⚪ 未运行 · 尚未导入数字乐谱"
                })
                MetricRow("Tracking Engine", "${companion.phase} · ${companion.reason}")
                MetricRow("Microphone", audio.deviceName ?: if (audio.status == AudioStatus.IDLE) "未监听 · 尚未选择输入设备" else audio.statusMessage)
                MetricRow("Backend / API", "不适用 · 本版无后端服务")
            }
        }
        item {
            MonitorCard("数字乐谱与导入") {
                MetricRow("输入类型", scores.selectedScore?.source?.format ?: "尚未选择")
                MetricRow("本地来源数量", scores.totalScoreCount.toString())
                MetricRow("当前标题", scores.selectedMetadata?.displayTitle ?: "尚未打开")
                MetricRow("Score ID", scores.selectedMetadata?.scoreId ?: "尚无")
                MetricRow("来源 SHA256", scores.selectedScore?.id ?: "尚无")
                MetricRow("校验疑点", scores.selectedScore?.issues?.size?.toString() ?: "尚未解析")
                MetricRow("OMR 分数 / 区域", if (scores.selectedScore?.source?.format == "MusicXML") "不适用 · MusicXML直接解析" else "实际模型分数，未校准；区域为谱表边界，非精确音符框")
                MetricRow("图像识别记录", "原图分块、谱表裁切、二次识别证据随来源归档；详见原图校对")
                Text("修订乐谱可从工作台开始陪练；原图、音符修订与报告只保存在本机。", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            MonitorCard("练习与实时链路") {
                MetricRow("当前练习模式", if (companion.scoreId != null) companion.config.mode.label else state.preparation?.mode?.label ?: "尚未设置")
                MetricRow("目标连续正确次数", state.preparation?.repeatTarget?.toString() ?: "尚未设置")
                MetricRow("当前练习状态", companion.phase.name)
                MetricRow("LOCKED / 连续正确次数", "${companion.phase} / ${companion.consecutivePasses}")
                MetricRow("当前小节 / 起音", if (companion.scoreId == null) "尚未开始" else "${companion.measureIndex + 1} / ${companion.cursor + 1}")
                MetricRow("目标 / 本次新起音 MIDI", "${companion.expectedMidi} / ${companion.detectedMidi}")
                MetricRow("连续频率 / 音分偏差", "当前模型识别离散琴键；未实现调音器音分测量")
                MetricRow("Score Following", "${companion.decision} · ${companion.reason}")
                MetricRow("Audio Latency", audioMetricNumber(audio.audioLatencyMs, "ms"))
                MetricRow("Pitch 计算耗时", recognition.computeMs?.let { "${it}ms；不等于端到端延迟" } ?: "尚未推理")
                MetricRow("模型输入上下文", pianoFrame?.let { "${it.modelWindowMs}ms；实际录音时刻=${it.audioTimeSeconds}s" } ?: "尚未产生识别帧")
                MetricRow("原始模型起音 / 持续 MIDI", pianoFrame?.let { "${it.onsetMidi} / ${it.activeMidi}" } ?: "尚无")
                MetricRow("踏板状态", pianoFrame?.pedalState ?: "UNKNOWN")
                MetricRow("Following / UI Cursor Latency", "尚未采集")
                MetricRow("FPS / OMR Processing Time", "帧率尚未采集；OMR 用时记录在本地导入日志")
                MetricRow("校准 Profile", calibration.profile?.let { "${it.deviceName} · ${it.observedSeconds}秒真实输入" } ?: "尚未校准")
                MetricRow("本地反馈案例", "${calibration.cases.size}项；保存授权=${calibration.caseConsent}")
                calibration.environmentWarning?.let { MetricRow("校准提醒", it) }
                OutlinedButton(onClick = onCalibration) { Text("环境校准与误判案例") }
            }
        }
        item {
            MonitorCard("音频输入与判断证据") {
                MetricRow("采集状态", audio.statusMessage)
                MetricRow("输入电平", audioMetricNumber(audio.inputLevelDbfs, "dBFS"))
                MetricRow("估计底噪", audioMetricNumber(audio.noiseFloorDbfs, "dBFS"))
                MetricRow("采样配置", "${audio.sampleRate} Hz · ${audio.frameSamples} 采样/帧")
                MetricRow("本次有效帧数", audio.totalFrames.toString())
                MetricRow("最近输入时间", audio.capturedAtNanos?.let { "${it / 1_000_000} ms · 单调时钟" } ?: "尚无有效帧")
                MetricRow("削波", if (audio.totalFrames == 0L) "尚未采集" else if (audio.clipped) "警告 · 输入过强" else "当前帧未见削波")
                MetricRow("起音状态", onsetStateLabel(audio.onsetState))
                MetricRow("持续音状态", if (audio.onsetState == OnsetState.SUSTAIN) "持续输入 · 不重复累计起音" else "未确认持续输入")
                MetricRow("候选起音次数", if (audio.totalFrames > 0) audio.onsetCount.toString() else "尚未采集")
                MetricRow("起音证据分（0–1）", audioMetricNumber(audio.onsetConfidence, "", 2))
                MetricRow("检测音符（DetectedNotes）", pianoFrame?.onsetMidi?.joinToString("、") ?: "尚无真实识别帧")
                MetricRow("音符激活证据", pianoFrame?.notes?.joinToString("、") { "${it.midi}: onset=${it.onsetScore}, frame=${it.frameScore}" } ?: "尚无真实识别帧")
                MetricRow("当前乐谱候选", companion.expectedMidi.toString())
                MetricRow("最终判断", "${companion.decision} · ${companion.reason}")
                MetricRow("UNCERTAIN 音符 / 原因", pianoFrame?.candidateRejections?.toString() ?: "尚无识别证据")
                MetricRow("输入帧分析耗时", audioMetricNumber(audio.processingMs, "ms", 2))
                Text(
                    "起音证据分不是音符准确率。输入帧分析耗时是本地计算用时，不代表音频端到端延迟或识别延迟。钢琴校准与真实琴房效果尚未实测。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MonitorCard("进程采样") {
                MetricRow("PSS 内存", memoryMb?.let { String.format(Locale.ROOT, "%.1f MB", it) } ?: "尚未采集")
                MetricRow("CPU · 按单核计", cpuPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "等待下一次采样")
                MetricRow("采样时间", sampledAt?.let(::timeLabel) ?: "尚未采集")
                Text(
                    "CPU 为两次进程累计耗时之差；多核并行时可能超过 100%。离开页面或进入后台后停止采样。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                metricsError?.let { Text("采样失败：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Live Logs", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (paused) "已暂停 · 查看冻结快照" else "实时日志 · 本机保留最新 200 条",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索日志") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedLevel == null,
                        onClick = { selectedLevel = null },
                        label = { Text("全部") },
                    )
                    LOG_LEVELS.forEach { level ->
                        FilterChip(
                            selected = selectedLevel == level,
                            onClick = { selectedLevel = level },
                            label = { Text(level) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { frozenLogs = if (paused) null else state.logs.toList() },
                    ) {
                        Text(if (paused) "继续日志" else "暂停日志")
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = state.databaseReady && !state.saving,
                    ) {
                        Text("清空日志")
                    }
                }
                Text("匹配 ${visibleLogs.size} 条", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visibleLogs.isEmpty()) {
            item {
                Text(
                    if ((frozenLogs ?: state.logs).isEmpty()) "暂无日志" else "没有匹配的日志",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visibleLogs, key = { it.id }) { entry ->
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${timeLabel(entry.timeMillis)} · ${entry.level} · ${entry.module}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.level == "ERROR" || entry.level == "CRITICAL") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(entry.message)
                    if (entry.detail.isNotBlank()) {
                        TextButton(onClick = { expandedLogId = if (expandedLogId == entry.id) null else entry.id }) {
                            Text(if (expandedLogId == entry.id) "收起详情" else "查看详情")
                        }
                        if (expandedLogId == entry.id) {
                            HorizontalDivider()
                            Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志") },
            text = { Text("将删除设备中保存的诊断日志。乐谱和练习准备会保留。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        frozenLogs = null
                        expandedLogId = null
                        onClearLogs()
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MonitorCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun timeLabel(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timeMillis))
