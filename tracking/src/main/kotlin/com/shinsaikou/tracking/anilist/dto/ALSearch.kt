// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/anilist/dto/ALSearch.kt (modified: yes) the package
package com.shinsaikou.tracking.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALSearchResult(
    val data: ALSearchPage,
)

@Serializable
data class ALSearchPage(
    @SerialName("Page")
    val page: ALSearchMedia,
)

@Serializable
data class ALSearchMedia(
    val media: List<ALSearchItem>,
)
