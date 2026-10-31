// Vendored from upstream v0.20.4: core-metadata/src/main/java/tachiyomi/core/metadata/comicinfo/ComicInfo.kt (modified: yes)
// Modifications: the SManga extension functions getComicInfo/copyFromComicInfo are dropped (they
// live in :sources:local against MangaInfo; :core:metadata may not depend on the source contract),
// and ComicInfoPublishingStatus carries the status codes as literals instead of SManga constants.
// The ComicInfo model, its wrapper classes and every serial name are upstream's, unchanged.
package tachiyomi.core.metadata.comicinfo

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

const val COMIC_INFO_FILE = "ComicInfo.xml"

// https://anansi-project.github.io/docs/comicinfo/schemas/v2.0
@Suppress("UNUSED")
@Serializable
@XmlSerialName("ComicInfo", "", "")
data class ComicInfo(
    val title: Title?,
    val series: Series?,
    val number: Number?,
    val summary: Summary?,
    val writer: Writer?,
    val penciller: Penciller?,
    val inker: Inker?,
    val colorist: Colorist?,
    val letterer: Letterer?,
    val coverArtist: CoverArtist?,
    val translator: Translator?,
    val genre: Genre?,
    val tags: Tags?,
    val web: Web?,
    val publishingStatus: PublishingStatusTachiyomi?,
    val categories: CategoriesTachiyomi?,
    val source: SourceName?,
) {
    @XmlElement(false)
    @XmlSerialName("xmlns:xsd", "", "")
    val xmlSchema: String = "http://www.w3.org/2001/XMLSchema"

    @XmlElement(false)
    @XmlSerialName("xmlns:xsi", "", "")
    val xmlSchemaInstance: String = "http://www.w3.org/2001/XMLSchema-instance"

    @Serializable
    @XmlSerialName("Title", "", "")
    data class Title(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Series", "", "")
    data class Series(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Number", "", "")
    data class Number(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Summary", "", "")
    data class Summary(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Writer", "", "")
    data class Writer(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Penciller", "", "")
    data class Penciller(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Inker", "", "")
    data class Inker(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Colorist", "", "")
    data class Colorist(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Letterer", "", "")
    data class Letterer(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("CoverArtist", "", "")
    data class CoverArtist(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Translator", "", "")
    data class Translator(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Genre", "", "")
    data class Genre(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Tags", "", "")
    data class Tags(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Web", "", "")
    data class Web(@XmlValue(true) val value: String = "")

    // The spec doesn't have a good field for this
    @Serializable
    @XmlSerialName("PublishingStatusTachiyomi", "http://www.w3.org/2001/XMLSchema", "ty")
    data class PublishingStatusTachiyomi(@XmlValue(true) val value: String = "")

    @Serializable
    @XmlSerialName("Categories", "http://www.w3.org/2001/XMLSchema", "ty")
    data class CategoriesTachiyomi(@XmlValue(true) val value: String = "")

    @Serializable
    // The element name and prefix are upstream's wire format: other readers find the source under
    // this name in the ComicInfo.xml of an exported or copied chapter, so they must not change.
    @XmlSerialName("SourceMihon", "http://www.w3.org/2001/XMLSchema", "mh")
    data class SourceName(@XmlValue(true) val value: String = "")
}

/**
 * Two-way lookup between the publication status codes the source contract, the database and the
 * backup format all carry (`MangaStatus.code`, upstream's SManga constants) and the strings written
 * into ComicInfo.xml.
 */
enum class ComicInfoPublishingStatus(
    val comicInfoValue: String,
    val statusCode: Int,
) {
    ONGOING("Ongoing", 1),
    COMPLETED("Completed", 2),
    LICENSED("Licensed", 3),
    PUBLISHING_FINISHED("Publishing finished", 4),
    CANCELLED("Cancelled", 5),
    ON_HIATUS("On hiatus", 6),
    UNKNOWN("Unknown", 0),
    ;

    companion object {
        fun toComicInfoValue(value: Long): String {
            return entries.firstOrNull { it.statusCode == value.toInt() }?.comicInfoValue
                ?: UNKNOWN.comicInfoValue
        }

        fun toStatusCode(value: String?): Int {
            return entries.firstOrNull { it.comicInfoValue == value }?.statusCode
                ?: UNKNOWN.statusCode
        }
    }
}
