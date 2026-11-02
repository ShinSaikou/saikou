package com.shinsaikou.reader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.image.ImageUtil
import com.shinsaikou.reader.viewer.PageImageActions
import com.shinsaikou.sources.local.isLocal
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.RoundingMode

/**
 * Save, share and set as cover, over the bytes a ready page exposes through `page.stream`.
 *
 * There is no file path in the reader's page API, deliberately: a split page's bytes are a part
 * file the reader owns. So every action here copies the stream once, and the destination decides
 * where to.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppPageImageActions(
    private val context: Context,
    private val savedPictures: SavedPictures,
    private val readerPreferences: ReaderPreferences,
    private val coverCache: CoverCache,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val savedPageNotifier: SavedPageNotifier,
    private val dispatchers: Dispatchers,
) : PageImageActions {

    /**
     * [SavedPictures] picks the place, one of three: the export tree when the owner has picked one
     * (nothing sets it today), the gallery's `Pictures/Saikou` through MediaStore on API 29 and later,
     * as upstream uses its own folder under Pictures, and the app's own external pictures folder on API 26 to 28, where
     * the public folder would need `WRITE_EXTERNAL_STORAGE`, which Saikou does not hold by decision.
     * In each, a folder per entry only while `create_folder_per_manga` is on, as upstream's save path
     * reads it (phase 4 unit SR); upstream's default is off, so a page saved by an owner who never set
     * it lands in the root. Once saved, [SavedPageNotifier] posts upstream's notification for it.
     */
    override suspend fun save(manga: Manga, chapter: Chapter, page: ReaderPage): Result<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val open = page.readyStream()
                val type = ImageUtil.findImageType(open)
                val mimeType = type?.mime ?: FALLBACK_MIME_TYPE
                savedPictures.save(
                    displayName = fileName(manga, chapter, page, type),
                    mimeType = mimeType,
                    subFolder = savedPageSubFolder(manga.title, readerPreferences.folderPerManga.get()),
                ) { output -> open().use { input -> input.copyTo(output) } }
                    .also { uri -> savedPageNotifier.showSaved(uri, mimeType, caption(manga, chapter, page)) }
            }
        }

    override suspend fun share(manga: Manga, chapter: Chapter, page: ReaderPage): Result<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val open = page.readyStream()
                val type = ImageUtil.findImageType(open)
                val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
                val target = File(directory, fileName(manga, chapter, page, type))
                open().use { input -> target.outputStream().use(input::copyTo) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", target)
                val mimeType = type?.mime ?: FALLBACK_MIME_TYPE
                context.startActivity(shareChooser(uri, mimeType, caption(manga, chapter, page)))
                uri
            }
        }

    override suspend fun setAsCover(manga: Manga, page: ReaderPage): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                val open = page.readyStream()
                when {
                    manga.isLocal() -> open().use { coverManager.update(manga.url, it) }
                        ?: throw IOException(context.getString(R.string.error_no_storage_location))
                    manga.favorite -> open().use { coverCache.setCustomCoverToCache(manga, it) }
                    // A cover has nowhere to live until the entry is in the library: the custom
                    // cover file is keyed on the manga id and nothing would ever read it back.
                    else -> throw IOException(context.getString(R.string.error_manga_not_in_library))
                }
                updateManga.awaitUpdateCoverLastModified(manga.id)
                Unit
            }
        }

    /** Upstream's caption for a shared page, "title: chapter, page N", for an app that shows text with it. */
    private fun caption(manga: Manga, chapter: Chapter, page: ReaderPage): String =
        context.getString(R.string.share_page_info, manga.displayTitle, chapter.name, page.number)

    private fun ReaderPage.readyStream(): () -> InputStream =
        stream ?: throw IOException(context.getString(R.string.error_page_not_ready))

    private fun fileName(manga: Manga, chapter: Chapter, page: ReaderPage, type: ImageUtil.ImageType?): String =
        savedPageFileName(
            mangaTitle = manga.title,
            chapterNumber = chapter.chapterNumber.takeIf { chapter.isRecognizedNumber },
            chapterName = chapter.name,
            pageNumber = page.number,
            extension = type?.extension ?: FALLBACK_EXTENSION,
        )

    private companion object {
        /** What an unrecognised magic-byte header is treated as, the way a browser would. */
        const val FALLBACK_EXTENSION = "jpg"
        const val FALLBACK_MIME_TYPE = "image/jpeg"
    }
}

/** Matches the `cache-path` entry of `provider_paths.xml`; nothing else is shareable. */
internal const val SHARE_DIRECTORY = "share"

/**
 * The system share sheet for [uri], a file under [SHARE_DIRECTORY] read through the app's provider or
 * a saved picture's content address, with [caption] as the text where there is one.
 */
internal fun shareChooser(uri: Uri, mimeType: String, caption: String? = null): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        caption?.let { putExtra(Intent.EXTRA_TEXT, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(null, uri)
    }
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The folder a saved page goes into inside the pictures folder: one named for [mangaTitle] while
 * [folderPerManga] is on, none while it is off. Upstream's `saveImage` builds the same relative path,
 * the title through `DiskUtil.buildValidFilename` or nothing.
 */
internal fun savedPageSubFolder(mangaTitle: String, folderPerManga: Boolean): String? =
    if (folderPerManga) DiskUtil.buildValidFilename(mangaTitle) else null

/**
 * A saved or shared page's file name, `<title> - <chapter number> - <chapter name> - <page
 * number>.<extension>`, made valid as a whole. The number keeps pages of two chapters that share a
 * name from writing over each other, since a page saved under a name already there replaces it;
 * a chapter whose number was not recognised has none.
 */
internal fun savedPageFileName(
    mangaTitle: String,
    chapterNumber: Double?,
    chapterName: String,
    pageNumber: Int,
    extension: String,
): String {
    val chapter = listOfNotNull(chapterNumber?.let(::chapterNumberText), chapterName).joinToString(" - ")
    return "${DiskUtil.buildValidFilename("$mangaTitle - $chapter - $pageNumber")}.$extension"
}

/**
 * 12.0 as `12` and 12.5 as `12.5`, with a dot whatever the locale, to the three places the chapter
 * list shows. A source's number is a `Float` the chapter sync widens, so 1.1 arrives as
 * 1.100000023841858.
 */
private fun chapterNumberText(number: Double): String =
    number.toBigDecimal().setScale(3, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
