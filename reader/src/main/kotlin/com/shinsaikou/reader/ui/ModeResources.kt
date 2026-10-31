package com.shinsaikou.reader.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.ui.graphics.vector.ImageVector
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.R as ReaderR

/**
 * The labels and icons `:domain`'s two reader enums cannot carry. Contract 07 sections 2.3 and 5.5.
 *
 * Upstream hangs a `StringResource` and an icon off each enum case. `:domain` may not depend on `:i18n`
 * and holds no `@DrawableRes`, so they arrive here as extension properties, which is the shape
 * ruling R17 established for `AppTheme.titleRes`.
 *
 * Each `when` is exhaustive with no `else`: adding a reading mode without a label is a compile
 * error rather than a blank chip in the bottom bar.
 */

@get:StringRes
val ReadingMode.stringRes: Int
    get() = when (this) {
        ReadingMode.DEFAULT -> R.string.label_default
        ReadingMode.LEFT_TO_RIGHT -> R.string.left_to_right_viewer
        ReadingMode.RIGHT_TO_LEFT -> R.string.right_to_left_viewer
        ReadingMode.VERTICAL -> R.string.vertical_viewer
        ReadingMode.WEBTOON -> R.string.webtoon_viewer
        ReadingMode.CONTINUOUS_VERTICAL -> R.string.vertical_plus_viewer
    }

@get:DrawableRes
val ReadingMode.iconRes: Int
    get() = when (this) {
        ReadingMode.DEFAULT -> ReaderR.drawable.ic_reader_default_24dp
        ReadingMode.LEFT_TO_RIGHT -> ReaderR.drawable.ic_reader_ltr_24dp
        ReadingMode.RIGHT_TO_LEFT -> ReaderR.drawable.ic_reader_rtl_24dp
        ReadingMode.VERTICAL -> ReaderR.drawable.ic_reader_vertical_24dp
        ReadingMode.WEBTOON -> ReaderR.drawable.ic_reader_webtoon_24dp
        ReadingMode.CONTINUOUS_VERTICAL -> ReaderR.drawable.ic_reader_continuous_vertical_24dp
    }

@get:StringRes
val ReaderOrientation.stringRes: Int
    get() = when (this) {
        ReaderOrientation.DEFAULT -> R.string.label_default
        ReaderOrientation.FREE -> R.string.rotation_free
        ReaderOrientation.PORTRAIT -> R.string.rotation_portrait
        ReaderOrientation.LANDSCAPE -> R.string.rotation_landscape
        ReaderOrientation.LOCKED_PORTRAIT -> R.string.rotation_force_portrait
        ReaderOrientation.LOCKED_LANDSCAPE -> R.string.rotation_force_landscape
        ReaderOrientation.REVERSE_PORTRAIT -> R.string.rotation_reverse_portrait
    }

/** Material icons, as in upstream: the orientation chips are the one place with no vector asset. */
val ReaderOrientation.icon: ImageVector
    get() = when (this) {
        ReaderOrientation.DEFAULT, ReaderOrientation.FREE -> Icons.Default.ScreenRotation
        ReaderOrientation.PORTRAIT, ReaderOrientation.REVERSE_PORTRAIT -> Icons.Default.StayCurrentPortrait
        ReaderOrientation.LANDSCAPE -> Icons.Default.StayCurrentLandscape
        ReaderOrientation.LOCKED_PORTRAIT -> Icons.Default.ScreenLockPortrait
        ReaderOrientation.LOCKED_LANDSCAPE -> Icons.Default.ScreenLockLandscape
    }
