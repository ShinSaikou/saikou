package com.shinsaikou.source.model

/**
 * Result of [com.shinsaikou.source.Source.getMangaUpdate]. A null field means that part was not
 * requested; an empty [chapters] list means the source returned no chapters.
 */
data class MangaUpdate(
    /** Refreshed details, or null when details were not requested. */
    val manga: MangaInfo?,
    /** Refreshed chapter list, or null when chapters were not requested. */
    val chapters: List<ChapterInfo>?,
)
