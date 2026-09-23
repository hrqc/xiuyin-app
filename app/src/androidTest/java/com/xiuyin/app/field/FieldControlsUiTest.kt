package com.xiuyin.app.field

import android.os.StatFs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.AppDataGate
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.pitch.RecognitionEngineSnapshot
import com.xiuyin.app.pitch.RecognitionEngineStatus
import com.xiuyin.app.practice.CompanionMode
import com.xiuyin.app.practice.PracticeConfig
import com.xiuyin.app.practice.PracticeHand
import com.xiuyin.app.practice.CompanionPhase
import com.xiuyin.app.practice.CompanionState
import com.xiuyin.app.score.MusicXmlParser
import com.xiuyin.app.score.ScoreIssue
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreTimelineCompiler
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.*
import org.junit.After
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.Comparator
import org.junit.Rule
import org.junit.Test

/** Explicit TEST DATA fixtures test controls only; no fabricated frame is counted as a recognition result. */
class FieldControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val diagnostics = FieldDiagnosticsState(storageWritable = true, freeSpaceBytes = 50L * 1024 * 1024)
    private val ownedStoreRoots = mutableListOf<File>()
    private val actualStores = mutableMapOf<String, FieldSessionStore>()
    private fun storedSession(practice: CompanionState? = null): FieldSessionInfo {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "field-binding-ui-${UUID.randomUUID()}")
        check(root.mkdir())
        ownedStoreRoots += root
        // Android does not implement Files.getFileStore; read actual free space on this volume.
        val store = FieldSessionStore(File(root, "sessions"), File(root, "exports"),
            availableBytes = { directory -> StatFs(directory.absolutePath).availableBytes })
        val metadata = if (practice == null) emptyMap() else mapOf(
            "scoreId" to requireNotNull(practice.scoreId), "scoreTitle" to practice.title,
            "revisionGeneration" to practice.revisionGeneration.toString())
        val session = store.start("TEST DATA binding controls", mapOf("testPurpose" to "UI_CONTROLS_NOT_RECOGNITION"), emptyMap(), metadata)
        actualStores[session.id] = store
        return session
    }
    @After fun removeOnlyThisTestsIsolatedSessionStores() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        ownedStoreRoots.forEach { root ->
            check(root.name.startsWith("field-binding-ui-") && root.canonicalFile.parentFile == cache && !Files.isSymbolicLink(root.toPath()))
            // Files.walk does not follow symbolic links; no shared app/session directory is traversed.
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
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
        val practice = practice().copy(phase = CompanionPhase.LOCKED, locked = true)
        val active = storedSession(practice)
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
        val practice = practice().copy(phase = CompanionPhase.PAUSED)
        val active = storedSession(practice)
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
    @Test fun configuredLeftHandStopOnErrorIncludesExplicitScoreReview() {
        val original = practice()
        val warning = ScoreIssue("field:test:warning", "TEST DATA 来源警告", ScoreIssueSeverity.WARNING)
        val practice = original.copy(timeline = requireNotNull(original.timeline).copy(issues = listOf(warning)), preparationIssues = listOf(warning))
        val configs = mutableListOf<PracticeConfig>()
        var legacyStarts = 0
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice,
            onStart = { _, _ -> legacyStarts++ }, onConfiguredStart = { _, config -> configs += config }) } }
        compose.onNodeWithTag("field_mode_STOP_ON_ERROR").performScrollTo().performClick()
        compose.onNodeWithTag("field_hand_LEFT").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_review_ack").performScrollTo().performClick()
        compose.onNodeWithTag("field_start").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(PracticeConfig(CompanionMode.STOP_ON_ERROR, reviewAcknowledged = true, hand = PracticeHand.LEFT)), configs)
        assertEquals("提供配置回调时不得再调用旧入口", 0, legacyStarts)
    }

    @Test fun configuredRightHandLoopKeepsSelectedModeInsteadOfDefaultingToBothHands() {
        val configs = mutableListOf<PracticeConfig>()
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice(),
            onConfiguredStart = { _, config -> configs += config }) } }
        compose.onNodeWithTag("field_mode_LOOP").performScrollTo().performClick()
        compose.onNodeWithTag("field_hand_RIGHT").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_start").performScrollTo().performClick()
        assertEquals(listOf(PracticeConfig(CompanionMode.LOOP, hand = PracticeHand.RIGHT)), configs)
    }

    @Test fun pausedPracticeKeepsItsExactConfigurationAndCannotReconfigureInFieldPage() {
        val expected = PracticeConfig(CompanionMode.REINFORCE, startMeasure = 0, endMeasure = 0, repeatTarget = 5,
            reviewAcknowledged = true, hand = PracticeHand.LEFT)
        val configs = mutableListOf<PracticeConfig>()
        val practice = practice().copy(phase = CompanionPhase.PAUSED, config = expected)
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice,
            onConfiguredStart = { _, config -> configs += config }) } }
        compose.onNodeWithTag("field_existing_config").performScrollTo().assertTextContains("强化易错片段 · 左手", substring = true)
        compose.onNodeWithTag("field_mode_FREE").assertDoesNotExist()
        compose.onNodeWithTag("field_hand_RIGHT").assertDoesNotExist()
        compose.onNodeWithTag("field_start").performScrollTo().performClick()
        assertEquals(listOf(expected), configs)
    }

    @Test fun scorePreparationErrorShowsCauseAndRepairEntryWhileReadyModelStillCannotStart() {
        val original = practice()
        val measure = requireNotNull(original.score).parts.first().measures.first()
        val issue = ScoreIssue("field:test:error", "TEST DATA 声部时值重叠", ScoreIssueSeverity.ERROR, measure.id, measure.events.first().id)
        val practice = original.copy(timeline = null, error = "练习准备失败：谱面有结构错误", preparationIssues = listOf(issue))
        var repairs = 0
        var starts = 0
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice,
            onConfiguredStart = { _, _ -> starts++ }, onPractice = { repairs++ }) } }
        compose.onNodeWithTag("field_score_problem").performScrollTo().assertTextContains("第 1 小节：TEST DATA 声部时值重叠", substring = true)
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_hand_LEFT").assertDoesNotExist()
        compose.onNodeWithTag("field_repair_score").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, repairs)
        assertEquals(0, starts)
    }

    @Test fun structuralErrorCannotPassFieldGateMerelyBecauseTimelineIsPresent() {
        val original = practice()
        val issue = ScoreIssue("field:test:error", "TEST DATA 时值不一致", ScoreIssueSeverity.ERROR)
        val practice = original.copy(timeline = requireNotNull(original.timeline).copy(issues = listOf(issue)))
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice,
            onConfiguredStart = { _, _ -> fail("结构 ERROR 不得开启实地测试") }) } }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_score_problem").performScrollTo().assertTextContains("TEST DATA 时值不一致", substring = true)
        compose.onNodeWithTag("field_review_ack").assertDoesNotExist()
    }

    @Test fun configuredSingleStaffCannotSelectLeftOrRightHand() {
        val original = practice()
        val score = requireNotNull(original.score).let { score -> score.copy(parts = score.parts.map { part ->
            part.copy(measures = part.measures.map { it.copy(events = it.events.filter { event -> event.staff == 1 },
                clefs = it.clefs.filter { clef -> clef.staff == 1 }, declaredStaves = 1) })
        }) }
        val practice = original.copy(score = score, timeline = ScoreTimelineCompiler.compile(score))
        val configs = mutableListOf<PracticeConfig>()
        compose.setContent { XiuyinTheme { TestScreen(diagnostics, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice,
            onConfiguredStart = { _, config -> configs += config }) } }
        compose.onNodeWithTag("field_hand_LEFT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_hand_RIGHT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_hand_BOTH").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("field_start").performScrollTo().performClick()
        assertEquals(PracticeHand.BOTH, configs.single().hand)
    }

    @Test fun anotherScoresActiveSessionBlocksCaptureAndFeedbackUntilRealSessionEnds() {
        val oldPractice = practice().copy(scoreId = "TEST DATA score A", revisionGeneration = 2)
        val active = storedSession(oldPractice)
        val source = practice()
        val warning = ScoreIssue("field:binding:warning", "TEST DATA 新谱待校对", ScoreIssueSeverity.WARNING)
        val nextPractice = source.copy(scoreId = "TEST DATA score B", revisionGeneration = 2,
            timeline = requireNotNull(source.timeline).copy(issues = listOf(warning)), preparationIssues = listOf(warning))
        val displayed = mutableStateOf(diagnostics.copy(activeSession = active, sessions = listOf(active)))
        val configs = mutableListOf<PracticeConfig>()
        var endRequests = 0
        val exports = mutableListOf<String>()
        compose.setContent { XiuyinTheme { TestScreen(displayed.value, RecognitionEngineSnapshot(RecognitionEngineStatus.READY), nextPractice,
            onConfiguredStart = { _, config -> configs += config }, onEndSession = { endRequests++ }, onExport = { exports += it }) } }
        compose.onNodeWithTag("field_binding_problem").performScrollTo().assertTextContains("与当前谱面或修订不同", substring = true)
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_feedback_dispute").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_feedback_confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_export_current").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(active.id), exports)
        compose.onNodeWithTag("field_binding_end_session").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, endRequests)
        assertTrue(configs.isEmpty())
        val store = actualStores.getValue(active.id)
        store.end("TEST_USER_EXPLICITLY_ENDED_OLD_SESSION")
        compose.runOnIdle { displayed.value = diagnostics.copy(activeSession = store.active(), sessions = store.sessions()) }
        compose.onNodeWithTag("field_binding_problem").assertDoesNotExist()
        compose.onNodeWithTag("field_hand_LEFT").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_review_ack").performScrollTo().performClick()
        compose.onNodeWithTag("field_start").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(PracticeConfig(hand = PracticeHand.LEFT, reviewAcknowledged = true)), configs)
    }

    @Test fun sameScoreDifferentRevisionCannotReuseTheActualSessionBinding() {
        val prior = practice().copy(revisionGeneration = 4)
        val active = storedSession(prior)
        val current = mutableStateOf(prior.copy(revisionGeneration = 5))
        var starts = 0
        compose.setContent { XiuyinTheme { TestScreen(diagnostics.copy(activeSession = active, sessions = listOf(active)),
            RecognitionEngineSnapshot(RecognitionEngineStatus.READY), current.value, onStart = { _, _ -> starts++ }) } }
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_binding_problem").performScrollTo().assertTextContains("修订 4", substring = true)
        compose.runOnIdle { current.value = prior.copy(phase = CompanionPhase.PAUSED) }
        compose.onNodeWithTag("field_binding_problem").assertDoesNotExist()
        compose.onNodeWithTag("field_start").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, starts)
    }

    @Test fun genuineLegacyManifestWithoutBindingOffersEndAndExportButNeverCapture() {
        val legacy = storedSession()
        assertNull(legacy.scoreId); assertNull(legacy.revisionGeneration)
        var endRequests = 0
        val exports = mutableListOf<String>()
        compose.setContent { XiuyinTheme { TestScreen(diagnostics.copy(activeSession = legacy, sessions = listOf(legacy)),
            RecognitionEngineSnapshot(RecognitionEngineStatus.READY), practice(), onEndSession = { endRequests++ }, onExport = { exports += it }) } }
        compose.onNodeWithTag("field_binding_problem").performScrollTo().assertTextContains("缺少可核对的谱面绑定", substring = true)
        compose.onNodeWithTag("field_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_feedback_dispute").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field_binding_end_session").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field_export_current").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, endRequests)
        assertEquals(listOf(legacy.id), exports)
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(state: FieldDiagnosticsState, engine: RecognitionEngineSnapshot, practice: CompanionState,
        onStart: (String, Boolean) -> Unit = { _, _ -> }, onRetry: () -> Unit = {},
        onFeedback: (FieldFeedbackLabel) -> Unit = {}, onExport: (String) -> Unit = {}, onClear: (Set<String>) -> Unit = {},
        appDataAvailable: Boolean = true, appDataError: String? = null,
        onConfiguredStart: ((String, PracticeConfig) -> Unit)? = null, onPractice: () -> Unit = {}, onEndSession: () -> Unit = {}) {
        FieldTestScreen(state, FieldOperationState(), AudioSnapshot(), engine, null, practice, true, true,
            onStart, {}, onEndSession, onRetry, {}, onFeedback, onExport, {}, {}, onClear, {}, {}, onPractice, {}, {},
            appDataAvailable = appDataAvailable, appDataError = appDataError, onConfiguredStart = onConfiguredStart)
    }
}