// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaUtils.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka

import tachiyomi.domain.track.model.Track

// Modified: the package and the domain Track row; the reverse mapping, which upstream kept on its list
// entry DTO, lives here too. A status or a state this code does not know is reading, as MyAnimeList's
// and AniList's are: upstream threw `NotImplementedError`, an `Error` the retry worker would not catch.

fun Track.toApiStatus(): String = when (status) {
    MangaBaka.CONSIDERING -> "considering"
    MangaBaka.COMPLETED -> "completed"
    MangaBaka.DROPPED -> "dropped"
    MangaBaka.PAUSED -> "paused"
    MangaBaka.PLAN_TO_READ -> "plan_to_read"
    MangaBaka.READING -> "reading"
    MangaBaka.REREADING -> "rereading"
    else -> "reading"
}

fun toMangaBakaStatus(state: String): Long = when (state) {
    "considering" -> MangaBaka.CONSIDERING
    "completed" -> MangaBaka.COMPLETED
    "dropped" -> MangaBaka.DROPPED
    "paused" -> MangaBaka.PAUSED
    "plan_to_read" -> MangaBaka.PLAN_TO_READ
    "reading" -> MangaBaka.READING
    "rereading" -> MangaBaka.REREADING
    else -> MangaBaka.READING
}
