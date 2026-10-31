package com.shinsaikou.sources.local

import android.content.Context
import com.hippo.unifile.UniFile
import com.shinsaikou.core.metadata.comicinfo.ComicInfoXml
import com.shinsaikou.i18n.R
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.UnmeteredSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.Filter
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaPage
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.PageInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import okio.buffer
import okio.source
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.readChapterMetadata
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.time.Duration.Companion.days

/**
 * The source backed by a folder on the device: one directory per manga, one directory, archive or
 * epub per chapter. Unlike upstream's, this one serves pages and images too (contract 06 decision
 * F7), so the reader needs no local-only path.
 *
 * Every call touches the file system or the storage access framework, so every call switches to
 * [Dispatchers.IO] before doing anything.
 */
@SingleIn(AppScope::class)
@Inject
class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    private val archiveOpener: ArchiveOpener,
    private val json: Json,
    private val comicInfoXml: ComicInfoXml,
) : CatalogueSource, UnmeteredSource {

    override val id: Long = ID
    override val name: String = context.getString(R.string.local_source)
    override val lang: String = "other"
    override val supportsLatest: Boolean = true

    override suspend fun getPopularManga(page: Int): MangaPage =
        getSearchManga(page, "", FilterList(OrderBy.popular(context)))

    override suspend fun getLatestUpdates(page: Int): MangaPage =
        getSearchManga(page, "", FilterList(OrderBy.latest(context)))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangaPage =
        withContext(Dispatchers.IO) {
            val selection = filters.filterIsInstance<Filter.Sort>().firstOrNull()?.state
            val byDate = selection?.index == OrderBy.INDEX_DATE
            val ascending = selection?.ascending ?: true
            val lastModifiedLimit = if (byDate) System.currentTimeMillis() - LATEST_THRESHOLD else 0L

            val matching = fileSystem.getFilesInBaseDirectory()
                .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
                .distinctBy { it.name }
                .filter {
                    when {
                        lastModifiedLimit == 0L && query.isBlank() -> true
                        lastModifiedLimit == 0L -> it.name.orEmpty().contains(query, ignoreCase = true)
                        else -> it.lastModified() >= lastModifiedLimit
                    }
                }

            val ordered = when {
                byDate && ascending -> matching.sortedBy(UniFile::lastModified)
                byDate -> matching.sortedByDescending(UniFile::lastModified)
                ascending -> matching.sortedWith(compareBy(NAME_COLLATOR) { it.name.orEmpty() })
                else -> matching.sortedWith(compareByDescending(NAME_COLLATOR) { it.name.orEmpty() })
            }

            val mangas = ordered
                .map { directory -> async { listingEntry(directory) } }
                .awaitAll()

            // The listing is whole: there is never a second page.
            MangaPage(mangas, hasNextPage = false)
        }

    override fun getFilterList(): FilterList = FilterList(OrderBy.popular(context))

    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = withContext(Dispatchers.IO) {
        var details = manga.copy(initialized = true)
        coverManager.find(manga.url)?.let { details = details.copy(thumbnailUrl = it.uri.toString()) }

        try {
            details = applyMetadataFiles(details)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Could not read the local metadata of a manga folder" }
        }

        if (details.thumbnailUrl.isNullOrBlank()) {
            // No cover file yet: take the first image of the first chapter, as upstream does.
            val firstChapter = chapterFiles(manga.url).lastOrNull()
            if (firstChapter != null) {
                extractCover(manga.url, firstChapter)?.let { details = details.copy(thumbnailUrl = it.uri.toString()) }
            }
        }

        details
    }

    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = withContext(Dispatchers.IO) {
        val chapters = chapterFiles(manga.url)
            .map { file -> chapterInfo(manga, file) }
            .sortedWith { first, second -> second.name.compareToCaseInsensitiveNaturalOrder(first.name) }

        if (manga.thumbnailUrl.isNullOrBlank()) {
            chapterFiles(manga.url).lastOrNull()?.let { extractCover(manga.url, it) }
        }

        chapters
    }

    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = withContext(Dispatchers.IO) {
        val entryNames = when (val format = getFormat(chapter)) {
            is Format.Directory -> format.file.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .map { it.name.orEmpty() }
                .sortedWith(NAME_NATURAL_ORDER)
            is Format.Archive -> archiveOpener.readArchive(format.file) { reader ->
                reader.useEntries { entries ->
                    entries.filter { it.isFile && ImageUtil.isImage(it.name) }.map { it.name }.toList()
                }
            }.sortedWith(NAME_NATURAL_ORDER)
            // An epub's reading order is the spine, not the entry order, so it is kept as it comes.
            is Format.Epub -> archiveOpener.readEpub(format.file) { it.getImagesFromPages() }
        }

        entryNames.mapIndexed { index, entryName ->
            PageInfo(index = index, url = "${chapter.url}/$entryName", imageUrl = null)
        }
    }

    override suspend fun getImage(page: PageInfo): ImageResult = withContext(Dispatchers.IO) {
        val (mangaDirName, chapterEntry, entryPath) = splitPageUrl(page.url)
        val chapterFile = fileSystem.getMangaDirectory(mangaDirName)?.findFile(chapterEntry)
            ?: throw FileNotFoundException(page.url)

        when (val format = Format.valueOf(chapterFile)) {
            is Format.Directory -> {
                val file = format.file.findFile(entryPath) ?: throw FileNotFoundException(page.url)
                ImageResult.ofSource(
                    source = file.openInputStream().source().buffer(),
                    contentType = null,
                    contentLength = file.length(),
                    imageUrl = null,
                )
            }
            is Format.Archive -> {
                val bytes = archiveOpener.readArchive(format.file) { reader ->
                    reader.getInputStream(entryPath)?.use { it.readBytes() }
                } ?: throw FileNotFoundException(page.url)
                ImageResult.ofBytes(bytes, contentType = null)
            }
            is Format.Epub -> {
                val bytes = archiveOpener.readEpub(format.file) { reader ->
                    reader.getInputStream(entryPath)?.use { it.readBytes() }
                } ?: throw FileNotFoundException(page.url)
                ImageResult.ofBytes(bytes, contentType = null)
            }
        }
    }

    override fun toString(): String = name

    /** Re-derives the chapter's on-disk format from its url. Throws when the entry is gone. */
    suspend fun getFormat(chapter: ChapterInfo): Format = withContext(Dispatchers.IO) {
        val (mangaDirName, chapterEntry) = chapter.url.split('/', limit = 2)
            .takeIf { it.size == 2 }
            ?: throw Format.UnknownFormatException()
        val file = fileSystem.getMangaDirectory(mangaDirName)?.findFile(chapterEntry)
            ?: throw FileNotFoundException(chapter.url)
        Format.valueOf(file)
    }

    private fun listingEntry(directory: UniFile): MangaInfo {
        val folderName = directory.name.orEmpty()
        return MangaInfo(
            url = folderName,
            title = folderName,
            thumbnailUrl = coverManager.find(folderName)?.uri?.toString(),
        )
    }

    /** Every entry of a manga folder that is a chapter, in listing order. */
    private fun chapterFiles(mangaUrl: String): List<UniFile> = fileSystem.getFilesInMangaDirectory(mangaUrl)
        .filterNot { it.name.orEmpty().startsWith('.') }
        .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }

    private fun chapterInfo(manga: MangaInfo, file: UniFile): ChapterInfo {
        val entryName = if (file.isDirectory) file.name else file.nameWithoutExtension
        val chapter = ChapterInfo(
            url = "${manga.url}/${file.name}",
            name = entryName.orEmpty(),
            dateUpload = file.lastModified(),
            chapterNumber = ChapterRecognition
                .parseChapterNumber(manga.title, entryName.orEmpty(), ChapterInfo.UNKNOWN_NUMBER.toDouble())
                .toFloat(),
        )

        return try {
            if (file.extension.equals("epub", true)) {
                val metadata = archiveOpener.readEpub(file) { it.readChapterMetadata() }
                chapter.copy(
                    name = metadata.name ?: chapter.name,
                    scanlator = metadata.scanlator ?: chapter.scanlator,
                    dateUpload = metadata.dateUpload ?: chapter.dateUpload,
                )
            } else {
                val info = readComicInfo(file) ?: return chapter
                chapter.copy(
                    name = info.title?.value ?: chapter.name,
                    chapterNumber = info.number?.value?.toFloatOrNull() ?: chapter.chapterNumber,
                    scanlator = info.translator?.value ?: chapter.scanlator,
                )
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not read the metadata of a local chapter" }
            chapter
        }
    }

    /**
     * The three metadata sources, in upstream's precedence: a top-level `ComicInfo.xml`, then the
     * legacy JSON file, then the first `ComicInfo.xml` found inside a chapter. A folder that has
     * none gains a `.noxml` marker so the third step runs once and not on every refresh.
     */
    private fun applyMetadataFiles(manga: MangaInfo): MangaInfo {
        val mangaDir = fileSystem.getMangaDirectory(manga.url) ?: return manga
        val files = mangaDir.listFiles().orEmpty().toList()
        val comicInfoFile = files.firstOrNull { it.name == COMIC_INFO_FILE }
        val noXmlFile = files.firstOrNull { it.name == NO_XML_FILE }
        val legacyJsonFile = files.firstOrNull { it.extension == "json" }

        return when {
            comicInfoFile != null -> {
                noXmlFile?.delete()
                manga.copyFromComicInfo(comicInfoFile.openInputStream().use(comicInfoXml::decode))
            }

            legacyJsonFile != null -> {
                val migrated = manga.applyLegacyDetails(legacyJsonFile.openInputStream())
                if (writeComicInfo(mangaDir, migrated.toComicInfo())) {
                    legacyJsonFile.delete()
                }
                migrated
            }

            noXmlFile == null -> {
                val copied = copyComicInfoFromChapters(files, mangaDir)
                if (copied == null) {
                    // Nothing to find; remember that so the archives are not reopened next time.
                    mangaDir.createFile(NO_XML_FILE)
                    manga
                } else {
                    manga.copyFromComicInfo(copied.openInputStream().use(comicInfoXml::decode))
                }
            }

            else -> manga
        }
    }

    private fun MangaInfo.applyLegacyDetails(stream: InputStream): MangaInfo {
        val details = stream.use { json.decodeFromStream<MangaDetails>(it) }
        return copy(
            title = details.title ?: title,
            author = details.author ?: author,
            artist = details.artist ?: artist,
            description = details.description ?: description,
            genre = details.genre?.joinToString(", ") ?: genre,
            status = details.status?.let(MangaStatus::fromCode) ?: status,
        )
    }

    private fun copyComicInfoFromChapters(files: List<UniFile>, mangaDir: UniFile): UniFile? {
        files.filter(Archive::isSupported).forEach { archive ->
            val copied = archiveOpener.readArchive(archive) { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use { stream ->
                    mangaDir.createFile(COMIC_INFO_FILE)?.also { target ->
                        target.openOutputStream().use(stream::copyTo)
                    }
                }
            }
            if (copied != null) return copied
        }
        return null
    }

    private fun writeComicInfo(mangaDir: UniFile, info: ComicInfo): Boolean {
        val target = mangaDir.createFile(COMIC_INFO_FILE) ?: return false
        target.openOutputStream().use { comicInfoXml.encode(info, it) }
        return true
    }

    private fun readComicInfo(chapterFile: UniFile): ComicInfo? = if (chapterFile.isDirectory) {
        chapterFile.findFile(COMIC_INFO_FILE)?.openInputStream()?.use(comicInfoXml::decode)
    } else {
        archiveOpener.readArchive(chapterFile) { reader ->
            reader.getInputStream(COMIC_INFO_FILE)?.use(comicInfoXml::decode)
        }
    }

    /** Writes the first image of [chapterFile] as the manga's cover. Never throws. */
    private fun extractCover(mangaUrl: String, chapterFile: UniFile): UniFile? = try {
        when (val format = Format.valueOf(chapterFile)) {
            is Format.Directory -> format.file.listFiles().orEmpty()
                .sortedWith(compareBy(NAME_NATURAL_ORDER) { it.name.orEmpty() })
                .firstOrNull { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                ?.let { coverManager.update(mangaUrl, it.openInputStream()) }

            is Format.Archive -> archiveOpener.readArchive(format.file) { reader ->
                reader.useEntries { entries ->
                    entries.filter { it.isFile && ImageUtil.isImage(it.name) }
                        .sortedWith(compareBy(NAME_NATURAL_ORDER) { it.name })
                        .firstOrNull()
                }?.let { entry ->
                    reader.getInputStream(entry.name)?.let { coverManager.update(mangaUrl, it) }
                }
            }

            is Format.Epub -> archiveOpener.readEpub(format.file) { reader ->
                reader.getImagesFromPages().firstOrNull()
                    ?.let { reader.getInputStream(it) }
                    ?.let { coverManager.update(mangaUrl, it) }
            }
        }
    } catch (e: Throwable) {
        logcat(LogPriority.ERROR, e) { "Could not read a cover out of a local chapter" }
        null
    }

    /**
     * A page url is `<mangaDir>/<chapterEntry>/<entryPath>`. A chapter entry name can never hold a
     * separator, so the first two are unambiguous and everything after them is the entry path.
     */
    private fun splitPageUrl(url: String): Triple<String, String, String> {
        val parts = url.split('/', limit = 3)
        if (parts.size != 3) throw FileNotFoundException(url)
        return Triple(parts[0], parts[1], parts[2])
    }

    companion object {
        const val ID: Long = SourceIds.LOCAL

        /** Marks a manga folder whose chapters were already searched for a `ComicInfo.xml`. */
        const val NO_XML_FILE = ".noxml"

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds

        private val NAME_COLLATOR = Comparator<String> { first, second -> first.compareToWithCollator(second) }

        private val NAME_NATURAL_ORDER =
            Comparator<String> { first, second -> first.compareToCaseInsensitiveNaturalOrder(second) }
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID
