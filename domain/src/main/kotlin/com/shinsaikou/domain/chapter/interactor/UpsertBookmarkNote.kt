package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import dev.zacsweers.metro.Inject

@Inject
class UpsertBookmarkNote(
    private val bookmarkNoteRepository: BookmarkNoteRepository,
) {
    suspend fun await(chapterId: Long, pageIndex: Long, note: String) {
        bookmarkNoteRepository.upsert(chapterId, pageIndex, note, System.currentTimeMillis())
    }
}
