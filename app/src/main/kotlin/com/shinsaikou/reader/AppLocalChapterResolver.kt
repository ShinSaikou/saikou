package com.shinsaikou.reader

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.reader.pipeline.LocalChapterFormat
import com.shinsaikou.reader.pipeline.LocalChapterResolver
import com.shinsaikou.sources.local.LocalSource
import com.shinsaikou.sources.local.isLocal
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.io.Format

/**
 * Resolves a Local-source chapter to the directory, archive or epub that holds its pages.
 *
 * `:reader` declares the interface and no module implements it, because the answer needs
 * `:sources:local`'s file system and `:reader` may not depend on that module. The app module is
 * the only place that sees both, so ruling R22 assigns the implementation here.
 *
 * The two format types are structurally identical by design: [Format] is the Local source's own,
 * [LocalChapterFormat] is the reader's, and this class is the seam between them.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppLocalChapterResolver(
    private val localSource: LocalSource,
    private val dispatchers: Dispatchers,
) : LocalChapterResolver {

    override suspend fun resolve(manga: Manga, chapter: Chapter): LocalChapterFormat? {
        if (!manga.isLocal()) return null
        return withContext(dispatchers.io) {
            // getFormat throws when the entry is gone or unrecognised; a missing file is a normal
            // outcome here, so it becomes null and the page loader reports "no pages" instead.
            val format = runCatching { localSource.getFormat(chapter.toChapterInfo()) }
                .onFailure { logcat(LogPriority.WARN, it) { "No local chapter file for chapter ${chapter.id}" } }
                .getOrNull()
            when (format) {
                is Format.Directory -> LocalChapterFormat.Directory(format.file)
                is Format.Archive -> LocalChapterFormat.Archive(format.file)
                is Format.Epub -> LocalChapterFormat.Epub(format.file)
                null -> null
            }
        }
    }
}
