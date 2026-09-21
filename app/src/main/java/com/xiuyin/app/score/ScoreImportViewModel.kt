package com.xiuyin.app.score

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.data.SampleLibrary
import com.xiuyin.app.omr.OmrImportService
import com.xiuyin.app.practice.PracticeReportStore
import android.provider.OpenableColumns
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
    val selectedOriginal: DigitalScore? = null,
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
    private val omr = OmrImportService(application)
    private val midi = com.xiuyin.app.midi.MidiImportService(application)
    private val reports = PracticeReportStore(File(application.filesDir, "practice-reports"))
    private val revisions = ScoreRevisionStore(File(application.filesDir, "score-revisions"))
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
            it.copy(selectedScore = revisions.load(metadata.scoreId, score).current, selectedOriginal = score, selectedMetadata = metadata,
                namingMetadata = metadata.takeIf { isNew && it.originalTitle == null && !it.customNamed },
                query = "", selectedTag = null)
        }
        publishLibrary()
        log("INFO", "MusicXML 已本地导入：${metadata.displayTitle.take(160)}",
            "scoreId: ${metadata.scoreId}\nSource: MusicXML\nSHA256: ${score.id}\nBytes: ${input.bytes.size}\n" +
                "Validation issues: ${score.issues.size}\nStatus: ${score.status}\nOMR: 不适用，结构化直接解析\nConfidence/sourceBox: 不适用")
    }

    fun openBuiltIn(id: String) = operation("打开原创练习谱") {
        val piece = requireNotNull(SampleLibrary.find(id)) { "内置曲目不存在" }
        val bytes = BuiltinScoreDocument.encode(piece)
        val document = parser.parseDocument(bytes)
        store.save(bytes)
        val metadata = ScoreMetadataDatabase(getApplication()).use { it.ensureImported(document.score.id, ScoreImportMetadata.from(document, "$id.musicxml"), restoreDeleted = true) }
        refreshSources()
        mutableState.update { it.copy(selectedOriginal = document.score, selectedScore = revisions.load(metadata.scoreId, document.score).current,
            selectedMetadata = metadata, query = "", selectedTag = null) }
        publishLibrary()
    }

    fun importImages(uris: List<Uri>) = operation("本地图片 / PDF 识谱") {
        require(uris.isNotEmpty()) { "未选择谱图" }
        val result = kotlinx.coroutines.withTimeout(15 * 60 * 1000L) { kotlinx.coroutines.runInterruptible(Dispatchers.IO) { omr.importDocuments(uris, ::stage) } }
        val sourceName = runCatching { getApplication<Application>().contentResolver.query(uris.first(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.take(256) else null
        } }.getOrNull()
        val score = result.score
        val sourceInfo = ScoreImportMetadata.from(ParsedScoreDocument(score, null, null, null, result.sourceImages.size), sourceName).copy(sourceType = score.source.format, recognitionComplete = true, hasReviewIssues = true)
        val metadata: ScoreMetadata
        val fresh: Boolean
        ScoreMetadataDatabase(getApplication()).use { db ->
            val old = db.findBySourceId(score.id, includeDeleted = true)
            fresh = old == null || old.deletedAt != null
            metadata = db.ensureImported(score.id, sourceInfo, restoreDeleted = true)
        }
        refreshSources()
        mutableState.update { it.copy(selectedOriginal = score, selectedScore = revisions.load(metadata.scoreId, score).current, selectedMetadata = metadata,
            namingMetadata = metadata.takeIf { fresh && !it.customNamed && it.originalTitle == null }, query = "", selectedTag = null) }
        publishLibrary()
        log("INFO", "本地模型识谱结束，需人工校对", "scoreId=${metadata.scoreId}; sourceSHA256=${score.id}; pages=${result.sourceImages.size}; issues=${score.issues.size}; reused=${result.reusedExisting}", "OMR")
    }

    fun importMidi(uri: Uri) = operation("导入 MIDI") {
        stage("正在解析本地 MIDI 音符和时间…")
        val result = kotlinx.coroutines.withTimeout(60_000) { kotlinx.coroutines.runInterruptible(Dispatchers.IO) { midi.importDocument(uri) } }
        val name = runCatching { getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.take(256) else null
        } }.getOrNull()
        val score = result.score
        val info = ScoreImportMetadata.from(ParsedScoreDocument(score, score.title.takeIf(String::isNotBlank), null, null, null), name).copy(sourceType = "MIDI")
        var fresh = false
        val metadata = ScoreMetadataDatabase(getApplication()).use { db ->
            val old = db.findBySourceId(score.id, includeDeleted = true); fresh = old == null || old.deletedAt != null
            db.ensureImported(score.id, info, restoreDeleted = true)
        }
        refreshSources()
        mutableState.update { it.copy(selectedOriginal = score, selectedScore = revisions.load(metadata.scoreId, score).current, selectedMetadata = metadata,
            namingMetadata = metadata.takeIf { fresh && !it.customNamed && it.originalTitle == null }, query = "", selectedTag = null) }
        publishLibrary()
        log("INFO", "本地 MIDI 已导入", "scoreId=${metadata.scoreId}; SHA256=${score.id}; issues=${score.issues.size}; OMR=not_applicable", "MIDI")
    }
    private fun loadOriginal(metadata: ScoreMetadata): DigitalScore = when (metadata.sourceType) {
        "MusicXML" -> parser.parse(store.load(metadata.sourceId).bytes)
        "MIDI" -> midi.load(metadata.sourceId).score
        else -> omr.load(metadata.sourceId).score
    }
    private fun deleteSource(metadata: ScoreMetadata) {
        when (metadata.sourceType) {
            "MusicXML" -> store.delete(metadata.sourceId)
            "MIDI" -> midi.delete(metadata.sourceId)
            else -> omr.delete(metadata.sourceId)
        }
    }

    fun openScore(id: String) = operation("打开数字乐谱") {
        stage("正在读取并校验本地乐谱…")
        val metadata = ScoreMetadataDatabase(getApplication()).use { requireNotNull(it.get(id)) { "乐谱命名记录不存在，请重新导入" } }
        check(metadata.deletedAt == null) { "该乐谱已删除，请主动重新导入" }
        val score = loadOriginal(metadata)
        mutableState.update { it.copy(selectedScore = revisions.load(metadata.scoreId, score).current, selectedOriginal = score, selectedMetadata = metadata) }
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
            if (deletePracticeRecords) reports.deleteForScore(id)
            db.markDeleted(id, deletePracticeRecords)
            // A tombstone is durable before file removal: an interrupted delete cannot re-import itself.
            deleteSource(metadata)
            revisions.delete(id)
            db.confirmSourceDeleted(id)
        }
        mutableState.update { state ->
            if (state.selectedMetadata?.scoreId == id) state.copy(selectedScore = null, selectedOriginal = null, selectedMetadata = null, namingMetadata = null) else state
        }
        refreshSources()
        log("INFO", "乐谱文件已删除，命名记录退出可用曲库", "scoreId: $id\n删除练习记录选项: $deletePracticeRecords\n原谱和该谱的修订历史已删除；旧版内置曲目配置未修改。", "ScoreMetadata")
    }

    fun skipNaming() { mutableState.update { it.copy(namingMetadata = null) } }
    fun updateQuery(query: String) { mutableState.update { it.copy(query = query.take(256)) }; publishLibrary() }
    fun updateSort(sort: ScoreSort) { mutableState.update { it.copy(sort = sort) }; publishLibrary() }
    fun updateTag(tag: String?) { mutableState.update { it.copy(selectedTag = tag) }; publishLibrary() }
    fun externalInputFailed(message: String) { mutableState.update { it.copy(error = message) } }
    fun selectionCancelled() { if (!mutableState.value.importing) mutableState.update { it.copy(progress = null) } }

    private fun refreshSources() {
        val problems = mutableListOf<String>()
        val entries = mutableListOf<ScoreSummary>()
        ScoreMetadataDatabase(getApplication()).use { db ->
            db.pendingSourceDeletions().forEach { metadata ->
                try { deleteSource(metadata); revisions.delete(metadata.scoreId); db.confirmSourceDeleted(metadata.scoreId) }
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
                        val current = revisions.load(metadata.scoreId, document.score).current
                        entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, current.parts.size,
                            current.parts.sumOf { it.measures.size }, current.issues.size, metadata = metadata)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        entries += ScoreSummary(prior?.scoreId ?: sourceId, prior?.displayTitle ?: "无法读取的乐谱", 0, 0, 0,
                            error.message ?: "本地文件读取失败", prior)
                    }
                }
            }
            val omrInventory = omr.inspect()
            problems += omrInventory.issues
            val omrIds = omrInventory.ids
            omrIds.forEach { sourceId ->
                val prior = db.findBySourceId(sourceId, includeDeleted = true)
                if (prior?.deletedAt == null) {
                    try {
                        val result = omr.load(sourceId)
                        val metadata = prior ?: db.ensureImported(sourceId, ScoreImportMetadata.from(ParsedScoreDocument(result.score, null, null, null, result.sourceImages.size)))
                        val current = revisions.load(metadata.scoreId, result.score).current
                        entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, current.parts.size, current.parts.sumOf { it.measures.size }, current.issues.size, metadata = metadata)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        entries += ScoreSummary(prior?.scoreId ?: sourceId, prior?.displayTitle ?: "无法读取的识谱结果", 0, 0, 0, error.message ?: "本地识谱读取失败", prior)
                    }
                }
            }
            val midiInventory = midi.inspect()
            problems += midiInventory.issues
            midiInventory.ids.forEach { sourceId ->
                val prior = db.findBySourceId(sourceId, includeDeleted = true)
                if (prior?.deletedAt == null) {
                    try {
                        val result = midi.load(sourceId)
                        val metadata = prior ?: db.ensureImported(sourceId, ScoreImportMetadata.from(ParsedScoreDocument(result.score, result.score.title.takeIf(String::isNotBlank), null, null, null)).copy(sourceType = "MIDI"))
                        val current = revisions.load(metadata.scoreId, result.score).current
                        entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, current.parts.size, current.parts.sumOf { it.measures.size }, current.issues.size, metadata = metadata)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        entries += ScoreSummary(prior?.scoreId ?: sourceId, prior?.displayTitle ?: "无法读取的 MIDI", 0, 0, 0, error.message ?: "MIDI 读取失败", prior)
                    }
                }
            }
            db.list().filter { it.deletedAt == null && it.sourceId !in inventory.ids && it.sourceId !in omrIds && it.sourceId !in midiInventory.ids }.forEach { metadata ->
                entries += ScoreSummary(metadata.scoreId, metadata.displayTitle, 0, 0, 0, "原谱文件缺失；命名信息仍保留", metadata)
            }
            allSummaries = entries
            mutableState.update { state ->
                val selected = state.selectedMetadata?.scoreId?.let(db::get)?.takeIf { it.deletedAt == null }
                state.copy(storageIssues = problems, selectedMetadata = selected,
                    selectedScore = if (selected == null) null else state.selectedOriginal?.let { revisions.load(selected.scoreId, it).current },
                    selectedOriginal = if (selected == null) null else state.selectedOriginal,
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
                    catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        val message = "$label 超时：已停止处理，原谱和用户修订保留，请缩小输入后重试"
                        mutableState.update { it.copy(error = message) }
                        withContext(Dispatchers.IO) { log("WARNING", message, timeout.stackTraceToString().take(16000)) }
                    }
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