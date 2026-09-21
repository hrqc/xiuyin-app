package com.xiuyin.app

import android.content.Context
import android.Manifest
import android.content.Intent
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
    private val scoreDocument = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri == null) scoreModel.selectionCancelled() else scoreModel.importFile(uri)
    }
    private var audioPageVisible = false
    private var permissionPending = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (!granted) {
            appModel.audioPermissionDenied()
        } else if (audioPageVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            appModel.startAudio()
        }
    }

    private fun requestAudio() {
        if (!audioPageVisible || permissionPending) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            appModel.startAudio()
        } else {
            permissionPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    override fun onStop() {
        appModel.stopAudio("应用已进入后台，麦克风已停止")
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiuyinTheme {
                val model = appModel
                val state by model.state.collectAsStateWithLifecycle()
                val audio by model.audio.collectAsStateWithLifecycle()
                val scores by scoreModel.state.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf("home") }
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                val currentPiece = SampleLibrary.find(selectedId ?: state.preparation?.pieceId ?: state.recentPieceId.orEmpty())
                val navigate: (String) -> Unit = { destination ->
                    audioPageVisible = !state.loading && (destination == "monitor" || (destination == "practice" && state.error == null))
                    // Resource lifetime must not depend on a future Compose render.
                    if (!audioPageVisible) model.stopAudio("已离开音频页面，麦克风已停止")
                    route = destination
                }
                val openPiece: (String) -> Unit = { id -> selectedId = id; model.openPiece(id); navigate("score") }

                LaunchedEffect(route, state.loading, state.error) {
                    audioPageVisible = !state.loading && (route == "monitor" || (route == "practice" && state.error == null))
                    if (!audioPageVisible) model.stopAudio("音频页面不可见，麦克风已停止")
                }
                LaunchedEffect(route, state.loading) {
                    if (!state.loading) model.visit(route)
                }
                BackHandler(route != "home") { navigate(if (route == "score" || route == "digital") "library" else "home") }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("home" to "首页", "library" to "曲库", "practice" to "练习", "monitor" to "监控").forEach { (key, label) ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_$key"),
                                    selected = route == key || (key == "library" && (route == "score" || route == "digital")),
                                    onClick = { navigate(key) },
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
                            route == "monitor" -> DashboardScreen(state, model::refreshLogs, model::clearLogs, audio = audio, scores = scores)
                            state.error != null -> Column(Modifier.padding(24.dp).align(Alignment.Center)) {
                                Text("本地数据暂时无法读取", style = MaterialTheme.typography.titleLarge)
                                Text(state.error.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
                                TextButton(onClick = { model.initialize() }) { Text("重新检查") }
                                TextButton(onClick = { navigate("monitor") }) { Text("查看诊断") }
                            }
                            else -> when (route) {
                                "library" -> LibraryScreen(SampleLibrary.pieces, openPiece, onDigitalScores = { navigate("digital") })
                                "score" -> currentPiece?.let {
                                    ScoreScreen(it, onBack = { navigate("library") }, onPrepare = { navigate("practice") })
                                } ?: LibraryScreen(SampleLibrary.pieces, openPiece, onDigitalScores = { navigate("digital") })
                                "digital" -> DigitalScoreScreen(
                                    importing = scores.importing, progress = scores.progress,
                                    error = scores.error, storageIssues = scores.storageIssues,
                                    savedScores = scores.savedScores, selectedScore = scores.selectedScore,
                                    onImport = { scoreDocument.launch(arrayOf("*/*")) },
                                    onOpen = scoreModel::openScore, onBack = { navigate("library") },
                                    query = scores.query, sort = scores.sort, selectedTag = scores.selectedTag,
                                    availableTags = scores.availableTags, selectedMetadata = scores.selectedMetadata,
                                    namingMetadata = scores.namingMetadata, onQueryChange = scoreModel::updateQuery,
                                    onSortChange = scoreModel::updateSort, onTagChange = scoreModel::updateTag,
                                    onToggleFavorite = scoreModel::toggleFavorite, onSaveMetadata = scoreModel::saveMetadata,
                                    onSkipNaming = scoreModel::skipNaming, onDeleteScore = scoreModel::deleteScore,
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
