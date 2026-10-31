// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/components/DateText.kt (modified: yes) the two preference values are parameters instead of Injekt reads; only the LocalDate overload, non-null
package eu.kanade.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shinsaikou.domain.preferences.UiPreferences
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.datetime.LocalDate

/**
 * A day as upstream writes it on its Updates headers: [relativeTime] is Relative timestamps
 * (`relative_time_v2`) and [dateFormat] the stored Date format (`app_date_format`), read through
 * [UiPreferences.dateFormat] (R102 item 4). The caller's view model reads both keys live, where
 * upstream's reads them once per composition through Injekt.
 */
@Composable
fun relativeDateText(
    localDate: LocalDate,
    relativeTime: Boolean,
    dateFormat: String,
): String {
    val context = LocalContext.current

    val formatter = remember(dateFormat) { UiPreferences.dateFormat(dateFormat) }

    return localDate.toRelativeString(
        context = context,
        relative = relativeTime,
        dateFormat = formatter,
    )
}
