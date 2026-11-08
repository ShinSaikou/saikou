// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt (modified: yes) departures in the comment, the latest the Experimental two-page view
package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.TappingInvertMode
import com.shinsaikou.domain.preferences.TwoPageLayout
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.settings.titleRes
import com.shinsaikou.reader.ui.stringRes
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

/**
 * Modified (S) and section 2.3: the per-manga reading mode and orientation come off the view model's
 * flows (which mirror `ReaderState.mangaReadingMode` and `mangaOrientation`) rather than being
 * decoded out of `manga.viewer_flags` here, `viewerFlow` is a boolean `isWebtoonFlow` because
 * `:reader` never hands a live `Viewer` to Compose, and the four option lists moved to
 * [ReaderSettingsOptions]. The long strip's group ends with the Experimental auto-scroll's speed,
 * shown only while its switch is on (upstream has no auto-scroll). The pager's group ends with the
 * Experimental "Two pages side by side", likewise shown only while its switch is on (upstream has no
 * two-page view).
 */
@Composable
internal fun ColumnScope.ReadingModePage(viewModel: ReaderSettingsViewModel) {
    HeadingItem(R.string.pref_category_for_this_series)

    val readingMode by viewModel.readingModeFlow.collectAsState()
    SettingsChipRow(R.string.pref_category_reading_mode) {
        ReadingMode.entries.map {
            FilterChip(
                selected = it == readingMode,
                onClick = { viewModel.onChangeReadingMode(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    val orientation by viewModel.orientationFlow.collectAsState()
    SettingsChipRow(R.string.rotation_type) {
        ReaderOrientation.entries.map {
            FilterChip(
                selected = it == orientation,
                onClick = { viewModel.onChangeOrientation(it) },
                label = { Text(stringResource(it.stringRes)) },
            )
        }
    }

    val isWebtoon by viewModel.isWebtoonFlow.collectAsState()
    if (isWebtoon) {
        WebtoonViewerSettings(viewModel)
    } else {
        PagerViewerSettings(viewModel)
    }
}

@Composable
private fun ColumnScope.PagerViewerSettings(viewModel: ReaderSettingsViewModel) {
    HeadingItem(R.string.pager_viewer)

    val navigationModePager by viewModel.preferences.navigationModePager.collectAsState()
    val pagerNavInverted by viewModel.preferences.pagerNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModePager,
        onSelect = viewModel.preferences.navigationModePager::set,
        invertMode = pagerNavInverted,
        onSelectInvertMode = viewModel.preferences.pagerNavInverted::set,
    )

    val imageScaleType by viewModel.preferences.imageScaleType.collectAsState()
    SettingsChipRow(R.string.pref_image_scale_type) {
        ReaderSettingsOptions.ImageScaleType.mapIndexed { index, it ->
            FilterChip(
                selected = imageScaleType == index + 1,
                onClick = { viewModel.preferences.imageScaleType.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    val zoomStart by viewModel.preferences.zoomStart.collectAsState()
    SettingsChipRow(R.string.pref_zoom_start) {
        ReaderSettingsOptions.ZoomStart.mapIndexed { index, it ->
            FilterChip(
                selected = zoomStart == index + 1,
                onClick = { viewModel.preferences.zoomStart.set(index + 1) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(R.string.pref_crop_borders),
        pref = viewModel.preferences.cropBorders,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_landscape_zoom),
        pref = viewModel.preferences.landscapeZoom,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_navigate_pan),
        pref = viewModel.preferences.navigateToPan,
    )

    val dualPageSplitPaged by viewModel.preferences.dualPageSplitPaged.collectAsState()
    CheckboxItem(
        label = stringResource(R.string.pref_dual_page_split),
        pref = viewModel.preferences.dualPageSplitPaged,
    )

    if (dualPageSplitPaged) {
        CheckboxItem(
            label = stringResource(R.string.pref_dual_page_invert),
            pref = viewModel.preferences.dualPageInvertPaged,
        )
    }

    val dualPageRotateToFit by viewModel.preferences.dualPageRotateToFit.collectAsState()
    CheckboxItem(
        label = stringResource(R.string.pref_page_rotate),
        pref = viewModel.preferences.dualPageRotateToFit,
    )

    if (dualPageRotateToFit) {
        CheckboxItem(
            label = stringResource(R.string.pref_page_rotate_invert),
            pref = viewModel.preferences.dualPageRotateToFitInvert,
        )
    }

    val twoPages by viewModel.preferences.twoPages.collectAsState()
    if (twoPages) {
        val twoPageLayout by viewModel.preferences.twoPageLayout.collectAsState()
        SettingsChipRow(R.string.pref_two_pages) {
            TwoPageLayout.entries.map {
                FilterChip(
                    selected = it == twoPageLayout,
                    onClick = { viewModel.preferences.twoPageLayout.set(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WebtoonViewerSettings(viewModel: ReaderSettingsViewModel) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    HeadingItem(R.string.webtoon_viewer)

    val navigationModeWebtoon by viewModel.preferences.navigationModeWebtoon.collectAsState()
    val webtoonNavInverted by viewModel.preferences.webtoonNavInverted.collectAsState()
    TapZonesItems(
        selected = navigationModeWebtoon,
        onSelect = viewModel.preferences.navigationModeWebtoon::set,
        invertMode = webtoonNavInverted,
        onSelectInvertMode = viewModel.preferences.webtoonNavInverted::set,
    )

    val webtoonSidePadding by viewModel.preferences.webtoonSidePadding.collectAsState()
    SliderItem(
        value = webtoonSidePadding,
        valueRange = ReaderSettingsOptions.let { it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX },
        label = stringResource(R.string.pref_webtoon_side_padding),
        valueString = numberFormat.format(webtoonSidePadding / 100f),
        onChange = {
            viewModel.preferences.webtoonSidePadding.set(it)
        },
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    CheckboxItem(
        label = stringResource(R.string.pref_crop_borders),
        pref = viewModel.preferences.cropBordersWebtoon,
    )

    val dualPageSplitWebtoon by viewModel.preferences.dualPageSplitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(R.string.pref_dual_page_split),
        pref = viewModel.preferences.dualPageSplitWebtoon,
    )

    if (dualPageSplitWebtoon) {
        CheckboxItem(
            label = stringResource(R.string.pref_dual_page_invert),
            pref = viewModel.preferences.dualPageInvertWebtoon,
        )
    }

    val dualPageRotateToFitWebtoon by viewModel.preferences.dualPageRotateToFitWebtoon.collectAsState()
    CheckboxItem(
        label = stringResource(R.string.pref_page_rotate),
        pref = viewModel.preferences.dualPageRotateToFitWebtoon,
    )

    if (dualPageRotateToFitWebtoon) {
        CheckboxItem(
            label = stringResource(R.string.pref_page_rotate_invert),
            pref = viewModel.preferences.dualPageRotateToFitInvertWebtoon,
        )
    }

    CheckboxItem(
        label = stringResource(R.string.pref_double_tap_zoom),
        pref = viewModel.preferences.webtoonDoubleTapZoomEnabled,
    )
    CheckboxItem(
        label = stringResource(R.string.pref_webtoon_disable_zoom_out),
        pref = viewModel.preferences.webtoonDisableZoomOut,
    )

    val stripAutoScroll by viewModel.preferences.stripAutoScroll.collectAsState()
    if (stripAutoScroll) {
        val autoScrollSpeed by viewModel.preferences.stripAutoScrollSpeed.collectAsState()
        SliderItem(
            value = autoScrollSpeed,
            valueRange = ReaderSettingsOptions.let { it.AUTO_SCROLL_SPEED_MIN..it.AUTO_SCROLL_SPEED_MAX },
            label = stringResource(R.string.pref_auto_scroll_speed),
            onChange = viewModel.preferences.stripAutoScrollSpeed::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun ColumnScope.TapZonesItems(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: TappingInvertMode,
    onSelectInvertMode: (TappingInvertMode) -> Unit,
) {
    SettingsChipRow(R.string.pref_viewer_nav) {
        ReaderSettingsOptions.TapZones.mapIndexed { index, it ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(it)) },
            )
        }
    }

    if (selected != 5) {
        SettingsChipRow(R.string.pref_read_with_tapping_inverted) {
            TappingInvertMode.entries.map {
                FilterChip(
                    selected = it == invertMode,
                    onClick = { onSelectInvertMode(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}
