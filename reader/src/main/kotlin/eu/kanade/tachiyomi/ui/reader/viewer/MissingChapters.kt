// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/MissingChapters.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.domain.chapter.service.calculateChapterGap as domainCalculateChapterGap

/**
 * Modified: `ReaderChapter.chapter` is already `tachiyomi.domain.chapter.model.Chapter` here, where
 * upstream holds its own mutable database model, so the `toDomainChapter()` conversion on each side
 * goes away.
 */
fun calculateChapterGap(higherReaderChapter: ReaderChapter?, lowerReaderChapter: ReaderChapter?): Int =
    domainCalculateChapterGap(higherReaderChapter?.chapter, lowerReaderChapter?.chapter)
