// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/dto/ALManga.kt (modified: yes)
package com.shinsaikou.tracking.anilist.dto

import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.anilist.Anilist
import com.shinsaikou.tracking.anilist.AnilistApi
import org.jsoup.parser.Parser
import tachiyomi.domain.track.model.Track
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Modified: a search hit becomes a [TrackSearchResult] and a list entry is laid over the stored row
 * ([ALUserManga.toTrack]), in place of upstream's mutable beans. The description is read as text by
 * [descriptionText], since upstream's `htmlDecode` is Android's HTML parser, which a JVM test cannot
 * run. A list status this code does not know reads as Reading, as MyAnimeList's does, where upstream
 * threw `NotImplementedError`, an `Error` the retry worker would not catch.
 */

data class ALManga(
    val remoteId: Long,
    val title: String,
    val imageUrl: String,
    val description: String?,
    val format: String,
    val publishingStatus: String,
    val startDateFuzzy: Long,
    val totalChapters: Long,
    val averageScore: Int,
    val staff: ALStaff,
) {
    fun toTrackSearchResult(): TrackSearchResult {
        val authors = mutableListOf<String>()
        val artists = mutableListOf<String>()
        staff.edges.forEach {
            val name = it.node.name() ?: return@forEach
            if ("Story" in it.role) authors += name
            if ("Art" in it.role) artists += name
        }
        val startDate = if (startDateFuzzy != 0L) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(startDateFuzzy)
            } catch (_: IllegalArgumentException) {
                ""
            }
        } else {
            ""
        }
        return TrackSearchResult(
            trackerId = TrackerIds.ANILIST,
            remoteId = remoteId,
            title = title,
            trackingUrl = AnilistApi.mangaUrl(remoteId),
            coverUrl = imageUrl,
            summary = description?.let(::descriptionText).orEmpty(),
            publishingStatus = publishingStatus,
            publishingType = format,
            startDate = startDate,
            totalChapters = totalChapters,
            authors = authors,
            artists = artists,
            score = averageScore.toDouble(),
        )
    }
}

data class ALUserManga(
    val libraryId: Long,
    val listStatus: String,
    val scoreRaw: Int,
    val chaptersRead: Int,
    val startDateFuzzy: Long,
    val completedDateFuzzy: Long,
    val manga: ALManga,
    val private: Boolean,
) {
    /** [base] with this list entry's fields, as upstream's `toTrack` fills a new bean with them. */
    fun toTrack(base: Track): Track = base.copy(
        remoteId = manga.remoteId,
        title = manga.title,
        status = toTrackStatus(),
        score = scoreRaw.toDouble(),
        startDate = startDateFuzzy,
        finishDate = completedDateFuzzy,
        lastChapterRead = chaptersRead.toDouble(),
        libraryId = libraryId,
        totalChapters = manga.totalChapters,
        private = private,
    )

    private fun toTrackStatus() = when (listStatus) {
        "CURRENT" -> Anilist.READING
        "COMPLETED" -> Anilist.COMPLETED
        "PAUSED" -> Anilist.ON_HOLD
        "DROPPED" -> Anilist.DROPPED
        "PLANNING" -> Anilist.PLAN_TO_READ
        "REPEATING" -> Anilist.REREADING
        else -> Anilist.READING
    }
}

private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val TAG = Regex("""<[^>]*>""")

/** AniList's description as plain text: line breaks kept, other tags dropped, entities decoded. */
internal fun descriptionText(html: String): String =
    Parser.unescapeEntities(html.replace(LINE_BREAK, "\n").replace(TAG, ""), false).trim()
