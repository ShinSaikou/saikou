package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.ui.icon
import com.shinsaikou.reader.ui.iconRes
import com.shinsaikou.reader.ui.stringRes
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

private val ReadingModesWithoutDefault = ReadingMode.entries - ReadingMode.DEFAULT
private val ReaderOrientationsWithoutDefault = ReaderOrientation.entries - ReaderOrientation.DEFAULT

/**
 * Reading direction and rotation lock, for this entry only.
 *
 * Neither grid offers DEFAULT as a chip. The reader's rule is that the entry's own value wins
 * unless it is DEFAULT, in which case the global one does, so what the owner should see selected
 * is the value they would actually read with, and the way back to the global value is the
 * revert button rather than a seventh chip that means "whatever the settings screen says".
 * That button only appears while there is an override to revert.
 *
 * Both grids are chip rows and not [tachiyomi.presentation.core.components.SettingsIconGrid]: that
 * one is a lazy grid, and two of them stacked inside a scrolling column is a lazy layout nested in
 * a scroll of the same axis.
 */
@Composable
internal fun MangaReadingModeSheet(
    readingMode: ReadingMode,
    orientation: ReaderOrientation,
    defaultReadingMode: ReadingMode,
    defaultOrientation: ReaderOrientation,
    onDismissRequest: () -> Unit,
    onApply: (ReadingMode, ReaderOrientation) -> Unit,
    onUseDefault: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest, enableImplicitDismiss = true) {
        var selectedMode by remember {
            mutableStateOf(if (readingMode == ReadingMode.DEFAULT) defaultReadingMode else readingMode)
        }
        var selectedOrientation by remember {
            mutableStateOf(if (orientation == ReaderOrientation.DEFAULT) defaultOrientation else orientation)
        }

        ModeSelectionDialog(
            onApply = { onApply(selectedMode, selectedOrientation) },
            onUseDefault = onUseDefault.takeIf {
                readingMode != ReadingMode.DEFAULT || orientation != ReaderOrientation.DEFAULT
            },
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsChipRow(R.string.pref_category_reading_mode) {
                    ReadingModesWithoutDefault.forEach { mode ->
                        IconToggleButton(
                            checked = mode == selectedMode,
                            onCheckedChange = { selectedMode = mode },
                            imageVector = ImageVector.vectorResource(mode.iconRes),
                            title = stringResource(mode.stringRes),
                            modifier = Modifier.width(CHIP_WIDTH),
                        )
                    }
                }
                SettingsChipRow(R.string.rotation_type) {
                    ReaderOrientationsWithoutDefault.forEach { value ->
                        IconToggleButton(
                            checked = value == selectedOrientation,
                            onCheckedChange = { selectedOrientation = value },
                            imageVector = value.icon,
                            title = stringResource(value.stringRes),
                            modifier = Modifier.width(CHIP_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

/** Wide enough for the longest mode label at two lines, and narrow enough to fit two per row. */
private val CHIP_WIDTH = 150.dp
