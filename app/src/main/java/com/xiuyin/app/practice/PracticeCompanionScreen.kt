package com.xiuyin.app.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiuyin.app.score.ScoreEditing
import com.xiuyin.app.ui.NotationMeasure
import com.xiuyin.app.ui.ScoreDisplayMode
import java.text.DateFormat
import java.util.Date

@Composable
fun PracticeCompanionScreen(
    state: CompanionState,
    onStart: (PracticeConfig) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeekMeasure: (Int) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    onDispute: () -> Unit = {},
    feedbackMessage: String? = null,
    onCalibration: () -> Unit = {},
) {
    var modeName by rememberSaveable(state.scoreId) { mutableStateOf(CompanionMode.FREE.name) }
    var handName by rememberSaveable(state.scoreId) { mutableStateOf(PracticeHand.BOTH.name) }
    var startText by rememberSaveable(state.scoreId) { mutableStateOf("1") }
    var endText by rememberSaveable(state.scoreId) { mutableStateOf("") }
    var repeatText by rememberSaveable(state.scoreId) { mutableStateOf("3") }
    var reviewed by rememberSaveable(state.scoreId) { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var display by rememberSaveable { mutableStateOf(ScoreDisplayMode.STAFF.name) }
    val running = state.phase == CompanionPhase.RUNNING || state.phase == CompanionPhase.LOCKED
    val hasSession = running || state.phase == CompanionPhase.PAUSED
    val measureCount = state.timeline?.measures?.size ?: 0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall)
        Text("本机实时陪练 · 修谱版本 ${state.revisionGeneration}", style = MaterialTheme.typography.labelLarge)
        Text("模型分数为激活证据，不是准确率。模型分批推理，有可感知等待；踏板、复杂多音及琴房误停率仍未验证。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onCalibration, enabled = !state.busy) { Text("钢琴环境校准与误判案例") }
        feedbackMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!hasSession && !state.busy) {
            CompanionMode.entries.forEach { mode ->
                FilterChip(selected = modeName == mode.name, onClick = { modeName = mode.name }, label = { Text(mode.label) })
            }
            val supportsHands = state.timeline?.let(::supportsHandSelection) == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PracticeHand.entries.forEach { hand ->
                FilterChip(handName == hand.name, { handName = hand.name }, enabled = hand == PracticeHand.BOTH || supportsHands, label = { Text(hand.label) })
            } }
            if (!supportsHands) Text("当前谱面缺少明确双谱表音符，不能猜测左右手；使用双手模式。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startText, { startText = it }, label = { Text("起始小节") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(endText, { endText = it }, label = { Text("结束小节（空为全曲）") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (modeName == CompanionMode.REINFORCE.name) OutlinedTextField(repeatText, { repeatText = it }, label = { Text("整段连续正确次数") }, singleLine = true)
            if (state.timeline?.issues?.isNotEmpty() == true) {
                Text("当前谱面仍有 ${state.timeline.issues.size} 项来源或结构警告，请先对照原谱。", color = MaterialTheme.colorScheme.error)
                Row { Checkbox(reviewed, { reviewed = it }); Text("我已校对当前谱面，接受这些来源警告") }
            }
            Button(onClick = {
                val first = startText.toIntOrNull()
                val last = if (endText.isBlank()) measureCount else endText.toIntOrNull()
                val repeats = repeatText.toIntOrNull()
                if (first == null || last == null || first !in 1..measureCount || last !in first..measureCount || repeats == null || repeats !in 1..20) {
                    inputError = "请输入有效小节范围和 1–20 次强化目标"
                } else {
                    inputError = null
                    onStart(PracticeConfig(CompanionMode.valueOf(modeName), first - 1, last - 1, repeats, reviewed, PracticeHand.valueOf(handName)))
                }
            }, enabled = state.timeline != null) { Text("开始实时陪练") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("状态：${phaseLabel(state.phase)} · ${state.config.mode.label} · ${state.config.hand.label}")
                Text("当前位置：第 ${state.measureIndex + 1} 小节 · 起音位置 ${state.cursor + 1}")
                Text("目标：${noteNames(state.expectedMidi)}")
                Text("本次新起音：${noteNames(state.detectedMidi)}")
                Text("决策：${state.decision.name} · ${state.reason}")
                Text("确认输入 ${state.confirmedInputs} · 正确 ${state.correct} · 错音证据 ${state.wrong} · 跳过 ${state.missed}")
                Text("完成 ${state.completedPasses} 轮 · 整段连续正确 ${state.consecutivePasses}/${state.config.repeatTarget}")
                if (state.phase == CompanionPhase.LOCKED) Text("LOCKED：请重弹目标音；正确的新击键可恢复。暂停和继续不会跳过错误。", color = MaterialTheme.colorScheme.error)
                if (state.phase == CompanionPhase.LOCKED) OutlinedButton(onClick = onDispute, enabled = !state.busy) { Text("我弹对了") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) OutlinedButton(onClick = onPause, enabled = !state.busy) { Text("暂停陪练") }
                    if (state.phase == CompanionPhase.PAUSED) Button(onClick = onResume, enabled = !state.busy) { Text("继续陪练") }
                    if (hasSession) OutlinedButton(onClick = onFinish, enabled = !state.busy) { Text("结束并保存报告") }
                }
            }
        }
        if (state.score != null && state.timeline != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ScoreDisplayMode.entries.forEach { item ->
                FilterChip(display == item.name, { display = item.name }, label = { Text(item.label) })
            } }
            Text("实际修订谱面 · 跟随当前位置切换小节")
            state.score.parts.forEach { part ->
                part.measures.getOrNull(state.measureIndex)?.let { measure ->
                    Text("${part.name} · 第 ${measure.number} 小节")
                    NotationMeasure(measure, ScoreDisplayMode.valueOf(display), null, state.activeEventIds, emptyMap(), onSelect = {},
                        wrongEventIds = state.evidence.filter { it.decision == PracticeDecision.WRONG || it.decision == PracticeDecision.MISSED }.flatMap { it.eventIds }.toSet())
                }
            }
            if (hasSession) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onSeekMeasure(state.measureIndex - 1) }, enabled = state.measureIndex > state.config.startMeasure && !state.busy) { Text("定位上一小节") }
                OutlinedButton({ onSeekMeasure(state.measureIndex + 1) }, enabled = state.measureIndex < (state.config.endMeasure ?: measureCount - 1) && !state.busy) { Text("定位下一小节") }
            }
        }
        if (state.evidence.isNotEmpty()) {
            Text("实际决策证据", style = MaterialTheme.typography.titleMedium)
            state.evidence.take(8).forEach { item ->
                Text("第 ${item.measureIndex + 1} 小节 ${item.decision}：目标 ${noteNames(item.expectedMidi)} / 输入 ${noteNames(item.detectedMidi)}\n${item.reason}", style = MaterialTheme.typography.bodySmall)
                if (!hasSession && (item.decision == PracticeDecision.WRONG || item.decision == PracticeDecision.MISSED)) {
                    OutlinedButton(onClick = { onStart(PracticeConfig(CompanionMode.REINFORCE, item.measureIndex, item.measureIndex, 3, reviewed, state.config.hand)) }) { Text("强化第 ${item.measureIndex + 1} 小节") }
                }
            }
        }
        if (state.reports.isNotEmpty()) {
            Text("本机练习报告", style = MaterialTheme.typography.titleMedium)
            state.reports.take(5).forEach { report ->
                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.endedAtMillis))} · ${report.config.mode.label} · ${report.config.hand.label}\n确认输入 ${report.confirmedInputs}，正确 ${report.correct}，错音证据 ${report.wrong}，跳过 ${report.missed}；修谱版本 ${report.revisionGeneration}", style = MaterialTheme.typography.bodySmall)
            }
            Text("以上是模型辅助练习记录，未换算为真实演奏准确率。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onBack, enabled = !state.busy) { Text("返回乐谱工作台") }
    }
}
private fun noteNames(notes: Set<Int>): String = if (notes.isEmpty()) "暂无" else notes.sorted().joinToString("、") { ScoreEditing.pitchFromMidi(it).displayName }
private fun phaseLabel(phase: CompanionPhase): String = when (phase) {
    CompanionPhase.READY -> "待开始"; CompanionPhase.RUNNING -> "监听跟谱"; CompanionPhase.PAUSED -> "已暂停"
    CompanionPhase.LOCKED -> "等待纠正"; CompanionPhase.COMPLETED -> "已结束"
}


