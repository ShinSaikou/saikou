// Fresh: Saikou's own palette, in the vendored package so it can extend the internal BaseColorScheme.
package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The colours of `AppTheme.DEFAULT`, the theme labelled "Saikou": direction A of
 * `design/plans/look-rethink/direction.md` ("Upstream, signed"), an upstream theme in the brand's red with
 * warm-neutral surfaces. Every Material 3 role is set here, so nothing falls back to Material's
 * baseline purple. Pure black is not a third scheme: [BaseColorScheme.getColorScheme] derives it
 * from [darkScheme] as it does for every upstream theme.
 *
 * Named in direction A's table and copied hex by hex: primary, onPrimary, primaryContainer,
 * onPrimaryContainer, secondary, secondaryContainer, tertiary, background and surface,
 * surfaceContainer, surfaceContainerHigh, onSurface, onSurfaceVariant, outline, outlineVariant and
 * error. Every pair of the contrast test passes with these values, so none moved.
 *
 * Derived, because the table does not name them, the way upstream's blue `TachiyomiColorScheme`
 * relates its own roles:
 * - onSecondary and onSecondaryContainer are onPrimary and onPrimaryContainer, as secondary is
 *   primary; onBackground is onSurface.
 * - inversePrimary is the other mode's primary; surfaceTint is primary.
 * - The tertiary roles are upstream's own, because the table's tertiary is upstream's green.
 * - onError and the error containers are Material 3's baseline, as upstream's schemes have them.
 * - The neutral roles take Material 3's tones on the table's warm neutral (CIELAB a* 0.83,
 *   b* 1.93, the hue of light surfaceContainerHigh and dark onSurface): light lowest 100, low 96,
 *   highest 90, dim 87, inverseSurface 20, inverseOnSurface 95; dark lowest 4, low 10, highest 22,
 *   bright 24, inverseOnSurface 20. surfaceVariant is surfaceContainer, as upstream's is; surfaceBright
 *   in light and surfaceDim in dark are surface; dark inverseSurface is onSurface; scrim is black.
 * - The fixed roles follow Material 3's tones of the light scheme, the same in both modes:
 *   primaryFixed tone 90, primaryFixedDim tone 80, onPrimaryFixed tone 10, onPrimaryFixedVariant
 *   tone 30, and likewise for secondary and tertiary.
 */
internal object SaikouColorScheme : BaseColorScheme() {

    // Light, direction A's table.
    private val Primary = Color(0xFFC43B20)
    private val OnPrimary = Color(0xFFFFFFFF)
    private val PrimaryContainer = Color(0xFFFFDBD1)
    private val OnPrimaryContainer = Color(0xFF3E0A00)
    private val Tertiary = Color(0xFF006E1B)
    private val Surface = Color(0xFFFCFAF8)
    private val SurfaceContainer = Color(0xFFF1EEEB)
    private val SurfaceContainerHigh = Color(0xFFECE8E5)
    private val OnSurface = Color(0xFF1C1B1A)
    private val OnSurfaceVariant = Color(0xFF514946)
    private val Outline = Color(0xFF837A76)
    private val OutlineVariant = Color(0xFFD5CDC9)
    private val Error = Color(0xFFBA1A1A)

    // Dark, direction A's table.
    private val DarkPrimary = Color(0xFFFFB4A1)
    private val DarkOnPrimary = Color(0xFF601400)
    private val DarkPrimaryContainer = Color(0xFF872A12)
    private val DarkOnPrimaryContainer = Color(0xFFFFDBD1)
    private val DarkTertiary = Color(0xFF7ADC77)
    private val DarkSurface = Color(0xFF141312)
    private val DarkSurfaceContainer = Color(0xFF1F1D1C)
    private val DarkSurfaceContainerHigh = Color(0xFF2A2726)
    private val DarkOnSurface = Color(0xFFE6E2DF)
    private val DarkOnSurfaceVariant = Color(0xFFD3C8C3)
    private val DarkOutline = Color(0xFF9B908B)
    private val DarkOutlineVariant = Color(0xFF4F4744)
    private val DarkError = Color(0xFFFFB4AB)

    // Upstream's tertiary tones, which go with its green.
    private val TertiaryContainer = Color(0xFF95F990)
    private val OnTertiaryContainer = Color(0xFF002203)
    private val DarkOnTertiary = Color(0xFF003909)
    private val DarkTertiaryContainer = Color(0xFF005312)

    // The warm neutral at Material 3's tones.
    private val Neutral20 = Color(0xFF33302E)
    private val Neutral95 = Color(0xFFF4F0ED)

    override val lightScheme = lightColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        inversePrimary = DarkPrimary,
        secondary = Primary, // Unread badge
        onSecondary = OnPrimary, // Unread badge text
        secondaryContainer = PrimaryContainer, // Navigation bar selector pill
        onSecondaryContainer = OnPrimaryContainer, // Navigation bar selector icon
        tertiary = Tertiary, // Downloaded badge
        onTertiary = OnPrimary, // Downloaded badge text
        tertiaryContainer = TertiaryContainer,
        onTertiaryContainer = OnTertiaryContainer,
        background = Surface,
        onBackground = OnSurface,
        surface = Surface,
        onSurface = OnSurface,
        surfaceVariant = SurfaceContainer, // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = OnSurfaceVariant,
        surfaceTint = Primary,
        inverseSurface = Neutral20,
        inverseOnSurface = Neutral95,
        error = Error,
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Outline,
        outlineVariant = OutlineVariant,
        scrim = Color(0xFF000000),
        surfaceBright = Surface,
        surfaceContainer = SurfaceContainer, // Navigation bar background
        surfaceContainerHigh = SurfaceContainerHigh,
        surfaceContainerHighest = Color(0xFFE6E2DF),
        surfaceContainerLow = Color(0xFFF7F3F0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDDD9D6),
        primaryFixed = PrimaryContainer,
        primaryFixedDim = DarkPrimary,
        onPrimaryFixed = OnPrimaryContainer,
        onPrimaryFixedVariant = DarkPrimaryContainer,
        secondaryFixed = PrimaryContainer,
        secondaryFixedDim = DarkPrimary,
        onSecondaryFixed = OnPrimaryContainer,
        onSecondaryFixedVariant = DarkPrimaryContainer,
        tertiaryFixed = TertiaryContainer,
        tertiaryFixedDim = DarkTertiary,
        onTertiaryFixed = OnTertiaryContainer,
        onTertiaryFixedVariant = DarkTertiaryContainer,
    )

    override val darkScheme = darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        inversePrimary = Primary,
        secondary = DarkPrimary, // Unread badge
        onSecondary = DarkOnPrimary, // Unread badge text
        secondaryContainer = DarkPrimaryContainer, // Navigation bar selector pill
        onSecondaryContainer = DarkOnPrimaryContainer, // Navigation bar selector icon
        tertiary = DarkTertiary, // Downloaded badge
        onTertiary = DarkOnTertiary, // Downloaded badge text
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = TertiaryContainer,
        background = DarkSurface,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceContainer, // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceTint = DarkPrimary,
        inverseSurface = DarkOnSurface,
        inverseOnSurface = Neutral20,
        error = DarkError,
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF3C3936),
        surfaceContainer = DarkSurfaceContainer, // Navigation bar background
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = Color(0xFF373432),
        surfaceContainerLow = Color(0xFF1C1B1A),
        surfaceContainerLowest = Color(0xFF0F0E0D),
        surfaceDim = DarkSurface,
        primaryFixed = PrimaryContainer,
        primaryFixedDim = DarkPrimary,
        onPrimaryFixed = OnPrimaryContainer,
        onPrimaryFixedVariant = DarkPrimaryContainer,
        secondaryFixed = PrimaryContainer,
        secondaryFixedDim = DarkPrimary,
        onSecondaryFixed = OnPrimaryContainer,
        onSecondaryFixedVariant = DarkPrimaryContainer,
        tertiaryFixed = TertiaryContainer,
        tertiaryFixedDim = DarkTertiary,
        onTertiaryFixed = OnTertiaryContainer,
        onTertiaryFixedVariant = DarkTertiaryContainer,
    )
}
