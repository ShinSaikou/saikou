// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/dto/ALUserList.kt (modified: yes) the package
package com.shinsaikou.tracking.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALUserListMangaQueryResult(
    val data: ALUserListMangaPage,
)

@Serializable
data class ALUserListMangaPage(
    @SerialName("Page")
    val page: ALUserListMediaList,
)

@Serializable
data class ALUserListMediaList(
    val mediaList: List<ALUserListItem>,
)

@Serializable
data class ALUserListItem(
    val id: Long,
    val status: String,
    val scoreRaw: Int,
    val progress: Int,
    val startedAt: ALFuzzyDate,
    val completedAt: ALFuzzyDate,
    val media: ALSearchItem,
    val private: Boolean,
) {
    fun toALUserManga(): ALUserManga {
        return ALUserManga(
            libraryId = this@ALUserListItem.id,
            listStatus = status,
            scoreRaw = scoreRaw,
            chaptersRead = progress,
            startDateFuzzy = startedAt.toEpochMilli(),
            completedDateFuzzy = completedAt.toEpochMilli(),
            manga = media.toALManga(),
            private = private,
        )
    }
}
