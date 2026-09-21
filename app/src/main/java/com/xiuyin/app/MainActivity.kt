package com.xiuyin.app

import android.content.Context
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xiuyin.app.practice.*
import com.xiuyin.app.calibration.*
import com.xiuyin.app.pitch.PitchEvidenceConfig
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.score.ScoreWorkbenchViewModel
import com.xiuyin.app.ui.ScoreWorkbenchScreen
import com.xiuyin.app.score.ScoreImportViewModel
import com.xiuyin.app.ui.DigitalScoreScreen
import com.xiuyin.app.data.SampleLibrary
import com.xiuyin.app.monitor.DashboardScreen
import com.xiuyin.app.ui.HomeScreen
import com.xiuyin.app.ui.LibraryScreen
import com.xiuyin.app.ui.PracticeScreen
import com.xiuyin.app.ui.ScoreScreen
import com.xiuyin.app.ui.XiuyinTheme

class MainActivity : ComponentActivity() {
    private val appModel: AppViewModel by lazy { ViewModelProvider(this)[AppViewModel::class.java] }
    private val scoreModel: ScoreImportViewModel by lazy { ViewModelProvider(this)[ScoreImportViewModel::class.java] }
    private val companionModel: PracticeViewModel by lazy { ViewModelProvider(this)[PracticeViewModel::class.java] }
    private var companionPageVisible = false
    private var calibrationPageVisible = false
    private val calibrationModel: CalibrationViewModel by lazy { ViewModelProvider(this)[CalibrationViewModel::class.java] }
    private val workbenchModel: ScoreWorkbenchViewModel by lazy { ViewModelProvider(this)[ScoreWorkbenchViewModel::class.java] }
    private val scoreDocument = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri == null) scoreModel.selectionCancelled() else scoreModel.importFile(uri)
    }
    private val midiDocument = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri -> if (uri != null) scoreModel.importMidi(uri) else scoreModel.selectionCancelled() }
    private var exportBpm = 80
    private val midiExport = registerForActivityResult(object : ActivityResultContracts.CreateDocument("audio/midi") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri != null) workbenchModel.exportMidi(uri, exportBpm)
    }
    private val scoreImages = registerForActivityResult(object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uris -> if (uris.isNotEmpty()) scoreModel.importImages(uris) else scoreModel.selectionCancelled() }
    private var cameraUri: Uri? = null
    private val scoreCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) scoreModel.importImages(listOf(uri)) else scoreModel.selectionCancelled()
    }
    private fun photographScore() {
        try {
            val folder = File(cacheDir, "score-camera").also { check(it.exists() || it.mkdir()) }
            val file = File.createTempFile("score-", ".jpg", folder)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.score-files", file)
            scoreCamera.launch(cameraUri!!)
        } catch (error: Exception) { scoreModel.externalInputFailed("无法打开相机：${error.message}；可选择本地图片") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("midi_export_bpm", exportBpm)
        cameraUri?.let { outState.putString("score_camera_uri", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    private var audioPageVisible = false
    private var permissionPending = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (!granted) {
            appModel.audioPermissionDenied()
            if (companionPageVisible) companionModel.inputUnavailable("麦克风权限未授权，请允许后重试")
        } else if (audioPageVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (companionPageVisible || calibrationPageVisible) { companionModel.invalidateInputFrames(); appModel.startCompanionAudio(calibrationModel.state.value.profile?.parameters ?: PitchEvidenceConfig()) } else appModel.startAudio()
        }
    }

    private fun requestAudio() {
        if (!audioPageVisible || permissionPending) return
        workbenchModel.stopPlayback("开始收音，试听已停止")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (companionPageVisible || calibrationPageVisible) { companionModel.invalidateInputFrames(); appModel.startCompanionAudio(calibrationModel.state.value.profile?.parameters ?: PitchEvidenceConfig()) } else appModel.startAudio()
        } else {
            permissionPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    override fun onStop() {
        workbenchModel.stopPlayback("应用进入后台，试听已停止")
        companionModel.pause()
        calibrationModel.cancelCalibration(); calibrationModel.clearMemory()
        appModel.stopAudio("应用已进入后台，麦克风已停止")
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportBpm = savedInstanceState?.getInt("midi_export_bpm", 80) ?: 80
        cameraUri = savedInstanceState?.getString("score_camera_uri")?.toUri()
        enableEdgeToEdge()
        appModel.setRecognitionListener { frame -> companionModel.accept(frame); calibrationModel.acceptRecognition(frame) }
        appModel.setPcmObserver(calibrationModel::appendPcm) { calibrationModel.clearMemory(); companionModel.invalidateInputFrames() }
        setContent {
            XiuyinTheme {
                val model = appModel
                val state by model.state.collectAsStateWithLifecycle()
                val audio by model.audio.collectAsStateWithLifecycle()
                val scores by scoreModel.state.collectAsStateWithLifecycle()
                val workbench by workbenchModel.state.collectAsStateWithLifecycle()
                val playback by workbenchModel.playback.collectAsStateWithLifecycle()
                val companion by companionModel.state.collectAsStateWithLifecycle()
                val recognition by model.recognition.collectAsStateWithLifecycle()
                val pianoFrame by model.pianoFrame.collectAsStateWithLifecycle()
                val calibration by calibrationModel.state.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf("home") }
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                var returnAfterPractice by rememberSaveable { mutableStateOf(false) }
                var showPracticeSwitch by rememberSaveable { mutableStateOf(false) }
                var switchAfterPractice by rememberSaveable { mutableStateOf(false) }
                val currentPiece = SampleLibrary.find(selectedId ?: state.preparation?.pieceId ?: state.recentPieceId.orEmpty())
                val navigate: (String) -> Unit = { destination ->
                    if (route == "companion" && destination != "companion") {
                        companionModel.pause()
                        model.stopAudio("已离开陪练页面")
                    }
                    if (route == "calibration" && destination != "calibration") { calibrationModel.cancelCalibration(); model.stopAudio("离开环境校准") }
                    companionPageVisible = destination == "companion"
                    calibrationPageVisible = destination == "calibration"
                    audioPageVisible = !state.loading && (destination == "monitor" || ((destination == "practice" || destination == "companion" || destination == "calibration") && state.error == null))
                    // Resource lifetime must not depend on a future Compose render.
                    if (!audioPageVisible) model.stopAudio("已离开音频页面，麦克风已停止")
                    if (destination != "workbench") workbenchModel.stopPlayback("已离开试听页面")
                    route = destination
                }
                val openSelectedPractice: () -> Unit = {
                    val revision = workbench.revision
                    val id = workbench.scoreId
                    if (revision != null && id != null && companionModel.open(id, workbench.title, revision.current,
                            revision.generation, "Magenta O&F " + recognition.modelSha256)) navigate("companion")
                }
                LaunchedEffect(switchAfterPractice, companion.busy, companion.phase, companion.error) {
                    if (switchAfterPractice && !companion.busy) {
                        switchAfterPractice = false
                        if (companion.error != null || companionModel.hasActiveSession()) showPracticeSwitch = true
                        else openSelectedPractice()
                    }
                }
                if (showPracticeSwitch) {
                    AlertDialog(onDismissRequest = { if (!companion.busy) showPracticeSwitch = false },
                        title = { Text("切换练习乐谱") },
                        text = { Text("当前仍有“${companion.title}”的练习。结束并保存后，再打开“${workbench.title}”。" +
                            (companion.error?.let { "\n$it" } ?: "")) },
                        confirmButton = { TextButton(modifier = Modifier.testTag("practice_switch_finish"), enabled = !companion.busy,
                            onClick = { model.stopAudio("切换乐谱，先结束当前练习"); companionModel.finish(); showPracticeSwitch = false; switchAfterPractice = true }) { Text("结束保存并切换") } },
                        dismissButton = { TextButton(modifier = Modifier.testTag("practice_switch_return"), enabled = !companion.busy,
                            onClick = { showPracticeSwitch = false; navigate("companion") }) { Text("返回当前陪练") } })
                }
                val saveRecognitionCase: (CaseTrigger) -> Unit = { trigger ->
                    val practice = companionModel.state.value
                    val evidence = practice.evidence.firstOrNull { it.decision == PracticeDecision.WRONG }
                    val frame = practice.lastWrongFrame
                    calibrationModel.saveCase(MisjudgmentContext(trigger = trigger, scoreId = practice.scoreId,
                        measureIndex = evidence?.measureIndex ?: practice.measureIndex, expectedMidi = evidence?.expectedMidi ?: practice.expectedMidi,
                        rawDetectedMidi = evidence?.detectedMidi ?: frame?.onsetMidi.orEmpty(),
                        notes = frame?.notes?.map { CaseNoteEvidence(it.midi, it.onsetScore.toDouble(), it.frameScore.toDouble(), it.midi in frame.onsetMidi, it.midi in frame.activeMidi) }.orEmpty(),
                        pedalState = frame?.pedalState ?: "UNKNOWN", finalDecision = evidence?.decision?.name ?: "UNCERTAIN",
                        practiceMode = practice.config.mode.name, userDisagrees = trigger == CaseTrigger.USER_DISPUTE,
                        modelDescription = "Magenta O&F " + model.recognition.value.modelSha256), frame?.audioTimeSeconds)
                }
                LaunchedEffect(audio) { calibrationModel.acceptAudio(audio) }
                LaunchedEffect(companion.wrong) {
                    if (route == "companion" && companion.wrong > 0 && calibration.caseConsent) saveRecognitionCase(CaseTrigger.CONFIRMED_WRONG)
                }
                val openPiece: (String) -> Unit = { id -> selectedId = id; model.openPiece(id); navigate("score") }

                LaunchedEffect(route, state.loading, state.error) {
                    companionPageVisible = route == "companion"
                    calibrationPageVisible = route == "calibration"
                    audioPageVisible = !state.loading && (route == "monitor" || ((route == "practice" || route == "companion" || route == "calibration") && state.error == null))
                    if (!audioPageVisible) { calibrationModel.cancelCalibration(); model.stopAudio("音频页面不可见，麦克风已停止") }
                }
                LaunchedEffect(returnAfterPractice, companion.busy, companion.phase, companion.error) {
                    if (returnAfterPractice && !companion.busy) {
                        if (companion.error != null) returnAfterPractice = false
                        else if (companion.phase in setOf(CompanionPhase.READY, CompanionPhase.COMPLETED)) {
                            returnAfterPractice = false; navigate("workbench")
                        }
                    }
                }
                LaunchedEffect(companion.phase, route) {
                    if (route == "companion" && companion.phase !in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) model.stopAudio("陪练未运行，收音已停止")
                }
                LaunchedEffect(audio.status, recognition.status, route) {
                    if (route == "companion" && companion.phase in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) {
                        if (recognition.status == RecognitionEngineStatus.ERROR) companionModel.inputUnavailable(recognition.message)
                        else if (audio.status in setOf(AudioStatus.PERMISSION_DENIED, AudioStatus.UNAVAILABLE, AudioStatus.ERROR, AudioStatus.SILENCED)) {
                            companionModel.inputUnavailable(audio.statusMessage); model.stopAudio("音频不可用，暂停陪练")
                        }
                    }
                }
                LaunchedEffect(route, state.loading) {
                    if (!state.loading) model.visit(route)
                }
                BackHandler(route != "home") { if (route == "companion") { model.stopAudio("结束陪练"); companionModel.finish(); returnAfterPractice = true } else if (route == "workbench") { scoreModel.reload(); navigate("digital") } else navigate(if (route == "score" || route == "digital") "library" else "home") }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("home" to "首页", "library" to "曲库", "practice" to "练习", "monitor" to "监控").forEach { (key, label) ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_$key"),
                                    selected = route == key || (key == "practice" && route == "companion") || (key == "library" && (route == "score" || route == "digital" || route == "workbench")),
                                    onClick = { navigate(if (key == "practice" && companion.scoreId != null) "companion" else key) },
                                    icon = { Text(when (key) { "home" -> "⌂"; "library" -> "♫"; "practice" -> "♩"; else -> "☷" }) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { insets ->
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            route == "monitor" -> DashboardScreen(state, model::refreshLogs, model::clearLogs, audio = audio, scores = scores, recognition = recognition, pianoFrame = pianoFrame, companion = companion, calibration = calibration, onCalibration = { navigate("calibration") })
                            state.error != null -> Column(Modifier.padding(24.dp).align(Alignment.Center)) {
                                Text("本地数据暂时无法读取", style = MaterialTheme.typography.titleLarge)
                                Text(state.error.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
                                TextButton(onClick = { model.initialize() }) { Text("重新检查") }
                                TextButton(onClick = { navigate("monitor") }) { Text("查看诊断") }
                            }
                            else -> when (route) {
                                "calibration" -> CalibrationScreen(calibration,
                                    onStartCalibration = { calibrationModel.startCalibration(); requestAudio() },
                                    onFinishCalibration = { calibrationModel.finishCalibration(); if (!calibrationModel.state.value.calibrating) model.stopAudio("校准已结束") },
                                    onConsentChange = calibrationModel::setCaseConsent, onApplyParameters = calibrationModel::applyParameters,
                                    onResetDefaults = calibrationModel::resetDefaults, onClearCases = calibrationModel::clearCases,
                                    onBack = { navigate(if (companion.scoreId != null) "companion" else "monitor") })
                                "library" -> LibraryScreen(SampleLibrary.pieces, openPiece, onDigitalScores = { navigate("digital") })
                                "score" -> currentPiece?.let {
                                    ScoreScreen(it, onBack = { navigate("library") }, onPrepare = { navigate("practice") }, onWorkbench = { scoreModel.openBuiltIn(it.id); navigate("digital") })
                                } ?: LibraryScreen(SampleLibrary.pieces, openPiece, onDigitalScores = { navigate("digital") })
                                "digital" -> DigitalScoreScreen(
                                    importing = scores.importing, progress = scores.progress,
                                    error = scores.error, storageIssues = scores.storageIssues,
                                    savedScores = scores.savedScores, selectedScore = scores.selectedScore,
                                    onImport = { scoreDocument.launch(arrayOf("*/*")) },
                                    onImportMidi = { midiDocument.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream")) },
                                    onImportImages = { scoreImages.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) },
                                    onCamera = ::photographScore,
                                    onOpen = scoreModel::openScore, onBack = { navigate("library") },
                                    query = scores.query, sort = scores.sort, selectedTag = scores.selectedTag,
                                    availableTags = scores.availableTags, selectedMetadata = scores.selectedMetadata,
                                    namingMetadata = scores.namingMetadata, onQueryChange = scoreModel::updateQuery,
                                    onSortChange = scoreModel::updateSort, onTagChange = scoreModel::updateTag,
                                    onToggleFavorite = scoreModel::toggleFavorite, onSaveMetadata = scoreModel::saveMetadata,
                                    onSkipNaming = scoreModel::skipNaming, onDeleteScore = scoreModel::deleteScore,
                                    onWorkbench = {
                                        val metadata = scores.selectedMetadata; val original = scores.selectedOriginal
                                        if (metadata != null && original != null) { workbenchModel.open(metadata, original); navigate("workbench") }
                                    },
                                )
                                "workbench" -> ScoreWorkbenchScreen(workbench, playback,
                                    onBack = { scoreModel.reload(); navigate("digital") },
                                    onExportMidi = { bpm -> exportBpm = bpm; midiExport.launch("xiuyin-score.mid") },
                                    onSelect = workbenchModel::select, onEdit = workbenchModel::edit,
                                    onTranspose = workbenchModel::transpose, onUndo = workbenchModel::undo,
                                    onRedo = workbenchModel::redo, onVerify = workbenchModel::verifySelected,
                                    onRestoreEvent = workbenchModel::restoreSelected, onRestoreMeasure = workbenchModel::restoreMeasure,
                                    onPlay = { bpm, volume, measure, loop, metronome ->
                                        model.stopAudio("开始试听，收音已停止")
                                        workbenchModel.startPlayback(bpm, volume, measure, loop, metronome)
                                    }, onStop = { workbenchModel.stopPlayback() },
                                    onPractice = {
                                        if (companionModel.hasActiveSession() || companion.busy) showPracticeSwitch = true
                                        else openSelectedPractice()
                                    },
                                )
                                "companion" -> PracticeCompanionScreen(companion,
                                    onCalibration = { navigate("calibration") },
                                    onDispute = { saveRecognitionCase(CaseTrigger.USER_DISPUTE) },
                                    feedbackMessage = calibration.error ?: calibration.message,
                                    onStart = { config -> companionModel.start(config); if (companionModel.state.value.phase == CompanionPhase.RUNNING) requestAudio() },
                                    onPause = { companionModel.pause(); model.stopAudio("用户暂停陪练") },
                                    onResume = { companionModel.resume(); if (companionModel.state.value.phase in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) requestAudio() },
                                    onSeekMeasure = companionModel::seekMeasure,
                                    onFinish = { model.stopAudio("练习结束"); companionModel.finish() },
                                    onBack = { model.stopAudio("返回修谱"); companionModel.finish(); returnAfterPractice = true },
                                )
                                "practice" -> PracticeScreen(
                                    currentPiece, state.preparation, { navigate("library") }, model::savePreparation, state.saving,
                                    audioSnapshot = audio, onStartAudio = ::requestAudio,
                                    onStopAudio = { model.stopAudio() }, onOpenPermissionSettings = ::openPermissionSettings,
                                )
                                else -> HomeScreen(SampleLibrary.pieces, state.recentPieceId, openPiece, { navigate("library") })
                            }
                        }
                    }
                }
            }
        }
    }
}
