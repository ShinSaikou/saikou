package com.shinsaikou.ui.fixture

import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.preferences.DownloadPreferences
import io.mockk.mockk
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository

/** What a real [SetReadStatus] handed the download cleanup: each call's chapter ids. */
internal class RecordingReadChapterCleanup : ReadChapterCleanup {
    val marked = mutableListOf<List<Long>>()

    override suspend fun chapterFinished(sessionChapterIds: List<Long>) = error("not a reader test")

    override suspend fun readerClosed() = error("not a reader test")

    override fun markedRead(chapterIds: List<Long>) {
        marked += chapterIds
    }
}

/**
 * A real [SetReadStatus] with "After manually marked as read" on (phase 3 unit DR), over
 * [chapterRepository], whose hand-offs land in [cleanup]. A screen's test builds its view model
 * with it to show that the screen's mark read reaches the delete, where the mocked interactor the
 * other tests use would show only that the screen called it.
 */
internal fun deletingSetReadStatus(
    chapterRepository: ChapterRepository,
    cleanup: RecordingReadChapterCleanup,
    mangaRepository: MangaRepository = mockk(),
): SetReadStatus = SetReadStatus(
    chapterRepository = chapterRepository,
    mangaRepository = mangaRepository,
    downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()).apply { removeAfterMarkedAsRead.set(true) },
    readChapterCleanup = cleanup,
)
