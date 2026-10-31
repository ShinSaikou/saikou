// Vendored from upstream v0.20.4: presentation-core/src/main/java/tachiyomi/presentation/core/i18n/Localize.kt (modified: yes)
package tachiyomi.presentation.core.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource as androidPluralStringResource
import androidx.compose.ui.res.stringResource as androidStringResource

@Composable
@ReadOnlyComposable
fun stringResource(@StringRes resource: Int): String {
    return androidStringResource(resource)
}

@Composable
@ReadOnlyComposable
fun stringResource(@StringRes resource: Int, vararg args: Any): String {
    return androidStringResource(resource, *args)
}

@Composable
@ReadOnlyComposable
fun pluralStringResource(@PluralsRes resource: Int, count: Int): String {
    return androidPluralStringResource(resource, count)
}

@Composable
@ReadOnlyComposable
fun pluralStringResource(@PluralsRes resource: Int, count: Int, vararg args: Any): String {
    return androidPluralStringResource(resource, count, *args)
}
