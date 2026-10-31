package com.shinsaikou.data.chapter

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.chapter.model.BookmarkNote
import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BookmarkNoteRepositoryImpl(
    private val handler: DatabaseHandler,
) : BookmarkNoteRepository {

    override suspend fun getByChapterId(chapterId: Long): List<BookmarkNote> =
        handler.awaitList { bookmark_notesQueries.getByChapterId(chapterId, ::mapBookmarkNote) }

    override fun subscribeByChapterId(chapterId: Long): Flow<List<BookmarkNote>> =
        handler.subscribeToList { bookmark_notesQueries.getByChapterId(chapterId, ::mapBookmarkNote) }

    override fun subscribeByMangaId(mangaId: Long): Flow<List<BookmarkNote>> =
        handler.subscribeToList { bookmark_notesQueries.getByMangaId(mangaId, ::mapBookmarkNote) }

    override suspend fun upsert(chapterId: Long, pageIndex: Long, note: String, now: Long) {
        handler.await {
            bookmark_notesQueries.upsert(
                chapterId = chapterId,
                pageIndex = pageIndex,
                note = note,
                now = now,
            )
        }
    }

    override suspend fun remove(chapterId: Long, pageIndex: Long) {
        handler.await { bookmark_notesQueries.delete(chapterId, pageIndex) }
    }
}
