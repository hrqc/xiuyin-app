package com.xiuyin.app.score

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.playback.ScorePlaybackEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        operation("打开修谱工作台") {
            val revision = revisions.load(metadata.scoreId, original)
            val images = if (metadata.sourceType in setOf("MusicXML", "MIDI")) emptyList() else com.xiuyin.app.omr.OmrImportService(getApplication()).load(metadata.sourceId).sourceImages
            mutableState.value = ScoreWorkbenchState(true, metadata.scoreId, metadata.displayTitle, original, revision,
                selectedEventId = revision.current.parts.firstOrNull()?.measures?.firstOrNull()?.events?.firstOrNull()?.id, sourceImages = images)
            accept(revision, "已读取本地修订；原文件保持不变")
        }
    }

    fun exportMidi(uri: android.net.Uri, bpm: Int) = operation("导出当前修订 MIDI") {
        val score = requireNotNull(mutableState.value.revision?.current) { "没有当前乐谱" }
        val result = com.xiuyin.app.midi.MidiImportService(getApplication()).exportCurrent(uri, score, bpm)
        mutableState.update { it.copy(message = "当前修订已导出 MIDI（${result.bytes.size} 字节）；原图与修订保留在 App 中") }
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

    fun restoreSelected() = operation("恢复当前音符原始结果") {
        player.stop("原始音符已恢复，试听停止")
        val state = mutableState.value
        val prior = requireNotNull(state.revision)
        val selected = requireNotNull(state.selectedEventId) { "请先选择音符" }
        val original = requireNotNull(state.original).parts.flatMap { it.measures }.flatMap { it.events }.firstOrNull { it.id == selected }
            ?: throw IllegalArgumentException("这是用户新增音符，没有原始识别结果")
        val score = ScoreEditing.apply(prior.current, ScorePatch.ReplaceEvent(selected, original)).score
        accept(revisions.save(requireNotNull(state.scoreId), prior.generation, score, prior.verifiedEventIds - selected, prior.fingerings - selected), "已恢复此音符原始结果；可撤销")
    }

    fun restoreMeasure(measureId: String) = operation("恢复当前小节原始结果") {
        player.stop("原始小节已恢复，试听停止")
        val state = mutableState.value
        val prior = requireNotNull(state.revision)
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

    private fun accept(revision: ScoreRevisionSnapshot, message: String) {
        var problem: String? = null
        val timeline = try { ScoreTimelineCompiler.compile(revision.current) }
            catch (error: IllegalArgumentException) { problem = error.message; null }
        mutableState.update { old ->
            val ids = revision.current.parts.flatMap { it.measures }.flatMap { it.events }.map { it.id }
            old.copy(revision = revision, timeline = timeline, selectedEventId = old.selectedEventId?.takeIf { it in ids },
                timelineProblem = problem, message = message + if (revision.historyTruncated) "；仅保留最近可用撤销历史" else "")
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
