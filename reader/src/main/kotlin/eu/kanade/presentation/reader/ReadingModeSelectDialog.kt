// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/reader/ReadingModeSelectDialog.kt (modified: yes)
package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.ui.iconRes
import com.shinsaikou.reader.ui.stringRes
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import eu.kanade.presentation.theme.SaikouPreviewTheme
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.SettingsIconGrid
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Modified (S), plus section 2.3: the dialog reads the raw per-manga value off
 * `ReaderSettingsViewModel.readingModeFlow` (which is `ReaderState.mangaReadingMode` and may be
 * `DEFAULT`) rather than decoding `manga.viewer_flags` itself, and the label and icon come from
 * `ModeResources`. `onChange` carries a `@StringRes Int` where upstream passes a moko `StringResource`.
 */
private val ReadingModesWithoutDefault = ReadingMode.entries - ReadingMode.DEFAULT

@Composable
fun ReadingModeSelectDialog(
    onDismissRequest: () -> Unit,
    viewModel: ReaderSettingsViewModel,
    onChange: (Int) -> Unit,
) {
    val readingMode by viewModel.readingModeFlow.collectAsState()

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        DialogContent(
            readingMode = readingMode,
            onChangeReadingMode = {
                viewModel.onChangeReadingMode(it)
                onChange(it.stringRes)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun DialogContent(
    readingMode: ReadingMode,
    onChangeReadingMode: (ReadingMode) -> Unit,
) {
    var selected by remember { mutableStateOf(readingMode) }

    ModeSelectionDialog(
        onUseDefault = { onChangeReadingMode(ReadingMode.DEFAULT) }.takeIf { readingMode != ReadingMode.DEFAULT },
        onApply = { onChangeReadingMode(selected) },
    ) {
        SettingsIconGrid(R.string.pref_category_reading_mode) {
            items(ReadingModesWithoutDefault) { mode ->
                IconToggleButton(
                    checked = mode == selected,
                    onCheckedChange = {
                        selected = mode
                    },
                    modifier = Modifier.fillMaxWidth(),
                    imageVector = ImageVector.vectorResource(mode.iconRes),
                    title = stringResource(mode.stringRes),
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DialogContentPreview() {
    SaikouPreviewTheme {
        Surface {
            Column {
                DialogContent(
                    readingMode = ReadingMode.DEFAULT,
                    onChangeReadingMode = {},
                )

                DialogContent(
                    readingMode = ReadingMode.LEFT_TO_RIGHT,
                    onChangeReadingMode = {},
                )
            }
        }
    }
}
