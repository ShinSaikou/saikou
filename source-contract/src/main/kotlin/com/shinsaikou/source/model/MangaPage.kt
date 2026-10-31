package com.shinsaikou.source.model

/** One page of a listing. Mirrors MangasPage. */
data class MangaPage(
    /** The entries on this listing page, in the source's own order. */
    val mangas: List<MangaInfo>,
    /** Whether asking for the next page number can yield more entries. */
    val hasNextPage: Boolean,
) {
    companion object {
        /** No entries and no next page; the browse screens render this as an empty state. */
        val EMPTY = MangaPage(emptyList(), hasNextPage = false)
    }
}
