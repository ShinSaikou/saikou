package com.shinsaikou.downloads.adoption

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSourceNames
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Matches upstream's download tree, `<downloads>/<source>/<manga>/<chapter folder or .cbz>`, to
 * Saikou's rows, every level by the names [DownloadProvider] gives (phase 2 decision Q6(c)(i)):
 *
 * 1. A source folder to a source by `getSourceDirName` of the name upstream gives a source,
 *    `"<name> (<LANG>)"` (upstream's `HttpSource.toString`), under both values of the ASCII setting.
 *    The comparison ignores case, as upstream's own `DownloadCache` does when it reads the folder. The
 *    candidates are the installed sources and, for a source that is not installed, every source of
 *    a library entry through the name and language [StubSourceNames] remembers for its id. A folder
 *    must name exactly one of them. A backup restore remembers a source's name with no language
 *    (upstream's backup carries none), so such a source is keyed by its name alone, and answers a
 *    folder `"<name> (<any language>)"` only when no source matched it by name and language.
 * 2. A manga folder to the library entries of that source whose `getMangaDirName` equals it, under
 *    either ASCII setting. Upstream keeps one folder for entries of one source with one title, so a
 *    folder can stand for more than one entry, and its chapters are matched against all of theirs.
 * 3. A chapter folder, or a file ending in `.cbz`, to the chapter one of whose
 *    `getValidChapterDirNames` (current and legacy names, folder and archive) it is, exactly. A name
 *    two chapters share matches neither. When two entries match one chapter, the one earlier in that
 *    chapter's name list is taken, which is the one upstream itself would read.
 *
 * Anything that matches nothing is listed as unmatched, by its path relative to the picked tree, and
 * left where it is: a source or manga folder once, without its contents, and a `_tmp` folder,
 * which is a download upstream had not finished. Other files are not chapters and are passed over.
 * Each is listed with its [AdoptionUnmatchedReason], the source folder's name and language, the
 * entry folder's name, and for a chapter that matched no chapter the entry it was matched against
 * (unit adoption-unmatched-reasons), so the summary can name the action that would match it.
 *
 * The owner may pick upstream's storage folder or the `downloads` folder inside it; a tree with a
 * `downloads` folder at its top is taken for the first.
 */
@Inject
@SingleIn(AppScope::class)
class AdoptionWalker(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    private val stubSourceNames: StubSourceNames,
    private val libraryPreferences: LibraryPreferences,
    private val dispatchers: Dispatchers,
) {

    /** A chapter the walker matched: [node] is its folder or `.cbz` in the tree, at [path]. */
    data class Match(
        val node: AdoptionNode,
        val path: String,
        val manga: Manga,
        val chapter: Chapter,
        /** The name of the source in Saikou, which names the source folder the copy goes under. */
        val sourceName: String,
    ) {
        val isArchive: Boolean get() = !node.isDirectory
    }

    data class Result(val matches: List<Match>, val unmatched: List<AdoptionUnmatched>)

    /** A source folder's name read as upstream writes it; [lang] is null when it does not read that way. */
    private data class SourceFolder(val name: String, val lang: String?)

    suspend fun walk(tree: AdoptionNode): Result {
        // WorkManager resumes a run after process death with nobody waiting, and a source that has
        // not loaded yet would be matched through its remembered name at best, or not at all
        // (ruling R69, item 4). The wait is cancellable, so a stop during it ends the run as any
        // other stop does.
        sourceManager.isInitialized.first { it }
        val library = mangaRepository.getFavorites().filter { it.source != SourceIds.LOCAL }
        val sources = sourceNames(library)
        val sourceKeys = mutableMapOf<String, MutableSet<Long>>()
        val keysWithoutLang = mutableMapOf<String, MutableSet<Long>>()
        sources.forEach { (id, names) ->
            val (name, lang) = names
            val keys = if (lang.isBlank()) keysWithoutLang else sourceKeys
            val dirName = if (lang.isBlank()) name else upstreamSourceName(name, lang)
            ASCII_SETTINGS.forEach { ascii ->
                keys.getOrPut(DownloadProvider.getSourceDirName(dirName, ascii).lowercase()) { mutableSetOf() } += id
            }
        }
        val libraryBySource = library.groupBy { it.source }

        val matches = mutableListOf<Match>()
        val unmatched = mutableListOf<AdoptionUnmatched>()
        withContext(dispatchers.io) {
            val downloads = tree.children().firstOrNull { it.isDirectory && it.name == DOWNLOADS_DIR }
            val (root, prefix) = if (downloads != null) downloads to "$DOWNLOADS_DIR/" else tree to ""

            root.children().filter { it.isDirectory && it.name.isNotBlank() }.sortedBy { it.name }.forEach { sourceDir ->
                currentCoroutineContext().ensureActive()
                val sourcePath = prefix + sourceDir.name
                val key = sourceDir.name.lowercase()
                val sourceId = (sourceKeys[key] ?: withoutLang(key)?.let(keysWithoutLang::get))?.singleOrNull()
                val (folderName, folderLang) = parseSourceDirName(sourceDir.name)
                val folder = SourceFolder(folderName, folderLang)
                if (sourceId == null) {
                    unmatched += AdoptionUnmatched(
                        path = sourcePath,
                        reason = AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED,
                        sourceName = folder.name,
                        sourceLang = folder.lang,
                    )
                    return@forEach
                }
                val entries = libraryBySource[sourceId].orEmpty()
                walkSource(sourceDir, sourcePath, folder, entries, sources.getValue(sourceId).first, matches, unmatched)
            }
        }
        return Result(matches, unmatched)
    }

    private suspend fun walkSource(
        sourceDir: AdoptionNode,
        sourcePath: String,
        folder: SourceFolder,
        entries: List<Manga>,
        sourceName: String,
        matches: MutableList<Match>,
        unmatched: MutableList<AdoptionUnmatched>,
    ) {
        val entriesByDirName = mutableMapOf<String, MutableList<Manga>>()
        entries.forEach { manga ->
            ASCII_SETTINGS.map { DownloadProvider.getMangaDirName(manga.title, it) }.distinct().forEach { name ->
                entriesByDirName.getOrPut(name) { mutableListOf() } += manga
            }
        }
        sourceDir.children().filter { it.isDirectory && it.name.isNotBlank() }.sortedBy { it.name }.forEach { mangaDir ->
            currentCoroutineContext().ensureActive()
            val mangaPath = "$sourcePath/${mangaDir.name}"
            val mangas = entriesByDirName[mangaDir.name]
            if (mangas == null) {
                unmatched += AdoptionUnmatched(
                    path = mangaPath,
                    reason = AdoptionUnmatchedReason.NOT_IN_LIBRARY,
                    sourceName = folder.name,
                    sourceLang = folder.lang,
                    title = mangaDir.name,
                )
                return@forEach
            }
            walkManga(mangaDir, mangaPath, folder, mangas, sourceName, matches, unmatched)
        }
    }

    private suspend fun walkManga(
        mangaDir: AdoptionNode,
        mangaPath: String,
        folder: SourceFolder,
        mangas: List<Manga>,
        sourceName: String,
        matches: MutableList<Match>,
        unmatched: MutableList<AdoptionUnmatched>,
    ) {
        val disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get()
        // Every name of every chapter, with the chapter's own preference order kept for the tie break.
        val chaptersByName = mutableMapOf<String, MutableSet<Long>>()
        val namesByChapter = mutableMapOf<Long, List<String>>()
        val chapters = mutableMapOf<Long, Pair<Manga, Chapter>>()
        mangas.forEach { manga ->
            chapterRepository.getChapterByMangaId(manga.id).forEach { chapter ->
                val names = DownloadProvider.getValidChapterDirNames(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    disallowNonAscii,
                )
                namesByChapter[chapter.id] = names
                chapters[chapter.id] = manga to chapter
                names.forEach { chaptersByName.getOrPut(it) { mutableSetOf() } += chapter.id }
            }
        }

        fun unmatchedChapter(name: String, reason: AdoptionUnmatchedReason) = AdoptionUnmatched(
            path = "$mangaPath/$name",
            reason = reason,
            sourceName = folder.name,
            sourceLang = folder.lang,
            title = mangaDir.name,
            // The entry whose chapter list would have to name it; the first, when one folder stands
            // for several entries.
            mangaId = mangas.first().id.takeIf { reason == AdoptionUnmatchedReason.NO_CHAPTER },
        )

        val candidates = mutableMapOf<Long, MutableList<AdoptionNode>>()
        mangaDir.children().filter { it.name.isNotBlank() }.sortedBy { it.name }.forEach { entry ->
            val isChapter = entry.isDirectory || entry.name.endsWith(ARCHIVE_SUFFIX)
            if (!isChapter) return@forEach
            if (entry.name.endsWith(DownloadProvider.TMP_DIR_SUFFIX)) {
                unmatched += unmatchedChapter(entry.name, AdoptionUnmatchedReason.UNFINISHED)
                return@forEach
            }
            val chapterId = chaptersByName[entry.name]?.singleOrNull()
            if (chapterId == null) {
                unmatched += unmatchedChapter(entry.name, AdoptionUnmatchedReason.NO_CHAPTER)
                return@forEach
            }
            candidates.getOrPut(chapterId) { mutableListOf() } += entry
        }

        candidates.forEach { (chapterId, nodes) ->
            val names = namesByChapter.getValue(chapterId)
            val ordered = nodes.sortedBy { names.indexOf(it.name) }
            val (manga, chapter) = chapters.getValue(chapterId)
            matches += Match(ordered.first(), "$mangaPath/${ordered.first().name}", manga, chapter, sourceName)
            ordered.drop(1).forEach { unmatched += unmatchedChapter(it.name, AdoptionUnmatchedReason.DUPLICATE) }
        }
    }

    /**
     * Name and language per source id: the installed sources, and each library source that is not
     * installed through the name remembered for it. A library source with nothing remembered cannot
     * be named, so its folder cannot be matched.
     */
    private suspend fun sourceNames(library: List<Manga>): Map<Long, Pair<String, String>> {
        val names = mutableMapOf<Long, Pair<String, String>>()
        sourceManager.getSources().first()
            .filter { it.id != SourceIds.LOCAL }
            .forEach { names[it.id] = it.name to it.lang }
        library.map { it.source }.distinct().filter { it !in names }.forEach { id ->
            stubSourceNames.find(id)?.let { names[id] = it.name to it.lang }
        }
        return names
    }

    companion object {
        /** Upstream's `StorageManager.DOWNLOADS_PATH`. */
        const val DOWNLOADS_DIR = "downloads"

        private const val ARCHIVE_SUFFIX = ".cbz"

        private val ASCII_SETTINGS = listOf(false, true)

        /** What upstream's `HttpSource.toString` returns, which is what it names a source's folder after. */
        fun upstreamSourceName(name: String, lang: String): String = "$name (${lang.uppercase()})"

        /** A language code as [upstreamSourceName] writes it: letters, digits and hyphens, in parentheses at the end. */
        private val LANG_SUFFIX = Regex(" \\([a-z0-9-]+\\)$")

        /** [key], a lowercased source folder name, without the language [upstreamSourceName] ends it with. */
        private fun withoutLang(key: String): String? =
            LANG_SUFFIX.find(key)?.let { key.substring(0, it.range.first) }

        /** [LANG_SUFFIX] on a folder name as it is written, capturing the language. */
        private val WRITTEN_LANG_SUFFIX = Regex(" \\(([A-Za-z0-9-]+)\\)$")

        /**
         * A source folder's name read back into the name and language [upstreamSourceName] built it
         * from, the language as the folder spells it; the whole name and no language when it does
         * not end in one.
         */
        internal fun parseSourceDirName(dirName: String): Pair<String, String?> {
            val suffix = WRITTEN_LANG_SUFFIX.find(dirName)
            if (suffix == null || suffix.range.first == 0) return dirName to null
            return dirName.substring(0, suffix.range.first) to suffix.groupValues[1]
        }
    }
}
