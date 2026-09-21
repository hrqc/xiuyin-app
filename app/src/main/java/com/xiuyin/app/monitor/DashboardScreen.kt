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
import com.xiuyin.app.AppState
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.data.LogEntry
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(state: AppState, onRefresh: () -> Unit, onClearLogs: () -> Unit) {
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
                    "本机状态 · 页面可见时每 5 秒刷新",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MonitorCard("版本与数据") {
                MetricRow("当前版本", "v${BuildConfig.VERSION_NAME}")
                MetricRow("Git Commit", BuildConfig.GIT_COMMIT)
                MetricRow("Git Tag（构建时）", BuildConfig.GIT_TAG)
                MetricRow("数据位置", "设备本地 SQLite · 不保存录音")
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
                MetricRow("Audio Engine", "⚪ 未运行 · V0.3")
                MetricRow("Pitch Engine", "⚪ 未运行 · V0.3")
                MetricRow("OMR Engine", "⚪ 未运行 · V0.7")
                MetricRow("Score Engine", "⚪ 未运行 · V0.2 数字乐谱")
                MetricRow("Tracking Engine", "⚪ 未运行 · V0.4")
                MetricRow("Microphone", "⚪ 未运行 · 未申请权限")
                MetricRow("Backend / API", "不适用 · 本版无后端服务")
            }
        }
        item {
            MonitorCard("练习与实时链路") {
                MetricRow("当前练习模式", state.preparation?.mode?.label ?: "尚未设置")
                MetricRow("目标连续正确次数", state.preparation?.repeatTarget?.toString() ?: "尚未设置")
                MetricRow("当前练习状态", "未开始 · 仅保存练习准备")
                MetricRow("LOCKED / 连续正确次数", "尚未采集")
                MetricRow("当前小节 / 拍 / 音符", "尚未采集")
                MetricRow("目标音符 / 识别音符", "尚未采集")
                MetricRow("频率 / 音高偏差", "尚未采集")
                MetricRow("Score Following", "未运行")
                MetricRow("Audio / Pitch Latency", "尚未采集")
                MetricRow("Following / UI Cursor Latency", "尚未采集")
                MetricRow("FPS / OMR Processing Time", "尚未采集")
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
