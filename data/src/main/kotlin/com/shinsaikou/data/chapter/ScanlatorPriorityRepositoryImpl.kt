package com.shinsaikou.data.chapter

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import com.shinsaikou.domain.chapter.repository.ScanlatorPriorityRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ScanlatorPriorityRepositoryImpl(
    private val handler: DatabaseHandler,
) : ScanlatorPriorityRepository {

    override suspend fun getByMangaId(mangaId: Long): List<ScanlatorPriority> =
        handler.awaitList {
            scanlator_priorityQueries.getByMangaId(mangaId, ::mapScanlatorPriority)
        }

    override fun subscribeByMangaId(mangaId: Long): Flow<List<ScanlatorPriority>> =
        handler.subscribeToList {
            scanlator_priorityQueries.getByMangaId(mangaId, ::mapScanlatorPriority)
        }

    /**
     * The delete and the inserts are one transaction, so a reader never sees the manga with a
     * partial ranking. Position in the list is the priority, lowest first.
     */
    override suspend fun setPriorities(mangaId: Long, orderedScanlators: List<String>) {
        handler.await(inTransaction = true) {
            scanlator_priorityQueries.deleteByMangaId(mangaId)
            orderedScanlators.forEachIndexed { index, scanlator ->
                scanlator_priorityQueries.insert(
                    mangaId = mangaId,
                    scanlator = scanlator,
                    priority = index.toLong(),
                )
            }
        }
    }

    override suspend fun clear(mangaId: Long) {
        handler.await { scanlator_priorityQueries.deleteByMangaId(mangaId) }
    }
}
