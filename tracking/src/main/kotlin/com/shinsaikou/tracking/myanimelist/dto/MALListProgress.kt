package com.shinsaikou.tracking.myanimelist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One page of `GET users/@me/mangalist` asked with `fields=num_chapters,list_status` (phase 4 unit
 * TC): the owner's entries with their progress. A missing `paging` reads as the last page.
 */
@Serializable
data class MALListProgressPage(
    val data: List<MALListProgressItem>,
    val paging: MALSearchPaging = MALSearchPaging(next = null),
)

/**
 * One entry of the page. [listStatus] is null when the answer left it out, which the comparison
 * never reads as 0 chapters.
 */
@Serializable
data class MALListProgressItem(
    val node: MALListProgressNode,
    @SerialName("list_status")
    val listStatus: MALListItemStatus? = null,
)

/** The entry itself: its id, its title and its chapter count, 0 when MyAnimeList has none. */
@Serializable
data class MALListProgressNode(
    val id: Long,
    val title: String = "",
    @SerialName("num_chapters")
    val numChapters: Long = 0,
)
