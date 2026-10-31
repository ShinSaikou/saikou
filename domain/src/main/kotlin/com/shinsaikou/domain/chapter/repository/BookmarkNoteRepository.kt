package com.shinsaikou.domain.chapter.repository

import com.shinsaikou.domain.chapter.model.BookmarkNote
import kotlinx.coroutines.flow.Flow

interface BookmarkNoteRepository {
    /** ORDER BY page_index. */
    suspend fun getByChapterId(chapterId: Long): List<BookmarkNote>

    fun subscribeByChapterId(chapterId: Long): Flow<List<BookmarkNote>>

    fun subscribeByMangaId(mangaId: Long): Flow<List<BookmarkNote>>

    /** ON CONFLICT(chapter_id, page_index) replace the note text. */
    suspend fun upsert(chapterId: Long, pageIndex: Long, note: String, now: Long)

    suspend fun remove(chapterId: Long, pageIndex: Long)
}
