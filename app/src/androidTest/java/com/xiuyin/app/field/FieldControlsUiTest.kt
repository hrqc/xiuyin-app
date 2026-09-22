package com.xiuyin.app.field

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppDataGate
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.score.MusicXmlParser
import com.xiuyin.app.score.ScoreTimelineCompiler
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit TEST DATA fixtures test controls only; no fabricated frame is counted as a recognition result. */
class FieldControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val diagnostics = FieldDiagnosticsState(storageWritable = true, freeSpaceBytes = 50L * 1024 * 1024)
    private fun practice(): CompanionState {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
        val score = MusicXmlParser().parseDocument(bytes).score
        return CompanionState(title = "TEST DATA · UI 控件用谱", scoreId = score.id, score = score, timeline = ScoreTimelineCompiler.compile(score))
    }
    @Test fun loadingCannotStartAndReadyRequiresAnExplicitClick() {
        val engine = mutableStateOf(RecognitionEngineSnapshot(RecognitionEngineStatus.LOADING, "TEST DATA loading"))
        var starts = 0; var retries = 0
        val practice = practice()
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, engine.value, practice, onStart = { _, _ -> starts++ }, onRetry = { retries++ }) } }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { engine.value = engine.value.copy(status = RecognitionEngineStatus.ERROR, message = "TEST DATA model failure") }
        compose.onNodeWithTag("model_prepare").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, retries); assertEquals(0, starts)
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { engine.value = engine.value.copy(status = RecognitionEngineStatus.READY, message = "TEST DATA ready") }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals("READY 不得排队自动开始", 0, starts) }
        compose.onNodeWithTag("field_start").performClick()
        assertEquals(1, starts)
    }
    @Test fun bothFeedbackLabelsWorkWithoutClipConsentAndDoNotUnlock() {
        val recorded = mutableListOf<FieldFeedbackLabel>()
        val active = FieldSessionInfo("test-ui-active", "TEST DATA", 1)
        val practice = practice().copy(phase = CompanionPhase.LOCKED, locked = true)
        compose.setContent { XiuyinTheme { TestScreen(diagnostics.copy(activeSession = active, sessions = listOf(active), clipConsent = false),
            RecognitionEngineSnapshot(RecognitionEngineStatus.RUNNING), practice, onFeedback = { recorded += it }) } }
        compose.onNodeWithTag("field_clip_consent").performScrollTo().assertIsOff()
        compose.onNodeWithTag("field_feedback_dispute").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_feedback_confirm").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(FieldFeedbackLabel.USER_SAYS_SYSTEM_WRONG, FieldFeedbackLabel.USER_CONFIRMS_WRONG_NOTE), recorded)
        compose.onNodeWithTag("field_lock").performScrollTo().assertTextContains("LOCKED=true", substring = true)
        compose.onNodeWithTag("field_clip_consent").performScrollTo().assertIsOff()
    }
    @Test fun failedActiveSessionBlocksNewInputButKeepsRecoveryExportAvailable() {
        val active = FieldSessionInfo("test-ui-fault", "TEST DATA fault", 1)
        val practice = practice().copy(phase = CompanionPhase.PAUSED)
        compose.setContent { XiuyinTheme { TestScreen(diagnostics.copy(activeSession = active, sessions = listOf(active), error = "TEST DATA writer failure"),
            RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice) } }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_feedback_dispute").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_end").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("field_export_current").performScrollTo().assertIsEnabled()
    }
    @Test fun incompleteHistoryExportsAndCleanupRequiresConfirmation() {
        val history = FieldSessionInfo("test-ui-incomplete", "TEST DATA incomplete", 1, status = FieldSessionStatus.INCOMPLETE)
        val exports = mutableListOf<String>(); val deleted = mutableListOf<Set<String>>()
        val practice = practice()
        compose.setContent { XiuyinTheme { TestScreen(diagnostics.copy(sessions = listOf(history)), RecognitionEngineSnapshot(), practice,
            onExport = { exports += it }, onClear = { deleted += it }) } }
        compose.onNodeWithTag("field_export_${history.id}").performScrollTo().performClick()
        assertEquals(listOf(history.id), exports)
        compose.onNodeWithTag("field_clear_${history.id}").performScrollTo().performClick()
        compose.onNodeWithTag("field_clear_cancel").performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithTag("field_clear_${history.id}").performClick()
        compose.onNodeWithTag("field_clear_confirm").performClick()
        assertEquals(listOf(setOf(history.id)), deleted)
    }
    @Test fun databaseFailureStillAllowsSettingsAndHistoryExportButBlocksCapture() {
        val route = mutableStateOf("settings")
        val injectedError = "TEST DATA · 数据库读取失败"
        val history = FieldSessionInfo("test-ui-db-recovery", "TEST DATA recovery", 1, status = FieldSessionStatus.INCOMPLETE)
        val exports = mutableListOf<String>()
        var starts = 0
        val practice = practice()
        compose.setContent {
            XiuyinTheme {
                AppDataGate(route.value, loading = false, error = injectedError, onRetry = {}, onDiagnostics = { route.value = "monitor" }) {
                    when (route.value) {
                        "settings" -> FieldSettingsScreen(onOpenField = { route.value = "field" }, onMonitor = { route.value = "monitor" })
                        "field" -> TestScreen(diagnostics.copy(sessions = listOf(history)),
                            RecognitionEngineSnapshot(RecognitionEngineStatus.READY, "TEST DATA ready"), practice,
                            onStart = { _, _ -> starts++ }, onExport = { exports += it },
                            appDataAvailable = false, appDataError = injectedError)
                    }
                }
            }
        }
        compose.onNodeWithTag("open_field_test").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_database_unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(injectedError).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_export_${history.id}").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("数据库故障不得阻断独立诊断文件的导出入口", listOf(history.id), exports)
            assertEquals("数据库故障期间不得新开正式会话或收音", 0, starts)
        }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun databaseFailureStillBlocksProductPagesAndOffersRetryAndDiagnostics() {
        var contentCalls = 0
        var retries = 0
        var diagnosticsRequests = 0
        compose.setContent {
            XiuyinTheme {
                AppDataGate("practice", loading = false, error = "TEST DATA · 数据库读取失败",
                    onRetry = { retries++ }, onDiagnostics = { diagnosticsRequests++ }) {
                    androidx.compose.runtime.SideEffect { contentCalls++ }
                }
            }
        }
        compose.onNodeWithText("本地数据暂时无法读取").assertIsDisplayed()
        compose.onNodeWithText("重新检查").performClick()
        compose.onNodeWithText("查看诊断").performClick()
        compose.runOnIdle {
            assertEquals("练习页不可越过数据库故障门禁", 0, contentCalls)
            assertEquals(1, retries)
            assertEquals(1, diagnosticsRequests)
        }
    }
    @androidx.compose.runtime.Composable
    private fun TestScreen(state: FieldDiagnosticsState, engine: RecognitionEngineSnapshot, practice: CompanionState,
        onStart: (String, Boolean) -> Unit = { _, _ -> }, onRetry: () -> Unit = {},
        onFeedback: (FieldFeedbackLabel) -> Unit = {}, onExport: (String) -> Unit = {}, onClear: (Set<String>) -> Unit = {},
        appDataAvailable: Boolean = true, appDataError: String? = null) {
        FieldTestScreen(state, FieldOperationState(), AudioSnapshot(), engine, null, practice, true, true,
            onStart, {}, {}, onRetry, {}, onFeedback, onExport, {}, {}, onClear, {}, {}, {}, {}, {},
            appDataAvailable = appDataAvailable, appDataError = appDataError)
    }
}