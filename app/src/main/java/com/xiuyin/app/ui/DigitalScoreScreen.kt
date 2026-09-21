package com.xiuyin.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScoreSummary
import com.xiuyin.app.score.ScoreMetadata
import com.xiuyin.app.score.ScoreSort
import kotlinx.coroutines.launch

/** A read-only view of parsed data, never a substitute for OMR or a notation editor. */
@Composable
fun DigitalScoreScreen(
    importing: Boolean,
    error: String?,
    progress: String?,
    savedScores: List<ScoreSummary>,
    selectedScore: DigitalScore?,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    query: String,
    sort: ScoreSort,
    selectedTag: String?,
    availableTags: List<String>,
    selectedMetadata: ScoreMetadata?,
    namingMetadata: ScoreMetadata?,
    onQueryChange: (String) -> Unit,
    onSortChange: (ScoreSort) -> Unit,
    onTagChange: (String?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSaveMetadata: (String, String, List<String>, List<String>) -> Unit,
    onSkipNaming: () -> Unit,
    onDeleteScore: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    storageIssues: List<String> = emptyList(),
) {
    var editingScoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingScoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingSave by rememberSaveable { mutableStateOf(false) }
    var awaitingDelete by rememberSaveable { mutableStateOf(false) }
    fun metadataFor(id: String?): ScoreMetadata? = id?.let { scoreId ->
        selectedMetadata?.takeIf { it.scoreId == scoreId }
            ?: savedScores.firstOrNull { it.id == scoreId }?.metadata
    }
    val editingMetadata = metadataFor(editingScoreId)
    val deletingMetadata = metadataFor(deletingScoreId)
    LaunchedEffect(importing, error, awaitingSave, awaitingDelete) {
        if (!importing) {
            if (awaitingSave) {
                if (error == null) editingScoreId = null
                awaitingSave = false
            }
            if (awaitingDelete) {
                if (error == null) deletingScoreId = null
                awaitingDelete = false
            }
        }
    }
    namingMetadata?.let { metadata ->
        MetadataEditor(
            metadata, naming = true, busy = importing, error = error,
            onSave = { title, aliases, tags -> onSaveMetadata(metadata.scoreId, title, aliases, tags) },
            onDismiss = onSkipNaming,
        )
    }
    editingMetadata?.takeIf { namingMetadata == null }?.let { metadata ->
        MetadataEditor(
            metadata, naming = false, busy = importing, error = error,
            onSave = { title, aliases, tags ->
                awaitingSave = true
                onSaveMetadata(metadata.scoreId, title, aliases, tags)
            },
            onDismiss = { editingScoreId = null },
        )
    }
    deletingMetadata?.let { metadata ->
        MetadataDeleteDialog(
            metadata, busy = importing, error = error,
            onDelete = { includeRecords ->
                awaitingDelete = true
                onDeleteScore(metadata.scoreId, includeRecords)
            },
            onDismiss = { deletingScoreId = null },
        )
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    LaunchedEffect(selectedScore?.id, selectedMetadata?.scoreId) {
        listState.scrollToItem(if (selectedScore != null) 4 + savedScores.size else 0)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("score_content"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("返回曲库") }
                Text("导入与数字乐谱", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "把已有的 MusicXML 留在本机，逐小节查看音符与疑点。",
                    color = XiuyinMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item(key = "capabilities") {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地解析 · 数据校对视图", fontWeight = FontWeight.SemiBold)
                    Text(
                        "本版支持未压缩的 .musicxml / .xml（score-partwise），最大 2 MiB。" +
                            "图片、PDF、MIDI 与压缩 MXL 尚不支持。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "完整五线谱排版、简谱、移调、编辑、试听与 OMR 尚未实现；本页不会启动陪练。",
                        style = MaterialTheme.typography.bodySmall,
                        color = XiuyinMuted,
                    )
                }
            }
        }
        item(key = "import") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (storageIssues.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("score_storage_warning"),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("本地归档提示", fontWeight = FontWeight.SemiBold)
                            Text(storageIssues.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            Text("此提示独立于本次操作结果，已有可读乐谱仍可管理。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(
                    onClick = onImport,
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().testTag("score_import"),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (importing) "正在处理文件…" else "选择 MusicXML 文件") }
                if (importing) {
                    Text(
                        progress ?: "正在处理本地文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XiuyinMuted,
                        modifier = Modifier.testTag("score_import_progress"),
                    )
                }
                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("score_import_error"),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("文件未能打开", fontWeight = FontWeight.SemiBold)
                            Text(error, style = MaterialTheme.typography.bodyMedium)
                            Text("可以选择另一份文件，已保存的乐谱仍可打开。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item(key = "library_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的乐谱 · ${savedScores.size}", style = MaterialTheme.typography.titleLarge)
                ScoreLibraryControls(query, sort, selectedTag, availableTags, onQueryChange, onSortChange, onTagChange)
                if (savedScores.isEmpty()) {
                    Text(
                        if (query.isNotBlank() || selectedTag != null) "没有找到符合条件的乐谱，可以修改搜索词或标签。"
                        else "还没有导入乐谱。选择文件后，原文件会保存在本机。",
                        color = XiuyinMuted,
                    )
                }
            }
        }
        items(savedScores, key = { "saved:${it.id}" }) { score ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutlinedButton(
                    onClick = { onOpen(score.id) },
                    enabled = !importing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("saved_score_" + score.id),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(score.metadata?.displayTitle ?: score.title, style = MaterialTheme.typography.titleMedium, color = XiuyinInk)
                        if (score.error != null) {
                            Text(score.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Text("点此重新检查", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Text(
                                "${score.partCount} 个分部 · ${score.measureCount} 个小节条目 · ${score.issueCount} 处疑点",
                                style = MaterialTheme.typography.bodySmall,
                                color = XiuyinMuted,
                            )
                            Text(
                                if (selectedMetadata?.scoreId == score.id) "当前打开" else "打开本地乐谱",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        score.metadata?.tags?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
                        }
                    }
                }
                score.metadata?.let { metadata ->
                    TextButton(
                        onClick = { onToggleFavorite(metadata.scoreId) },
                        enabled = !importing,
                        modifier = Modifier.testTag("favorite_score_" + metadata.scoreId),
                    ) { Text(if (metadata.favorite) "★ 已收藏 · 取消收藏" else "☆ 收藏") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { editingScoreId = metadata.scoreId },
                            enabled = !importing,
                            modifier = Modifier.testTag("manage_score_" + metadata.scoreId),
                        ) { Text("改名 / 标签") }
                        TextButton(
                            onClick = { deletingScoreId = metadata.scoreId },
                            enabled = !importing,
                            modifier = Modifier.testTag("delete_saved_score_" + metadata.scoreId),
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (selectedScore != null) {
            item(key = "selected_header") {
                Column(
                    Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = XiuyinLine)
                    TextButton(
                        onClick = { scrollScope.launch { listState.animateScrollToItem(3) } },
                        modifier = Modifier.testTag("score_back_to_library"),
                    ) { Text("返回我的乐谱列表") }
                    Text(
                        selectedMetadata?.displayTitle ?: selectedScore.title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag("score_title"),
                    )
                    Text(
                        "待确认 ${selectedScore.issues.size} 处 · 基础结构检查",
                        color = if (selectedScore.issues.isEmpty()) XiuyinMuted else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("score_issue_count"),
                    )
                    Text(
                        "数据直接来自 MusicXML，未经过 OMR；没有生成识别置信度。" +
                            "未发现疑点不等于全谱已经人工验证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = XiuyinMuted,
                    )
                    selectedMetadata?.let { metadata ->
                        ScoreMetadataDetails(
                            metadata, enabled = !importing,
                            onEdit = { editingScoreId = metadata.scoreId },
                            onDelete = { deletingScoreId = metadata.scoreId },
                        )
                    }
                    Text(
                        "下方起点从 0 开始，时长单位均为四分音符；和弦各音共享起点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = XiuyinMuted,
                    )
                }
            }
            items(selectedScore.issues.filter { it.measureId == null }, key = { "global_issue:${it.id}" }) {
                ScoreIssueNotice(it)
            }
            selectedScore.parts.forEach { part ->
                item(key = "part:${part.id}") {
                    Text(
                        "${part.name} · ${part.measures.size} 小节",
                        style = MaterialTheme.typography.titleLarge,
                        color = XiuyinNavy,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                part.measures.forEach { measure ->
                    item(key = "measure:${measure.id}") { ScoreMeasureHeading(measure) }
                    items(
                        selectedScore.issues.filter { it.measureId == measure.id },
                        key = { "measure_issue:${it.id}" },
                    ) { ScoreIssueNotice(it) }
                    if (measure.events.isEmpty()) {
                        item(key = "empty:${measure.id}") {
                            Text("此小节没有已解析的音符或休止符。", color = XiuyinMuted)
                        }
                    }
                    items(measure.events, key = { "event:${it.id}" }) { ScoreEventRow(it) }
                }
            }
        }
        item(key = "footer") {
            Text(
                "本地处理，不上传乐谱。真实手机兼容性与复杂 MusicXML 全集支持尚未验证。",
                modifier = Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = XiuyinMuted,
            )
        }
    }
}

@Composable
private fun ScoreMeasureHeading(measure: ScoreMeasure) {
    val keyText = when (val fifths = measure.fifths) {
        null -> "调号未知"
        0 -> "无升降调号"
        in 1..7 -> "${fifths} 个升号"
        else -> "${-fifths} 个降号"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("score_measure_" + measure.id),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("第 ${measure.number} 小节", style = MaterialTheme.typography.titleMedium)
            Text(
                "${measure.time?.toString() ?: "拍号未知"} · $keyText" + if (measure.implicit) " · 不完整小节" else "",
                style = MaterialTheme.typography.bodySmall,
                color = XiuyinMuted,
            )
            measure.keyMode?.let { mode ->
                Text("调式：$mode · ${measure.declaredStaves} 个谱表", style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
            }
            if (measure.clefs.isNotEmpty()) {
                Text(
                    measure.clefs.joinToString("；") {
                        "谱表 ${it.staff}：${it.sign} 谱号" +
                            (it.line?.let { line -> "第 $line 线" } ?: "") +
                            if (it.octaveChange != 0) "，八度变化 ${it.octaveChange}" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = XiuyinMuted,
                )
            }
        }
    }
}

@Composable
private fun ScoreEventRow(event: ScoreEvent) {
    Surface(
        border = BorderStroke(1.dp, XiuyinLine),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("score_event_" + event.id),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    event.pitch?.displayName ?: "休止符",
                    style = MaterialTheme.typography.titleMedium,
                    color = XiuyinInk,
                )
                Text(
                    "谱表 ${event.staff} · 声部 ${event.voice}",
                    style = MaterialTheme.typography.bodySmall,
                    color = XiuyinMuted,
                )
            }
            Text(
                "起点 ${event.offset} · 时长 ${event.duration}" + if (event.chord) " · 同组和弦音" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            val details = buildList {
                if (event.dots > 0) add("${event.dots} 个附点")
                if ("start" in event.ties) add("延音线起点")
                if ("stop" in event.ties) add("延音线终点")
            }
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScoreIssueNotice(issue: ScoreIssue) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            (if (issue.severity == ScoreIssueSeverity.ERROR) "需检查：" else "待确认：") + issue.message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}


@Composable
private fun ScoreLibraryControls(
    query: String,
    sort: ScoreSort,
    selectedTag: String?,
    availableTags: List<String>,
    onQueryChange: (String) -> Unit,
    onSortChange: (ScoreSort) -> Unit,
    onTagChange: (String?) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("搜索我的乐谱") },
        placeholder = { Text("名称、别名、标签、文件、作者或作品号") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("score_search"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { sortMenu = true },
                modifier = Modifier.fillMaxWidth().testTag("score_sort"),
            ) { Text(scoreSortLabel(sort) + " ▾") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                ScoreSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(scoreSortLabel(option)) },
                        onClick = { sortMenu = false; onSortChange(option) },
                        modifier = Modifier.testTag("score_sort_" + option.name),
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { tagMenu = true },
                modifier = Modifier.fillMaxWidth().testTag("score_tag_filter"),
            ) { Text((selectedTag ?: "全部标签") + " ▾") }
            DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                DropdownMenuItem(
                    text = { Text("全部标签") },
                    onClick = { tagMenu = false; onTagChange(null) },
                    modifier = Modifier.testTag("score_tag_all"),
                )
                availableTags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag) },
                        onClick = { tagMenu = false; onTagChange(tag) },
                        modifier = Modifier.testTag("score_tag_" + tag),
                    )
                }
            }
        }
    }
    Text(
        "搜索在本机进行，收藏保存在本机。最近练习仅统计真实练习，打开乐谱不会计为练习。",
        style = MaterialTheme.typography.bodySmall,
        color = XiuyinMuted,
    )
}

private fun scoreSortLabel(sort: ScoreSort): String = when (sort) {
    ScoreSort.RECENT_IMPORT -> "最近导入"
    ScoreSort.RECENT_PRACTICE -> "最近练习"
    ScoreSort.NAME -> "名称"
    ScoreSort.FAVORITES -> "收藏"
}
