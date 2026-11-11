// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/dto/ComickSearchResult.kt (modified: yes)
package com.shinsaikou.tracking.comick.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: the package; every field but [ComickSearchItem.hid] has a default, so one title that
 * leaves a field out does not fail a whole search; and the item becomes a search result in
 * `ComickApi`, which skips an item whose HID does not decode, where upstream's `toTrackSearch` threw.
 * [publishingStatus] reads a status this code does not know as none, where upstream threw
 * `NotImplementedError`, and [publishingType] is upstream's mapping by country.
 */
@Serializable
data class ComickSearchResult(
    val data: List<ComickSearchItem>,
)

@Serializable
data class ComickTitleLookupResult(
    val data: ComickSearchItem,
)

@Serializable
data class ComickSearchItem(
    val hid: String,
    val title: String = "",
    val year: Int? = null,
    val description: String? = null,
    val status: Int? = null,
    @SerialName("bayesian_rating")
    val bayesianRating: Double? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val country: String? = null,
    val url: String = "",
) {
    fun publishingStatus(): String = when (status) {
        1 -> "Ongoing"
        2 -> "Completed"
        3 -> "Cancelled"
        4 -> "Hiatus"
        else -> ""
    }

    fun publishingType(): String = when (country) {
        "kr" -> "Manhwa"
        "cn" -> "Manhua"
        "fr" -> "Manfra"
        else -> "Manga"
    }
}
