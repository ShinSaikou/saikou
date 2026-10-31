package com.shinsaikou.data.chapter

import com.shinsaikou.domain.chapter.model.BookmarkNote

/** The `bookmark_notes` columns in declaration order. */
fun mapBookmarkNote(
    id: Long,
    chapterId: Long,
    pageIndex: Long,
    note: String,
    dateAdded: Long,
): BookmarkNote = BookmarkNote(
    id = id,
    chapterId = chapterId,
    pageIndex = pageIndex,
    note = note,
    dateAdded = dateAdded,
)
