package com.xiuyin.app

import android.os.Bundle
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiuyin.app.data.SampleLibrary
import com.xiuyin.app.monitor.DashboardScreen
import com.xiuyin.app.ui.HomeScreen
import com.xiuyin.app.ui.LibraryScreen
import com.xiuyin.app.ui.PracticeScreen
import com.xiuyin.app.ui.ScoreScreen
import com.xiuyin.app.ui.XiuyinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiuyinTheme {
                val model: AppViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                var route by rememberSaveable { mutableStateOf("home") }
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                val currentPiece = SampleLibrary.find(selectedId ?: state.preparation?.pieceId ?: state.recentPieceId.orEmpty())
                val openPiece: (String) -> Unit = { id -> selectedId = id; model.openPiece(id); route = "score" }

                LaunchedEffect(route, state.loading) {
                    if (!state.loading) model.visit(route)
                }
                BackHandler(route != "home") { route = if (route == "score") "library" else "home" }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("home" to "首页", "library" to "曲库", "practice" to "练习", "monitor" to "监控").forEach { (key, label) ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_$key"),
                                    selected = route == key || (key == "library" && route == "score"),
                                    onClick = { route = key },
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
                            route == "monitor" -> DashboardScreen(state, model::refreshLogs, model::clearLogs)
                            state.error != null -> Column(Modifier.padding(24.dp).align(Alignment.Center)) {
                                Text("本地数据暂时无法读取", style = MaterialTheme.typography.titleLarge)
                                Text(state.error.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
                                TextButton(onClick = { model.initialize() }) { Text("重新检查") }
                                TextButton(onClick = { route = "monitor" }) { Text("查看诊断") }
                            }
                            else -> when (route) {
                                "library" -> LibraryScreen(SampleLibrary.pieces, openPiece)
                                "score" -> currentPiece?.let {
                                    ScoreScreen(it, onBack = { route = "library" }, onPrepare = { route = "practice" })
                                } ?: LibraryScreen(SampleLibrary.pieces, openPiece)
                                "practice" -> PracticeScreen(currentPiece, state.preparation, { route = "library" }, model::savePreparation, state.saving)
                                else -> HomeScreen(SampleLibrary.pieces, state.recentPieceId, openPiece, { route = "library" })
                            }
                        }
                    }
                }
            }
        }
    }
}
