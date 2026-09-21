package com.xiuyin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiuyin.app.audio.AudioSnapshot
import com.xiuyin.app.audio.AudioStatus
import com.xiuyin.app.audio.FinalDecision
import com.xiuyin.app.audio.OnsetState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit TEST DATA rendering tests. They never open AudioRecord or request system permission. */
@RunWith(AndroidJUnit4::class)
class AudioInputCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingControlsOnlyInvokeCallbacksAfterAnExplicitTap() {
        var audio by mutableStateOf(AudioSnapshot(statusMessage = "TEST DATA · 尚未启动"))
        var starts = 0
        var stops = 0
        var settings = 0
        renderCard(
            audio = { audio },
            onStart = {
                starts++
                audio = audio.copy(status = AudioStatus.STARTING, statusMessage = "TEST DATA · 等待输入")
            },
            onStop = {
                stops++
                audio = AudioSnapshot(statusMessage = "TEST DATA · 已停止")
            },
            onSettings = { settings++ },
        )
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(0, stops)
            assertEquals(0, settings)
        }
        compose.onNodeWithTag("audio_status").assertTextEquals("⚪ 未运行 · 尚未开始采集")
        compose.onNodeWithTag("audio_stop").assertDoesNotExist()
        compose.onNodeWithTag("audio_start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("audio_status").assertTextEquals("🟡 正在启动 · 等待有效输入")
        compose.onNodeWithTag("audio_start").assertDoesNotExist()
        compose.onNodeWithTag("audio_stop").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("audio_start").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertEquals(0, settings)
        }
    }

    @Test
    fun deniedPermissionExplainsRecoveryAndDoesNotOpenSettingsAutomatically() {
        var starts = 0
        var settings = 0
        renderCard(
            audio = {
                AudioSnapshot(
                    status = AudioStatus.PERMISSION_DENIED,
                    statusMessage = "TEST DATA · 用户拒绝了麦克风权限",
                )
            },
            onStart = { starts++ },
            onStop = {},
            onSettings = { settings++ },
        )
        compose.onNodeWithTag("audio_status").assertTextEquals("🟡 麦克风权限未允许")
        compose.onNodeWithText("麦克风权限未允许。可重新申请；如果系统不再弹出授权框，请打开系统权限设置。")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, settings) }
        compose.onNodeWithText("打开系统权限设置").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, settings)
            assertEquals(0, starts)
        }
        compose.onNodeWithTag("audio_start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithTag("audio_final_decision").performScrollTo()
            .assertTextEquals("暂无法判断（UNCERTAIN）")
    }

    @Test
    fun inputEvidenceIsDisplayedWithoutInventingNotesOrCorrectDecisions() {
        var audio by mutableStateOf(
            AudioSnapshot(
                status = AudioStatus.LISTENING,
                statusMessage = "TEST DATA · 已收到输入帧",
                deviceName = "TEST DATA · 内置麦克风",
                totalFrames = 60,
                capturedAtNanos = 1_234_567_890L,
                inputLevelDbfs = -8.0,
                noiseFloorDbfs = -60.5,
                onsetState = OnsetState.SUSTAIN,
                onsetConfidence = 0.88,
                onsetCount = 1,
                notes = emptyList(),
                decision = FinalDecision.UNCERTAIN,
                processingMs = 0.42,
            ),
        )
        var stops = 0
        renderCard(
            audio = { audio },
            onStart = {},
            onStop = {
                stops++
                audio = audio.copy(status = AudioStatus.STOPPING, statusMessage = "TEST DATA · 正在释放")
            },
            onSettings = {},
        )
        compose.onNodeWithTag("audio_status").assertTextEquals("🟢 正常 · 正在采集输入")
        compose.onNodeWithTag("audio_input_level").performScrollTo().assertTextEquals("-8.0 dBFS")
        compose.onNodeWithTag("audio_noise_floor").performScrollTo().assertTextEquals("-60.5 dBFS")
        compose.onNodeWithTag("audio_onset_state").performScrollTo()
            .assertTextEquals("持续输入 · 不重复累计起音")
        compose.onNodeWithTag("audio_onset_count").performScrollTo().assertTextEquals("1")
        compose.onNodeWithTag("audio_onset_confidence").performScrollTo().assertTextEquals("0.88")
        compose.onNodeWithTag("audio_detected_notes").performScrollTo().assertTextEquals("暂无（输入诊断不进行音符判定）")
        compose.onNodeWithTag("audio_final_decision").performScrollTo().assertTextEquals("暂无法判断（UNCERTAIN）")
        compose.onNodeWithText("正确（CORRECT）").assertDoesNotExist()
        compose.onNodeWithText("错误（WRONG）").assertDoesNotExist()

        compose.runOnIdle { audio = audio.copy(clipped = true, onsetState = OnsetState.UNCERTAIN, onsetConfidence = null) }
        compose.onNodeWithText("输入过强，出现削波。请适当远离钢琴或减小输入响度；当前信号不用于确认起音。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio_final_decision").assertTextEquals("暂无法判断（UNCERTAIN）")
        compose.onNodeWithTag("audio_stop").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun aListeningFlagWithoutInputFramesDoesNotShowHealthyCapture() {
        renderCard(
            audio = { AudioSnapshot(status = AudioStatus.LISTENING, statusMessage = "TEST DATA · 尚无音频帧") },
            onStart = {},
            onStop = {},
            onSettings = {},
        )
        compose.onNodeWithTag("audio_status").assertTextEquals("🟡 等待输入 · 尚无有效帧")
        compose.onNodeWithTag("audio_input_level").performScrollTo().assertTextEquals("尚未采集")
        compose.onNodeWithTag("audio_onset_count").performScrollTo().assertTextEquals("尚未采集")
        compose.onNodeWithTag("audio_final_decision").performScrollTo().assertTextEquals("暂无法判断（UNCERTAIN）")
    }

    private fun renderCard(
        audio: () -> AudioSnapshot,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onSettings: () -> Unit,
    ) {
        compose.setContent {
            XiuyinTheme {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("TEST DATA · 音频状态界面测试，不使用实际麦克风")
                    AudioInputCard(audio(), onStart, onStop, onSettings)
                }
            }
        }
    }
}