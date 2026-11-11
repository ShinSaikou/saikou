// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/dto/MangaBakaListEntry.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: the package; [rating] is a number, not upstream's `Long`, since `GET
 * /v1/my/library/{series_id}` documents it as one; every field but [state] has a default, so an
 * answer that leaves one out still reads; and the state's mapping is [toMangaBakaStatus], which
 * reads a state this code does not know as reading where upstream threw.
 */
@Serializable
data class MangaBakaListResult(
    val data: MangaBakaListEntry,
)

@Serializable
data class MangaBakaListEntry(
    val state: String,
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("finish_date")
    val finishDate: String? = null,
    @SerialName("is_private")
    val isPrivate: Boolean = false,
    @SerialName("progress_chapter")
    val progressChapter: Double? = null,
    val rating: Double? = null,
)
