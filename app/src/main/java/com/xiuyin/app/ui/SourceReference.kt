package com.xiuyin.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.score.SourceBox
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Source pixels are displayed unchanged; the box is explicitly staff-level, not a fabricated note box. */
@Composable
fun SourceReference(images: List<File>, sourceBox: SourceBox?) {
    if (images.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selectedPage by rememberSaveable(images.firstOrNull()?.absolutePath) { mutableIntStateOf(0) }
    LaunchedEffect(sourceBox?.page) { sourceBox?.page?.takeIf { it in images.indices }?.let { selectedPage = it } }
    val page = selectedPage.coerceIn(images.indices)
    val file = images[page]
    var preview by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var originalWidth by remember(file) { mutableIntStateOf(0) }
    var originalHeight by remember(file) { mutableIntStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try {
            val result = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "原图无法解码" }
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1) }
                Triple(requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)), bounds.outWidth, bounds.outHeight)
            }
            preview = result.first; originalWidth = result.second; originalHeight = result.third
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (exception: Exception) { error = exception.message ?: "原图读取失败" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("原图对照", style = MaterialTheme.typography.titleMedium)
        ChoiceField("页码", (page + 1).toString(), (1..images.size).map(Int::toString), { selectedPage = it.toInt() - 1 }, "source_page")
        Text("橙框定位当前音符所属谱表；模型没有精确单音符边框。", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { zoom = 1f; pan = Offset.Zero; expanded = true }, enabled = preview != null, modifier = Modifier.testTag("source_expand")) { Text("放大原图 · 双指缩放与拖动") }
        preview?.let { bitmap ->
            Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).testTag("source_reference_image")) {
                Image(bitmap.asImageBitmap(), "第 ${page + 1} 页原始乐谱", Modifier.fillMaxSize())
                if (sourceBox?.page == page && originalWidth > 0 && originalHeight > 0) Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xffbf723f), Offset((sourceBox.x / originalWidth * size.width).toFloat(), (sourceBox.y / originalHeight * size.height).toFloat()),
                        Size((sourceBox.width / originalWidth * size.width).toFloat(), (sourceBox.height / originalHeight * size.height).toFloat()), style = Stroke(3.dp.toPx()))
                }
            }
        } ?: if (error == null) Text("正在打开原图…") else Unit
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(10.dp), title = { Text("原图第 ${page + 1} 页 · ${(zoom * 100).toInt()}%") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(440.dp).clipToBounds().testTag("source_zoom")
                    .pointerInput(file) { detectTransformGestures { _, delta, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 6f)
                        pan = if (zoom == 1f) Offset.Zero else Offset((pan.x + delta.x).coerceIn(-size.width * zoom, size.width * zoom), (pan.y + delta.y).coerceIn(-size.height * zoom, size.height * zoom))
                    } }) {
                    preview?.let { Image(it.asImageBitmap(), "可缩放原始谱图", Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) }
                }
                Slider(zoom, { zoom = it; if (it == 1f) pan = Offset.Zero }, valueRange = 1f..6f)
                Text("仅改变查看倍率，原图文件保持不变。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ expanded = false }) { Text("返回修谱") } },
        dismissButton = { TextButton({ zoom = 1f; pan = Offset.Zero }) { Text("恢复原始视图") } })
}

