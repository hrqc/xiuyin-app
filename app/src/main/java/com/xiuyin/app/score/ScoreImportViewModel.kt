package com.xiuyin.app.score

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.data.AppDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScoreSummary(
    val id: String,
    val title: String,
    val partCount: Int,
    val measureCount: Int,
    val issueCount: Int,
    val error: String? = null,
    val metadata: ScoreMetadata? = null,
)

data class ScoreImportState(
    val importing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val savedScores: List<ScoreSummary> = emptyList(),
    val selectedScore: DigitalScore? = null,
    val selectedMetadata: ScoreMetadata? = null,
    val namingMetadata: ScoreMetadata? = null,
    val storageIssues: List<String> = emptyList(),
    val query: String = "",
    val sort: ScoreSort = ScoreSort.RECENT_IMPORT,
    val selectedTag: String? = null,
    val availableTags: List<String> = emptyList(),
    val totalScoreCount: Int = 0,
    val unreadableScoreCount: Int = 0,
)

/** Imported sources and naming are independent of the original schema-1 practice database. */
class ScoreImportViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScoreSourceStore(File(application.filesDir, "digital-scores"))
    private val parser = MusicXmlParser()
    private val operationMutex = Mutex()
    private var pendingOperations = 0
    private val mutableState = MutableStateFlow(ScoreImportState())
    @Volatile private var allSummaries = emptyList<ScoreSummary>()
    val state = mutableState.asStateFlow()

    init { reload() }

    fun reload() = operation("读取本地乐谱") { refreshSources() }

    fun importFile(uri: Uri) = operation("导入乐谱") {
        stage("正在读取文件…")
        val input = BoundedScoreReader.readDocument(getApplication(), uri)
        currentCoroutineContext().ensureActive()
        stage("正在解析并校验乐谱…")
        val document = parser.parseDocument(input.bytes)
        val score = document.score
        val sourceInfo = ScoreImportMetadata.from(document, input.sourceFileName).normalized()
        currentCoroutineContext().ensureActive()
        stage("正在保存到本机…")
        val saved = store.save(input.bytes)
        check(saved.id == score.id) { "保存的来源与解析结果不一致" }
        val metadata: ScoreMetadata
        val isNew: Boolean
        ScoreMetadataDatabase(getApplication()).use { db ->
            val existing = db.findBySourceId(score.id, includeDeleted = true)
            isNew = existing == null || existing.deletedAt != null
            metadata = db.ensureImported(score.id, sourceInfo, restoreDeleted = true)
        }
        refreshSources()
        mutableState.update {
            it.copy(selectedScore = score, selectedMetadata = metadata,
                namingMetadata = metadata.takeIf { isNew && it.originalTitle == null && !it.customNamed },
                query = "", selectedTag = null)
        }
        publishLibrary()
        log("INFO", "MusicXML 已本地导入：${metadata.displayTitle.take(160)}",
            "scoreId: ${metadata.scoreId}\nSource: MusicXML\nSHA256: ${score.id}\nBytes: ${input.bytes.size}\n" +
                "Validation issues: ${score.issues.size}\nStatus: ${score.status}\nOMR: 不适用，结构化直接解析\nConfidence/sourceBox: 不适用")
    }

    fun openScore(id: String) = operation("打开数字乐谱") {
        stage("正在读取并校验本地乐谱…")
        val metadata = ScoreMetadataDatabase(getApplication()).use { requireNotNull(it.get(id)) { "乐谱命名记录不存在，请重新导入" } }
        check(metadata.deletedAt == null) { "该乐谱已删除，请主动重新导入" }
        val score = parser.parse(store.load(metadata.sourceId).bytes)
        mutableState.update { it.copy(selectedScore = score, selectedMetadata = metadata) }
        log("INFO", "打开本地数字乐谱：${metadata.displayTitle.take(160)}", "scoreId: $id\nSHA256: ${metadata.sourceId}\nValidation issues: ${score.issues.size}")
        // Opening or editing a score is not a practice session. lastPracticedAt stays unchanged.
    }

    fun saveMetadata(id: String, title: String, aliases: List<String>, tags: List<String>) = operation("保存乐谱信息") {
        ScoreMetadataDatabase(getApplication()).use { it.update(id, title, aliases, tags) }
        refreshSources()
        mutableState.update { it.copy(namingMetadata = null) }
        log("INFO", "乐谱名称、别名与标签已保存", "scoreId: $id；原始音符与来源字节未修改", "ScoreMetadata")
    }

    fun toggleFavorite(id: String) = operation("更新收藏") {
        ScoreMetadataDatabase(getApplication()).use { db ->
            val metadata = requireNotNull(db.get(id)) { "乐谱不存在" }
            db.setFavorite(id, !metadata.favorite)
        }
        refreshSources()
        log("INFO", "乐谱收藏状态已更新", "scoreId: $id", "ScoreMetadata")
    }

    fun deleteScore(id: String, deletePracticeRecords: Boolean) = operation("删除乐谱") {
        ScoreMetadataDatabase(getApplication()).use { db ->
            val metadata = requireNotNull(db.get(id, includeDeleted = true)) { "乐谱不存在" }
            db.markDeleted(id, deletePracticeRecords)
            // A tombstone is durable before file removal: an interrupted delete cannot re-import itself.
            store.delete(metadata.sourceId)
            db.confirmSourceDeleted(id)
        }
        mutableState.update { state ->
            if (state.selectedMetadata?.scoreId == id) state.copy(selectedScore = null, selectedMetadata = null, namingMetadata = null) else state
        }
        refreshSources()
        log("INFO", "乐谱文件已删除，命名记录退出可用曲库", "scoreId: $id\n删除练习记录选项: $deletePracticeRecords\n本版没有该谱的修谱结果或真实练习记录；旧版内置曲目配置未修改。", "ScoreMetadata")
    }

    fun skipNaming() { mutableState.update { it.copy(namingMetadata = null) } }
    fun updateQuery(query: String) { mutableState.update { it.copy(query = query.take(256)) }; publishLibrary() }
    fun updateSort(sort: ScoreSort) { mutableState.update { it.copy(sort = sort) }; publishLibrary() }
    fun updateTag(tag: String?) { mutableState.update { it.copy(selectedTag = tag) }; publishLibrary() }
    fun selectionCancelled() { if (!mutableState.value.importing) mutableState.update { it.copy(progress = null) } }

    private fun refreshSources() {
        val problems = mutableListOf<String>()
        val entries = mutableListOf<ScoreSummary>()
        ScoreMetadataDatabase(getApplication()).use { db ->
            db.pendingSourceDeletions().forEach { metadata ->
                try { store.delete(metadata.sourceId); db.confirmSourceDeleted(metadata.scoreId) }
                catch (error: Exception) { problems += "删除尚未完成：${metadata.displayTitle}；${error.message}" }
            }
            val inventory = store.inspect()
            problems += inventory.issues
            inventory.ids.forEach { sourceId ->
                val prior = db.findBySourceId(sourceId, includeDeleted = true)
                if (prior?.deletedAt == null) {
                    try {
                        val document = parser.parseDocument(store.load(sourceId).bytes)
                        val metadata = db.ensureImported(sourceId, ScoreImportMetadata.from(document))
                        entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, document.score.parts.size,
                            document.score.parts.sumOf { it.measures.size }, document.score.issues.size, metadata = metadata)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        entries += ScoreSummary(prior?.scoreId ?: sourceId, prior?.displayTitle ?: "无法读取的乐谱", 0, 0, 0,
                            error.message ?: "本地文件读取失败", prior)
                    }
                }
            }
            db.list().filter { it.deletedAt == null && it.sourceId !in inventory.ids }.forEach { metadata ->
                entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, 0, 0, 0, "原谱文件缺失；命名信息仍保留", metadata)
            }
            allSummaries = entries
            mutableState.update { state ->
                val selected = state.selectedMetadata?.scoreId?.let(db::get)?.takeIf { it.deletedAt == null }
                state.copy(storageIssues = problems, selectedMetadata = selected,
                    selectedScore = if (selected == null) null else state.selectedScore,
                    totalScoreCount = entries.size,
                    unreadableScoreCount = entries.count { it.error != null },
                    availableTags = entries.flatMap { it.metadata?.tags.orEmpty() }.distinct().sorted())
            }
        }
        publishLibrary()
    }

    private fun publishLibrary() {
        val entries = allSummaries
        mutableState.update { state ->
            val matches = queryScoreMetadata(entries.mapNotNull { it.metadata }, state.query, state.selectedTag, state.sort)
            val byId = entries.associateBy { it.id }
            val missing = entries.filter { it.metadata == null && state.query.isBlank() && state.selectedTag == null }
            state.copy(savedScores = matches.mapNotNull { byId[it.scoreId] } + missing)
        }
    }

    private fun stage(message: String) { mutableState.update { it.copy(progress = message) } }

    private fun operation(label: String, action: suspend () -> Unit) {
        // Queue system picker results arriving during init; never silently discard a selected URI.
        pendingOperations++
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                operationMutex.withLock {
                    mutableState.update { it.copy(progress = "$label…", error = null) }
                    try { withContext(Dispatchers.IO) { action() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val message = "$label 失败：${error.message ?: error.javaClass.simpleName}"
                        mutableState.update { it.copy(error = message) }
                        withContext(Dispatchers.IO) { log("WARNING", message.take(4000), error.stackTraceToString().take(16000)) }
                    }
                }
            } finally {
                pendingOperations--
                if (pendingOperations == 0) mutableState.update { it.copy(importing = false, progress = null) }
            }
        }
    }

    private fun log(level: String, message: String, detail: String, module: String = "ScoreImport") {
        try { AppDatabase(getApplication()).use { it.appendLog(level, module, message, detail) } }
        catch (error: Exception) {
            Log.e("Xiuyin", "数字乐谱日志保存失败", error)
            mutableState.update { it.copy(error = "乐谱操作已结束，但诊断日志保存失败：${error.message}") }
        }
    }
}