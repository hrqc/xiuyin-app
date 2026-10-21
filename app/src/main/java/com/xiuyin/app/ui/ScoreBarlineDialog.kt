package com.xiuyin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.score.ScoreBarlinePreview
import java.io.File

/** Explicit user revision: the source reference is a staff crop, not a detected barline location. */
@Composable
fun ScoreBarlineDialog(preview: ScoreBarlinePreview, images: List<File>, busy: Boolean,
    onDismiss: () -> Unit, onApply: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("barline_preview"),
        title = { Text("确认这条小节线多识别了吗？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("第 ${preview.leftMeasure.number} 小节与紧接的第 ${preview.rightMeasure.number} 小节将合并。后段起点整体后移 ${preview.joinBeat} 个四分音符拍，音高与时值不变。")
                Text("原图位置为谱表范围，请核对两小节分界。若这条线是真实小节线，请取消。", style = MaterialTheme.typography.bodySmall)
                val box = preview.leftMeasure.events.firstNotNullOfOrNull { it.sourceBox }
                    ?: preview.rightMeasure.events.firstNotNullOfOrNull { it.sourceBox }
                if (images.isNotEmpty()) SourceReference(images, box)
                else Text("未找到原图，请先核对手头原谱。", color = MaterialTheme.colorScheme.error)
                Text("合并前：${preview.before.partCount} 个声部，${preview.before.errorCount} 项结构错误", modifier = Modifier.testTag("barline_before"))
                NotationMeasure(preview.leftMeasure, ScoreDisplayMode.BOTH, null, emptySet(), emptyMap(), {})
                NotationMeasure(preview.rightMeasure, ScoreDisplayMode.BOTH, null, emptySet(), emptyMap(), {})
                Text("合并后：${preview.after.partCount} 个声部，${preview.after.errorCount} 项结构错误", modifier = Modifier.testTag("barline_after"))
                val leftEventIds = preview.leftMeasure.events.map { it.id }.toSet()
                preview.candidate.parts.flatMap { it.measures }.firstOrNull { measure -> measure.events.any { it.id in leftEventIds } }?.let {
                    NotationMeasure(it, ScoreDisplayMode.BOTH, null, emptySet(), emptyMap(), {})
                }
                preview.after.timelineProblem?.let { Text("仍需修正：$it", color = MaterialTheme.colorScheme.error) }
                Text("仅在各组上下谱表逐小节对应时恢复双谱表。其他错误保留；结构通过不代表音符准确。原图和原始识别保持不变，已有改音和指法保留，受影响音符需要复核，可一次撤销。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onApply, enabled = !busy,
            modifier = Modifier.testTag("barline_apply")) { Text("确认误线，保存修订") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy,
            modifier = Modifier.testTag("barline_cancel")) { Text("取消，保留原样") } })
}
