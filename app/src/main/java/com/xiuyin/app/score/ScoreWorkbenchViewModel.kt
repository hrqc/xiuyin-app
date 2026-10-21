package com.xiuyin.app.score

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.omr.OmrReanalysisService
import com.xiuyin.app.omr.OmrPreviewMethod
import com.xiuyin.app.omr.OmrStructurePreview
import com.xiuyin.app.omr.OmrStructureUpgrade
import com.xiuyin.app.playback.ScorePlaybackEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScoreWorkbenchState(
    val busy: Boolean = false,
    val scoreId: String? = null,
    val title: String = "乐谱工作台",
    val original: DigitalScore? = null,
    val revision: ScoreRevisionSnapshot? = null,
    val timeline: ScoreTimeline? = null,
    val selectedEventId: String? = null,
    val error: String? = null,
    val message: String = "打开一份本地乐谱后开始校对。",
    val timelineProblem: String? = null,
    val sourceImages: List<File> = emptyList(),
    val structurePreview: OmrStructurePreview? = null,
    val repairIssue: ScoreIssue? = null,
    val barlinePreview: ScoreBarlinePreview? = null,
)

class ScoreWorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    private val revisions = ScoreRevisionStore(File(application.filesDir, "score-revisions"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ScoreWorkbenchState())
    val state = mutableState.asStateFlow()
    private val player = ScorePlaybackEngine(application)
    val playback = player.state

    fun open(metadata: ScoreMetadata, original: DigitalScore) {
        player.stop("已切换乐谱，选择速度后可试听")
        operation("打开修谱工作台") { loadWorkbench(metadata, original) }
    }

    /** Repair navigation binds to the practice score ID, never the previously open workbench. */
    fun openForRepair(scoreId: String, issue: ScoreIssue?): Boolean {
        if (mutableState.value.busy) return false
        player.stop("返回当前练习乐谱修谱，试听已停止")
        mutableState.value = ScoreWorkbenchState(scoreId = scoreId, title = "正在打开当前练习乐谱",
            message = "正在读取该乐谱的命名、原件和修订…")
        operation("读取待修正的练习乐谱") {
            val application = getApplication<Application>()
            val metadata = ScoreMetadataDatabase(application).use { requireNotNull(it.get(scoreId)) { "当前练习乐谱的命名记录不存在或已删除" } }
            require(metadata.deletedAt == null) { "当前练习乐谱已删除" }
            val original = when (metadata.sourceType) {
                "MusicXML" -> MusicXmlParser().parse(ScoreSourceStore(File(application.filesDir, "digital-scores")).load(metadata.sourceId).bytes)
                "MIDI" -> com.xiuyin.app.midi.MidiImportService(application).load(metadata.sourceId).score
                else -> com.xiuyin.app.omr.OmrImportService(application).load(metadata.sourceId).score
            }
            require(original.id == metadata.sourceId && original.source.sha256 == metadata.sourceId) { "当前练习乐谱的原件来源不匹配，未打开其他乐谱" }
            loadWorkbench(metadata, original, issue)
        }
        return true
    }

    private fun loadWorkbench(metadata: ScoreMetadata, original: DigitalScore, issue: ScoreIssue? = null) {
        val revision = revisions.load(metadata.scoreId, original)
        val images = if (metadata.sourceType in setOf("MusicXML", "MIDI")) emptyList() else
            com.xiuyin.app.omr.OmrImportService(getApplication()).load(metadata.sourceId).sourceImages
        val measures = revision.current.parts.flatMap { it.measures }
        val events = measures.flatMap { it.events }
        val target = issue?.eventId?.takeIf { id -> events.any { it.id == id } }
            ?: measures.firstOrNull { it.id == issue?.measureId }?.events?.firstOrNull()?.id
        mutableState.value = ScoreWorkbenchState(true, metadata.scoreId, metadata.displayTitle, original, revision,
            selectedEventId = target ?: events.firstOrNull()?.id, sourceImages = images)
        val locationMessage = if (issue == null) "" else if (target != null) "；已定位当前练习的待修正位置" else "；原疑点位置已变化或没有音符，请查看当前修订的结构提示"
        accept(revision, "已读取本地修订；原文件保持不变$locationMessage", issue)
    }

    fun exportMidi(uri: android.net.Uri, bpm: Int) = operation("导出当前修订 MIDI") {
        val score = requireNotNull(mutableState.value.revision?.current) { "没有当前乐谱" }
        val result = com.xiuyin.app.midi.MidiImportService(getApplication()).exportCurrent(uri, score, bpm)
        mutableState.update { it.copy(message = "当前修订已导出 MIDI（${result.bytes.size} 字节）；原图与修订保留在 App 中") }
    }
    fun previewOmrStructure() = operation("预览识谱结构整理") {
        mutableState.update { it.copy(structurePreview = null, barlinePreview = null) }
        player.stop("正在整理已有识谱记录，试听已停止")
        val current = mutableState.value
        val revision = requireNotNull(current.revision) { "没有当前乐谱" }
        val preview = OmrStructureUpgrade(getApplication()).preview(requireNotNull(current.original), revision.current, revision.generation)
        mutableState.update { it.copy(structurePreview = preview,
            message = "已按当前规则重新解释本机保存的模型记录；未运行新识别、未保存修订，请对照预览确认") }
    }

    fun recognizeOmrAgain() = operation("原图真实重识别") {
        mutableState.update { it.copy(structurePreview = null, barlinePreview = null) }
        player.stop("正在重新识别原图，试听已停止")
        val current = mutableState.value
        val revision = requireNotNull(current.revision) { "没有当前乐谱" }
        val preview = OmrReanalysisService(getApplication()).recognize(requireNotNull(current.original), revision.current,
            revision.generation, progress = { message -> mutableState.update { it.copy(message = message) } },
            checkCancellation = { viewModelScope.coroutineContext.ensureActive() })
        mutableState.update { it.copy(structurePreview = preview,
            message = "原图真实重识别已结束，证据编号 ${preview.evidenceId}；请对照预览确认，尚未应用修订") }
    }

    fun dismissStructurePreview() {
        if (!mutableState.value.busy) mutableState.update { it.copy(structurePreview = null) }
    }

    fun previewBarlineMerge(request: ScoreBarlineMergeRequest) = operation("预览手动小节线修正") {
        mutableState.update { it.copy(barlinePreview = null, structurePreview = null) }
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        request.requireCurrent(current.scoreId, prior.generation)
        player.stop("正在预览小节线修正，试听已停止")
        val preview = ScoreBarlineEditing.previewMerge(requireNotNull(current.original), prior.current, request)
        mutableState.update { it.copy(barlinePreview = preview,
            message = "请对照原图确认所选小节线是否多识别；尚未保存，音符准确性仍需复核") }
    }

    fun dismissBarlinePreview() {
        if (!mutableState.value.busy) mutableState.update { it.copy(barlinePreview = null) }
    }

    fun applyBarlineMerge() = operation("保存手动小节线修正") {
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        val preview = requireNotNull(current.barlinePreview) { "请先对照原图预览小节线" }
        preview.requireCurrent(current.scoreId, prior.generation, prior.current)
        player.stop("小节线修订已更新，试听已停止")
        accept(revisions.save(preview.scoreId, prior.generation, preview.candidate,
            prior.verifiedEventIds - preview.changedEventIds, prior.fingerings),
            "已按用户确认合并小节并保存一条可撤销修订；结构错误 ${preview.before.errorCount} → ${preview.after.errorCount}。音符和时值仍待复核。结束旧练习后重新打开陪练以使用此修订")
    }

    fun applyOmrStructure() = operation("保存识谱结构整理") {
        player.stop("识谱结构已更新，试听已停止")
        val current = mutableState.value
        val prior = requireNotNull(current.revision)
        val preview = requireNotNull(current.structurePreview) { "请先预览整理结果" }
        val original = requireNotNull(current.original)
        OmrStructureUpgrade.requireUnedited(original, prior.current)
        require(preview.sourceId == original.source.sha256 && preview.revisionGeneration == prior.generation) { "预览已过期，请重新整理预览" }
        val (verified, fingerings) = preview.annotationsForApply(prior)
        accept(revisions.save(requireNotNull(current.scoreId), prior.generation, preview.candidate,
            verified, fingerings),
            "${if (preview.method == OmrPreviewMethod.REAL_IMAGE_RECOGNITION) "真实重识别" else "整理"}结果已另存为本机修订，可撤销；结构错误 ${preview.before.errorCount} → ${preview.after.errorCount}。请对照原图复核；接着设置实际拍号，并在需要时重新选择顺序一次演奏")
    }

    fun confirmLinearRepeat() = operation("保存顺序一次演奏选择") {
        player.stop("演奏顺序选择已更新，试听已停止")
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        val repeatCount = ScoreStructureTools.pendingRepeatChoices(prior.current).size
        val before = ScoreStructureTools.summarize(prior.current)
        val score = ScoreStructureTools.confirmLinearRepeat(prior.current)
        val after = ScoreStructureTools.summarize(score)
        accept(revisions.save(requireNotNull(current.scoreId), prior.generation, score,
            prior.verifiedEventIds, prior.fingerings),
            "用户选择当前导入乐谱顺序演奏一次、不执行反复，已记录 $repeatCount 处反复记号选择；结构错误 ${before.errorCount} → ${after.errorCount}。其他错误仍需修正，可撤销")
    }

    fun fillMissingScoreTime(scoreId: String, generation: Long, beats: Int, beatType: Int) = operation("补全缺失拍号") {
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        require(current.scoreId == scoreId && prior.generation == generation) {
            "乐谱或修订已变化，旧拍号窗口未应用；请在当前谱面重新选择"
        }
        val count = ScoreStructureTools.missingTimeMeasures(prior.current).size
        val score = ScoreStructureTools.fillMissingTime(prior.current, ScoreTime(beats, beatType))
        player.stop("拍号已补全，试听已停止")
        accept(revisions.save(scoreId, generation, score, prior.verifiedEventIds, prior.fingerings),
            "已按用户选择为 $count 个缺失或无效拍号的小节填入 $beats/$beatType；已有合法拍号、音符时值、指法与确认记录保留，可撤销。请继续校对其余谱面问题")
    }
    fun setWholeScoreTime(beats: Int, beatType: Int) = operation("保存全谱拍号") {
        player.stop("拍号已更新，试听已停止")
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        val before = ScoreStructureTools.summarize(prior.current)
        val score = ScoreStructureTools.setWholeScoreTime(prior.current, ScoreTime(beats, beatType))
        val after = ScoreStructureTools.summarize(score)
        accept(revisions.save(requireNotNull(current.scoreId), prior.generation, score,
            prior.verifiedEventIds, prior.fingerings),
            "用户设置全谱拍号为 $beats/$beatType，结构错误 ${before.errorCount} → ${after.errorCount}；音符时值不变，可撤销")
    }

    fun select(eventId: String) {
        val exists = mutableState.value.revision?.current?.parts?.any { p -> p.measures.any { m -> m.events.any { it.id == eventId } } } == true
        if (exists) mutableState.update { it.copy(selectedEventId = eventId) }
    }

    fun edit(patch: ScorePatch, fingering: Int?) = operation("保存音符修正") {
        player.stop("乐谱已修改，试听已停止")
        val prior = requireNotNull(mutableState.value.revision)
        val result = ScoreEditing.apply(prior.current, patch)
        val ids = result.score.parts.flatMap { it.measures }.flatMap { it.events }.map { it.id }.toSet()
        val verified = (prior.verifiedEventIds + result.changedEventIds).intersect(ids)
        val fingerings = prior.fingerings.filterKeys { it in ids }.toMutableMap()
        val eventId = when (patch) { is ScorePatch.ReplaceEvent -> patch.eventId; is ScorePatch.InsertEvent -> patch.event.id; else -> null }
        if (eventId != null) {
            if (fingering == null) fingerings.remove(eventId) else { require(fingering in 1..5); fingerings[eventId] = fingering }
        }
        accept(revisions.save(requireNotNull(mutableState.value.scoreId), prior.generation, result.score, verified, fingerings), "修谱已保存到本机")
        if (eventId != null) select(eventId)
    }

    fun transpose(semitones: Int, targetFifths: Int? = null) = operation("保存移调乐谱") {
        player.stop("移调后试听已停止")
        val prior = requireNotNull(mutableState.value.revision)
        val score = ScoreEditing.transpose(prior.current, semitones, targetFifths).score
        // A deterministic transpose is not a new human verification of every source note.
        accept(revisions.save(requireNotNull(mutableState.value.scoreId), prior.generation, score, prior.verifiedEventIds, prior.fingerings), "移调已同步到数字乐谱、两种谱式和试听")
    }

    fun shiftOctaveRange(request: ScoreOctaveRangeRequest) = operation("保存手动八度范围修正") {
        val current = mutableState.value
        val prior = requireNotNull(current.revision) { "没有当前乐谱" }
        request.requireCurrent(current.scoreId, prior.generation)
        val result = ScoreRangeEditing.shiftOctave(prior.current, request.startEventId, request.endEventId, request.octaves)
        player.stop("手动八度范围已更新，试听已停止")
        // A batch operation changes pitch, but does not certify that every selected note is correct.
        accept(revisions.save(request.scoreId, prior.generation, result.score,
            prior.verifiedEventIds - result.changedEventIds, prior.fingerings),
            "已手动${if (request.octaves > 0) "升" else "降"}一个八度，共 ${result.changedEventIds.size} 个音；时值、休止与原图保留，修改音符仍待复核，可一次撤销。结束旧练习后重新打开陪练以使用此修订")
    }

    fun restoreSelected() = operation("恢复当前音符原始结果") {
        player.stop("原始音符已恢复，试听停止")
        val state = mutableState.value
        val prior = requireNotNull(state.revision)
        val selected = requireNotNull(state.selectedEventId) { "请先选择音符" }
        ScoreStructureTools.requireOriginalRestoreCompatible(requireNotNull(state.original), prior.current)
        val original = requireNotNull(state.original).parts.flatMap { it.measures }.flatMap { it.events }.firstOrNull { it.id == selected }
            ?: throw IllegalArgumentException("这是用户新增音符，没有原始识别结果")
        val score = ScoreEditing.apply(prior.current, ScorePatch.ReplaceEvent(selected, original)).score
        accept(revisions.save(requireNotNull(state.scoreId), prior.generation, score, prior.verifiedEventIds - selected, prior.fingerings - selected), "已恢复此音符原始结果；可撤销")
    }

    fun restoreMeasure(measureId: String) = operation("恢复当前小节原始结果") {
        player.stop("原始小节已恢复，试听停止")
        val state = mutableState.value
        val prior = requireNotNull(state.revision)
        ScoreStructureTools.requireOriginalRestoreCompatible(requireNotNull(state.original), prior.current)
        val original = requireNotNull(state.original).parts.flatMap { it.measures }.firstOrNull { it.id == measureId }
            ?: throw IllegalArgumentException("没有此小节的原始结果")
        val affected = prior.current.parts.flatMap { it.measures }.filter { it.id == measureId }.flatMap { it.events }.map { it.id }.toSet() + original.events.map { it.id }
        val replaced = prior.current.copy(parts = prior.current.parts.map { part -> part.copy(measures = part.measures.map { if (it.id == measureId) original else it }) })
        val score = replaced.copy(issues = ScoreEditing.validate(replaced))
        accept(revisions.save(requireNotNull(state.scoreId), prior.generation, score, prior.verifiedEventIds - affected, prior.fingerings - affected), "已恢复此小节原始结果；其他小节保留；可撤销")
    }

    fun verifySelected() = operation("保存用户确认") {
        val prior = requireNotNull(mutableState.value.revision)
        val selected = requireNotNull(mutableState.value.selectedEventId) { "请先选择音符" }
        accept(revisions.save(requireNotNull(mutableState.value.scoreId), prior.generation, prior.current,
            prior.verifiedEventIds + selected, prior.fingerings), "已记录该音符为用户确认")
    }

    fun undo() = history(true)
    fun redo() = history(false)
    private fun history(undo: Boolean) = operation(if (undo) "撤销修谱" else "重做修谱") {
        player.stop("修订切换，试听已停止")
        val current = mutableState.value
        val revision = requireNotNull(current.revision)
        val next = if (undo) revisions.undo(requireNotNull(current.scoreId), requireNotNull(current.original), revision.generation)
            else revisions.redo(requireNotNull(current.scoreId), requireNotNull(current.original), revision.generation)
        accept(next, if (undo) "已撤销并保存" else "已重做并保存")
    }

    fun startPlayback(bpm: Int, volume: Float, startMeasure: Int, loop: Boolean, metronome: Boolean) {
        val current = mutableState.value
        if (current.busy) return
        val score = current.revision?.current ?: return
        try { player.play(score, bpm, volume, startMeasure, loop, metronome) }
        catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "试听无法开始") } }
    }
    fun stopPlayback(reason: String = "试听已停止") = player.stop(reason)

    private fun accept(revision: ScoreRevisionSnapshot, message: String, repairIssue: ScoreIssue? = null) {
        var problem: String? = null
        val timeline = try { ScoreTimelineCompiler.compile(revision.current) }
            catch (error: IllegalArgumentException) { problem = error.message; null }
        mutableState.update { old ->
            val ids = revision.current.parts.flatMap { it.measures }.flatMap { it.events }.map { it.id }
            old.copy(revision = revision, timeline = timeline, selectedEventId = old.selectedEventId?.takeIf { it in ids },
                timelineProblem = problem, structurePreview = null, barlinePreview = null, repairIssue = repairIssue, message = message + if (revision.historyTruncated) "；仅保留最近可用撤销历史" else "")
        }
    }

    private fun operation(label: String, action: () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                mutex.withLock {
                    withContext(Dispatchers.IO) {
                        action()
                        AppDatabase(getApplication()).use { it.appendLog("INFO", "ScoreWorkbench", label, "scoreId=${mutableState.value.scoreId}; revision=${mutableState.value.revision?.generation}") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(error = "$label 失败：${error.message ?: error.javaClass.simpleName}") }
                withContext(Dispatchers.IO) {
                    try { AppDatabase(getApplication()).use { it.appendLog("ERROR", "ScoreWorkbench", label, error.stackTraceToString().take(16000)) } }
                    catch (loggingError: Exception) { Log.e("Xiuyin", "修谱错误日志保存失败", loggingError) }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() { player.close(); super.onCleared() }
}
