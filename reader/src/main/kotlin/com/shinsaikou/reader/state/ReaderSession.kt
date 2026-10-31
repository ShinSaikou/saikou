package com.shinsaikou.reader.state

import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.source.SourceIds
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/**
 * The ordered, filtered chapter list of one reader session, computed once at open.
 * Contract 07 section 3.4.
 */
class ReaderSession(val manga: Manga, val chapters: List<ReaderChapter>) {

    fun indexOf(chapter: ReaderChapter): Int = chapters.indexOfFirst { it.chapter.id == chapter.chapter.id }

    fun chapterFor(chapterId: Long): ReaderChapter = chapters.first { it.chapter.id == chapterId }

    fun previous(chapter: ReaderChapter): ReaderChapter? = indexOf(chapter)
        .takeIf { it >= 0 }
        ?.let { chapters.getOrNull(it - 1) }

    fun next(chapter: ReaderChapter): ReaderChapter? = indexOf(chapter)
        .takeIf { it >= 0 }
        ?.let { chapters.getOrNull(it + 1) }

    /**
     * The ids of [chapter] and of up to [ReadChapterCleanup.MAX_SLOT] chapters before it in this
     * session, nearest first, for the delete after reading (phase 3 unit DR). Element `n` is upstream's
     * `chapterList.getOrNull(currentChapterPosition - n)`, taken from this list, which was built once
     * at open: rebuilt at the finish, skip read would already have dropped the chapters the session
     * held. Empty for a chapter this session does not hold.
     */
    fun readBack(chapter: ReaderChapter): List<Long> {
        val index = indexOf(chapter)
        if (index < 0) return emptyList()
        return (index downTo maxOf(0, index - ReadChapterCleanup.MAX_SLOT)).map { chapters[it].chapter.id }
    }

    companion object {

        /**
         * Upstream's `chapterList` rule, in six steps: drop tombstones, skip read, skip filtered, sort
         * ascending, skip duplicates, wrap.
         *
         * The one place this differs from upstream is the rule that makes the whole thing usable: the
         * chapter the user tapped is always in the result. Upstream adds it back after the read and
         * filter passes but not after the duplicate pass, so a duplicate of the tapped chapter can
         * displace it; here the tapped chapter wins every group it is in.
         *
         * [downloaded] is the chapters whose files are on disk, as `DownloadedChapters` answers
         * it, and the downloaded filter reads it as upstream's reader does. It is only consulted under
         * `skipFiltered`, so the caller may pass an empty set when that is off or the filter is.
         * A Local-source chapter always counts as downloaded, which is how the chapter list and the
         * library already count it; upstream's reader skips it instead, so a Local entry under the
         * downloaded filter opened on the tapped chapter alone.
         *
         * [ranking] is the title's scanlator ranking, `GetScanlatorPriority.await`'s map, which the
         * duplicate pass reads to keep the best-ranked copy of each other number (phase 4 unit SC).
         * It is only consulted under `skipDupe`, so the caller may pass an empty map when that is
         * off; empty is also an unranked title, which keeps upstream's rule.
         */
        fun build(
            manga: Manga,
            all: List<Chapter>,
            initialChapterId: Long,
            prefs: SessionFilters,
            downloaded: Set<Long>,
            ranking: Map<String, Long>,
        ): ReaderSession {
            val initial = all.firstOrNull { it.id == initialChapterId }
                ?: error("the chapter being opened is not in the manga's chapter list")

            var kept = all.filter { it.deletedAt == null }
            if (prefs.skipRead || prefs.skipFiltered) {
                kept = kept.filterNot { chapter ->
                    when {
                        prefs.skipRead && chapter.read -> true
                        prefs.skipFiltered -> isFilteredOut(manga, chapter, downloaded)
                        else -> false
                    }
                }
            }
            if (kept.none { it.id == initialChapterId }) {
                kept = kept + initial
            }

            var sorted = kept.sortedWith(getChapterSort(manga, sortDescending = false))
            if (prefs.skipDupe) {
                sorted = sorted.removeDuplicates(initial, ranking)
            }
            return ReaderSession(manga, sorted.map(::ReaderChapter))
        }

        private fun isFilteredOut(manga: Manga, chapter: Chapter, downloaded: Set<Long>): Boolean {
            val isDownloaded = manga.source == SourceIds.LOCAL || chapter.id in downloaded
            return (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !chapter.read) ||
                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && chapter.read) ||
                (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED && !isDownloaded) ||
                (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED && isDownloaded) ||
                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !chapter.bookmark) ||
                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && chapter.bookmark)
        }
    }
}

/** The three `ReaderPreferences` keys [ReaderSession.build] reads, passed in so it stays pure. */
data class SessionFilters(
    val skipRead: Boolean,
    val skipFiltered: Boolean,
    val skipDupe: Boolean,
)
