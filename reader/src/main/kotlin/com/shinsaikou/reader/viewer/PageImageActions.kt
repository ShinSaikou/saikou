package com.shinsaikou.reader.viewer

import android.net.Uri
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Saving, sharing and setting a page as the cover. Contract 07 sections 1.2 and part 2 7.1.
 *
 * `page.stream` is non-null whenever `page.status is PageState.Ready`, and it is the only way
 * `:app` gets at the bytes: there is no file path in the API, because a split page's bytes are a
 * part file the reader owns. The reader shows the [Result] as a toast and does nothing else with
 * it. There is deliberately no `copyToClipboard` (decision D22). The page sheet's fourth button,
 * the page's note, is not here: it needs only `:domain`, so the reader's view model writes it
 * (phase 4 unit BN).
 */
interface PageImageActions {

    suspend fun save(manga: Manga, chapter: Chapter, page: ReaderPage): Result<Uri>

    suspend fun share(manga: Manga, chapter: Chapter, page: ReaderPage): Result<Uri>

    suspend fun setAsCover(manga: Manga, page: ReaderPage): Result<Unit>
}
