package com.shinsaikou.ui.upcoming

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
 */
fun upcomingEntries(
    library: List<LibraryManga>,
    included: Set<Long>,
    excluded: Set<Long>,
    startOfToday: Long,
): List<Manga> = library
    .filter { item ->
        val manga = item.manga
        manga.nextUpdate >= startOfToday &&
            manga.status in UPCOMING_STATUSES &&
            manga.updateStrategy != UpdateStrategy.ONLY_FETCH_ONCE &&
            item.categories.none { it in excluded } &&
            (included.isEmpty() || item.categories.any { it in included })
    }
    .map { it.manga }
    .sortedBy { it.nextUpdate }

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
