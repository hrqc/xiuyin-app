package com.xiuyin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.xiuyin.app.score.ScoreMetadata
import com.xiuyin.app.score.ScoreMetadataLimits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Edits descriptive metadata only; note data and original titles stay with their source. */
@Composable
fun MetadataEditor(
    metadata: ScoreMetadata,
    naming: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (String, List<String>, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(metadata.scoreId) { mutableStateOf(metadata.customTitle.orEmpty()) }
    var aliases by rememberSaveable(metadata.scoreId) { mutableStateOf(metadata.aliases.joinToString("，")) }
    var tags by rememberSaveable(metadata.scoreId) { mutableStateOf(metadata.tags.joinToString("，")) }
    val aliasValues = metadataValues(aliases)
    val tagValues = metadataValues(tags)
    val validation = when {
        title.trim().length > ScoreMetadataLimits.TITLE_LENGTH -> "名称最多 120 个字符"
        aliasValues.size > ScoreMetadataLimits.LIST_COUNT -> "别名最多 20 个"
        aliasValues.any { it.length > ScoreMetadataLimits.ALIAS_LENGTH } -> "每个别名最多 100 个字符"
        tagValues.size > ScoreMetadataLimits.LIST_COUNT -> "标签最多 20 个"
        tagValues.any { it.length > ScoreMetadataLimits.TAG_LENGTH } -> "每个标签最多 50 个字符"
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (naming) "未检测到曲名，请为这份乐谱命名" else "编辑名称、别名与标签") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (naming) "可以暂时跳过，使用“${metadata.displayTitle}”。以后仍可重命名。"
                    else "自定义名称优先显示；清空后使用原始名称或临时名称。音符数据不会因此改变。",
                    style = MaterialTheme.typography.bodySmall,
                )
                metadata.originalTitle?.let {
                    Text("原始名称：$it", style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("自定义名称") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("metadata_title"),
                )
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("别名，用逗号分隔") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("metadata_aliases"),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签，用逗号分隔") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("metadata_tags"),
                )
                val shownError = validation ?: error
                if (shownError != null) Text(
                    shownError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("metadata_error"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), aliasValues, tagValues) },
                enabled = !busy && validation == null,
                modifier = Modifier.testTag("metadata_save"),
            ) { Text(if (busy) "正在保存…" else "保存") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.testTag(if (naming) "metadata_skip" else "metadata_cancel"),
            ) { Text(if (naming) "暂时跳过" else "取消") }
        },
    )
}

@Composable
fun MetadataDeleteDialog(
    metadata: ScoreMetadata,
    busy: Boolean,
    error: String?,
    onDelete: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除“${metadata.displayTitle}”？") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("将乐谱移出我的乐谱，并删除本地来源与当前数字乐谱；相关修谱结果也属于删除范围。练习记录可选择保留或一并删除。")
                Text(
                    "本版尚无该谱的修谱结果和真实练习记录；不会删除内置曲目的练习设置。本机保留名称与删除状态，只有主动重新导入同一文件时才恢复命名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = XiuyinMuted,
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Column {
                TextButton(
                    onClick = { onDelete(false) },
                    enabled = !busy,
                    modifier = Modifier.testTag("delete_score_only"),
                ) { Text("仅删除乐谱") }
                TextButton(
                    onClick = { onDelete(true) },
                    enabled = !busy,
                    modifier = Modifier.testTag("delete_score_and_records"),
                ) { Text("删除乐谱及练习记录") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.testTag("delete_score_cancel"),
            ) { Text("取消") }
        },
    )
}

@Composable
fun ScoreMetadataDetails(
    metadata: ScoreMetadata,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(metadata.scoreId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("metadata_details"),
        ) { Text(if (expanded) "收起乐谱信息" else "查看名称与文件信息") }
        if (expanded) {
            MetadataLine("自定义名称", metadata.customTitle ?: "尚未命名")
            MetadataLine("原始名称", metadata.originalTitle ?: "未检测到")
            MetadataLine("别名", metadata.aliases.joinToString("、").ifEmpty { "尚无" })
            MetadataLine("标签", metadata.tags.joinToString("、").ifEmpty { "尚无" })
            MetadataLine("来源文件", metadata.sourceFileName ?: "文件名未知")
            MetadataLine("作者", metadata.composer ?: "未提供")
            MetadataLine("作品编号", metadata.workNumber ?: "未提供")
            MetadataLine("导入时间", metadataDate(metadata.importedAt))
            MetadataLine("最近练习", metadata.lastPracticedAt?.let(::metadataDate) ?: "尚无")
            MetadataLine("页数", metadata.pageCount?.toString() ?: "未知（结构化文件未提供可验证页数）")
            MetadataLine("拍号", metadata.timeSignatures.joinToString("、").ifEmpty { "未知" })
            MetadataLine("调号", metadata.keySignatures.joinToString("、").ifEmpty { "未知" })
            MetadataLine("原始文件类型", metadata.sourceType)
            MetadataLine("命名方式", if (metadata.customNamed) "用户自定义" else if (metadata.originalTitle != null) "原始名称" else "临时名称")
            MetadataLine("解析状态", if (metadata.recognitionComplete) "结构化解析完成；未使用 OMR" else "尚未完成")
            MetadataLine("校对状态", if (metadata.hasReviewIssues) "存在待确认项" else "基础校验未发现疑点；未人工确认")
            metadata.firstMeasureSummary?.let { MetadataLine("首小节摘要", it) }
            MetadataLine("本地乐谱 ID", metadata.scoreId)
        }
        TextButton(
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.testTag("metadata_edit"),
        ) { Text("重命名 / 编辑别名与标签") }
        TextButton(
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.testTag("metadata_delete"),
        ) { Text("删除这份乐谱", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Text("$label：$value", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
}

private fun metadataValues(value: String): List<String> = value.split(',', '，').map(String::trim)
    .filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }

private fun metadataDate(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
