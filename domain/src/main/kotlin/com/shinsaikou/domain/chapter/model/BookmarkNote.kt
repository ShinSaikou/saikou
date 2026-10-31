package com.shinsaikou.domain.chapter.model

/** A note attached to one page of one chapter. */
data class BookmarkNote(
    val id: Long,
    val chapterId: Long,
    val pageIndex: Long,
    val note: String,
    val dateAdded: Long,
)
