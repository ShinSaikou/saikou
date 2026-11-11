// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/dto/MangaBakaItem.kt (modified: yes)
package com.shinsaikou.tracking.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modified: the package; every field but [MangaBakaItem.id] has a default, so one series that leaves
 * a field out does not fail a whole search; [MangaBakaItem.canonicalUrl] and
 * [MangaBakaItem.totalChapters] are read, which upstream ignored; and a series with no title in any
 * language falls back to its `title` field and then its id, where upstream wrote a sentence asking
 * the owner to report it.
 */
@Serializable
data class MangaBakaItemResult(
    val data: MangaBakaItem,
)

@Serializable
data class MangaBakaSearchResult(
    val data: List<MangaBakaItem>,
)

@Serializable
data class MangaBakaItem(
    val id: Long,
    val title: String = "",
    @SerialName("canonical_url")
    val canonicalUrl: String? = null,
    val cover: MangaBakaCover = MangaBakaCover(),
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val description: String? = null,
    val published: MangaBakaPublishData = MangaBakaPublishData(),
    val status: String = "",
    val type: String = "",
    val rating: Double? = null,
    /** A count as text, such as `"147"`; null while MangaBaka does not know it. */
    @SerialName("total_chapters")
    val totalChapters: String? = null,
    val titles: List<MangaBakaItemTitle>? = null,
) {
    fun chooseBestTitle(): String {
        // based on MangaBaka's titles v2 announcement ("finding the title you want"),
        // extended with zh-Latn and zh
        val bestTitlePerLanguage = TITLE_PRIORITIES.associateWith { lang ->
            titles?.filter { it.language == lang }
                ?.minByOrNull {
                    when {
                        it.isPrimary == true -> 0
                        "official" in it.traits -> 1
                        "native" in it.traits -> 2
                        else -> 3
                    }
                }
        }

        return TITLE_PRIORITIES
            .firstNotNullOfOrNull { bestTitlePerLanguage[it]?.title }
            ?: titles?.firstOrNull()?.title
            ?: title.ifBlank { null }
            ?: id.toString()
    }

    /** [totalChapters] as a whole count, 0 while MangaBaka has none. */
    fun chapterCount(): Long = totalChapters?.trim()?.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
}

@Serializable
data class MangaBakaCover(
    val x250: MangaBakaScaledCover = MangaBakaScaledCover(),
)

@Serializable
data class MangaBakaScaledCover(
    val x1: String? = null,
)

@Serializable
data class MangaBakaPublishData(
    @SerialName("start_date")
    val startDate: String? = null,
)

@Serializable
data class MangaBakaItemTitle(
    val language: String,
    val traits: List<String> = emptyList(),
    val title: String,
    @SerialName("is_primary")
    val isPrimary: Boolean? = null,
)

// Not on a companion: the serializer plugin puts `serializer()` on this class's own.
private val TITLE_PRIORITIES = listOf("en", "ja-Latn", "ja", "ko-Latn", "ko", "zh-Latn", "zh")
