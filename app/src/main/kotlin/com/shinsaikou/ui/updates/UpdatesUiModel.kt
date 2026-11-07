package com.shinsaikou.ui.updates

import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * What the Updates list draws, one entry per list item (phase 4 unit UG). Upstream's `UpdatesUiModel`
 * has the [Header] and the [Item]; the [Fold] is Saikou's, for consecutive chapters of one entry
 * inside a day.
 */
sealed interface UpdatesUiModel {
    /** The list's key, kept apart between kinds: a chapter row keeps its chapter id. */
    val key: Any

    /** Upstream's day header, an ordinary list item, not sticky. */
    data class Header(val date: LocalDate) : UpdatesUiModel {
        override val key: Any get() = "updates-header-$date"
    }

    /** One chapter's row: a chapter alone in its run, or one inside an expanded [Fold]. */
    data class Item(val update: UpdatesWithRelations, val inFold: Boolean = false) : UpdatesUiModel {
        override val key: Any get() = update.chapterId
    }

    /**
     * Two or more consecutive chapters of one entry fetched on one day, drawn as one row with the
     * entry and the count. When [expanded], the chapters follow it as [Item]s.
     *
     * The list key is the fold's oldest chapter, the one a new fetch of the same entry, landing on
     * top of the run, leaves in place.
     */
    data class Fold(
        val updates: List<UpdatesWithRelations>,
        val expanded: Boolean,
    ) : UpdatesUiModel {
        override val key: Any get() = "updates-fold-${updates.last().chapterId}"

        val chapterIds: List<Long> get() = updates.map { it.chapterId }
    }
}

/**
 * Whether a fold of [size] chapters starts expanded: three or fewer do, more start collapsed
 * (`design/plans/phase4-start.md` section 5). The one line to change to reverse that default.
 */
internal fun foldStartsExpanded(size: Int): Boolean = size <= 3

/**
 * The list for [items], which the query answers newest fetch first. A [UpdatesUiModel.Header] goes
 * before the first row of each day, the day read from `dateFetch` in [zone] as upstream's
 * `getUiModel` reads it. Inside a day, a run of consecutive rows of one entry, or of one merged title,
 * becomes a [UpdatesUiModel.Fold] when it holds two or more. An entry that updated on two days has a
 * fold, or a row, under each.
 *
 * [expanded] holds the owner's choices by chapter id: a tap on a fold writes its choice for every
 * chapter the fold holds. A fold takes the choice of its newest chapter that has one, or
 * [foldStartsExpanded] when none has. So the choice follows the chapters the owner saw, whatever
 * else the query adds above them or takes away: a fold that gains or loses a chapter keeps it, a
 * newer run of the same entry above it does not take it, and a fold split in two by another
 * entry's row leaves it on both halves.
 */
fun updatesUiModels(
    items: List<UpdatesWithRelations>,
    expanded: Map<Long, Boolean> = emptyMap(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<UpdatesUiModel> {
    val models = ArrayList<UpdatesUiModel>(items.size + 8)
    val days = items.map { it.dateFetch.toLocalDate(zone) }
    var start = 0
    while (start < items.size) {
        val date = days[start]
        if (start == 0 || days[start - 1] != date) {
            models += UpdatesUiModel.Header(date)
        }
        val titleId = items[start].titleId
        var end = start + 1
        while (end < items.size && items[end].titleId == titleId && days[end] == date) end++
        val run = items.subList(start, end)
        if (run.size == 1) {
            models += UpdatesUiModel.Item(run.single())
        } else {
            val open = run.firstNotNullOfOrNull { expanded[it.chapterId] } ?: foldStartsExpanded(run.size)
            models += UpdatesUiModel.Fold(run.toList(), open)
            if (open) run.forEach { models += UpdatesUiModel.Item(it, inFold = true) }
        }
        start = end
    }
    return models
}
