package com.shinsaikou.source.model

import kotlinx.serialization.json.JsonObject

/**
 * Chapter as a source sees it. Mirrors SChapter: url, name, chapter_number, scanlator,
 * date_upload, memo. [chapterNumber] keeps SChapterImpl's -1f default as the "not parsed"
 * sentinel; ChapterRecognition in :domain fills it from [name] when it is negative.
 */
data class ChapterInfo(
    /** Source-scoped key, unique within a manga. Chapters are matched across refreshes by url alone. */
    val url: String,
    /** Display name as the source emits it, usually including the chapter number. */
    val name: String,
    /** Chapter number, or [UNKNOWN_NUMBER] when the source did not parse one. */
    val chapterNumber: Float = UNKNOWN_NUMBER,
    /** Scanlation group, or null when the source does not track one. */
    val scanlator: String? = null,
    /** Epoch milliseconds, 0 when the source did not provide one. */
    val dateUpload: Long = 0L,
    /** Source-private free-form metadata (tachiyomix 1.6 memo). Never shown; round-tripped as-is. */
    val memo: JsonObject = MangaInfo.EMPTY_MEMO,
) {
    /** Whether the source set a real [chapterNumber] rather than leaving the sentinel. */
    fun hasKnownNumber(): Boolean = chapterNumber >= 0f

    companion object {
        /** SChapterImpl's default: the source did not set a number. */
        const val UNKNOWN_NUMBER: Float = -1f
    }
}
