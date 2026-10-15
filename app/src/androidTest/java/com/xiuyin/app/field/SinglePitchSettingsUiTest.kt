package com.xiuyin.app.field

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import com.xiuyin.app.ui.XiuyinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** TEST DATA: verifies an opt-in control; does not claim acoustic or device performance. */
class SinglePitchSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun referenceStartsDisabledAndCannotChangeDuringActiveSession() {
        val enabled = mutableStateOf(false)
        val allowed = mutableStateOf(true)
        var changes = 0
        compose.setContent { XiuyinTheme {
            FieldSettingsScreen({}, {}, enabled.value, allowed.value) {
                changes++
                enabled.value = it
            }
        } }
        compose.onNodeWithTag("single_pitch_reference_toggle").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithTag("single_pitch_reference_toggle").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, changes) }
    }
}
