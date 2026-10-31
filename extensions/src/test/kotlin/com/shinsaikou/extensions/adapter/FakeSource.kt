package com.shinsaikou.extensions.adapter

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

/** A bare ABI source whose only interesting member is the filter tree the test hands it. */
internal class FakeSource(
    override val id: Long = 1L,
    override val name: String = "Fixture Source A",
    override val lang: String = "en",
    override val supportsLatest: Boolean = false,
    private val filters: () -> AbiFilterList,
) : Source {
    override fun getFilterList(): AbiFilterList = filters()
    override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
    override suspend fun getSearchManga(page: Int, query: String, filters: AbiFilterList): MangasPage =
        throw UnsupportedOperationException()
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = throw UnsupportedOperationException()
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
}
