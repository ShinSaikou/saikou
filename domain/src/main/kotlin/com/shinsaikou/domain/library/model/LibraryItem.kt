package com.shinsaikou.domain.library.model

import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The row the library screen renders. Built by GetLibraryItems, never by a repository. */
data class LibraryItem(
    val libraryManga: LibraryManga,
    /** From downloads_manifest; 0 until the download engine ships. */
    val downloadCount: Long,
    val unreadCount: Long,
    val isLocal: Boolean,
    /** "" when the source is not installed. */
    val sourceLanguage: String,
) {
    val id: Long get() = libraryManga.id
    val manga: Manga get() = libraryManga.manga
}
