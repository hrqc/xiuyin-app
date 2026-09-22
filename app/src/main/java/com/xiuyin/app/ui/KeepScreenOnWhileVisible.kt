package com.xiuyin.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A window flag only: never changes the user's display timeout or system settings. */
@Composable
fun KeepScreenOnWhileVisible(activity: Activity, enabled: Boolean) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(activity, owner, enabled) {
        fun update() {
            if (enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}