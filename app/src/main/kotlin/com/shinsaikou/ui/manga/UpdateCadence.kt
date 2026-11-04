package com.shinsaikou.ui.manga

import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import tachiyomi.domain.manga.model.Manga
import kotlin.math.absoluteValue

/**
 * What the entry's update interval button and dialog say. A skipped entry's next update only
 * drifts into the past, so it would read as due for as long as the skip lasted; the skip is said
 * instead. With automatic updates off, nothing runs on a schedule, so the date is the predicted
 * release and not a check.
 */
sealed interface UpdateCadence {
    data object FetchOnce : UpdateCadence
    data object CompletedSkipped : UpdateCadence
    data object UnreadSkipped : UpdateCadence
    data object NotStartedSkipped : UpdateCadence
    data object EveryUpdate : UpdateCadence
    data object Completed : UpdateCadence
    data object NoPrediction : UpdateCadence
    data class Custom(val days: Int, val nextCheck: LocalDate) : UpdateCadence
    data class Estimated(val days: Int, val nextCheck: LocalDate) : UpdateCadence

    /** The prediction alone, while no automatic update will act on it. */
    data class Predicted(val days: Int, val nextRelease: LocalDate) : UpdateCadence
}

/**
 * [manga]'s cadence under the smart update [restrictions]. The rules follow
 * `FlatLibraryUpdate.skipReason` in its order, so the button agrees with what the next automatic
 * update does. [chapterTotal] and [chapterRead] count the entry's chapters before the list's filters.
 */
internal fun updateCadenceOf(
    manga: Manga,
    restrictions: Set<String>,
    automaticUpdates: Boolean,
    chapterTotal: Int,
    chapterRead: Int,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): UpdateCadence {
    val completed = manga.status == MangaStatus.COMPLETED.code.toLong()
    val predicted = manga.fetchInterval != 0 && manga.nextUpdate != 0L
    val days = manga.fetchInterval.absoluteValue
    val next = manga.nextUpdate.toLocalDate(zone)
    return when {
        manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && chapterTotal > 0 -> UpdateCadence.FetchOnce
        !automaticUpdates -> when {
            completed -> UpdateCadence.Completed
            predicted -> UpdateCadence.Predicted(days, next)
            else -> UpdateCadence.NoPrediction
        }
        LibraryUpdateRestrictions.MANGA_NON_COMPLETED in restrictions && completed -> UpdateCadence.CompletedSkipped
        LibraryUpdateRestrictions.MANGA_HAS_UNREAD in restrictions && chapterRead < chapterTotal ->
            UpdateCadence.UnreadSkipped
        LibraryUpdateRestrictions.MANGA_NON_READ in restrictions && chapterTotal > 0 && chapterRead == 0 ->
            UpdateCadence.NotStartedSkipped
        LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD !in restrictions -> UpdateCadence.EveryUpdate
        manga.fetchInterval < 0 -> UpdateCadence.Custom(days, next)
        completed -> UpdateCadence.Completed
        predicted -> UpdateCadence.Estimated(days, next)
        else -> UpdateCadence.NoPrediction
    }
}
