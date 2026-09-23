package com.xiuyin.app.field

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI operations only. Session files and evidence are owned by FieldDiagnostics. */
data class FieldOperationState(
    val busy: Boolean = false,
    val exportedFile: File? = null,
    val exportedSessionId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class FieldTestViewModel(application: Application) : AndroidViewModel(application) {
    private val diagnostics = FieldDiagnostics.get(application)
    val state = diagnostics.state
    private val mutableOperation = MutableStateFlow(FieldOperationState())
    val operation = mutableOperation.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!mutableOperation.value.busy) operate { withContext(Dispatchers.IO) { diagnostics.refresh() } }
    }
    fun setClipConsent(value: Boolean) = diagnostics.setClipConsent(value)
    fun reportError(message: String) { mutableOperation.value = mutableOperation.value.copy(error = message, message = null) }
    fun reportMessage(message: String) { mutableOperation.value = mutableOperation.value.copy(message = message, error = null) }

    fun start(label: String, metadata: Map<String, String>, scoreSnapshot: FieldScoreSnapshot.Request? = null, onStarted: () -> Boolean) = operate {
        withContext(Dispatchers.IO) {
            val snapshot = scoreSnapshot?.let { FieldScoreSnapshot.capture(it) }
            diagnostics.refresh()
            check(state.value.storageWritable) { "调试目录不可写，请检查存储空间后重试" }
            check(state.value.freeSpaceBytes >= 10L * 1024 * 1024) { "可用存储不足 10 MiB，请先保存并清理历史会话" }
            diagnostics.startSession(label.ifBlank { "钢琴现场测试" }, metadata, snapshot)
        }
        // Permission and READY may have changed while durable session creation was in flight.
        if (!onStarted()) {
            withContext(Dispatchers.IO) { diagnostics.endSession("START_PRECONDITION_CHANGED") }
            error("开始条件已经变化，本次未启动收音；请检查状态后再次手动开始")
        }
    }

    fun finish(reason: String = "USER_ENDED") = operate {
        withContext(Dispatchers.IO) { diagnostics.endSession(reason) }
    }

    fun feedback(label: FieldFeedbackLabel, context: Map<String, String>, audioTimeSeconds: Double?) {
        if (state.value.activeSession == null) { reportError("请先开启实地测试会话，再记录现场反馈"); return }
        if (state.value.error != null) { reportError("当前会话写入故障，无法保存新标签；请先结束并导出已有记录"); return }
        diagnostics.recordFeedback(label, context, audioTimeSeconds)
    }

    fun export(sessionId: String) = operate {
        val file = withContext(Dispatchers.IO) { diagnostics.exportSession(sessionId) }
        check(file.isFile && file.length() > 0) { "导出文件未生成或为空，请重试" }
        mutableOperation.value = mutableOperation.value.copy(exportedFile = file, exportedSessionId = sessionId,
            message = "ZIP 已在本机生成，请选择保存到文件或分享。尚未标记为已保存。")
    }

    fun saveTo(uri: Uri, sessionId: String, path: String) = operate {
        var completeSnapshot = false
        withContext(Dispatchers.IO) {
            val file = File(path).canonicalFile
            val exportDir = File(getApplication<Application>().filesDir, "field-exports").canonicalFile
            check(file.parentFile == exportDir && file.isFile) { "导出文件已失效，请重新生成 ZIP" }
            completeSnapshot = java.util.zip.ZipFile(file).use { zip ->
                val manifest = zip.getEntry("manifest.json") ?: error("ZIP 缺少会话信息，请重新导出")
                val json = org.json.JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
                json.getString("status") != FieldSessionStatus.ACTIVE.name
            }
            val resolver = getApplication<Application>().contentResolver
            val output = resolver.openOutputStream(uri, "wt") ?: error("所选位置无法写入，请换一个保存位置")
            output.use { target -> file.inputStream().use { it.copyTo(target) }; target.flush() }
            fun digest(input: java.io.InputStream): ByteArray = input.use { stream ->
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; if (count > 0) sha.update(buffer, 0, count) }
                sha.digest()
            }
            val expected = digest(file.inputStream())
            val readBack = resolver.openInputStream(uri) ?: error("目标已写入，但无法读回验证；未标记为已导出，请换一个保存位置")
            check(expected.contentEquals(digest(readBack))) { "目标 ZIP 读回校验不一致；未标记为已导出，请重新保存" }
            if (completeSnapshot && state.value.activeSession?.id != sessionId) diagnostics.markExported(sessionId)
        }
        reportMessage(if (!completeSnapshot || state.value.activeSession?.id == sessionId)
            "当前快照已保存并读回校验一致；这份 ZIP 来自未结束会话，请结束后重新导出完整测试包"
        else "测试包已保存并通过 SHA256 读回校验，会话已标记为已导出")
    }

    fun clearConfirmed(ids: Set<String>) = operate {
        withContext(Dispatchers.IO) { diagnostics.clearSessions(ids) }
        if (mutableOperation.value.exportedSessionId in ids) {
            mutableOperation.value = mutableOperation.value.copy(exportedFile = null, exportedSessionId = null)
        }
    }

    private fun operate(block: suspend () -> Unit) {
        if (mutableOperation.value.busy) { reportError("另一项测试包操作尚未完成，本次操作未执行；请稍后重试"); return }
        mutableOperation.value = mutableOperation.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { reportError(error.message ?: "实地测试操作失败，请查看诊断日志后重试") }
            finally { mutableOperation.value = mutableOperation.value.copy(busy = false) }
        }
    }
}