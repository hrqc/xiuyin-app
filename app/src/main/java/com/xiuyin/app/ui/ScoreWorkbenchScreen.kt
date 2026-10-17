package com.xiuyin.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.omr.OmrPreviewMethod
import com.xiuyin.app.playback.PlaybackSnapshot
import com.xiuyin.app.score.*
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ScoreWorkbenchScreen(
    state: ScoreWorkbenchState,
    playback: PlaybackSnapshot,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (ScorePatch, Int?) -> Unit,
    onTranspose: (Int, Int?) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onVerify: () -> Unit,
    onRestoreEvent: () -> Unit,
    onRestoreMeasure: (String) -> Unit,
    onPlay: (Int, Float, Int, Boolean, Boolean) -> Unit,
    onStop: () -> Unit,
    onPractice: () -> Unit,
    modifier: Modifier = Modifier,
    onExportMidi: (Int) -> Unit = {},
    onPreviewOmrStructure: () -> Unit = {},
    onApplyOmrStructure: () -> Unit = {},
    onDismissStructurePreview: () -> Unit = {},
    onSetWholeScoreTime: (Int, Int) -> Unit = { _, _ -> },
    onConfirmLinearRepeat: () -> Unit = {},
    onRecognizeOmrAgain: () -> Unit = {},
    onFillMissingScoreTime: (String, Long, Int, Int) -> Unit = { _, _, _, _ -> },
    onShiftOctaveRange: (ScoreOctaveRangeRequest) -> Unit = {},
) {
    val revision = state.revision
    val score = revision?.current
    var mode by rememberSaveable { mutableStateOf(ScoreDisplayMode.BOTH) }
    var editing by remember { mutableStateOf<ScoreEvent?>(null) }
    var insertingIn by remember { mutableStateOf<ScoreMeasure?>(null) }
    var editingMeasure by remember(state.scoreId, revision?.generation) { mutableStateOf<ScoreMeasure?>(null) }
    var transposeOpen by remember { mutableStateOf(false) }
    var pendingTransposeGeneration by remember(state.scoreId) { mutableStateOf<Long?>(null) }
    var wholeScoreTimeOpen by remember(state.scoreId, revision?.generation) { mutableStateOf(false) }
    var linearRepeatOpen by remember(state.scoreId, revision?.generation) { mutableStateOf(false) }
    var missingTimeTarget by remember(state.scoreId, revision?.generation) { mutableStateOf<MissingTimeTarget?>(null) }
    var octaveRangeTarget by remember(state.scoreId, revision?.generation) { mutableStateOf<ScoreOctaveRangeRequest?>(null) }
    var restoreTarget by remember { mutableStateOf<String?>(null) }
    var reviewIndex by rememberSaveable { mutableStateOf(0) }
    var bpm by rememberSaveable { mutableStateOf(80) }
    var volume by rememberSaveable { mutableStateOf(0.35f) }
    var loop by rememberSaveable { mutableStateOf(false) }
    var metronome by rememberSaveable { mutableStateOf(false) }
    var startMeasure by rememberSaveable { mutableStateOf(0) }
    val measures = score?.parts?.flatMap { part -> part.measures.map { part.name to it } }.orEmpty()
    val selected = measures.flatMap { it.second.events }.firstOrNull { it.id == state.selectedEventId }
    val active = state.timeline?.events?.filter { playback.playing && playback.positionBeat >= it.startBeat.toDouble() && playback.positionBeat < it.endBeat.toDouble() }?.map { it.eventId }?.toSet().orEmpty()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reviewIssues = score?.let { ScoreStructureTools.reviewIssues(it, state.timeline) }.orEmpty()
    val preparation = score?.let { ScoreStructureTools.preparationNeeds(it, reviewIssues) }
    fun openMissingTime() {
        val id = state.scoreId ?: return
        val current = revision ?: return
        missingTimeTarget = MissingTimeTarget(id, current.generation, ScoreStructureTools.missingTimeMeasures(current.current).size)
    }
    fun locateIssue(issue: ScoreIssue) {
        if (preparation?.repeatIssues?.any { it.id == issue.id } == true) { linearRepeatOpen = true; return }
        val targetIndex = measures.indexOfFirst { (_, measure) ->
            measure.id == issue.measureId || measure.events.any { it.id == issue.eventId }
        }
        if (targetIndex >= 0) {
            val target = measures[targetIndex].second
            val eventId = issue.eventId ?: target.events.firstOrNull()?.id
            if (ScoreStructureTools.isMissingTimeIssue(issue)) editingMeasure = target
            else if (eventId != null) onSelect(eventId) else editingMeasure = target
            scope.launch { list.animateScrollToItem(targetIndex + 4) }
        }
    }
    LaunchedEffect(state.scoreId, state.repairIssue?.id) { state.repairIssue?.let { locateIssue(it) } }
    LaunchedEffect(revision?.generation, state.busy, state.error) {
        val previous = pendingTransposeGeneration
        if (previous != null && !state.busy) {
            if (state.error != null) pendingTransposeGeneration = null
            else if (revision != null && revision.generation > previous) {
                mode = ScoreDisplayMode.BOTH
                if (measures.isNotEmpty()) list.animateScrollToItem(4)
                pendingTransposeGeneration = null
            }
        }
    }
    LaunchedEffect(playback.playing, active.firstOrNull()) {
        if (playback.playing) {
            val index = measures.indexOfFirst { (_, m) -> m.events.any { it.id in active } }
            if (index >= 0 && index + 4 !in list.layoutInfo.visibleItemsInfo.map { it.index }) list.animateScrollToItem(index + 4)
        }
    }
    DisposableEffect(Unit) { onDispose { onStop() } }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("workbench"), state = list,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onBack) { Text("返回我的乐谱") }
                Text(state.title, style = MaterialTheme.typography.headlineMedium)
                if (preparation != null && preparation.errors.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().testTag("score_preparation_guide")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("练习前补全谱面信息", style = MaterialTheme.typography.titleMedium)
                            Text("当前有 ${preparation.errors.size} 项谱面准备问题。尚未开始监听，这些不是弹奏时的节拍错误。",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("score_preparation_count"))
                            if (preparation.missingTimeIssues.isNotEmpty()) {
                                val count = preparation.missingTimeIssues.mapNotNull { it.measureId }.distinct().size
                                Text("拍号待补全：$count 个小节缺少或使用了无效拍号；续页需对照原谱首页，不能自动猜测。",
                                    modifier = Modifier.testTag("score_missing_time_summary"))
                                Button(onClick = { openMissingTime() }, enabled = !state.busy,
                                    modifier = Modifier.testTag("score_fill_missing_time")) { Text("只补缺失或无效拍号") }
                                Text("保留已有合法拍号，包括变拍号。缺失段若本身有变拍号，请用下方疑点逐小节修改。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (preparation.repeatIssues.isNotEmpty()) {
                                Text("反复策略待确认：${preparation.repeatIssues.size} 处记号。", modifier = Modifier.testTag("score_repeat_summary"))
                                OutlinedButton(onClick = { linearRepeatOpen = true }, enabled = !state.busy,
                                    modifier = Modifier.testTag("score_guide_repeat")) { Text("选择顺序一次演奏策略") }
                            }
                            if (preparation.otherErrors.isNotEmpty()) {
                                Text("另有 ${preparation.otherErrors.size} 项结构错误，补拍号或确认反复不会替你修正。")
                                val first = preparation.otherErrors.first()
                                TextButton(onClick = { locateIssue(first) }, enabled = !state.busy,
                                    modifier = Modifier.testTag("score_guide_other")) { Text("查看其他结构错误") }
                            }
                        }
                    }
                }
                Text("轻点音符校对，让谱子更贴近你的演奏。", color = XiuyinMuted)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workbench_status"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workbench_error")) }
                state.timelineProblem?.let { Text("时间线需修正：$it", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoreDisplayMode.entries.forEach { option -> FilterChip(mode == option, { mode = option }, { Text(option.label) }, modifier = Modifier.testTag("display_${option.name}")) }
                }
                if (mode != ScoreDisplayMode.STAFF) Text("音区看上下点：上方一点高一个八度，下方一点低一个八度；两点为两个八度。数字右侧的附点表示时值。",
                    style = MaterialTheme.typography.bodySmall, color = XiuyinMuted, modifier = Modifier.testTag("numbered_register_legend"))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUndo, enabled = revision?.canUndo == true && !state.busy, modifier = Modifier.testTag("score_undo")) { Text("撤销") }
                    OutlinedButton(onClick = onRedo, enabled = revision?.canRedo == true && !state.busy, modifier = Modifier.testTag("score_redo")) { Text("重做") }
                    OutlinedButton(onClick = { transposeOpen = true }, enabled = score != null && !state.busy, modifier = Modifier.testTag("score_transpose")) { Text("移调") }
                    Button(onClick = onPractice, enabled = score != null && !state.busy, modifier = Modifier.testTag("score_start_practice")) { Text("开始陪练") }
                }
                TextButton(onClick = { scope.launch { list.animateScrollToItem(4) } }, enabled = measures.isNotEmpty(),
                    modifier = Modifier.testTag("score_show_digital")) { Text("查看当前数字谱（随移调更新）") }
                OutlinedButton(onClick = { wholeScoreTimeOpen = true }, enabled = score != null && !state.busy,
                    modifier = Modifier.testTag("score_set_whole_time")) { Text("设置全谱拍号") }
                Text("全谱覆盖会替换所有小节拍号；只补续页缺失信息请使用顶部引导，变拍号仍可逐小节修改。", style = MaterialTheme.typography.bodySmall)
                if (state.original?.source?.format == "OMR_IMAGE") {
                    OutlinedButton(onClick = onRecognizeOmrAgain, enabled = !state.busy,
                        modifier = Modifier.testTag("score_recognize_again")) { Text("重新识别原图（补查漏谱）") }
                    Text("使用本机模型重新读取保存的原图，可能需要数分钟；只生成预览，不覆盖原始结果或人工修订。新识别记录本机保留，最多 20 次、合计 64 MiB。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onPreviewOmrStructure, enabled = !state.busy,
                        modifier = Modifier.testTag("score_preview_structure")) { Text("重新整理识谱结构") }
                    Text("建议先整理识谱结构，再设置实际拍号、选择顺序一次。整理仅重新解释已存记录；再次整理会重新提示反复记号，需重新选择。原件保留、可撤销；已有人工修改受保护。", style = MaterialTheme.typography.bodySmall)
                }
                if (score != null && ScoreStructureTools.pendingRepeatChoices(score).isNotEmpty()) {
                    OutlinedButton(onClick = { linearRepeatOpen = true }, enabled = !state.busy,
                        modifier = Modifier.testTag("score_choose_linear_repeat")) { Text("本页顺序练一次，不执行反复") }
                    Text("识别到反复记号，尚未支持按记号跳转。可以明确选择当前导入乐谱按页面、小节顺序各演奏一次；其他结构错误仍需修正。", style = MaterialTheme.typography.bodySmall)
                }
                if (score != null && ScoreStructureTools.hasConfirmedLinearRepeat(score)) {
                    Text("已选择顺序一次：当前导入乐谱不执行反复跳转；可撤销此选择。", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("score_linear_repeat_selected"))
                }
                OutlinedButton(onClick = { onExportMidi(bpm) }, enabled = score != null && !state.busy, modifier = Modifier.testTag("score_export_midi")) { Text("导出当前修订 MIDI") }
                Text("简谱使用首调：大调以 1 为主音，小调以 6 为主音。原文件与命名信息独立保留。", style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("听一遍 · 本地合成音色", style = MaterialTheme.typography.titleMedium)
                    Text("速度 $bpm BPM · 音量 ${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(bpm.toFloat(), { bpm = it.roundToInt() }, valueRange = 30f..240f, modifier = Modifier.testTag("playback_tempo"))
                    Slider(volume, { volume = it }, valueRange = 0f..1f, modifier = Modifier.testTag("playback_volume"))
                    Row {
                        FilterChip(loop, { loop = !loop }, { Text("循环") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(metronome, { metronome = !metronome }, { Text("节拍器") })
                    }
                    val count = state.timeline?.measures?.size ?: 0
                    if (count > 0) ChoiceField("起始小节", (startMeasure + 1).toString(), (1..count).map(Int::toString), { startMeasure = it.toInt() - 1 }, "playback_start_measure")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlay(bpm, volume, startMeasure.coerceAtMost((count - 1).coerceAtLeast(0)), loop, metronome) }, enabled = score != null && !state.busy && !playback.playing, modifier = Modifier.testTag("score_play")) { Text("试听") }
                        OutlinedButton(onClick = onStop, enabled = playback.playing, modifier = Modifier.testTag("score_stop")) { Text("停止") }
                    }
                    Text(playback.error ?: playback.message, color = if (playback.error == null) XiuyinMuted else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("playback_status"))
                    if (score?.parts?.any { p -> p.measures.any { m -> m.events.any { it.grace } } } == true) Text("装饰音以 80 ms 参考时长试听；原谱零时值保持不变。", style = MaterialTheme.typography.bodySmall)
                    Text("试听高亮来自音频播放位置；实时陪练需单独开始。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("校对与修谱", style = MaterialTheme.typography.titleMedium)
                    if (selected == null) Text("选择下方音符，或在小节中增加音符。") else {
                        Text("${selected.pitch?.displayName ?: "休止符"} · 起点 ${selected.offset} · 时长 ${selected.duration}", modifier = Modifier.testTag("selected_event_details"))
                        Text(if (selected.id in revision!!.verifiedEventIds) "✓ 用户已确认" else "待人工确认", modifier = Modifier.testTag("selected_verification"))
                        selected.confidence?.let { Text("模型分数 ${"%.3f".format(it)}（未经准确率校准）", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { editing = selected }, enabled = !state.busy, modifier = Modifier.testTag("edit_selected_note")) { Text("修改音符") }
                            TextButton(onClick = onVerify, enabled = !state.busy, modifier = Modifier.testTag("verify_selected_note")) { Text("确认无误") }
                        }
                        OutlinedButton(onClick = {
                            val id = state.scoreId
                            val generation = revision.generation
                            if (id != null) octaveRangeTarget =
                                ScoreOctaveRangeRequest(id, generation, selected.id, selected.id, 1)
                        }, enabled = !state.busy && !selected.isRest,
                            modifier = Modifier.testTag("score_octave_range")) { Text("手动修正一段八度") }
                    }
                    if (selected != null) TextButton(onClick = { restoreTarget = "event" }, enabled = !state.busy,
                        modifier = Modifier.testTag("restore_original_note")) { Text("恢复此音符原始结果") }
                    if (state.sourceImages.isNotEmpty()) {
                        Text("原图对照：原始照片不会随移调改变。移调结果请看下方当前数字谱。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("source_reference_notice"))
                        SourceReference(state.sourceImages, selected?.sourceBox)
                    }
                    val issues = reviewIssues
                    if (issues.isNotEmpty()) {
                        val errors = issues.count { it.severity == ScoreIssueSeverity.ERROR }
                        Text("$errors 处阻塞错误 · ${issues.size - errors} 处复核提示（错误优先）",
                            color = if (errors > 0) MaterialTheme.colorScheme.error else XiuyinMuted,
                            modifier = Modifier.testTag("score_review_summary"))
                        val targets = issues.filter { issue -> preparation?.repeatIssues?.any { it.id == issue.id } == true || measures.any { (_, measure) ->
                            measure.id == issue.measureId || measure.events.any { it.id == issue.eventId }
                        } }
                        if (targets.isNotEmpty()) Row {
                            TextButton(onClick = { reviewIndex = Math.floorMod(reviewIndex - 1, targets.size); locateIssue(targets[reviewIndex]) }, modifier = Modifier.testTag("previous_review_issue")) { Text("上一个疑点") }
                            TextButton(onClick = { reviewIndex = (reviewIndex + 1) % targets.size; locateIssue(targets[reviewIndex]) }, modifier = Modifier.testTag("next_review_issue")) { Text("下一个疑点") }
                        }
                        issues.take(8).forEachIndexed { index, issue ->
                            val target = measures.firstOrNull { (_, measure) -> measure.id == issue.measureId || measure.events.any { it.id == issue.eventId } }
                            val location = target?.let { "${it.first} · 第 ${it.second.number} 小节：" }.orEmpty()
                            val label = if (issue.severity == ScoreIssueSeverity.ERROR) "需修正" else "请复核"
                            TextButton(onClick = { locateIssue(issue) }, enabled = !state.busy && (target != null || preparation?.repeatIssues?.any { it.id == issue.id } == true),
                                modifier = Modifier.testTag("score_review_issue_$index")) {
                                Text("$label · $location${issue.message}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (issues.size > 8) Text("已优先显示阻塞错误；可用上/下一个疑点定位其余提示，或在数字乐谱数据页查看。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        itemsIndexed(measures, key = { _, pair -> pair.second.id }) { index, (partName, measure) ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    if (index == 0) Text("当前数字谱 · 随修谱和移调更新", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("current_digital_score"))
                    Text("$partName · 第 ${measure.number} 小节", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    NotationMeasure(measure, mode, state.selectedEventId, active, revision?.fingerings.orEmpty(), onSelect)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        TextButton(onClick = { editingMeasure = measure }, enabled = !state.busy, modifier = Modifier.testTag("edit_measure_$index")) { Text("拍号 / 调号 / 谱号") }
                        TextButton(onClick = { restoreTarget = measure.id }, enabled = !state.busy, modifier = Modifier.testTag("restore_measure_$index")) { Text("恢复此小节原始结果") }
                        TextButton(onClick = { insertingIn = measure }, enabled = !state.busy, modifier = Modifier.testTag("insert_note_$index")) { Text("添加音符") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    missingTimeTarget?.let { target -> MissingTimeEditor(target.measureCount, state.busy,
        onDismiss = { missingTimeTarget = null }, onApply = { beats, beatType ->
            onFillMissingScoreTime(target.scoreId, target.generation, beats, beatType)
            missingTimeTarget = null
        }) }
    octaveRangeTarget?.let { target -> if (score != null) OctaveRangeEditor(score, target, state.busy,
        onDismiss = { octaveRangeTarget = null }, onApply = { request ->
            onShiftOctaveRange(request); octaveRangeTarget = null
        }) }
    if (wholeScoreTimeOpen) WholeScoreTimeEditor(onDismiss = { wholeScoreTimeOpen = false }) { beats, beatType ->
        onSetWholeScoreTime(beats, beatType); wholeScoreTimeOpen = false
    }
    if (linearRepeatOpen) AlertDialog(onDismissRequest = { linearRepeatOpen = false },
        title = { Text("按顺序只演奏一次？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目前不支持根据反复记号跳回、展开重复段或跳房子。此选择用于当前整份导入乐谱：按页面和小节顺序各演奏一次，不执行反复。")
                Text("只记录反复记号的演奏策略，不能确认音符识别正确，也不会解除拍号、时值重叠等其他错误。原始记录保留，保存后可撤销。")
                Text("如需整理识谱结构，请先取消并整理，随后再设置实际拍号、选择顺序一次。")
            }
        },
        confirmButton = { Button(onClick = { onConfirmLinearRepeat(); linearRepeatOpen = false }, enabled = !state.busy,
            modifier = Modifier.testTag("score_confirm_linear_repeat")) { Text("确认顺序一次，不执行反复") } },
        dismissButton = { TextButton(onClick = { linearRepeatOpen = false }, modifier = Modifier.testTag("score_cancel_linear_repeat")) { Text("取消") } })
    state.structurePreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.busy) onDismissStructurePreview() },
            title = { Text(if (preview.method == OmrPreviewMethod.REAL_IMAGE_RECOGNITION) "原图真实重识别预览" else "识谱结构整理预览") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (preview.method == OmrPreviewMethod.REAL_IMAGE_RECOGNITION)
                        "已使用本机模型重新识别归档原图；新模型输出与候选结果独立保存在本机。请对照原图复核，结构错误减少不代表识谱准确率已验证。"
                    else "仅重新解释已存模型记录，没有进行新模型识别，不能补回旧识别中漏掉的谱表。请对照原图复核；结构错误减少不代表识谱准确率已验证。")
                    preview.evidenceId?.let { Text("本机证据编号：$it", style = MaterialTheme.typography.bodySmall) }
                    Text("整理前：${structureSummaryText(preview.before)}", modifier = Modifier.testTag("score_structure_before"))
                    Text("整理后：${structureSummaryText(preview.after)}", modifier = Modifier.testTag("score_structure_after"))
                    preview.after.timelineProblem?.let { Text("时间线仍需修正：$it", color = MaterialTheme.colorScheme.error) }
                    Text(if (preview.method == OmrPreviewMethod.REAL_IMAGE_RECOGNITION)
                        "新推理的音符需要重新确认，不会沿用旧确认或指法。应用将保存新修订；原图、原始结果及旧确认和指法保留在撤销历史，可用撤销恢复。"
                    else "应用会另存为当前修订，原图和原始识别不变。可用撤销恢复整理前状态；有效音符 ID 的确认和指法保留。")
                }
            },
            confirmButton = { Button(onClick = onApplyOmrStructure, enabled = !state.busy,
                modifier = Modifier.testTag("score_apply_structure")) { Text("应用为可撤销修订") } },
            dismissButton = { TextButton(onClick = onDismissStructurePreview, enabled = !state.busy) { Text("保留原样") } })
    }
    restoreTarget?.let { target -> AlertDialog(onDismissRequest = { restoreTarget = null }, title = { Text("恢复${if (target == "event") "音符" else "小节"}原始结果？") },
        text = { Text("将移除该范围的用户确认和指法，其他内容保留；可以撤销。") },
        confirmButton = { TextButton(onClick = { if (target == "event") onRestoreEvent() else onRestoreMeasure(target); restoreTarget = null }, modifier = Modifier.testTag("restore_original_confirm")) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } }) }
    editing?.let { event -> EventEditor(event, revision?.fingerings?.get(event.id), false,
        onDismiss = { editing = null }, onSave = { changed, finger -> onEdit(ScorePatch.ReplaceEvent(event.id, changed), finger); editing = null },
        onDelete = { onEdit(ScorePatch.DeleteEvent(event.id), null); editing = null }) }
    insertingIn?.let { measure ->
        val event = remember(measure.id) { ScoreEvent("user-" + UUID.randomUUID(), BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("C", 0, 4), 1, "1", false, "quarter", 0, emptySet(), -1, -1) }
        EventEditor(event, null, true, onDismiss = { insertingIn = null }, onSave = { changed, finger -> onEdit(ScorePatch.InsertEvent(measure.id, changed), finger); insertingIn = null }, onDelete = {})
    }
    editingMeasure?.let { measure -> MeasureEditor(measure, { editingMeasure = null }) { patch -> onEdit(patch, null); editingMeasure = null } }
    if (transposeOpen && score != null) ScoreTransposeDialog(score, { transposeOpen = false }) { semitones, key ->
        pendingTransposeGeneration = revision.generation
        onTranspose(semitones, key)
        transposeOpen = false
    }
}

private data class MissingTimeTarget(val scoreId: String, val generation: Long, val measureCount: Int)

@Composable
private fun OctaveRangeEditor(score: DigitalScore, target: ScoreOctaveRangeRequest, busy: Boolean,
    onDismiss: () -> Unit, onApply: (ScoreOctaveRangeRequest) -> Unit) {
    val all = remember(score) { ScoreRangeEditing.endpoints(score) }
    val anchor = all.firstOrNull { it.eventId == target.startEventId }
    val lane = all.filter { anchor != null && !it.event.isRest && it.partId == anchor.partId &&
        it.event.staff == anchor.event.staff && it.event.voice == anchor.event.voice }
    var startId by remember { mutableStateOf(target.startEventId) }
    var endId by remember { mutableStateOf(target.endEventId) }
    var octaves by remember { mutableStateOf<Int?>(null) }
    val labels = lane.mapIndexed { index, event -> "${index + 1} · ${event.label}" }
    fun label(id: String) = lane.indexOfFirst { it.eventId == id }.takeIf { it >= 0 }?.let { labels[it] } ?: "位置不可用"
    val preview = octaves?.let { direction -> runCatching { ScoreRangeEditing.preview(score, startId, endId, direction) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手动八度范围修正") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请对照原图选择起止音符。这是你的手动修订，不是自动识别八度线。")
            anchor?.let { Text("${it.partName} · 谱表 ${it.event.staff} · 声部 ${it.event.voice}") }
            ChoiceField("起始音符", label(startId), labels, { startId = lane[labels.indexOf(it)].eventId }, "octave_range_start")
            ChoiceField("结束音符", label(endId), labels, { endId = lane[labels.indexOf(it)].eventId }, "octave_range_end")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(octaves == 1, { octaves = 1 }, { Text("升一个八度") }, modifier = Modifier.testTag("octave_range_up"))
                FilterChip(octaves == -1, { octaves = -1 }, { Text("降一个八度") }, modifier = Modifier.testTag("octave_range_down"))
            }
            preview?.getOrNull()?.let { plan ->
                Text("预览：修改 ${plan.changedEventIds.size} 个音，保留 ${plan.skippedRests} 个休止符。\n从 ${plan.start.label}\n至 ${plan.end.label}",
                    modifier = Modifier.testTag("octave_range_preview"))
            }
            preview?.exceptionOrNull()?.let { Text(it.message ?: "范围不可用", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("octave_range_error")) }
            Text("包含起止处同声部的整个和弦；只改这条声部的音高，时值与其他声部不变。保留原图与原始结果，可一次撤销；修改音符的旧确认会清除，需要重新核对。跨范围延音线可能需要继续修正。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = { onApply(target.copy(startEventId = startId, endEventId = endId, octaves = requireNotNull(octaves))) },
        enabled = !busy && preview?.isSuccess == true, modifier = Modifier.testTag("octave_range_apply")) { Text("保存这次范围修正") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("octave_range_cancel")) { Text("取消") } })
}

@Composable
private fun MissingTimeEditor(measureCount: Int, busy: Boolean, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var beats by remember { mutableStateOf("") }
    var beatType by remember { mutableStateOf("") }
    val numerator = beats.trim().toIntOrNull()
    val denominator = beatType.trim().toIntOrNull()
    val valid = numerator != null && denominator != null && ScoreStructureTools.isValidTime(ScoreTime(numerator, denominator))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("对照原谱补全拍号") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请查看原谱首页或这一段实际标注的拍号，再主动选择或填写。当前没有预选拍号。")
            Text("只为 $measureCount 个缺失或无效拍号的小节填入同一拍号；已有合法变拍号保留。若这些缺失小节采用不同拍号，请取消后逐小节修改。")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8).forEach { (top, bottom) ->
                    FilterChip(selected = beats == top.toString() && beatType == bottom.toString(),
                        onClick = { beats = top.toString(); beatType = bottom.toString() },
                        label = { Text("$top/$bottom") }, modifier = Modifier.testTag("missing_time_${top}_$bottom"))
                }
            }
            OutlinedTextField(beats, { beats = it }, label = { Text("原谱拍号分子") }, singleLine = true, modifier = Modifier.testTag("missing_time_beats"))
            OutlinedTextField(beatType, { beatType = it }, label = { Text("原谱拍号分母") }, singleLine = true, modifier = Modifier.testTag("missing_time_beat_type"))
            if ((beats.isNotBlank() || beatType.isNotBlank()) && !valid) Text("请填写有效拍号：分子 1–128，分母为 1、2、4、8 等。", color = MaterialTheme.colorScheme.error)
            Text("音符、休止符、时值、指法和确认记录不变；保存为可撤销修订。拍号补全不代表音符识别正确。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(onClick = { if (valid) onApply(requireNotNull(numerator), requireNotNull(denominator)) }, enabled = valid && !busy,
            modifier = Modifier.testTag("missing_time_apply")) { Text("确认只补缺失拍号") }
    }, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("missing_time_cancel")) { Text("取消") } })
}
private fun structureSummaryText(value: ScoreStructureSummary): String =
    "${value.partCount} 声部 · ${value.staffCount} 谱表 · ${value.measureCount} 小节记录 · ${value.eventCount} 音符/休止符（休止 ${value.restCount}）· ${value.errorCount} 处阻塞错误"

@Composable
private fun WholeScoreTimeEditor(onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var beats by remember { mutableStateOf("") }
    var beatType by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设置全谱拍号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请对照原谱首页填写。此操作会覆盖当前所有小节拍号，适用于全谱同拍号；有变拍号请取消并逐小节修改。")
                Text("音符、休止符和实际时值保持不变，不会自动补休止符；保存后可撤销。")
                OutlinedTextField(beats, { beats = it }, label = { Text("拍号分子（上方数字）") }, singleLine = true, modifier = Modifier.testTag("whole_time_beats"))
                OutlinedTextField(beatType, { beatType = it }, label = { Text("拍号分母（下方数字）") }, singleLine = true, modifier = Modifier.testTag("whole_time_beat_type"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            val numerator = beats.trim().toIntOrNull()
            val denominator = beatType.trim().toIntOrNull()
            if (numerator == null || denominator == null || numerator !in 1..128 || denominator !in 1..1024 ||
                (denominator and (denominator - 1)) != 0) error = "请填写原谱拍号；分子为 1–128，分母为 1、2、4、8 等。"
            else onApply(numerator, denominator)
        }, modifier = Modifier.testTag("whole_time_apply")) { Text("确认覆盖全谱拍号") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ChoiceField(label: String, value: String, options: List<String>, onChange: (String) -> Unit, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag(tag)) { Text("$label：$value") }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(item) },
            onClick = { onChange(item); expanded = false }, modifier = Modifier.testTag("${tag}_option_$item")) } }
    }
}

private fun fraction(text: String): BeatFraction {
    val parts = text.trim().split('/')
    require(parts.size in 1..2) { "拍时格式为整数或分数，例如 1/2" }
    return BeatFraction.of(parts[0].trim().toLong(), if (parts.size == 2) parts[1].trim().toLong() else 1)
}

@Composable
private fun EventEditor(event: ScoreEvent, fingering: Int?, insert: Boolean, onDismiss: () -> Unit, onSave: (ScoreEvent, Int?) -> Unit, onDelete: () -> Unit) {
    var rest by remember { mutableStateOf(event.isRest) }
    var grace by remember { mutableStateOf(event.grace) }
    var tupletActual by remember { mutableStateOf(event.tupletActual?.toString().orEmpty()) }
    var tupletNormal by remember { mutableStateOf(event.tupletNormal?.toString().orEmpty()) }
    var step by remember { mutableStateOf(event.pitch?.step ?: "C") }
    var alter by remember { mutableStateOf((event.pitch?.alter ?: 0).toString()) }
    var octave by remember { mutableStateOf((event.pitch?.octave ?: 4).toString()) }
    var type by remember { mutableStateOf(event.type ?: "quarter") }
    var dots by remember { mutableStateOf(event.dots.toString()) }
    var offset by remember { mutableStateOf(event.offset.toString()) }
    var duration by remember { mutableStateOf(event.duration.toString()) }
    var staff by remember { mutableStateOf(event.staff.toString()) }
    var voice by remember { mutableStateOf(event.voice) }
    var finger by remember { mutableStateOf(fingering?.toString().orEmpty()) }
    var chord by remember { mutableStateOf(event.chord) }
    var tieStart by remember { mutableStateOf("start" in event.ties) }
    var tieStop by remember { mutableStateOf("stop" in event.ties) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun recomputeDuration() {
        try {
            val actual = tupletActual.trim().takeIf { it.isNotEmpty() }?.toInt()
            val normal = tupletNormal.trim().takeIf { it.isNotEmpty() }?.toInt()
            duration = ScoreEditing.notatedDuration(type, dots.toInt(), actual, normal, grace).toString()
            error = null
        } catch (exception: IllegalArgumentException) { error = exception.message ?: "请填写完整连音比例，或将两项同时清空" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (insert) "添加音符" else "修正音符") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拍时单位为四分音符；保存后谱面、简谱、移调、试听和陪练共用此修订。", style = MaterialTheme.typography.bodySmall)
                FilterChip(rest, { rest = !rest }, { Text("休止符") }, modifier = Modifier.testTag("edit_rest"))
                if (!rest) {
                    ChoiceField("音名", step, listOf("C", "D", "E", "F", "G", "A", "B"), { step = it }, "edit_pitch_step")
                    ChoiceField("升降号半音", alter, (-2..2).map(Int::toString), { alter = it }, "edit_pitch_alter")
                    OutlinedTextField(octave, { octave = it }, label = { Text("八度（中央 C 为 4）") }, singleLine = true, modifier = Modifier.testTag("edit_pitch_octave"))
                }
                ChoiceField("时值符号", type, listOf("whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th"), { type = it; recomputeDuration() }, "edit_note_type")
                ChoiceField("附点", dots, (0..4).map(Int::toString), { dots = it; recomputeDuration() }, "edit_note_dots")
                OutlinedTextField(offset, { offset = it }, label = { Text("小节内起点（0 起算）") }, singleLine = true, modifier = Modifier.testTag("edit_note_offset"))
                OutlinedTextField(duration, { duration = it }, label = { Text("实际时长（可填 1/3 等连音分数）") }, singleLine = true, modifier = Modifier.testTag("edit_note_duration"))
                OutlinedTextField(staff, { staff = it }, label = { Text("谱表编号") }, singleLine = true, modifier = Modifier.testTag("edit_note_staff"))
                OutlinedTextField(voice, { voice = it }, label = { Text("声部") }, singleLine = true, modifier = Modifier.testTag("edit_note_voice"))
                OutlinedTextField(finger, { finger = it }, label = { Text("指法 1–5，留空清除") }, singleLine = true, modifier = Modifier.testTag("edit_note_finger"))
                FilterChip(grace, { grace = !grace; if (grace) { rest = false; tieStart = false; tieStop = false }; recomputeDuration() }, { Text("装饰音（记谱时值为零）") })
                OutlinedTextField(tupletActual, { tupletActual = it; recomputeDuration() }, label = { Text("连音实际数量，普通音符留空") }, singleLine = true, modifier = Modifier.testTag("edit_tuplet_actual"))
                OutlinedTextField(tupletNormal, { tupletNormal = it; recomputeDuration() }, label = { Text("连音正常数量，如三连音 3:2") }, singleLine = true, modifier = Modifier.testTag("edit_tuplet_normal"))
                TextButton(onClick = { recomputeDuration() }, modifier = Modifier.testTag("edit_recompute_duration")) { Text("按记谱与连音比例重算时长") }
                FilterChip(chord, { chord = !chord }, { Text("和弦成员（与前音同时）") })
                Row { FilterChip(tieStart, { tieStart = !tieStart }, { Text("延音线开始") }); Spacer(Modifier.width(4.dp)); FilterChip(tieStop, { tieStop = !tieStop }, { Text("延音线结束") }) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!insert) TextButton(onClick = { deleting = true }, modifier = Modifier.testTag("edit_note_delete")) { Text("删除这个音符", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button(onClick = {
            try {
                val pitch = if (rest) null else ScorePitch(step, alter.toInt(), octave.toInt()).also { require(it.midi in 0..127) { "音高超出范围" } }
                val start = fraction(offset); val length = fraction(duration); val staffNumber = staff.toInt()
                require(start >= BeatFraction.ZERO && (if (grace) length == BeatFraction.ZERO else length > BeatFraction.ZERO)) { "起点不能为负，时长必须为正" }
                require(staffNumber in 1..32 && voice.isNotBlank()) { "谱表或声部无效" }
                val fingeringValue = finger.trim().takeIf { it.isNotEmpty() }?.toInt()?.also { require(it in 1..5) { "指法必须为 1–5" } }
                val actual = tupletActual.takeIf { it.isNotBlank() }?.toInt()
                val normal = tupletNormal.takeIf { it.isNotBlank() }?.toInt()
                require((actual == null) == (normal == null) && (actual == null || actual in 1..32) && (normal == null || normal in 1..32)) { "连音比例无效" }
                require(!grace || (pitch != null && !tieStart && !tieStop)) { "装饰音不能为休止符或延音" }
                onSave(event.copy(pitch = pitch, grace = grace, tupletActual = actual, tupletNormal = normal, offset = start, duration = length, staff = staffNumber, voice = voice.trim(), chord = chord,
                    type = type, dots = dots.toInt(), ties = buildSet { if (tieStart) add("start"); if (tieStop) add("stop") }), fingeringValue)
            } catch (exception: IllegalArgumentException) { error = exception.message ?: "请输入有效音符参数" }
        }, modifier = Modifier.testTag("edit_note_save")) { Text("保存修正") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这个音符？") }, text = { Text("会更新所有谱式和试听，可使用撤销恢复。") },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.testTag("edit_note_delete_confirm")) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留") } })
}

@Composable
private fun MeasureEditor(measure: ScoreMeasure, onDismiss: () -> Unit, onSave: (ScorePatch.UpdateMeasure) -> Unit) {
    var beats by remember { mutableStateOf(measure.time?.beats?.toString().orEmpty()) }
    var beatType by remember { mutableStateOf(measure.time?.beatType?.toString().orEmpty()) }
    var fifths by remember { mutableStateOf(measure.fifths?.toString().orEmpty()) }
    var staves by remember { mutableStateOf(measure.declaredStaves.toString()) }
    var clefs by remember { mutableStateOf(measure.clefs) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("第 ${measure.number} 小节设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅修改当前小节。后续小节如需改变，请分别校正。", style = MaterialTheme.typography.bodySmall)
            Text("这里校正谱面标注。调号校正不会移动音符；整谱升降请用顶部“移调”。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(beats, { beats = it }, label = { Text("拍号分子，未知留空") }, singleLine = true, modifier = Modifier.testTag("measure_beats"))
            OutlinedTextField(beatType, { beatType = it }, label = { Text("拍号分母，未知留空") }, singleLine = true, modifier = Modifier.testTag("measure_beat_type"))
            OutlinedTextField(fifths, { fifths = it }, label = { Text("调号：升号正数，降号负数，0=C") }, singleLine = true, modifier = Modifier.testTag("measure_fifths"))
            OutlinedTextField(staves, { staves = it }, label = { Text("谱表数量") }, singleLine = true, modifier = Modifier.testTag("measure_staves"))
            repeat((staves.toIntOrNull() ?: 0).coerceIn(0, 8)) { index ->
                val staff = index + 1
                val clef = clefs.firstOrNull { it.staff == staff } ?: ScoreClef(staff, "G", 2)
                ChoiceField("谱表 $staff 谱号", "${clef.sign}${clef.line}", listOf("G2", "F4", "C3", "C4"), { value ->
                    clefs = clefs.filterNot { it.staff == staff } + ScoreClef(staff, value.take(1), value.drop(1).toInt(), clef.octaveChange)
                }, "measure_clef_$staff")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        try {
            val time = if (beats.isBlank() && beatType.isBlank()) null else ScoreTime(beats.toInt(), beatType.toInt())
            require(time == null || (time.beats in 1..64 && time.beatType in listOf(1, 2, 4, 8, 16, 32, 64))) { "拍号无效" }
            val key = fifths.takeIf { it.isNotBlank() }?.toInt(); require(key == null || key in -7..7) { "调号应为 -7 至 7" }
            val count = staves.toInt(); require(count in 1..32) { "谱表数量应为 1–32" }
            onSave(ScorePatch.UpdateMeasure(measure.id, time, key, clefs.filter { it.staff <= count }, count))
        } catch (exception: IllegalArgumentException) { error = exception.message ?: "设置无效" }
    }, modifier = Modifier.testTag("measure_save")) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
