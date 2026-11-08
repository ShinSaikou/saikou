// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/reader/appbars/ReaderBottomBar.kt (modified: yes) departures in the comment, the latest the Experimental two-page view's Shift pages
package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.ui.icon
import com.shinsaikou.reader.ui.iconRes
import tachiyomi.presentation.core.i18n.stringResource
import com.shinsaikou.reader.R as ReaderR

/**
 * Modified (S), plus: `readingMode.iconRes` and `orientation.icon` are `ModeResources` extensions
 * rather than enum members, and the two crop drawables come from `:reader`'s own `res/`. While the
 * page on screen has a note, a marker leads the row and opens the note ([hasNote], [onClickNote],
 * phase 4 unit BN; upstream has no page notes). While the Experimental auto-scroll is on and the
 * strip is the viewer, a play button before the settings starts it ([onClickAutoScroll], null
 * otherwise; upstream has no auto-scroll). While the Experimental two-page view shows two pages, a
 * Shift pages button moves the pairing by one ([onClickShiftPages], null otherwise; upstream has no
 * two-page view).
 */

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    hasNote: Boolean,
    onClickNote: () -> Unit,
    onClickAutoScroll: (() -> Unit)?,
    onClickShiftPages: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasNote) {
            IconButton(onClick = onClickNote) {
                Icon(
                    imageVector = Icons.Filled.EditNote,
                    contentDescription = stringResource(R.string.page_note_marker),
                )
            }
        }

        IconButton(onClick = onClickReadingMode) {
            Icon(
                painter = painterResource(readingMode.iconRes),
                contentDescription = stringResource(R.string.viewer),
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(R.string.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                painter = painterResource(if (cropEnabled) ReaderR.drawable.ic_crop_24dp else ReaderR.drawable.ic_crop_off_24dp),
                contentDescription = stringResource(R.string.pref_crop_borders),
            )
        }

        if (onClickShiftPages != null) {
            IconButton(onClick = onClickShiftPages) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.reader_shift_pages),
                )
            }
        }

        if (onClickAutoScroll != null) {
            IconButton(onClick = onClickAutoScroll) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.reader_auto_scroll_start),
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.action_settings),
            )
        }
    }
}
