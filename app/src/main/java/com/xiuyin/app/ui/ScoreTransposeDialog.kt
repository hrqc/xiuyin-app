package com.xiuyin.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.score.*

@Composable
internal fun ScoreTransposeDialog(score: DigitalScore, onDismiss: () -> Unit, onApply: (Int, Int?) -> Unit) {
    val firstKey = remember(score) { score.parts.flatMap { it.measures }.firstOrNull { it.fifths != null } }
    val current = firstKey?.fifths?.takeIf { it in -7..7 }
    var byKey by remember(score) { mutableStateOf(true) }
    var target by remember(score) { mutableStateOf(current?.let(ScoreTransposeChoice::numberedLabel).orEmpty()) }
    var semitones by remember(score) { mutableStateOf("0") }
    val request = remember(current, target, byKey, semitones) { runCatching {
        if (byKey) {
            val sourceKey = requireNotNull(current) { "原谱调号未确认，请先在校对中确认实际调号" }
            val targetKey = ScoreTransposeChoice.parseNumberedKey(target)
            ScoreTransposeChoice.nearestShift(sourceKey, targetKey) to targetKey
        } else requireNotNull(semitones.trim().toIntOrNull()) { "请输入有效的半音数" } to null
    } }
    val shift = request.getOrNull()?.first
    val requestedKey = request.getOrNull()?.second
    val preview = remember(score, request) {
        request.mapCatching { (shiftBy, key) -> ScoreEditing.transpose(score, shiftBy, key).score }
    }
    val before = remember(score) { score.parts.flatMap { it.measures }.firstOrNull { it.events.any { event -> !event.isRest } } }
    val after = preview.getOrNull()?.parts?.flatMap { it.measures }?.firstOrNull { it.id == before?.id }
    fun addSemitones(delta: Int) { semitones = ScoreTransposeChoice.step(semitones, delta) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("整谱移调") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("输入目标首调 do：1=音名，b 表示降号，# 表示升号。保存后整谱移调，可撤销。")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(byKey, { byKey = true }, { Text("按 1= 移调") },
                    modifier = Modifier.testTag("transpose_mode_key"))
                FilterChip(!byKey, { byKey = false }, { Text("高级：半音 / 八度") }, modifier = Modifier.testTag("transpose_mode_semitone"))
            }
            if (current != null) {
                Text("原谱首个已知调号：${ScoreTransposeChoice.numberedLabel(current)}", modifier = Modifier.testTag("transpose_original_key"))
                Text("这里的 1 表示简谱 do，和当前简谱编号一致；不改成小调主音 6，移调保留原谱调式。", style = MaterialTheme.typography.bodySmall)
            } else Text("原谱调号未确认，不能猜测 1=什么。请先校正调号；也可用高级半音移调改变真实音高。", modifier = Modifier.testTag("transpose_unknown_key"))
            if (byKey) {
                OutlinedTextField(target, { target = it }, label = { Text("目标首调 do（1=音名）") },
                    placeholder = { Text("例如 1=bD、1=#F") }, singleLine = true, enabled = current != null,
                    modifier = Modifier.testTag("transpose_numbered_key"))
                requestedKey?.let { key ->
                    val signature = when { key > 0 -> "$key 个升号"; key < 0 -> "${-key} 个降号"; else -> "无升降调号" }
                    Text("目标：${ScoreTransposeChoice.numberedLabel(key)} · $signature", modifier = Modifier.testTag("transpose_resolved_key"))
                }
                Text("默认按最近方向升降；相距 6 个半音时上移。需要指定八度或方向时使用高级半音 / 八度。", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ addSemitones(-1) }, modifier = Modifier.testTag("transpose_minus")) { Text("−1 半音") }
                    OutlinedButton({ addSemitones(1) }, modifier = Modifier.testTag("transpose_plus")) { Text("+1 半音") }
                }
                OutlinedTextField(semitones, { semitones = it }, label = { Text("升降半音数；+12 / −12 为一个八度") },
                    singleLine = true, modifier = Modifier.testTag("transpose_semitones"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ addSemitones(-12) }, modifier = Modifier.testTag("transpose_octave_minus")) { Text("−1 八度") }
                    OutlinedButton({ addSemitones(12) }, modifier = Modifier.testTag("transpose_octave_plus")) { Text("+1 八度") }
                }
            }
            shift?.let { Text("本次：${ScoreTransposeChoice.shiftLabel(it)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("transpose_shift_preview")) }
            preview.exceptionOrNull()?.let { Text(it.message ?: "无法按此设置移调", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("transpose_error")) }
            if (before != null && after != null) {
                Text("数字五线谱预览 · 第 ${before.number} 小节", style = MaterialTheme.typography.titleSmall)
                Text("移调前", modifier = Modifier.testTag("transpose_before_label"))
                NotationMeasure(before, ScoreDisplayMode.STAFF, null, emptySet(), emptyMap(), {}, Modifier.testTag("transpose_before"))
                Text("移调后", modifier = Modifier.testTag("transpose_after_label"))
                NotationMeasure(after, ScoreDisplayMode.STAFF, null, emptySet(), emptyMap(), {}, Modifier.testTag("transpose_after"))
                Text("${before.events.filterNot { it.isRest }.take(4).joinToString { it.pitch!!.displayName }} → ${after.events.filterNot { it.isRest }.take(4).joinToString { it.pitch!!.displayName }}",
                    modifier = Modifier.testTag("transpose_pitch_preview"))
            }
            Text("原始照片用于对照，不随移调改变。某些半音移调只需改变升降记号，符头位置可以不变。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(onClick = { shift?.let { onApply(it, requestedKey) } }, enabled = preview.isSuccess && (shift != 0 || (requestedKey != null && requestedKey != current)),
            modifier = Modifier.testTag("transpose_apply")) { Text("应用移调") }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
