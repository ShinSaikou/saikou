package com.shinsaikou.backup.importer

import com.shinsaikou.source.SourceIds
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedEntry

/**
 * One merged entry of a TachiyomiSY file, read off its synthetic entry, whose [SourceIds.SY_MERGED]
 * row Saikou never stores (R163 item 1). [members] are in chapter order, [info] names the entry the
 * merge took its details from, when it names one, and [showAll] is Show every copy.
 *
 * R163 has one rule for a number's copy, source order, so of the sort modes only no dedupe, which
 * keeps every copy, has an equivalent. A file written before SY had merge settings (field 604) has a
 * reference to the merge itself among its entries, at priority -1, which holds the sort mode and is
 * no member.
 */
internal class SyMerge private constructor(
    val entry: BackupManga,
    val members: List<BackupMergedEntry>,
    val info: EntryKey?,
    val showAll: Boolean,
) {
    companion object {
        /** SY's `CHAPTER_SORT_NO_DEDUPE`. */
        private const val SORT_NO_DEDUPE = 1

        fun of(entry: BackupManga): SyMerge {
            val settings = entry.mergeSettings
            val (self, members) = entry.mergedEntries.partition { it.mangaSourceId == SourceIds.SY_MERGED }
            val info = if (settings != null) {
                settings.infoMangaUrl.takeIf { it.isNotEmpty() }?.let { EntryKey(settings.infoMangaSourceId, it) }
            } else {
                members.firstOrNull { it.isInfoManga }?.key
            }
            val sortMode = settings?.chapterSortMode
                ?: (self.firstOrNull() ?: entry.mergedEntries.firstOrNull())?.chapterSortMode
            return SyMerge(
                entry = entry,
                members = members.distinctBy { it.key }.sortedBy { it.chapterPriority },
                info = info,
                showAll = sortMode == SORT_NO_DEDUPE,
            )
        }
    }
}

/** An entry as a file names it, before any source alias. */
internal data class EntryKey(val source: Long, val url: String)

internal val BackupMergedEntry.key: EntryKey get() = EntryKey(mangaSourceId, mangaUrl)
