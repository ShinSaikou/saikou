package com.shinsaikou.ui.upcoming

import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** What the Upcoming list draws: a header for each day, then that day's entries. */
sealed interface UpcomingUiModel {
    /** The list's key, kept apart between kinds: a row keeps its entry id. */
    val key: Any

    data class Header(val date: LocalDate, val count: Int) : UpcomingUiModel {
        override val key: Any get() = "upcoming-header-$date"
    }

    data class Item(val manga: Manga) : UpcomingUiModel {
        override val key: Any get() = manga.id
    }
}

private val UPCOMING_STATUSES = setOf(MangaStatus.ONGOING.code.toLong(), MangaStatus.PUBLISHING_FINISHED.code.toLong())

/**
 * The entries with a release predicted from [startOfToday] on, soonest first: upstream's statuses,
 * less what no update would ever check, an entry only fetched once and one the update's category
 * settings leave out. An entry never fetched has a next update of 0 and so is not here.
 *
 * A merged title of [groups] is one entry: its main entry at the earliest date of its members that
 * pass, a member with Check for updates off counting for nothing. A title whose main entry is not
 * in [library] yet is a tick behind the group, and its members stay as they are.
 */
fun upcomingEntries(
    library: List<LibraryManga>,
    included: Set<Long>,
    excluded: Set<Long>,
    startOfToday: Long,
    groups: List<MergedGroup> = emptyList(),
): List<Manga> {
    val upcoming = library.filter { item ->
        val manga = item.manga
        manga.nextUpdate >= startOfToday &&
            manga.status in UPCOMING_STATUSES &&
            manga.updateStrategy != UpdateStrategy.ONLY_FETCH_ONCE &&
            item.categories.none { it in excluded } &&
            (included.isEmpty() || item.categories.any { it in included })
    }
    val entries = if (groups.isEmpty()) upcoming.map { it.manga } else mergedTitles(upcoming, library, groups)
    return entries.sortedBy { it.nextUpdate }
}

/** [upcoming] with each title of [groups] folded into one [Manga], read off the whole [library]. */
private fun mergedTitles(
    upcoming: List<LibraryManga>,
    library: List<LibraryManga>,
    groups: List<MergedGroup>,
): List<Manga> {
    val mains = groups.map { it.mainMangaId }.toHashSet()
        .let { ids -> library.filter { it.id in ids }.associate { it.id to it.manga } }
    val titleOf = HashMap<Long, MergedGroup>()
    groups.filter { it.mainMangaId in mains }.forEach { group ->
        group.members.forEach { titleOf[it.mangaId] = group }
    }
    val resting = titleOf.values.flatMap { group -> group.members.filterNot { it.checkUpdates } }
        .mapTo(HashSet()) { it.mangaId }

    val (merged, alone) = upcoming.filterNot { it.id in resting }.partition { it.id in titleOf }
    return alone.map { it.manga } + merged
        .groupBy { titleOf.getValue(it.id).mainMangaId }
        .map { (mainId, members) ->
            mains.getValue(mainId).copy(nextUpdate = members.minOf { it.manga.nextUpdate })
        }
}

/** The list for [entries], which [upcomingEntries] answers soonest first, by the day in [zone]. */
fun upcomingUiModels(
    entries: List<Manga>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<UpcomingUiModel> {
    val models = ArrayList<UpcomingUiModel>(entries.size + 8)
    entries.groupBy { it.nextUpdate.toLocalDate(zone) }.forEach { (date, day) ->
        models += UpcomingUiModel.Header(date, day.size)
        day.mapTo(models) { UpcomingUiModel.Item(it) }
    }
    return models
}
