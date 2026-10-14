package com.xiuyin.quicktest

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiuyin.app.pitch.RecognitionEngineStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuickTestActivity : ComponentActivity() {
    val viewModel: QuickTestViewModel by lazy { ViewModelProvider(this)[QuickTestViewModel::class.java] }
    private var pendingComment: String? = null
    private var pendingSavePath: String? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val comment = pendingComment
        pendingComment = null
        if (!granted) viewModel.permissionDenied()
        else if (comment != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.start(comment)
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val path = pendingSavePath
        pendingSavePath = null
        if (uri != null && path != null) viewModel.saveTo(uri, path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSavePath = savedInstanceState?.getString("pendingSavePath")
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val model by viewModel.modelState.collectAsStateWithLifecycle()
            val audio by viewModel.audioState.collectAsStateWithLifecycle()
            var comment by rememberSaveable { mutableStateOf("") }
            var historyVisible by rememberSaveable { mutableStateOf(false) }
            val running = state.phase in setOf(QuickPhase.STARTING, QuickPhase.RECORDING)
            val busy = running || state.phase in setOf(QuickPhase.SAVING, QuickPhase.PREPARING)
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF186B5B), background = Color(0xFFF5F7F4))) {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("修音一键测试", style = MaterialTheme.typography.headlineLarge)
                        Text("点开始 → 弹琴 → 结束 → 发回数据包", style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(state.message, modifier = Modifier.testTag("quick_status"), style = MaterialTheme.typography.titleLarge)
                                if (state.phase in setOf(QuickPhase.PREPARING, QuickPhase.STARTING, QuickPhase.SAVING)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                if (running || state.receivedSamples > 0) {
                                    Text("已录 ${state.elapsedSeconds.toInt()} 秒 / 60 秒", style = MaterialTheme.typography.headlineSmall)
                                }
                                if (state.phase == QuickPhase.RECORDING) {
                                    Text("听到的音（待核对）", style = MaterialTheme.typography.labelLarge)
                                    Text(state.latestNotes, modifier = Modifier.testTag("quick_notes"), style = MaterialTheme.typography.headlineMedium)
                                    Text(if (state.modelFrames == 0L) "正在积累声音，识别结果稍后出现。" else "正在听，请继续弹琴。",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (audio.clipped) Text("声音过大，请把手机离琴稍远一点。", color = MaterialTheme.colorScheme.error)
                                }
                                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("quick_error")) }
                            }
                        }
                        if (running) {
                            OutlinedButton(viewModel::markProblem, enabled = state.phase == QuickPhase.RECORDING,
                                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("quick_mark")) {
                                Text("这里有问题（已标记 ${state.markedProblems} 次）")
                            }
                            Button({ viewModel.finish() }, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("quick_stop")) {
                                Text("结束并生成数据包", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("保持此页面打开。切到后台会结束并保存；满60秒自动结束。")
                        } else {
                            state.exportFile?.takeIf { it.isFile }?.let { file ->
                                Button({ share(file) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("quick_share")) {
                                    Text("分享数据包", style = MaterialTheme.typography.titleMedium)
                                }
                                OutlinedButton({ requestSave(file) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("quick_save")) { Text("保存到手机文件") }
                                Text("不用解压，把ZIP发回来即可。分享面板打开不代表已经发送。", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(comment, { comment = it.take(500) }, enabled = !busy,
                                label = { Text("可选：你准备弹什么") }, placeholder = { Text("例如：反复弹D4，不踩踏板") },
                                modifier = Modifier.fillMaxWidth().testTag("quick_comment"), minLines = 1, maxLines = 3)
                            if (state.phase == QuickPhase.ERROR) {
                                Button(viewModel::prepare, modifier = Modifier.fillMaxWidth().height(60.dp).testTag("quick_retry")) { Text("重新准备") }
                            } else {
                                Button({ requestStart(comment) }, enabled = !busy && model.status == RecognitionEngineStatus.READY,
                                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("quick_start")) {
                                    Text(if (state.exportFile == null) "开始测试" else "再测一次", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                TextButton({ startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }) {
                                    Text("麦克风权限设置")
                                }
                            }
                            Text("点开始会录下本次环境声音，最长60秒，仅保存在手机；由你选择分享。", style = MaterialTheme.typography.bodySmall)
                            Text("不需要导谱。建议先反复弹一个音，再弹一小段；发现显示不对时点“这里有问题”。", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton({ historyVisible = !historyVisible }, enabled = !busy,
                            modifier = Modifier.testTag("quick_history")) { Text("最近测试（${state.sessions.size}）${if (historyVisible) " 收起" else " 展开"}") }
                        if (historyVisible && !busy) {
                            state.sessions.forEach { session ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(session.startedAtMillis)))
                                        Text("${session.sampleCount / 48_000} 秒 · ${if (session.status == "COMPLETED") "已保存" else "提前结束，数据保留"}")
                                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        Row {
                                            TextButton({ viewModel.selectSession(session.id) }, modifier = Modifier.testTag("quick_export_${session.id}")) { Text("取出数据包") }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("测试采集版 ${BuildConfig.VERSION_NAME} · 识别结果需要核对，不显示准确率或练习评分。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private fun requestStart(comment: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.start(comment)
        else { pendingComment = comment; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("修音测试数据包", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "选择应用，把ZIP发回来"))
        } catch (error: Exception) { viewModel.reportShareProblem("无法打开分享：${error.message}。可以改用“保存到手机文件”。") }
    }

    private fun requestSave(file: File) {
        try {
            pendingSavePath = file.absolutePath
            saveFile.launch(file.name)
        } catch (error: Exception) {
            pendingSavePath = null
            viewModel.reportShareProblem("无法打开文件选择器：${error.message}。数据包仍保留，可以改用分享。")
        }
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.onForeground(true)
    }

    override fun onStop() {
        pendingComment = null
        viewModel.onForeground(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingSavePath", pendingSavePath)
        super.onSaveInstanceState(outState)
    }
}
