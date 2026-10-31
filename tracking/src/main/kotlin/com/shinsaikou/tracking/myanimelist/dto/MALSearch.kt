// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/dto/MALSearch.kt (modified: yes)
package com.shinsaikou.tracking.myanimelist.dto

import kotlinx.serialization.Serializable

@Serializable
data class MALSearchResult(
    val data: List<MALSearchResultNode>,
    val paging: MALSearchPaging,
)

@Serializable
data class MALSearchResultNode(
    val node: MALManga,
)

@Serializable
data class MALSearchPaging(
    val next: String?,
)
