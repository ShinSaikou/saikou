package com.shinsaikou.ui.manga

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * What the manga screen's chapter list draws, one entry per list item (phase 4 unit VG): a chapter
 * row, and a volume header above each run of chapters of one volume. The pattern is the Updates
 * list's `UpdatesUiModel`: plain rows in one list, the header not sticky.
 */
sealed interface ChapterListUiModel {
    /** The list's key, kept apart between kinds: a chapter row keeps its chapter id. */
    val key: Any

    /**
     * The header over a run of [volume]. Keyed by the run's first chapter, since one volume can head
     * two runs when chapters with no volume, or another volume, sit between them.
     */
    data class Header(val volume: Double, val firstChapterId: Long) : ChapterListUiModel {
        override val key: Any get() = "volume-header-$firstChapterId"
    }

    data class Item(val chapter: Chapter) : ChapterListUiModel {
        override val key: Any get() = chapter.id
    }
}

/**
 * Whether [sorting] keeps a volume's chapters together: source order and chapter number do; upload
 * date and name can interleave two volumes, so under them the list has no headers.
 */
internal fun sortKeepsVolumesTogether(sorting: Long): Boolean =
    sorting == Manga.CHAPTER_SORTING_SOURCE || sorting == Manga.CHAPTER_SORTING_NUMBER

/**
 * The list for [chapters], already filtered and sorted. With [groupByVolume] on and a [sorting]
 * that keeps volumes together, a [ChapterListUiModel.Header] goes before each chapter whose volume
 * differs from the row above it; a chapter with no volume gets none and sits under no header
 * (`design/plans/phase4-start.md` section 5). Otherwise every row is an item, as before the feature.
 */
fun chapterListUiModels(
    chapters: List<Chapter>,
    groupByVolume: Boolean,
    sorting: Long,
): List<ChapterListUiModel> {
    if (!groupByVolume || !sortKeepsVolumesTogether(sorting)) return chapters.map(ChapterListUiModel::Item)
    val models = ArrayList<ChapterListUiModel>(chapters.size + 8)
    var previous: Double? = null
    chapters.forEach { chapter ->
        val volume = chapter.volume
        if (volume != null && volume != previous) models += ChapterListUiModel.Header(volume, chapter.id)
        models += ChapterListUiModel.Item(chapter)
        previous = volume
    }
    return models
}
