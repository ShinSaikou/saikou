package com.shinsaikou.reader

import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.reader.state.ReaderEntries
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

/**
 * A title is one entry unless it is merged (R163): then every live member, the main entry first and
 * the rest in chapter order, whichever member the reader was opened on.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppReaderEntries(
    private val getMergedGroup: GetMergedGroup,
    private val getMergedChapters: GetMergedChapters,
    private val getManga: GetManga,
) : ReaderEntries {

    override suspend fun of(manga: Manga): List<Manga> {
        val group = getMergedGroup.await(manga.id) ?: return listOf(manga)
        val members = group.members.mapNotNull { if (it.mangaId == manga.id) manga else getManga.await(it.mangaId) }
        return members.sortedBy { it.id != group.mainMangaId }
    }

    override suspend fun chapterPass(entries: List<Manga>): (List<Chapter>) -> List<Chapter> {
        val group = getMergedGroup.await(entries.first().id) ?: return { it }
        return { chapters -> getMergedChapters.visible(chapters, group) }
    }
}
