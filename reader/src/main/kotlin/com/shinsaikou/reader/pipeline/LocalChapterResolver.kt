package com.shinsaikou.reader.pipeline

import com.hippo.unifile.UniFile
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Resolves how a Local-source chapter is stored. Contract 07 section 1.2.
 *
 * `:reader` cannot see `:sources:local`, so `:app` binds this with
 * `@ContributesBinding(AppScope::class)` over the Local source's own format lookup. Null when the
 * chapter is not local.
 */
interface LocalChapterResolver {
    suspend fun resolve(manga: Manga, chapter: Chapter): LocalChapterFormat?
}

sealed interface LocalChapterFormat {
    data class Directory(val dir: UniFile) : LocalChapterFormat
    data class Archive(val file: UniFile) : LocalChapterFormat
    data class Epub(val file: UniFile) : LocalChapterFormat
}
