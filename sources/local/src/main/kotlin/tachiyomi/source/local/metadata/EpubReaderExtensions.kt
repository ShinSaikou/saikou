// Vendored from upstream v0.20.4: source-local/src/main/kotlin/tachiyomi/source/local/metadata/EpubReaderExtensions.kt (modified: yes)
// Modifications: fillMetadata(SManga, SChapter) becomes readChapterMetadata(), which returns the
// three chapter fields instead of mutating two mutable beans. The manga-level author and
// description upstream wrote from the same document are dropped: the local source takes those from
// ComicInfo.xml, and MangaInfo is immutable.
package tachiyomi.source.local.metadata

import tachiyomi.core.archive.EpubReader
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/** What an epub's package document says about the chapter it holds. Every field may be absent. */
data class EpubChapterMetadata(
    val name: String?,
    val scanlator: String?,
    val dateUpload: Long?,
)

/**
 * Reads this epub's own metadata. The date format is the one epub declares for dcterms:modified.
 */
fun EpubReader.readChapterMetadata(): EpubChapterMetadata {
    val ref = getPackageHref()
    val doc = getPackageDocument(ref)

    val title = doc.getElementsByTag("dc:title").first()
    val publisher = doc.getElementsByTag("dc:publisher").first()
    val creator = doc.getElementsByTag("dc:creator").first()
    var date = doc.getElementsByTag("dc:date").first()
    if (date == null) {
        date = doc.select("meta[property=dcterms:modified]").first()
    }

    val scanlator = publisher?.text() ?: creator?.text()

    val dateUpload = date?.let {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
        try {
            dateFormat.parse(it.text())?.time
        } catch (_: ParseException) {
            null
        }
    }

    return EpubChapterMetadata(name = title?.text(), scanlator = scanlator, dateUpload = dateUpload)
}
