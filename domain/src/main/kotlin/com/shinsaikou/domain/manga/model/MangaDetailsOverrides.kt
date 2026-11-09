package com.shinsaikou.domain.manga.model

import tachiyomi.domain.manga.model.Manga

/**
 * The owner's edits of an entry's details, each null while that detail is unedited (phase 5 unit
 * ED, R138 item 2; R165 item 4): the six override columns, written whole by
 * `MangaRepository.setDetailsOverride`. [status] is a `MangaStatus` code and [genre] is stored as
 * the source's `genre` column is.
 */
data class MangaDetailsOverrides(
    val title: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: Long? = null,
    val genre: List<String>? = null,
) {
    /** Whether any detail is edited. */
    val any: Boolean
        get() = title != null || description != null || author != null || artist != null || status != null ||
            genre != null

    companion object {
        /** No edit at all: what Reset all writes. */
        val NONE = MangaDetailsOverrides()
    }
}

/** The six overrides [this] carries. */
val Manga.detailsOverrides: MangaDetailsOverrides
    get() = MangaDetailsOverrides(
        title = titleOverride,
        description = descriptionOverride,
        author = authorOverride,
        artist = artistOverride,
        status = statusOverride,
        genre = genreOverride,
    )
