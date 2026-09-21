package com.xiuyin.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiuyin.app.BuildConfig
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.data.PracticeMode
import com.xiuyin.app.data.PracticePreparation
import com.xiuyin.app.data.StudyPiece

@Composable
fun HomeScreen(
    pieces: List<StudyPiece>,
    recentPieceId: String?,
    onOpenPiece: (String) -> Unit,
    onLibrary: () -> Unit,
) {
    ScreenColumn {
        Surface(color = Color(0xFFEEE0CC), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("修音 / V${BuildConfig.VERSION_NAME}", color = XiuyinNavy, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(18.dp))
                    Text("从一张谱开始。", color = XiuyinInk, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "打开一首小练习，\n留一点时间给琴键。",
                        color = Color(0xFF74675B),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onLibrary,
                        colors = ButtonDefaults.buttonColors(containerColor = XiuyinTeal, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("浏览本地曲库", modifier = Modifier.padding(vertical = 3.dp)) }
                }
                PianoKeys(Modifier.fillMaxWidth().height(64.dp))
            }
        }

        val recentPiece = pieces.firstOrNull { it.id == recentPieceId }
        if (recentPiece != null) {
            SectionHeading("最近打开", "从上次看过的谱继续")
            PieceCard(recentPiece, onClick = { onOpenPiece(recentPiece.id) }, action = "重新打开")
        }

        SectionHeading("本地小练习", "原创示例随 App 保存，打开无需联网")
        pieces.take(3).forEach { piece ->
            PieceCard(piece, onClick = { onOpenPiece(piece.id) })
        }
        if (pieces.isEmpty()) {
            NoticeCard("曲库暂时不可用", "请查看开发监控中的数据库状态，再重新打开 App。")
        }
        NoticeCard(
            "当前可以做什么",
            "浏览示例谱、保存练习设置，并在练习页检查本机麦克风输入。音高识别、真实跟谱和拍照识谱尚未接入。",
        )
    }
}

@Composable
fun LibraryScreen(pieces: List<StudyPiece>, onOpenPiece: (String) -> Unit, onDigitalScores: () -> Unit = {}) {
    ScreenColumn {
        Text("琴谱在这里，\n随时可以翻开。", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${pieces.size} 首原创入门示例 · 已随 App 内置",
            style = MaterialTheme.typography.bodyMedium,
            color = XiuyinMuted,
        )
        OutlinedButton(onClick = onDigitalScores, modifier = Modifier.fillMaxWidth()) { Text("导入与数字乐谱") }
        Text("MusicXML 直接解析并保存至本机；图片 / PDF 识谱尚未接入。", color = XiuyinMuted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = XiuyinLine)
        pieces.forEach { piece -> PieceCard(piece, onClick = { onOpenPiece(piece.id) }) }
        if (pieces.isEmpty()) {
            NoticeCard("暂无可用示例", "请在开发监控中检查数据库是否正常。")
        }
        Text(
            "这些四小节短谱用于基础页面验证（TEST DATA）。正式曲库、搜索与收藏计划在 V0.8 提供。",
            style = MaterialTheme.typography.bodySmall,
            color = XiuyinMuted,
        )
    }
}

@Composable
fun ScoreScreen(piece: StudyPiece, onBack: () -> Unit, onPrepare: () -> Unit) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("返回曲库") }
        Badge("原创入门示例")
        Text(piece.title, style = MaterialTheme.typography.headlineMedium)
        Text(piece.subtitle, color = XiuyinMuted, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("调性", piece.keySignature, Modifier.weight(1f))
            StatCell("建议速度", "${piece.bpm} BPM", Modifier.weight(1f))
            StatCell("长度", "${piece.bars} 小节", Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, XiuyinLine)) {
            ScorePreview(piece)
        }
        SectionHeading("看谱提示", piece.focus)
        Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text("设置练习模式")
        }
        NoticeCard(
            "这是静态示例谱",
            "本页是原有静态示例；MusicXML 请从曲库的“导入与数字乐谱”打开。试听与实时陪练尚未接入，练习模式可先保存在本机。",
        )
        Text(
            "来源：修音项目原创，可随应用使用、复制与修改。",
            style = MaterialTheme.typography.bodySmall,
            color = XiuyinMuted,
        )
    }
}

@Composable
fun PracticeScreen(
    piece: StudyPiece?,
    preparation: PracticePreparation?,
    onChoosePiece: () -> Unit,
    onSavePreparation: (PracticePreparation) -> Unit,
    saving: Boolean,
    audioSnapshot: AudioSnapshot,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    if (piece == null) {
        ScreenColumn {
            Badge("练习设置预览")
            Text("先挑一首小练习。", style = MaterialTheme.typography.headlineMedium)
            Text("选好曲目，再保存适合自己的练习模式。", color = XiuyinMuted, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onChoosePiece, modifier = Modifier.fillMaxWidth()) { Text("选择练习曲目") }
            AudioInputCard(audioSnapshot, onStartAudio, onStopAudio, onOpenPermissionSettings)
        }
        return
    }
    val currentPreparation = preparation?.takeIf { it.pieceId == piece.id }
    var modeName by rememberSaveable(piece.id, currentPreparation?.mode) {
        mutableStateOf((currentPreparation?.mode ?: PracticeMode.CONTINUOUS).name)
    }
    var repeatTarget by rememberSaveable(piece.id, currentPreparation?.repeatTarget) {
        mutableIntStateOf(currentPreparation?.repeatTarget ?: 3)
    }
    val selectedMode = PracticeMode.valueOf(modeName)
    val editedPreparation = PracticePreparation(piece.id, selectedMode, repeatTarget)
    val isSaved = currentPreparation == editedPreparation

    ScreenColumn {
        Badge("练习设置预览")
        Text(piece.title, style = MaterialTheme.typography.headlineMedium)
        Text("${piece.keySignature} · ${piece.bars} 小节 · 建议 ${piece.bpm} BPM", style = MaterialTheme.typography.bodyMedium, color = XiuyinMuted)
        OutlinedButton(onClick = onChoosePiece) { Text("更换练习曲目") }
        AudioInputCard(audioSnapshot, onStartAudio, onStopAudio, onOpenPermissionSettings)
        SectionHeading("选择练习方式", "本版保存选项，尚不执行演奏判断")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PracticeMode.entries.forEach { mode ->
                ModeCard(
                    mode = mode,
                    selected = selectedMode == mode,
                    enabled = !saving,
                    onSelect = { modeName = mode.name },
                )
            }
        }
        if (selectedMode == PracticeMode.REINFORCE) {
            Surface(color = Color(0xFFFFFDF8), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连续正确目标", style = MaterialTheme.typography.titleMedium)
                    Text("先保存目标次数；强化判断将在 V0.5 接入。", color = XiuyinMuted, style = MaterialTheme.typography.bodySmall)
                    listOf(1, 2, 3, 5).chunked(2).forEach { options ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            options.forEach { count ->
                                FilterChip(
                                    selected = repeatTarget == count,
                                    onClick = { repeatTarget = count },
                                    enabled = !saving,
                                    label = { Text("$count 次") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { onSavePreparation(editedPreparation) },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (saving) "正在保存…" else "保存练习设置") }
        Text(
            if (isSaved) "当前设置已保存至本机。关闭 App 后仍可恢复。" else "设置尚未保存。保存后不会开启麦克风。",
            color = if (isSaved) XiuyinTeal else XiuyinMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        NoticeCard(
            "实时陪练尚未接入",
            "当前可检查收音与候选起音。音高、多音识别、乐谱约束、跟谱和强化判定将按后续小版本逐项验证。",
        )
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = XiuyinMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = Color(0xFFF4E1D3), shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFF6D3C28), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PieceCard(piece: StudyPiece, onClick: () -> Unit, action: String = "查看乐谱") {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFFFFDF8),
        border = BorderStroke(1.dp, XiuyinLine),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniScore(Modifier.size(width = 42.dp, height = 50.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(piece.title, style = MaterialTheme.typography.titleMedium)
                    Text(piece.subtitle, color = XiuyinMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("${piece.keySignature} · ${piece.bars} 小节 · ${piece.bpm} BPM", style = MaterialTheme.typography.labelMedium, color = XiuyinMuted)
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.SansSerif)
    }
}

@Composable
private fun NoticeCard(title: String, body: String) {
    Surface(color = Color(0xFFF1EAE0), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = XiuyinMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModeCard(mode: PracticeMode, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val detail = when (mode) {
        PracticeMode.CONTINUOUS -> "演奏不中断，结束后回看错误。"
        PracticeMode.STOP_ON_ERROR -> "弹错时停在当前位置，弹对再继续。"
        PracticeMode.REINFORCE -> "连续弹对指定次数，再向下一段前进。"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFFFAEBDE) else Color(0xFFFFFDF8),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) XiuyinTeal else XiuyinLine),
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mode.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = XiuyinMuted)
            }
        }
    }
}

@Composable
private fun PianoKeys(modifier: Modifier) {
    Canvas(modifier) {
        val keyWidth = size.width / 14f
        repeat(14) { key ->
            drawRect(
                color = Color(0xFFFFF8EE),
                topLeft = Offset(key * keyWidth + 1.dp.toPx(), 0f),
                size = Size(keyWidth - 2.dp.toPx(), size.height - 4.dp.toPx()),
            )
        }
        repeat(13) { key ->
            if (key % 7 !in listOf(2, 6)) {
                drawRect(
                    color = XiuyinNavy,
                    topLeft = Offset((key + 1) * keyWidth - keyWidth * 0.31f, 0f),
                    size = Size(keyWidth * 0.62f, size.height * 0.61f),
                )
            }
        }
    }
}

@Composable
private fun MiniScore(modifier: Modifier) {
    Box(modifier.background(Color(0xFFF1EAE0), RoundedCornerShape(7.dp)).padding(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            repeat(5) { line ->
                val y = size.height * 0.32f + line * 3.5.dp.toPx()
                drawLine(Color(0xFFB3A08D), Offset.Zero.copy(y = y), Offset(size.width, y), 0.75.dp.toPx())
            }
            val noteX = size.width * 0.48f
            val noteY = size.height * 0.6f
            drawOval(XiuyinTeal, Offset(noteX - 3.dp.toPx(), noteY), Size(6.dp.toPx(), 4.dp.toPx()))
            drawLine(XiuyinTeal, Offset(noteX + 3.dp.toPx(), noteY + 2.dp.toPx()), Offset(noteX + 3.dp.toPx(), noteY - 13.dp.toPx()), 1.25.dp.toPx())
        }
    }
}
