package com.shinsaikou.reader.state

import android.content.pm.ActivityInfo
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.readerOrientation
import com.shinsaikou.domain.manga.model.readingMode
import tachiyomi.domain.manga.model.Manga

/**
 * Resolves the per-manga override and the global default into the one effective value the viewer
 * is built from. Contract 07 section 3.8.
 *
 * `manga.readingMode` and `manga.readerOrientation` decode `mangas.viewer`, so an upstream backup's
 * `viewer_flags` selects the viewer with no translation. The two defaults are the raw `Int`
 * preferences, and a restored value outside the enum falls through to upstream's own default.
 */
object ModeResolver {

    fun readingMode(manga: Manga, defaultReadingMode: Int): ReadingMode =
        manga.readingMode.takeUnless { it == ReadingMode.DEFAULT }
            ?: ReadingMode.fromFlags(defaultReadingMode.toLong()).takeUnless { it == ReadingMode.DEFAULT }
            ?: ReadingMode.RIGHT_TO_LEFT

    fun orientation(manga: Manga, defaultOrientationType: Int): ReaderOrientation =
        manga.readerOrientation.takeUnless { it == ReaderOrientation.DEFAULT }
            ?: ReaderOrientation.fromFlags(defaultOrientationType.toLong()).takeUnless { it == ReaderOrientation.DEFAULT }
            ?: ReaderOrientation.FREE

    /**
     * The activity's `requestedOrientation`. Lives here rather than on the enum because `:domain`
     * may not reference `android.content.pm` for a UI concern.
     */
    fun ReaderOrientation.toActivityOrientation(): Int = when (this) {
        ReaderOrientation.DEFAULT, ReaderOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ReaderOrientation.LOCKED_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ReaderOrientation.LOCKED_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ReaderOrientation.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }
}
