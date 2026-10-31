package com.shinsaikou.ui.history

import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.history.model.HistoryWithRelations

/** What the History list draws: upstream's day headers between the rows. */
sealed interface HistoryUiModel {
    /** The list's key, kept apart between kinds: a row keeps its history id. */
    val key: Any

    data class Header(val date: LocalDate) : HistoryUiModel {
        override val key: Any get() = "history-header-$date"
    }

    data class Item(val history: HistoryWithRelations) : HistoryUiModel {
        override val key: Any get() = history.id
    }
}

/**
 * The list for [items], which the query answers newest read first: a [HistoryUiModel.Header] before
 * the first row of each day, the day of `readAt` in [zone], as upstream's `toHistoryUiModels` reads
 * it. A row with no read date gets no header of its own.
 */
fun historyUiModels(
    items: List<HistoryWithRelations>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<HistoryUiModel> {
    val models = ArrayList<HistoryUiModel>(items.size + 8)
    var previous: LocalDate? = null
    items.forEach { item ->
        val date = item.readAt?.time?.toLocalDate(zone)
        if (date != null && date != previous) models += HistoryUiModel.Header(date)
        previous = date
        models += HistoryUiModel.Item(item)
    }
    return models
}
