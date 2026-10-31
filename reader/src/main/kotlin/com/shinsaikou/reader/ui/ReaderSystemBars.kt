package com.shinsaikou.reader.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import kotlinx.coroutines.flow.sample
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every window side effect the reader has, in one composable, so there is exactly one writer per
 * window attribute. Contract 07 part 2 section 5.4.
 *
 * Two things it must not do: call `SaikouTheme`, which owns the bar colours and
 * `isNavigationBarContrastEnforced` for the rest of the app, and re-run `enableEdgeToEdge`. Both
 * belong to `ReaderActivity.onCreate` and to `ReaderTheme`'s absence of window effects.
 *
 * Upstream's display-profile branch (an ICC file passed to the image view) is deleted rather than
 * stubbed: the image view fork exposes no such setter and the preference is not in the reader's set
 * (decision D19).
 */
@Composable
fun ReaderSystemBars(
    state: ReaderState,
    preferences: ReaderPreferences,
    onEvent: (ReaderEvent) -> Unit,
) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val window = activity.window

    val fullscreen by preferences.fullscreen.collectAsState()
    val keepScreenOn by preferences.keepScreenOn.collectAsState()
    val customBrightness by preferences.customBrightness.collectAsState()

    val controller = remember(window, view) { WindowInsetsControllerCompat(window, view) }

    DisposableEffect(controller) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // The reader chrome is dark over a dark scrim in every reader theme, including the white
        // one, because the bars sit over the app bars' surface and not over the page.
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {}
    }

    // Upstream re-applies this from onCreate, onResume and onWindowFocusChanged to survive rotation and
    // focus races; the key plus the resume event cover the same three entry points.
    LaunchedEffect(state.menuVisible, fullscreen) {
        applyBarVisibility(controller, state.menuVisible, fullscreen)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        applyBarVisibility(controller, state.menuVisible, fullscreen)
    }

    LaunchedEffect(keepScreenOn) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(customBrightness) {
        if (!customBrightness) {
            setScreenBrightness(activity, 0)
            onEvent(ReaderEvent.BrightnessChanged(0))
            return@LaunchedEffect
        }
        preferences.customBrightnessValue.changes()
            .sample(BRIGHTNESS_SAMPLE)
            .collect { value ->
                setScreenBrightness(activity, value)
                onEvent(ReaderEvent.BrightnessChanged(value))
            }
    }
}

private fun applyBarVisibility(
    controller: WindowInsetsControllerCompat,
    menuVisible: Boolean,
    fullscreen: Boolean,
) {
    val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
    if (menuVisible || !fullscreen) {
        controller.show(bars)
    } else {
        controller.hide(bars)
    }
}

/**
 * Upstream's three-way rule: a positive value is the brightness, a negative one pins the screen to its
 * minimum and lets `ReaderContentOverlay` paint the rest of the dim, and 0 hands the screen back to
 * the system.
 */
private fun setScreenBrightness(activity: Activity, value: Int) {
    val attributes = activity.window.attributes
    attributes.screenBrightness = when {
        value > 0 -> value / 100f
        value < 0 -> MIN_BRIGHTNESS
        else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    activity.window.attributes = attributes
}

private const val MIN_BRIGHTNESS = 0.01f
private val BRIGHTNESS_SAMPLE = 100.milliseconds
