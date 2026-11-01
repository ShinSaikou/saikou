package com.shinsaikou.domain.chapter

import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.database.TransactionRunner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * The row a write for an open chapter belongs to once a source refresh has replaced the chapter's
 * own. A refresh that finds a chapter under a new url removes the old row and inserts a new one
 * with its progress and history ([SyncChaptersWithSource]). A reader still open on the old chapter
 * wrote to the old id: the page went nowhere and the history insert failed its foreign key. The ids
 * are SQLite rowids, which a new row can take over from a deleted one, so after a refresh that
 * replaced several chapters the old id could also name another chapter, and the reader wrote its
 * page there.
 *
 * [SyncChaptersWithSource] records each replacement it is sure of, inside its transaction, with
 * every [Follower] open; the reader opens one for its session. Kept in memory and only while a
 * follower is open: a reader opened later reads the chapters as they are.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterReplacements(
    private val chapterRepository: ChapterRepository,
    private val transactionRunner: TransactionRunner,
) {

    private val followers = ConcurrentHashMap.newKeySet<Follower>()

    /** Records the replacements of every entry from now until the follower is closed. */
    fun follow(): Follower = Follower().also { followers += it }

    /**
     * [replacedBy] maps the url of each removed row of [mangaId] to the url of the row that took its
     * place. Called inside the transaction that replaces them, so a write that finds the old row
     * gone also finds where it went.
     */
    internal fun record(mangaId: Long, replacedBy: Map<String, String>) {
        if (replacedBy.isEmpty()) return
        followers.forEach { it.record(mangaId, replacedBy) }
    }

    /**
     * Takes back what [record] recorded in a transaction that then did not commit: the old rows are
     * live again, and a later refresh that replaces one ambiguously must not find this guess. An
     * entry a later [record] changed is left alone.
     */
    internal fun forget(mangaId: Long, replacedBy: Map<String, String>) {
        if (replacedBy.isEmpty()) return
        followers.forEach { it.forget(mangaId, replacedBy) }
    }

    inner class Follower internal constructor() : AutoCloseable {

        /** The url that replaced each url, by entry and url. */
        private val replacedBy = ConcurrentHashMap<Pair<Long, String>, String>()

        internal fun record(mangaId: Long, urls: Map<String, String>) {
            urls.forEach { (old, new) -> replacedBy[mangaId to old] = new }
        }

        internal fun forget(mangaId: Long, urls: Map<String, String>) {
            urls.forEach { (old, new) -> replacedBy.remove(mangaId to old, new) }
        }

        /**
         * Runs [block] with the id of the row [chapter]'s progress goes to, in one transaction with
         * the lookup, so a refresh cannot replace the row between the two. Returns null without
         * running it when the chapter is gone and nothing replaced it.
         */
        suspend fun <T> write(chapter: Chapter, block: suspend (chapterId: Long) -> T): T? =
            transactionRunner.inTransaction {
                target(chapter)?.let { block(it) }
            }

        /**
         * The chapter's own row while it is live and still the chapter (same entry, same url).
         * Otherwise the live row at the end of its replacements. Otherwise its own row soft deleted,
         * as before: that row keeps the progress for a relisting.
         */
        private suspend fun target(chapter: Chapter): Long? {
            val own = chapterRepository.getChapterById(chapter.id)
                ?.takeIf { it.mangaId == chapter.mangaId && it.url == chapter.url }
            if (own != null && own.deletedAt == null) return own.id
            var url = chapter.url
            val seen = hashSetOf(url)
            while (true) {
                url = replacedBy[chapter.mangaId to url] ?: break
                if (!seen.add(url)) break
                val row = chapterRepository.getChapterByUrlAndMangaId(url, chapter.mangaId)
                if (row != null && row.deletedAt == null) return row.id
            }
            return own?.id
        }

        override fun close() {
            followers -= this
        }
    }
}
