// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/dto/ALAddManga.kt (modified: yes) the package
package com.shinsaikou.tracking.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALAddMangaResult(
    val data: ALAddMangaData,
)

@Serializable
data class ALAddMangaData(
    @SerialName("SaveMediaListEntry")
    val entry: ALAddMangaEntry,
)

@Serializable
data class ALAddMangaEntry(
    val id: Long,
)
