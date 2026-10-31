package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.model.BookmarkNote
import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class GetBookmarkNotes(
    private val bookmarkNoteRepository: BookmarkNoteRepository,
) {
    fun subscribe(chapterId: Long): Flow<List<BookmarkNote>> =
        bookmarkNoteRepository.subscribeByChapterId(chapterId)

    fun subscribeByManga(mangaId: Long): Flow<List<BookmarkNote>> =
        bookmarkNoteRepository.subscribeByMangaId(mangaId)
}
