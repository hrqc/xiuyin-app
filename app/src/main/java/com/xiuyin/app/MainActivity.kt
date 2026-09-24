package com.xiuyin.app

import android.content.ClipData
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.view.WindowManager
import com.xiuyin.app.field.*
import com.xiuyin.app.ui.KeepScreenOnWhileVisible
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
import androidx.compose.runtime.Composable
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
    private var fieldPageVisible = false
    private var currentRoute = "home"
    private val fieldModel: FieldTestViewModel by lazy { ViewModelProvider(this)[FieldTestViewModel::class.java] }
    private var pendingFieldSavePath: String? = null
    private var pendingFieldSaveSession: String? = null
    private val fieldSave = registerForActivityResult(object : ActivityResultContracts.CreateDocument("application/zip") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        val path = pendingFieldSavePath
        val session = pendingFieldSaveSession
        pendingFieldSavePath = null; pendingFieldSaveSession = null
        if (uri == null) fieldModel.reportMessage("已取消保存；测试包仍保留在本机，未标记为已导出")
        else if (path == null || session == null) fieldModel.reportError("保存上下文已失效，请重新生成并保存 ZIP")
        else fieldModel.saveTo(uri, session, path)
    }
    private fun saveFieldZip() {
        val exported = fieldModel.operation.value
        val file = exported.exportedFile ?: return fieldModel.reportError("请先生成测试 ZIP")
        val session = exported.exportedSessionId ?: return fieldModel.reportError("缺少测试会话，请重新导出")
        try {
            check(file.isFile) { "测试 ZIP 已不存在，请重新生成" }
            pendingFieldSavePath = file.absolutePath; pendingFieldSaveSession = session
            fieldSave.launch(file.name)
        } catch (error: Exception) { fieldModel.reportError("无法选择保存位置：${error.message}") }
    }
    private fun shareFieldZip() {
        try {
            val file = fieldModel.operation.value.exportedFile ?: error("请先生成测试 ZIP")
            check(file.isFile) { "测试 ZIP 已不存在，请重新生成" }
            val uri = FileProvider.getUriForFile(this, "$packageName.field-files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("修音实地测试 ZIP", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享实地测试 ZIP"))
            fieldModel.reportMessage("分享面板已打开；无法确认接收方已保存，会话保持未确认导出")
        } catch (error: Exception) { fieldModel.reportError("无法打开分享：${error.message}；可尝试保存 ZIP 到文件") }
    }
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
        pendingFieldSavePath?.let { outState.putString("field_save_path", it) }
        pendingFieldSaveSession?.let { outState.putString("field_save_session", it) }
        cameraUri?.let { outState.putString("score_camera_uri", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    private var audioPageVisible = false
    private var permissionPending = false
    private var pendingAudioAction: (() -> Unit)? = null
    private var pendingAudioRoute: String? = null
    private var pendingRequiresReady = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAudioAction
        val requestRoute = pendingAudioRoute
        val requiresReady = pendingRequiresReady
        permissionPending = false; pendingAudioAction = null; pendingAudioRoute = null
        if (!granted) {
            appModel.audioPermissionDenied()
            companionModel.inputUnavailable("麦克风权限未授权，请允许后重试")
            calibrationModel.inputUnavailable("麦克风权限未授权，请允许后重试")
            if (fieldPageVisible) fieldModel.reportError("麦克风权限未授权，未建立正式测试会话。可再次尝试，或手动前往系统设置。")
        } else if (requestRoute == currentRoute && audioPageVisible && appDataAvailable() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (requiresReady && !modelIsReady()) {
                if (fieldPageVisible) fieldModel.reportError("权限已授权，但模型不再 READY；准备完成后请重新手动开始")
            } else if (allowFieldBoundInput()) action?.invoke()
        }
    }
    private fun appDataAvailable() = !appModel.state.value.loading && appModel.state.value.error == null
    private fun modelIsReady() = appModel.recognition.value.status == RecognitionEngineStatus.READY
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun builtInMicrophoneAvailable(): Boolean = getSystemService(AudioManager::class.java)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } == true
    private fun prepareModel() = appModel.prepareCompanionModel(calibrationModel.state.value.profile?.parameters ?: PitchEvidenceConfig())

    private fun allowFieldBoundInput(): Boolean {
        val practice = companionModel.state.value
        val problem = FieldScoreBinding.problem(fieldModel.state.value.activeSession, practice.scoreId, practice.revisionGeneration)
            ?: return true
        appModel.stopAudio("实地测试谱面绑定不符，收音已停止")
        companionModel.inputUnavailable(problem)
        calibrationModel.inputUnavailable(problem)
        fieldModel.reportError(problem)
        return false
    }

    private fun requestMicrophone(requiresReady: Boolean, action: () -> Unit) {
        if (!appDataAvailable() || !audioPageVisible || permissionPending || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (requiresReady && !modelIsReady()) return
        if (!allowFieldBoundInput()) return
        workbenchModel.stopPlayback("开始收音，试听已停止")
        if (hasMicrophonePermission()) action()
        else {
            pendingAudioAction = action; pendingAudioRoute = currentRoute; pendingRequiresReady = requiresReady
            permissionPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun requestAudio() = requestMicrophone(false) { appModel.startAudio() }
    private fun requestReadyAudio(action: () -> Unit) {
        if (fieldModel.state.value.activeSession != null && fieldModel.state.value.error != null) {
            fieldModel.reportError("当前实地测试会话记录故障，请先结束并导出，再新建会话")
            return
        }
        requestMicrophone(true, action)
    }
    private fun beginFormalInput(): Boolean {
        if (!allowFieldBoundInput()) return false
        if (!appDataAvailable()) {
            companionModel.inputUnavailable("本地数据未就绪，禁止开始收音；可导出已有实地测试日志")
            calibrationModel.inputUnavailable("本地数据未就绪，禁止开始收音；可导出已有实地测试日志")
            return false
        }
        if (!modelIsReady() || !hasMicrophonePermission() || !audioPageVisible) return false
        companionModel.invalidateInputFrames()
        val started = appModel.startCompanionAudio(calibrationModel.state.value.profile?.parameters ?: PitchEvidenceConfig())
        if (!started) {
            companionModel.inputUnavailable("识别模型未就绪，未启动麦克风")
            calibrationModel.inputUnavailable("识别模型未就绪，未启动麦克风")
            if (fieldPageVisible) fieldModel.reportError("识别模型未就绪，未启动麦克风；请重试准备")
        }
        return started
    }
    private fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    override fun onStart() {
        super.onStart()
        if (companionPageVisible || calibrationPageVisible || fieldPageVisible) prepareModel()
    }
    override fun onStop() {
        pendingAudioAction = null; pendingAudioRoute = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        workbenchModel.stopPlayback("应用进入后台，试听已停止")
        companionModel.pause()
        calibrationModel.cancelCalibration(); calibrationModel.clearMemory()
        appModel.stopAudio("应用已进入后台，麦克风已停止")
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportBpm = savedInstanceState?.getInt("midi_export_bpm", 80) ?: 80
        pendingFieldSavePath = savedInstanceState?.getString("field_save_path")
        pendingFieldSaveSession = savedInstanceState?.getString("field_save_session")
        cameraUri = savedInstanceState?.getString("score_camera_uri")?.toUri()
        enableEdgeToEdge()
        appModel.setInputFailureListener { message ->
            companionModel.inputUnavailable(message)
            calibrationModel.inputUnavailable(message)
            if (fieldPageVisible) fieldModel.reportError("输入已暂停：$message；检查后需要手动重新开始")
        }
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
                val field by fieldModel.state.collectAsStateWithLifecycle()
                val fieldOperation by fieldModel.operation.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf("home") }
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                var returnAfterPractice by rememberSaveable { mutableStateOf(false) }
                var showPracticeSwitch by rememberSaveable { mutableStateOf(false) }
                var switchAfterPractice by rememberSaveable { mutableStateOf(false) }
                var repairNavigationError by rememberSaveable { mutableStateOf<String?>(null) }
                var fieldNavigationError by rememberSaveable { mutableStateOf<String?>(null) }
                val fieldBindingProblem = FieldScoreBinding.problem(field.activeSession, companion.scoreId, companion.revisionGeneration)
                val currentPiece = SampleLibrary.find(selectedId ?: state.preparation?.pieceId ?: state.recentPieceId.orEmpty())
                val navigate: (String) -> Unit = { destination ->
                    if ((route == "companion" || route == "field") && destination != route) {
                        companionModel.pause()
                        model.stopAudio("已离开陪练页面")
                    }
                    if (destination != route && destination in setOf("companion", "calibration", "field") &&
                        route !in setOf("companion", "calibration", "field")) model.stopAudio("进入模型准备页面，先停止已有收音")
                    if (route == "calibration" && destination != "calibration") { calibrationModel.cancelCalibration(); model.stopAudio("离开环境校准") }
                    pendingAudioAction = null; pendingAudioRoute = null
                    currentRoute = destination
                    fieldPageVisible = destination == "field"
                    companionPageVisible = destination == "companion"
                    calibrationPageVisible = destination == "calibration"
                    audioPageVisible = !state.loading && state.error == null && destination in setOf("monitor", "practice", "companion", "calibration", "field")
                    // Resource lifetime must not depend on a future Compose render.
                    if (!audioPageVisible) model.stopAudio("已离开音频页面，麦克风已停止")
                    if (destination != "workbench") workbenchModel.stopPlayback("已离开试听页面")
                    route = destination
                }
                KeepScreenOnWhileVisible(this@MainActivity, !state.loading &&
                    (route in setOf("practice", "companion", "calibration", "field", "monitor") || fieldOperation.busy))
                val recordFieldFeedback: (FieldFeedbackLabel) -> Unit = feedback@{ label ->
                    if (!allowFieldBoundInput()) return@feedback
                    val context = buildFieldFeedbackContext(companionModel.state.value, model.pianoFrame.value,
                        fieldModel.state.value.activeSession?.id)
                    fieldModel.feedback(label, context.values, context.audioTimeSeconds)
                }
                val startField: (String, PracticeConfig) -> Unit = { label, requestedConfig ->
                    val reviewed = requestedConfig.reviewAcknowledged
                    requestReadyAudio {
                        val practice = companionModel.state.value
                        if (fieldModel.state.value.activeSession != null && !companionModel.hasActiveSession())
                            fieldModel.reportError("这次实地测试的练习已经结束，请先结束并导出旧实地会话，再新建练习和测试。")
                        else if (practice.timeline == null || practice.score == null || practice.busy) fieldModel.reportError("数字乐谱尚未准备，请从工作台打开有效乐谱")
                        else if (!builtInMicrophoneAvailable()) fieldModel.reportError("系统未报告可用的内置麦克风，请检查设备")
                        else if (practice.phase != CompanionPhase.PAUSED && practice.timeline.issues.isNotEmpty() && !reviewed)
                            fieldModel.reportError("请先校对并确认谱面的来源或结构警告")
                        else {
                            val hadPracticeSession = companionModel.hasActiveSession()
                            val effectiveConfig = if (hadPracticeSession) practice.config else requestedConfig
                            val snapshot = FieldScoreSnapshot.Request(requireNotNull(practice.scoreId), practice.revisionGeneration,
                                practice.score, effectiveConfig,
                                if (hadPracticeSession) practice.phase else CompanionPhase.READY,
                                if (hadPracticeSession) practice.cursor else 0,
                                workbenchModel.state.value.original?.takeIf { it.source == practice.score.source })
                            val begin: () -> Boolean = {
                                val currentPractice = companionModel.state.value
                                val currentConfig = if (companionModel.hasActiveSession()) currentPractice.config else requestedConfig
                                if (!FieldScoreBinding.samePreparedScore(practice.scoreId, practice.revisionGeneration,
                                        currentPractice.scoreId, currentPractice.revisionGeneration)) {
                                    fieldModel.reportError("会话准备期间谱面或修订已变化，未启动收音，请重新打开目标谱面并新建会话。")
                                    false
                                } else if (currentPractice.score != practice.score || currentConfig != effectiveConfig ||
                                    companionModel.hasActiveSession() != hadPracticeSession) {
                                    fieldModel.reportError("会话准备期间谱面内容或练习设置已变化，未启动收音，请重新新建会话。")
                                    false
                                } else if (!allowFieldBoundInput()) false
                                else if (!appDataAvailable() || currentRoute != "field" || !fieldPageVisible || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !modelIsReady() || !hasMicrophonePermission()) false
                                else {
                                    if (companionModel.state.value.phase == CompanionPhase.PAUSED) companionModel.resume()
                                    else companionModel.start(requestedConfig)
                                    val phase = companionModel.state.value.phase
                                    if (phase !in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) {
                                        fieldModel.reportError(companionModel.state.value.error ?: "练习未开始，请检查谱面")
                                        false
                                    } else beginFormalInput()
                                }
                            }
                            if (fieldModel.state.value.activeSession == null) fieldModel.start(label, mapOf(
                                "scoreId" to practice.scoreId.orEmpty(), "scoreTitle" to practice.title,
                                "revisionGeneration" to practice.revisionGeneration.toString(),
                                "sourceSha256" to practice.score.source.sha256,
                                "modelReadyAtRequest" to modelIsReady().toString(), "modelSha256" to recognition.modelSha256,
                                "microphonePermissionAtRequest" to hasMicrophonePermission().toString(),
                            ), scoreSnapshot = snapshot, onStarted = begin)
                            else if (!begin()) fieldModel.reportError("现场收音未启动，请检查当前页面、模型、谱面和权限后重试")
                        }
                    }
                }
                val openSelectedPractice: () -> Unit = {
                    val problem = FieldScoreBinding.openingProblem(fieldModel.state.value.activeSession, fieldModel.operation.value.busy)
                    if (problem != null) fieldNavigationError = problem
                    else {
                        val currentWorkbench = workbenchModel.state.value
                        val revision = currentWorkbench.revision
                        val id = currentWorkbench.scoreId
                        if (revision != null && id != null && companionModel.open(id, currentWorkbench.title, revision.current,
                                revision.generation, "Magenta O&F " + recognition.modelSha256)) navigate("companion")
                    }
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
                fieldNavigationError?.let { message ->
                    AlertDialog(onDismissRequest = { fieldNavigationError = null }, title = { Text("请先处理当前实地测试") },
                        text = { Text(message) }, confirmButton = { TextButton(modifier = Modifier.testTag("field_binding_go_to_session"),
                            onClick = { fieldNavigationError = null; navigate("field") }) { Text("前往实地测试结束并导出") } },
                        dismissButton = { TextButton(onClick = { fieldNavigationError = null }) { Text("暂不切换") } })
                }
                repairNavigationError?.let { message ->
                    AlertDialog(onDismissRequest = { repairNavigationError = null }, title = { Text("暂时无法打开修谱") },
                        text = { Text(message) }, confirmButton = { TextButton(onClick = { repairNavigationError = null }) { Text("知道了") } })
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
                LaunchedEffect(field.activeSession?.id, companion.scoreId, companion.revisionGeneration, fieldBindingProblem) {
                    if (fieldBindingProblem != null) allowFieldBoundInput()
                }
                LaunchedEffect(audio) { calibrationModel.acceptAudio(audio) }
                LaunchedEffect(companion.wrong) {
                    if (route == "companion" && companion.wrong > 0 && calibration.caseConsent) saveRecognitionCase(CaseTrigger.CONFIRMED_WRONG)
                }
                val openPiece: (String) -> Unit = { id -> selectedId = id; model.openPiece(id); navigate("score") }

                LaunchedEffect(route, state.loading, state.error) {
                    currentRoute = route
                    fieldPageVisible = route == "field"
                    companionPageVisible = route == "companion"
                    calibrationPageVisible = route == "calibration"
                    audioPageVisible = !state.loading && state.error == null && route in setOf("monitor", "practice", "companion", "calibration", "field")
                    if (!audioPageVisible) { companionModel.pause(); calibrationModel.cancelCalibration(); model.stopAudio("音频页面不可用，麦克风已停止") }
                }
                LaunchedEffect(route) {
                    if (route in setOf("companion", "calibration", "field")) prepareModel()
                    if (route == "field") fieldModel.refresh()
                }
                LaunchedEffect(route, workbench.scoreId, workbench.revision?.generation, workbench.busy,
                    companion.busy, field.activeSession?.id, fieldOperation.busy) {
                    // Bottom-bar return uses this score's saved revision; live sessions retain their binding.
                    val editor = workbenchModel.state.value
                    val practice = companionModel.state.value
                    val revision = editor.revision
                    if (route == "companion" && !editor.busy && !practice.busy &&
                        !companionModel.hasActiveSession() && fieldModel.state.value.activeSession == null &&
                        !fieldModel.operation.value.busy && editor.error == null && revision != null &&
                        editor.scoreId != null && editor.scoreId == practice.scoreId &&
                        revision.generation != practice.revisionGeneration) {
                        companionModel.open(editor.scoreId, editor.title, revision.current, revision.generation,
                            "Magenta O&F " + recognition.modelSha256)
                    }
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
                    // Preparing READY is not capture. Do not reset a newly loaded model on the waiting screen.
                    if (route in setOf("companion", "field") && companion.phase == CompanionPhase.COMPLETED &&
                        audio.status in setOf(AudioStatus.STARTING, AudioStatus.LISTENING)) model.stopAudio("陪练已结束，收音已停止")
                }
                LaunchedEffect(audio.status, recognition.status, route) {
                    if (route in setOf("companion", "field") && companion.phase in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) {
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
                    topBar = {
                        if (field.activeSession != null) TextButton(onClick = { navigate("field") }, modifier = Modifier.testTag("field_active_banner")) {
                            Text("FIELD TEST MODE · ${if (audio.status == AudioStatus.LISTENING) "收音中" else "收音已停"} · 查看与导出")
                        }
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("home" to "首页", "library" to "曲库", "practice" to "练习", "monitor" to "监控", "settings" to "设置").forEach { (key, label) ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_$key"),
                                    selected = route == key || (key == "settings" && route == "field") || (key == "practice" && route == "companion") || (key == "library" && (route == "score" || route == "digital" || route == "workbench")),
                                    onClick = { navigate(if (key == "practice" && companion.scoreId != null) "companion" else key) },
                                    icon = { Text(when (key) { "home" -> "⌂"; "library" -> "♫"; "practice" -> "♩"; "settings" -> "⚙"; else -> "☷" }) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { insets ->
                    Box(Modifier.fillMaxSize().padding(insets)) {
                        AppDataGate(route, state.loading, state.error,
                            onRetry = { model.initialize() }, onDiagnostics = { navigate("monitor") }) {
                            when (route) {
                                "monitor" -> DashboardScreen(state, model::refreshLogs, model::clearLogs, audio = audio, scores = scores, recognition = recognition, pianoFrame = pianoFrame, companion = companion, calibration = calibration, onCalibration = { navigate("calibration") })
                                "settings" -> FieldSettingsScreen(onOpenField = { navigate("field") }, onMonitor = { navigate("monitor") })
                                "field" -> FieldTestScreen(field, fieldOperation, audio, recognition, pianoFrame, companion,
                                    microphonePermission = hasMicrophonePermission(), builtInMicrophoneAvailable = builtInMicrophoneAvailable(),
                                    appDataAvailable = !state.loading && state.error == null, appDataError = state.error,
                                    onStart = { label, reviewed -> startField(label, PracticeConfig(reviewAcknowledged = reviewed)) },
                                    onConfiguredStart = startField, onStopAudio = { companionModel.pause(); model.stopAudio("用户暂停现场收音") },
                                    onEndSession = { companionModel.pause(); model.stopAudio("用户结束实地测试"); fieldModel.finish() },
                                    onRetryModel = ::prepareModel, onConsent = fieldModel::setClipConsent,
                                    onFeedback = recordFieldFeedback, onExport = fieldModel::export,
                                    onSave = ::saveFieldZip, onShare = ::shareFieldZip, onClear = fieldModel::clearConfirmed,
                                    onRefresh = fieldModel::refresh, onChooseScore = { navigate("digital") },
                                    onPractice = { navigate("companion") }, onPermissionSettings = ::openPermissionSettings,
                                    onBack = { navigate("settings") })
                                "calibration" -> CalibrationScreen(calibration,
                                    onStartCalibration = { requestReadyAudio { calibrationModel.startCalibration(); if (calibrationModel.state.value.calibrating) beginFormalInput() } },
                                    onFinishCalibration = { calibrationModel.finishCalibration(); if (!calibrationModel.state.value.calibrating) model.stopAudio("校准已结束") },
                                    onConsentChange = calibrationModel::setCaseConsent, onApplyParameters = calibrationModel::applyParameters,
                                    onResetDefaults = calibrationModel::resetDefaults, onClearCases = calibrationModel::clearCases,
                                    onBack = { navigate(if (companion.scoreId != null) "companion" else "monitor") }, recognition = recognition, onRetryModel = ::prepareModel, fieldInputBlocked = field.activeSession != null && (field.error != null || fieldBindingProblem != null))
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
                                    onRecognizeOmrAgain = workbenchModel::recognizeOmrAgain,
                                    onPreviewOmrStructure = workbenchModel::previewOmrStructure,
                                    onApplyOmrStructure = workbenchModel::applyOmrStructure,
                                    onDismissStructurePreview = workbenchModel::dismissStructurePreview,
                                    onSetWholeScoreTime = workbenchModel::setWholeScoreTime,
                                    onFillMissingScoreTime = workbenchModel::fillMissingScoreTime,
                                    onConfirmLinearRepeat = workbenchModel::confirmLinearRepeat,
                                    onSelect = workbenchModel::select, onEdit = workbenchModel::edit,
                                    onTranspose = workbenchModel::transpose, onUndo = workbenchModel::undo,
                                    onRedo = workbenchModel::redo, onVerify = workbenchModel::verifySelected,
                                    onRestoreEvent = workbenchModel::restoreSelected, onRestoreMeasure = workbenchModel::restoreMeasure,
                                    onPlay = { bpm, volume, measure, loop, metronome ->
                                        model.stopAudio("开始试听，收音已停止")
                                        workbenchModel.startPlayback(bpm, volume, measure, loop, metronome)
                                    }, onStop = { workbenchModel.stopPlayback() },
                                    onPractice = {
                                        val problem = FieldScoreBinding.openingProblem(fieldModel.state.value.activeSession, fieldModel.operation.value.busy)
                                        if (problem != null) fieldNavigationError = problem
                                        else if (companionModel.hasActiveSession() || companion.busy) showPracticeSwitch = true
                                        else openSelectedPractice()
                                    },
                                )
                                "companion" -> PracticeCompanionScreen(companion,
                                    onRepairScore = { issue ->
                                        model.stopAudio("返回修谱定位结构错误")
                                        val targetScoreId = companionModel.state.value.scoreId
                                        if (targetScoreId == null) repairNavigationError = "当前练习没有乐谱记录，请先选择需要修正的乐谱。"
                                        else if (workbenchModel.openForRepair(targetScoreId, issue)) navigate("workbench")
                                        else repairNavigationError = "修谱工作台正在处理另一项操作，请完成后重试。不会打开或修改其他乐谱。"
                                    },
                                    onCalibration = { navigate("calibration") },
                                    recognition = recognition, onRetryModel = ::prepareModel,
                                    fieldMode = field.activeSession != null, fieldInputBlocked = field.activeSession != null && (field.error != null || fieldBindingProblem != null), onFieldTest = { navigate("field") },
                                    onDispute = { if (field.activeSession != null) recordFieldFeedback(FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG) else saveRecognitionCase(CaseTrigger.USER_DISPUTE) },
                                    onConfirmWrong = { recordFieldFeedback(FieldFeedbackLabel.USER_CONFIRMS_WRONG_NOTE) },
                                    feedbackMessage = if (field.activeSession != null) fieldOperation.error ?: field.error ?: field.message else calibration.error ?: calibration.message,
                                    onStart = { config ->
                                        val problem = FieldScoreBinding.openingProblem(fieldModel.state.value.activeSession, fieldModel.operation.value.busy)
                                        if (problem != null) fieldModel.reportError(problem)
                                        else requestReadyAudio { companionModel.start(config); if (companionModel.state.value.phase == CompanionPhase.RUNNING) beginFormalInput() }
                                    },
                                    onPause = {
                                        companionModel.pause()
                                        model.stopAudio("用户暂停陪练")
                                        // Preparation alone never queues capture. READY still needs a new Continue tap.
                                        if (companionPageVisible && appDataAvailable() && companionModel.hasActiveSession() &&
                                            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) prepareModel()
                                    },
                                    onResume = { requestReadyAudio { companionModel.resume(); if (companionModel.state.value.phase in setOf(CompanionPhase.RUNNING, CompanionPhase.LOCKED)) beginFormalInput() } },
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

/** Diagnostics use their own storage and must remain reachable when the product database fails. */
@Composable
internal fun AppDataGate(
    route: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDiagnostics: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (route in setOf("monitor", "settings", "field") || (!loading && error == null)) {
        content()
    } else Box(Modifier.fillMaxSize()) {
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else Column(Modifier.padding(24.dp).align(Alignment.Center)) {
            Text("本地数据暂时无法读取", style = MaterialTheme.typography.titleLarge)
            Text(error.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onRetry) { Text("重新检查") }
            TextButton(onClick = onDiagnostics) { Text("查看诊断") }
            Text("已有实地测试日志仍可通过底部设置进入实地测试后导出。", style = MaterialTheme.typography.bodySmall)
        }
    }
}