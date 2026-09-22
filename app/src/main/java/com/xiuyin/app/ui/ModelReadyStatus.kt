package com.xiuyin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus

@Composable
fun ModelReadyStatus(snapshot: RecognitionEngineSnapshot, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("识别模型：${snapshot.status.name}", modifier = Modifier.testTag("model_status"), style = MaterialTheme.typography.titleSmall)
            Text(when (snapshot.status) {
                RecognitionEngineStatus.READY -> "模型已就绪。点击开始后才会使用麦克风。"
                RecognitionEngineStatus.LOADING -> "正在加载本机模型，完成前不能开始；加载完成不会自动收音。"
                RecognitionEngineStatus.ERROR -> "模型失败，已暂停输入。请重试准备，随后手动开始。"
                RecognitionEngineStatus.WARMING_UP -> "正在等待足够的真实音频样本。"
                RecognitionEngineStatus.RUNNING -> "正在处理实际音频。激活分数不是音符准确率。"
                else -> "模型尚未准备，请先准备模型。"
            }, style = MaterialTheme.typography.bodySmall)
            Text(snapshot.message, style = MaterialTheme.typography.bodySmall,
                color = if (snapshot.status == RecognitionEngineStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.status in setOf(RecognitionEngineStatus.IDLE, RecognitionEngineStatus.ERROR, RecognitionEngineStatus.CLOSED)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("model_prepare")) {
                    Text(if (snapshot.status == RecognitionEngineStatus.ERROR) "重试准备模型" else "准备识别模型")
                }
            }
        }
    }
}