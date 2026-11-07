package com.shinsaikou.reader.state

import tachiyomi.domain.manga.model.Manga

/**
 * The entries a title is read from, the one the session stands on first: its title, reading mode
 * and chapter filters. A chapter is served by the entry it belongs to ([ReaderSession.ownerOf]), so
 * a title merged from several sources reads each chapter from its own source (R163).
 *
 * `:reader` cannot see how entries are linked, so `:app` binds this.
 */
fun interface ReaderEntries {

    /** Every entry of the title [manga] belongs to, never empty. */
    suspend fun of(manga: Manga): List<Manga>
}
