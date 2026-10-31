// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/AnilistUtils.kt (modified: yes)
package com.shinsaikou.tracking.anilist

import tachiyomi.domain.track.model.Track

// Modified: the package and the domain Track row; [toApiScore] takes the score format, where upstream
// read the preference through Injekt. A status this code does not know is null, and the API then
// sends CURRENT, as MyAnimeList sends reading; an unknown score format reads as POINT_10. Upstream
// threw `NotImplementedError` for both, an `Error` the retry worker would not catch.

fun Track.toApiStatus(): String? = when (status) {
    Anilist.READING -> "CURRENT"
    Anilist.COMPLETED -> "COMPLETED"
    Anilist.ON_HOLD -> "PAUSED"
    Anilist.DROPPED -> "DROPPED"
    Anilist.PLAN_TO_READ -> "PLANNING"
    Anilist.REREADING -> "REPEATING"
    else -> null
}

/** The score, stored on AniList's 100-point scale, as [scoreType] writes it. */
fun Track.toApiScore(scoreType: String): String = when (scoreType) {
    // 100 point
    Anilist.POINT_100 -> score.toInt().toString()
    // 5 stars
    Anilist.POINT_5 -> when {
        score == 0.0 -> "0"
        score < 30 -> "1"
        score < 50 -> "2"
        score < 70 -> "3"
        score < 90 -> "4"
        else -> "5"
    }
    // Smiley
    Anilist.POINT_3 -> when {
        score == 0.0 -> "0"
        score <= 35 -> ":("
        score <= 60 -> ":|"
        else -> ":)"
    }
    // 10 point decimal
    Anilist.POINT_10_DECIMAL -> (score / 10).toString()
    // 10 point
    else -> (score.toInt() / 10).toString()
}
