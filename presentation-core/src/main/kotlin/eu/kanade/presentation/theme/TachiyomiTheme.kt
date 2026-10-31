// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt (modified: yes)
package eu.kanade.presentation.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ThemeMode
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.TokyoNightColorScheme
import eu.kanade.presentation.theme.colorscheme.SaikouColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme

/**
 * The app theme. Light, dark and, when [amoled] is on and the resolved mode is dark, a pure black
 * dark variant whose system bars are painted to match (see [PureBlackSystemBars]).
 */
@Composable
fun SaikouTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    amoled: Boolean = false,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    PureBlackSystemBars(isDark = isDark, isPureBlack = isDark && amoled)
    BaseSaikouTheme(appTheme = appTheme, isDark = isDark, isAmoled = amoled, content = content)
}

/** The theme without the window side effects, for `@Preview` functions. */
@Composable
fun SaikouPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isDark: Boolean = false,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseSaikouTheme(appTheme, isDark, isAmoled, content)

/**
 * The colour scheme, with Material's own type and shapes as upstream's theme has them, and with no
 * window side effects and no theme-mode resolution.
 *
 * Use this wherever something other than the app shell owns the system bars. The reader is the
 * case that matters: `ReaderSystemBars` hides and shows them with the chrome, and [SaikouTheme]'s
 * `SideEffect` would rewrite them on every recomposition and fight it. Callers resolve `isDark`
 * themselves, which the reader has to do anyway because its background is its own preference.
 *
 * [SaikouPreviewTheme] is this function under a name that says "preview". Do not reach for that one
 * in production code just because it happens to behave correctly.
 */
@Composable
fun BaseSaikouTheme(
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = remember(appTheme, isDark, isAmoled) {
            getThemeColorScheme(
                context = context,
                appTheme = appTheme,
                isDark = isDark,
                isAmoled = isAmoled,
            )
        },
        content = content,
    )
}

/**
 * Upstream's pure black theme leaves the platform scrim behind the navigation bar in place, so the
 * gesture pill or the three buttons sit on a grey band that reads as a rendering fault against a
 * black page (upstream issues 1011 and 1330). Edge to edge comes from `MainActivity`, which calls
 * `enableEdgeToEdge` before anything is drawn (Android 15 and later force it anyway); this clears
 * both bar colours and turns contrast enforcement off while pure black is showing, so the black
 * content draws all the way through, and restores the enforcement for every other theme.
 */
@Composable
private fun PureBlackSystemBars(isDark: Boolean, isPureBlack: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.findActivity()?.window ?: return
    SideEffect {
        window.setTransparentBars(isPureBlack)
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

@Suppress("DEPRECATION")
private fun Window.setTransparentBars(transparent: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // True is the platform default: a scrim so the buttons stay legible over light content.
        isNavigationBarContrastEnforced = !transparent
    }
    // No-ops from API 35 on, where the platform always draws the bars transparent.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM && transparent) {
        statusBarColor = AndroidColor.TRANSPARENT
        navigationBarColor = AndroidColor.TRANSPARENT
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The scheme [BaseSaikouTheme] draws with, outside a composition. Public since unit ID3; the reader's
 * device check resolves the theme's colours through it.
 */
fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val colorScheme = baseColorScheme(appTheme) { MonetColorScheme(context) }
    return colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
}

/**
 * Upstream's `colorSchemes` map as an exhaustive `when`, so a new [AppTheme] without a scheme is a
 * compile error rather than upstream's fallback to the default. [monet] builds Dynamic, the one scheme
 * that needs a `Context`; taking it as a function keeps the rest testable on the JVM.
 */
internal fun baseColorScheme(appTheme: AppTheme, monet: () -> BaseColorScheme): BaseColorScheme = when (appTheme) {
    AppTheme.MONET -> monet()
    AppTheme.DEFAULT -> SaikouColorScheme
    AppTheme.CATPPUCCIN -> CatppuccinColorScheme
    AppTheme.TOKYONIGHT -> TokyoNightColorScheme
    AppTheme.GREEN_APPLE -> GreenAppleColorScheme
    AppTheme.LAVENDER -> LavenderColorScheme
    AppTheme.MIDNIGHT_DUSK -> MidnightDuskColorScheme
    AppTheme.MONOCHROME -> MonochromeColorScheme
    AppTheme.NORD -> NordColorScheme
    AppTheme.STRAWBERRY_DAIQUIRI -> StrawberryColorScheme
    AppTheme.TAKO -> TakoColorScheme
    AppTheme.TEALTURQUOISE -> TealTurqoiseColorScheme
    AppTheme.TIDAL_WAVE -> TidalWaveColorScheme
    AppTheme.YINYANG -> YinYangColorScheme
    AppTheme.YOTSUBA -> YotsubaColorScheme
}
