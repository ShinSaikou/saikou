// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/reader/settings/GeneralSettingsPage.kt (modified: yes)
package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.FlashColor
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.ui.stringRes
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val themes = listOf(
    R.string.black_background to 1,
    R.string.gray_background to 2,
    R.string.white_background to 0,
    R.string.automatic_background to 3,
)

private val flashColors = listOf(
    R.string.pref_flash_style_black to FlashColor.BLACK,
    R.string.pref_flash_style_white to FlashColor.WHITE,
    R.string.pref_flash_style_white_black to FlashColor.WHITE_BLACK,
)

/**
 * Modified (S), plus: `verticalNavigator` is a `Preference<Set<String>>` of [ReadingMode] names in
 * `:domain` (a preference store may not hold an enum set), so membership is tested by name, and
 * `MILLI_CONVERSION` moved to `com.shinsaikou.reader.settings.ReaderSettingsOptions`.
 */
@Composable
internal fun ColumnScope.GeneralPage(viewModel: ReaderSettingsViewModel) {
    val readerTheme by viewModel.preferences.readerTheme.collectAsState()

    val flashPageState by viewModel.preferences.flashOnPageChange.collectAsState()

    val flashMillisPref = viewModel.preferences.flashDurationMillis
    val flashMillis by flashMillisPref.collectAsState()

    val flashIntervalPref = viewModel.preferences.flashPageInterval
    val flashInterval by flashIntervalPref.collectAsState()

    val flashColorPref = viewModel.preferences.flashColor
    val flashColor by flashColorPref.collectAsState()

    SettingsChipRow(R.string.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { viewModel.preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(R.string.pref_show_page_number),
        pref = viewModel.preferences.showPageNumber,
    )

    val verticalNavigatorModes by viewModel.preferences.verticalNavigator.collectAsState()

    SettingsChipRow(R.string.pref_vertical_navigator) {
        ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.forEach { mode ->
            FilterChip(
                selected = verticalNavigatorModes.contains(mode.name),
                onClick = {
                    val newModes = if (verticalNavigatorModes.contains(mode.name)) {
                        verticalNavigatorModes - mode.name
                    } else {
                        verticalNavigatorModes + mode.name
                    }
                    viewModel.preferences.verticalNavigator.set(newModes)
                },
                label = { Text(stringResource(mode.stringRes)) },
            )
        }
    }

    if (verticalNavigatorModes.isNotEmpty()) {
        val verticalNavigatorHeightPref = viewModel.preferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        CheckboxItem(
            label = stringResource(R.string.pref_webtoon_vertical_navigator_on_left),
            pref = viewModel.preferences.verticalNavigatorOnLeft,
        )

        SliderItem(
            label = stringResource(R.string.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { verticalNavigatorHeightPref.set(it) },
        )
    }

    CheckboxItem(
        label = stringResource(R.string.pref_fullscreen),
        pref = viewModel.preferences.fullscreen,
    )

    val isFullscreen by viewModel.preferences.fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(R.string.pref_cutout_short),
            pref = viewModel.preferences.drawUnderCutout,
        )
    }

    CheckboxItem(
        label = stringResource(R.string.pref_keep_screen_on),
        pref = viewModel.preferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_read_with_long_tap),
        pref = viewModel.preferences.readWithLongTap,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_always_show_chapter_transition),
        pref = viewModel.preferences.alwaysShowChapterTransition,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_page_transitions),
        pref = viewModel.preferences.pageTransitions,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_flash_page),
        pref = viewModel.preferences.flashOnPageChange,
    )
    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderSettingsOptions.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(R.string.pref_flash_duration),
            valueString = stringResource(R.string.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderSettingsOptions.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(R.string.pref_flash_page_interval),
            valueString = pluralStringResource(R.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(R.string.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}
