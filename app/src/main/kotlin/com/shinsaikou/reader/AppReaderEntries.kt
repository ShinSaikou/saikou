package com.shinsaikou.reader

import com.shinsaikou.reader.state.ReaderEntries
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.manga.model.Manga

/** A title is one entry until the data layer can link several (R163). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppReaderEntries : ReaderEntries {

    override suspend fun of(manga: Manga): List<Manga> = listOf(manga)
}
