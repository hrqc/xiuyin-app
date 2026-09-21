package com.xiuyin.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.audio.MicrophoneEngine
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.data.LogEntry
import com.xiuyin.app.data.PracticePreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppState(
    val loading: Boolean = true,
    val databaseReady: Boolean = false,
    val recentPieceId: String? = null,
    val preparation: PracticePreparation? = null,
    val logs: List<LogEntry> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val audioEngine = MicrophoneEngine(application) { level, message, detail ->
        operation("保存音频诊断") {
            val mode = mutableState.value.preparation?.mode?.name ?: "未设置"
            database.appendLog(level, "Audio", message,
                "$detail\nSaved Practice Mode: $mode\nCurrent Measure/Beat/Expected Notes: 未定位\nRaw Detected Notes: []\nRaw Confidence: 未实现\nFiltered Notes: []\nFinal Decision: UNCERTAIN\nDecision Confidence: 未实现\nLOCKED: 不适用")
            refresh()
        }
    }
    val audio = audioEngine.state

    fun startAudio() = audioEngine.start()
    fun stopAudio(reason: String = "用户已停止监听") = audioEngine.stop(reason)
    fun audioPermissionDenied() = audioEngine.permissionDenied()

    init { initialize() }

    fun initialize() = operation("启动数据库") {
        check(database.healthCheck()) { "SQLite 健康检查失败" }
        database.appendLog("INFO", "App", "修音 V${BuildConfig.VERSION_NAME} 已启动")
        database.appendLog("INFO", "Database", "本地数据库检查通过")
        refresh()
    }

    fun openPiece(id: String) = operation("打开乐谱") {
        database.markOpened(id)
        database.appendLog("INFO", "Library", "打开示例谱：$id")
        refresh()
    }

    fun savePreparation(value: PracticePreparation) {
        mutableState.update { it.copy(saving = true) }
        operation("保存练习设置") {
            database.savePreparation(value)
            database.appendLog("INFO", "Practice", "练习设置已保存：${value.mode.label} / ${value.repeatTarget} 次；保存操作不启动音频")
            refresh()
            mutableState.update { it.copy(saving = false) }
        }
    }

    fun visit(page: String) = operation("记录页面状态") {
        database.appendLog("INFO", "UI", "打开页面：$page")
        refresh()
    }

    fun refreshLogs() = operation("刷新日志") {
        check(database.healthCheck()) { "SQLite 健康检查失败" }
        refresh()
    }

    fun clearLogs() = operation("清空日志") {
        database.clearLogs()
        refresh()
    }


    private fun refresh() {
        val preparation = database.loadPreparation()
        val recent = database.recentPieceId()
        val logs = database.logs()
        mutableState.update {
            it.copy(loading = false, databaseReady = true, recentPieceId = recent,
                preparation = preparation, logs = logs, error = null)
        }
    }

    private fun operation(label: String, block: () -> Unit) {
        viewModelScope.launch {
            try {
                mutex.withLock { withContext(Dispatchers.IO) { block() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("Xiuyin", label, error)
                val message = "$label 失败：${error.message ?: error.javaClass.simpleName}"
                val entry = LogEntry(-System.currentTimeMillis(), System.currentTimeMillis(), "ERROR", "Database", message, error.stackTraceToString())
                mutableState.update {
                    it.copy(loading = false, saving = false, databaseReady = false, error = message, logs = listOf(entry) + it.logs.take(199))
                }
            }
        }
    }

    override fun onCleared() {
        audioEngine.close()
        database.close()
        super.onCleared()
    }
}
