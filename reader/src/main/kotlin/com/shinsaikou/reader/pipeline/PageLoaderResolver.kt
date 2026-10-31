package com.shinsaikou.reader.pipeline

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.source.StubSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.withContext
import tachiyomi.core.archive.archiveReader
import tachiyomi.core.archive.epubReader
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * Picks the loader for one chapter. Contract 07 section 4.1.
 *
 * Four branches, first match wins: a downloaded chapter, a Local-source chapter, a source that is
 * not installed, and everything else.
 *
 * The first branch asks [DownloadedChapters] rather than the manifest table, so a row whose folder
 * is gone falls through to the source like any chapter that was never downloaded (phase 2 decision
 * Q4). Contract 07 section 4.1 tested the row alone, which opened a missing folder as an empty
 * chapter. The file it answers is a folder for a chapter the engine wrote and an archive for one
 * adopted from upstream's downloads, and both are read by the Local source's own loaders.
 *
 * The `@ApplicationContext` qualifier section 4.1 puts on [context] does not exist in this tree and
 * Metro defines none; the merged convention is a bare `Context` parameter with `@Inject` on the
 * class (`REMAINING.md` section 3, defect 6).
 */
@SingleIn(AppScope::class)
@Inject
class PageLoaderResolver(
    private val downloadedChapters: DownloadedChapters,
    private val localChapters: LocalChapterResolver,
    private val chapterCache: ChapterCache,
    private val probe: ImageProbe,
    private val splitter: TallStripSplitter,
    private val readerPreferences: ReaderPreferences,
    private val dispatchers: Dispatchers,
    private val context: Context,
) {

    suspend fun resolve(
        manga: Manga,
        chapter: ReaderChapter,
        source: Source,
        preload: () -> PreloadPolicy.Window,
    ): PageLoader {
        val downloaded = downloadedChapters.find(chapter.chapter.id)
        if (downloaded != null) return DownloadedPageLoader(localLoader(downloaded.toFormat(), chapter.chapter.id))

        if (manga.source == SourceIds.LOCAL) {
            val format = localChapters.resolve(manga, chapter.chapter)
                ?: error("local chapter not found")
            return localLoader(format, chapter.chapter.id)
        }

        if (source is StubSource) throw SourceNotInstalledException(source.id)

        return SourcePageLoader(
            chapter = chapter,
            source = source,
            cache = chapterCache,
            probe = probe,
            splitter = splitter,
            readerPreferences = readerPreferences,
            dispatchers = dispatchers,
            preload = preload,
        )
    }

    /** A downloaded chapter is never an epub: the engine writes folders, and upstream writes folders or cbz. */
    private fun File.toFormat(): LocalChapterFormat {
        val file = checkNotNull(UniFile.fromFile(this))
        return if (isDirectory) LocalChapterFormat.Directory(file) else LocalChapterFormat.Archive(file)
    }

    /** Opening an archive is a file-descriptor call, so it belongs on IO rather than the caller's thread. */
    private suspend fun localLoader(format: LocalChapterFormat, chapterId: Long): LocalPageLoader =
        withContext(dispatchers.io) {
            when (format) {
                is LocalChapterFormat.Directory -> DirectoryPageLoader(
                    dir = format.dir,
                    chapterId = chapterId,
                    probe = probe,
                    splitter = splitter,
                    cache = chapterCache,
                    readerPreferences = readerPreferences,
                    dispatchers = dispatchers,
                )
                is LocalChapterFormat.Archive -> ArchivePageLoader(
                    reader = format.file.archiveReader(context),
                    chapterId = chapterId,
                    probe = probe,
                    splitter = splitter,
                    cache = chapterCache,
                    readerPreferences = readerPreferences,
                    dispatchers = dispatchers,
                )
                is LocalChapterFormat.Epub -> EpubPageLoader(
                    reader = format.file.epubReader(context),
                    chapterId = chapterId,
                    probe = probe,
                    splitter = splitter,
                    cache = chapterCache,
                    readerPreferences = readerPreferences,
                    dispatchers = dispatchers,
                )
            }
        }
}
