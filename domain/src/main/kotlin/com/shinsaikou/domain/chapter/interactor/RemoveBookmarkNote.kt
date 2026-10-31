package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import dev.zacsweers.metro.Inject

@Inject
class RemoveBookmarkNote(
    private val bookmarkNoteRepository: BookmarkNoteRepository,
) {
    suspend fun await(chapterId: Long, pageIndex: Long) {
        bookmarkNoteRepository.remove(chapterId, pageIndex)
    }
}
