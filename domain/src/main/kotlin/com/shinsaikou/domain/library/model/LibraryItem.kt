package com.shinsaikou.domain.library.model

import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The row the library screen renders. Built by GetLibraryItems, never by a repository.
 *
 * A merged title is one of these, its main entry's row with the group's counts, categories and
 * dates in [libraryManga], so every filter and sort reads it as it reads any other row.
 */
data class LibraryItem(
    val libraryManga: LibraryManga,
    /** From downloads_manifest; 0 until the download engine ships. */
    val downloadCount: Long,
    val unreadCount: Long,
    val isLocal: Boolean,
    /** "" when the source is not installed. */
    val sourceLanguage: String,
    /** A merged title's entries in chapter order, the main entry among them; else empty. */
    val members: List<Manga> = emptyList(),
) {
    val id: Long get() = libraryManga.id
    val manga: Manga get() = libraryManga.manga
}
