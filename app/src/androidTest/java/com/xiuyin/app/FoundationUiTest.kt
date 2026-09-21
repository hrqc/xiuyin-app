package com.xiuyin.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.data.AppDatabase
import com.xiuyin.app.data.PracticeMode
import com.xiuyin.app.data.PracticePreparation
import com.xiuyin.app.data.SampleLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Compose + SQLite integration tests on an Android device/emulator.
 * No microphone input or score-following behavior is simulated here.
 * Each test navigates explicitly and tolerates existing application history.
 */
@RunWith(AndroidJUnit4::class)
class FoundationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun browseScoreSavePreparationAndRestoreAfterActivityRecreation() {
        openFirstScoreFromHome()
        compose.onNodeWithText("原创示例谱 · 静态预览 · TEST DATA")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("设置练习模式").performScrollTo().performClick()
        waitForText("练习设置预览")
        compose.onNodeWithText("强化训练").performScrollTo().performClick()

        // First save another value so a previous test run's five-count record
        // cannot make the persistence assertion pass without a fresh write.
        saveRepeatTarget(2)
        saveRepeatTarget(5)
        compose.onNodeWithText(SAVED_MESSAGE).performScrollTo().assertIsDisplayed()
        assertStoredPreparation(5)

        compose.activityRule.scenario.recreate()
        waitForText("练习设置预览")
        compose.onNodeWithText("强化训练").performScrollTo().assertIsSelected()
        compose.onNodeWithText("5 次").performScrollTo().assertIsSelected()
        compose.onNodeWithText(SAVED_MESSAGE).performScrollTo().assertIsDisplayed()
        assertStoredPreparation(5)

        // Saved settings and the recently opened score must survive navigation.
        compose.onNodeWithTag("nav_home").performClick()
        waitForText("最近打开")
        compose.onNodeWithText("最近打开").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(SampleLibrary.pieces.first().title)
            .onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun systemBackReturnsFromScoreToLibraryAndThenHome() {
        openFirstScoreFromHome()
        compose.onNodeWithText("设置练习模式").performScrollTo().assertIsDisplayed()

        pressSystemBack()
        compose.onNodeWithTag("nav_library").assertIsSelected()
        compose.onAllNodesWithText("查看乐谱").onFirst()
            .performScrollTo().assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)

        pressSystemBack()
        compose.onNodeWithTag("nav_home").assertIsSelected()
        compose.onNodeWithText("浏览本地曲库").performScrollTo().assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    @Suppress("DEPRECATION") // GET_PERMISSIONS keeps this test compatible with minSdk 26.
    fun foundationOnlyDeclaresMicrophoneAndNeverRequestsNetworkOrCameraPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
        listOf(Manifest.permission.INTERNET, Manifest.permission.CAMERA)
            .forEach { permission ->
                assertFalse("Local audio version must not request $permission", permission in requested)
            }
        assertTrue("Explicit microphone checks require RECORD_AUDIO in the manifest", Manifest.permission.RECORD_AUDIO in requested)
        assertEquals("com.xiuyin.app", context.packageName)
    }

    @Test
    fun monitorShowsInactiveEnginesAndControlsRealStoredLogs() {
        waitForText("浏览本地曲库")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "INSTRUMENTATION TEST DATA ${System.nanoTime()}"
        val firstMessage = "$marker before pause"
        val secondMessage = "$marker after pause"
        val originalPreparation: PracticePreparation?
        val originalRecent: String?
        AppDatabase(context).use { database ->
            originalPreparation = database.loadPreparation()
            originalRecent = database.recentPieceId()
            database.appendLog("INFO", "UiTest", firstMessage)
        }

        compose.onNodeWithTag("nav_monitor").performClick()
        waitForText("开发者监控")
        scrollMonitorTo("⚪ 未运行 · 尚未开始采集")
        compose.onNodeWithText("⚪ 未运行 · 尚未开始采集")
            .performScrollTo().assertIsDisplayed()
        scrollMonitorTo("Live Logs")
        compose.onNodeWithText("搜索日志").performTextInput(marker)
        waitForText("匹配 1 条")
        scrollMonitorTo(firstMessage)
        compose.onNodeWithText(firstMessage).assertIsDisplayed()

        scrollMonitorTo("Live Logs")
        compose.onNodeWithText("暂停日志").performScrollTo().performClick()
        compose.onNodeWithText("已暂停 · 查看冻结快照").assertIsDisplayed()
        AppDatabase(context).use { database ->
            database.appendLog("INFO", "UiTest", secondMessage)
        }
        scrollMonitorTo("刷新状态")
        compose.onNodeWithText("刷新状态").performScrollTo().performClick()
        scrollMonitorTo("Live Logs")
        compose.onNodeWithText("搜索日志").performTextReplacement(secondMessage)
        waitForText("匹配 0 条")
        compose.onNodeWithText("继续日志").performScrollTo().performClick()
        waitForText("匹配 1 条")
        scrollMonitorTo(secondMessage)
        compose.onNodeWithText(secondMessage).assertIsDisplayed()

        scrollMonitorTo("Live Logs")
        compose.onNodeWithText("ERROR").performScrollTo().performClick()
        waitForText("匹配 0 条")
        compose.onNodeWithText("全部").performScrollTo().performClick()
        waitForText("匹配 1 条")
        compose.onNodeWithText("清空日志").performScrollTo().performClick()
        compose.onNodeWithText("确认清空").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        AppDatabase(context).use { database ->
            assertTrue("Cancel must retain diagnostic history", database.logs().any { it.message == secondMessage })
        }

        compose.onNodeWithText("清空日志").performScrollTo().performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            AppDatabase(context).use { it.logs().isEmpty() }
        }
        waitForText("匹配 0 条")
        scrollMonitorTo("暂无日志")
        compose.onNodeWithText("暂无日志").assertIsDisplayed()
        AppDatabase(context).use { database ->
            assertEquals("Clearing logs must preserve saved preparation", originalPreparation, database.loadPreparation())
            assertEquals("Clearing logs must preserve recent score", originalRecent, database.recentPieceId())
        }
    }

    @Test
    fun openingPracticeDoesNotStartOrAutomaticallyRequestMicrophone() {
        waitForText("浏览本地曲库")
        compose.onNodeWithTag("nav_practice").performClick()
        waitForText("麦克风检查")
        compose.onNodeWithTag("audio_status").performScrollTo()
            .assertTextEquals("⚪ 未运行 · 尚未开始采集")
        compose.onNodeWithTag("audio_start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("audio_stop").assertDoesNotExist()
        compose.onNodeWithTag("audio_final_decision").performScrollTo()
            .assertTextEquals("暂无法判断（UNCERTAIN）")
    }

    private fun scrollMonitorTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun openFirstScoreFromHome() {
        waitForText("浏览本地曲库")
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithText("浏览本地曲库").performScrollTo().performClick()
        waitForText("查看乐谱")
        compose.onAllNodesWithText("查看乐谱").onFirst()
            .performScrollTo().performClick()
        waitForText("设置练习模式")
        compose.onNodeWithText(SampleLibrary.pieces.first().title).assertIsDisplayed()
    }

    private fun saveRepeatTarget(count: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = PracticePreparation(
            SampleLibrary.pieces.first().id,
            PracticeMode.REINFORCE,
            count,
        )
        var stage = "waiting for previous save to finish"
        try {
            // Database equality alone can be true before this click when a
            // prior run already saved the same value. Observe UI readiness
            // and a NEW persisted completion log for every requested write.
            waitForSaveButtonReady()
            stage = "selecting repeat target $count"
            compose.onNodeWithText("$count 次").performScrollTo()
                .assertIsEnabled().performClick().assertIsSelected()
            compose.onNodeWithText("强化训练").assertIsSelected()

            val priorLogId = AppDatabase(context).use { database ->
                database.logs().maxOfOrNull { it.id } ?: 0L
            }
            stage = "clicking save for $expected"
            compose.onNodeWithText("保存练习设置").performScrollTo()
                .assertIsEnabled().performClick()
            stage = "waiting for new completion log, stored target and enabled UI"
            compose.waitUntil(timeoutMillis = 10_000) {
                val writeCompleted = AppDatabase(context).use { database ->
                    database.loadPreparation() == expected && database.logs().any { entry ->
                        entry.id > priorLogId && entry.module == "Practice" &&
                            entry.message == "练习设置已保存：强化训练 / $count 次；保存操作不启动音频"
                    }
                }
                writeCompleted && saveButtonIsReady() &&
                    compose.onAllNodesWithText(SAVED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }
            stage = "checking the completed UI and database value"
            compose.onNodeWithText("保存练习设置").assertIsEnabled()
            compose.onNodeWithText("$count 次").assertIsSelected()
            compose.onNodeWithText("强化训练").assertIsSelected()
            assertStoredPreparation(count)
        } catch (failure: Throwable) {
            val storedState = runCatching {
                AppDatabase(context).use { database ->
                    "preparation=${database.loadPreparation()}; recent=${database.recentPieceId()}; " +
                        "latestLogs=${database.logs().take(8)}"
                }
            }.getOrElse { "Could not read diagnostics: $it" }
            val uiState = runCatching {
                compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8)
            }.getOrElse { "Could not read semantics: $it" }
            throw AssertionError(
                "saveRepeatTarget($count) failed during $stage\n$storedState\n$uiState",
                failure,
            )
        }
    }

    private fun waitForSaveButtonReady() {
        compose.waitUntil(timeoutMillis = 10_000) { saveButtonIsReady() }
    }

    private fun saveButtonIsReady(): Boolean =
        compose.onAllNodes(hasText("保存练习设置") and isEnabled())
            .fetchSemanticsNodes().size == 1

    private fun assertStoredPreparation(count: Int) {
        AppDatabase(InstrumentationRegistry.getInstrumentation().targetContext).use { database ->
            assertTrue("SQLite health check must pass after UI save", database.healthCheck())
            assertEquals(
                PracticePreparation(SampleLibrary.pieces.first().id, PracticeMode.REINFORCE, count),
                database.loadPreparation(),
            )
            assertEquals(SampleLibrary.pieces.first().id, database.recentPieceId())
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private companion object {
        const val SAVED_MESSAGE = "当前设置已保存至本机。关闭 App 后仍可恢复。"
    }
}