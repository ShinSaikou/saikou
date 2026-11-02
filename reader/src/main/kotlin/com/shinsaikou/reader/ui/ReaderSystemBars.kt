package com.shinsaikou.reader.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.ColorInt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.settings.ReaderBackground
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import kotlinx.coroutines.flow.sample
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.max
import kotlin.math.min
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
    val readerTheme by preferences.readerTheme.collectAsState()
    val colorFilter by preferences.colorFilter.collectAsState()
    val colorFilterValue by preferences.colorFilterValue.collectAsState()
    val colorFilterMode by preferences.colorFilterMode.collectAsState()

    val controller = remember(window, view) { WindowInsetsControllerCompat(window, view) }

    DisposableEffect(controller) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {}
    }

    // The bars' icons read against what is behind them: the app bars while the menu shows, the
    // page while it does not (the bars stay out unless fullscreen is off), with the brightness dim
    // and the colour filter the overlay paints over the bars' area too. Both were light whatever
    // the theme, which left white icons on the light theme's grey bars. The night mode is the
    // configuration's, which recomposes this when the activity takes a uiMode change without being
    // recreated, and the flags are set again on every configuration change, after which
    // enableEdgeToEdge puts its own back.
    val configuration = LocalConfiguration.current
    val isNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val chromeIsDark = prefersLightIcons(MaterialTheme.colorScheme.surface)
    val page = pageColorBehindBars(
        background = ReaderBackground.fromPreference(readerTheme).color(isNight),
        brightness = state.brightnessOverlayValue,
        filter = colorFilterValue.takeIf { colorFilter },
        mode = ReaderSettingsOptions.colorFilterModeOrNull(colorFilterMode)?.second ?: BlendMode.SrcOver,
    )
    val darkBehindBars = if (state.menuVisible) chromeIsDark else prefersLightIcons(page)
    LaunchedEffect(controller, darkBehindBars, configuration) {
        controller.isAppearanceLightStatusBars = !darkBehindBars
        // Below API 29 the platform keeps its scrim behind the navigation bar, light or dark with
        // the night mode, so its icons follow the scrim rather than the page.
        controller.isAppearanceLightNavigationBars =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !darkBehindBars else !isNight
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

/**
 * Whether light icons read better on [color] than dark ones: its contrast ratio against white beats
 * the one against black. The two cross at a luminance of about 0.18, not at the middle, so a white
 * page dimmed to -30 (0.45) keeps dark icons at 10:1 rather than taking white ones at 2:1.
 */
internal fun prefersLightIcons(color: Color): Boolean {
    val luminance = color.luminance()
    return (1f + CONTRAST_OFFSET) / (luminance + CONTRAST_OFFSET) > (luminance + CONTRAST_OFFSET) / CONTRAST_OFFSET
}

/** The flare term of the WCAG contrast ratio. */
private const val CONTRAST_OFFSET = 0.05f

/**
 * The colour behind the bars while the menu is closed: [background] under the brightness dim, black
 * at `-brightness` percent for a negative one, and then the colour [filter] drawn with [mode], as
 * `ReaderContentOverlay` paints them.
 */
internal fun pageColorBehindBars(
    @ColorInt background: Int,
    brightness: Int,
    @ColorInt filter: Int?,
    mode: BlendMode = BlendMode.SrcOver,
): Color {
    var color = Color(background)
    if (brightness < 0) color = Color.Black.copy(alpha = -brightness / 100f).compositeOver(color)
    if (filter != null) color = blendOver(Color(filter), color, mode)
    return color
}

/**
 * [source] drawn with [mode] over an opaque [backdrop], channel by channel as Skia blends them: the
 * separable modes put the blend over the backdrop at the source's alpha, and Modulate is the plain
 * product, whose alpha below one an opaque window shows as it stands, so it darkens by the alpha.
 * The modes are the reader's colour filter's (`ReaderSettingsOptions.ColorFilterMode`).
 */
private fun blendOver(source: Color, backdrop: Color, mode: BlendMode): Color {
    val a = source.alpha
    fun channel(s: Float, d: Float): Float = when (mode) {
        BlendMode.Modulate -> a * s * d
        BlendMode.Screen -> d + a * s - a * s * d
        BlendMode.Overlay -> (1 - a) * d + a * if (d <= HALF) 2 * s * d else 1 - 2 * (1 - s) * (1 - d)
        BlendMode.Lighten -> (1 - a) * d + a * max(s, d)
        BlendMode.Darken -> (1 - a) * d + a * min(s, d)
        else -> (1 - a) * d + a * s
    }
    return Color(
        red = channel(source.red, backdrop.red),
        green = channel(source.green, backdrop.green),
        blue = channel(source.blue, backdrop.blue),
    )
}

private const val HALF = 0.5f
private val BRIGHTNESS_SAMPLE = 100.milliseconds
