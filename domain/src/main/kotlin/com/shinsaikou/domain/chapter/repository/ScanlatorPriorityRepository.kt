package com.shinsaikou.domain.chapter.repository

import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import kotlinx.coroutines.flow.Flow

interface ScanlatorPriorityRepository {
    /** ORDER BY priority. */
    suspend fun getByMangaId(mangaId: Long): List<ScanlatorPriority>

    fun subscribeByMangaId(mangaId: Long): Flow<List<ScanlatorPriority>>

    /** Replaces the manga's whole list in one transaction; index in the list becomes priority. */
    suspend fun setPriorities(mangaId: Long, orderedScanlators: List<String>)

    suspend fun clear(mangaId: Long)
}
