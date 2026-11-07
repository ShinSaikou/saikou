package com.shinsaikou.reader.state

import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.merged.MergedChapters
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
class ReaderSession(
    val manga: Manga,
    val chapters: List<ReaderChapter>,
    private val owners: Map<Long, Manga> = mapOf(manga.id to manga),
) {

    /** The ids of the entries this session reads, one unless the title is merged. */
    val entryIds: Set<Long> get() = owners.keys

    /** The entry [chapter] belongs to, whose source serves it: [manga] unless the title is merged. */
    fun ownerOf(chapter: Chapter): Manga = owners[chapter.mangaId] ?: manga

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
         * Upstream's `chapterList` rule, in seven steps: drop tombstones, skip read, skip filtered,
         * sort ascending, keep only the downloaded ones under downloaded only, skip duplicates, wrap.
         *
         * The one place this differs from upstream is the rule that makes the whole thing usable: the
         * chapter the user tapped is always in the result. Upstream adds it back after the read and
         * filter passes but not after the duplicate pass, so a duplicate of the tapped chapter can
         * displace it; here the tapped chapter wins every group it is in.
         *
         * [downloaded] is the chapters whose files are on disk, as `DownloadedChapters` answers
         * it, and the downloaded filter and downloaded only read it as upstream's reader does. It is
         * only consulted under `skipFiltered` or `downloadedOnly`, so the caller may pass an empty set
         * when neither asks for it.
         * A Local-source chapter always counts as downloaded, which is how the chapter list and the
         * library already count it; upstream's reader skips it instead, so a Local entry under the
         * downloaded filter opened on the tapped chapter alone.
         *
         * [ranking] is the title's scanlator ranking, `GetScanlatorPriority.await`'s map, which the
         * duplicate pass reads to keep the best-ranked copy of each other number (phase 4 unit SC).
         * It is only consulted under `skipDupe`, so the caller may pass an empty map when that is
         * off; empty is also an unranked title, which keeps upstream's rule.
         *
         * [owners] is the session's entries by id, which settle whether a chapter is a Local one. In
         * a merged title that is per chapter; [manga] alone holds the filters. Several owners sort
         * as a merged list ([MergedChapters.sort]), and [chapterPass] is [ReaderEntries.chapterPass],
         * which runs after the downloaded filter, so a number whose winning copy is not downloaded
         * keeps the one that is. The tapped chapter stays through it too, see [mergedPass].
         */
        fun build(
            manga: Manga,
            all: List<Chapter>,
            initialChapterId: Long,
            prefs: SessionFilters,
            downloaded: Set<Long>,
            ranking: Map<String, Long>,
            owners: Map<Long, Manga> = mapOf(manga.id to manga),
            chapterPass: (List<Chapter>) -> List<Chapter> = { it },
        ): ReaderSession {
            val isLocal = { chapter: Chapter -> (owners[chapter.mangaId] ?: manga).source == SourceIds.LOCAL }
            val initial = all.firstOrNull { it.id == initialChapterId }
                ?: error("the chapter being opened is not in the manga's chapter list")

            var kept = all.filter { it.deletedAt == null }
            if (prefs.skipRead || prefs.skipFiltered) {
                kept = kept.filterNot { chapter ->
                    when {
                        prefs.skipRead && chapter.read -> true
                        prefs.skipFiltered ->
                            isFilteredOut(manga, chapter, isLocal(chapter) || chapter.id in downloaded)
                        else -> false
                    }
                }
            }
            if (kept.none { it.id == initialChapterId }) {
                kept = kept + initial
            }

            val sort = if (owners.size > 1) {
                MergedChapters.sort(manga, sortDescending = false)
            } else {
                getChapterSort(manga, sortDescending = false)
            }
            var sorted = kept.sortedWith(sort)
            // Upstream's filterDownloaded, which leaves a Local entry alone; the tapped chapter stays
            // here too, as it does through every other pass. Before the duplicate pass, where
            // upstream runs it after: a number whose preferred copy is not downloaded keeps its
            // downloaded one, where upstream drops the downloaded copy, then the preferred one, and
            // loses the number.
            if (prefs.downloadedOnly) {
                sorted = sorted.filter { isLocal(it) || it.id in downloaded || it.id == initialChapterId }
            }
            sorted = mergedPass(sorted, initial, chapterPass)
            if (prefs.skipDupe) {
                sorted = sorted.removeDuplicates(initial, ranking)
            }
            return ReaderSession(manga, sorted.map(::ReaderChapter), owners)
        }

        /**
         * [chapters] through [chapterPass], the tapped chapter always among them. A tapped chapter
         * the pass hid takes its number from the winner, every row of its own entry with it, or the
         * next chapter would be the same number again from another source.
         */
        private fun mergedPass(
            chapters: List<Chapter>,
            initial: Chapter,
            chapterPass: (List<Chapter>) -> List<Chapter>,
        ): List<Chapter> {
            val kept = chapterPass(chapters).mapTo(HashSet()) { it.id }
            if (initial.id in kept) return chapters.filter { it.id in kept }
            return chapters.filter { chapter ->
                if (initial.isRecognizedNumber && chapter.chapterNumber == initial.chapterNumber) {
                    chapter.mangaId == initial.mangaId
                } else {
                    chapter.id in kept || chapter.id == initial.id
                }
            }
        }

        private fun isFilteredOut(manga: Manga, chapter: Chapter, isDownloaded: Boolean): Boolean {
            return (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !chapter.read) ||
                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && chapter.read) ||
                (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED && !isDownloaded) ||
                (manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED && isDownloaded) ||
                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !chapter.bookmark) ||
                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && chapter.bookmark)
        }
    }
}

/**
 * The three `ReaderPreferences` keys [ReaderSession.build] reads, and the app's downloaded only mode,
 * passed in so it stays pure.
 */
data class SessionFilters(
    val skipRead: Boolean,
    val skipFiltered: Boolean,
    val skipDupe: Boolean,
    val downloadedOnly: Boolean = false,
)
