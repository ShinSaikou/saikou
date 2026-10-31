package com.shinsaikou.source.model

import kotlinx.serialization.json.JsonObject

/**
 * Manga as a source sees it. Field for field the same as the extension ABI's SManga
 * (url, title, thumbnail_url, artist, author, status, description, genre, update_strategy,
 * initialized, memo), renamed to Kotlin style. [url] is source-relative for extension sources
 * (HttpSource.setUrlWithoutDomain semantics) and a folder name for the Local source; the host
 * treats it as an opaque key and never parses it.
 */
data class MangaInfo(
    /** Source-scoped key. Together with the source id it identifies a manga. Never blank. */
    val url: String,
    /** Display title. Never blank for a listing entry; may be blank on a stub built from a URL only. */
    val title: String,
    /** Absolute cover URL, or null when the source did not provide one. */
    val thumbnailUrl: String? = null,
    /** Artist credit exactly as the source emits it, or null when unknown. */
    val artist: String? = null,
    /** Author credit exactly as the source emits it, or null when unknown. */
    val author: String? = null,
    /** Publication status; [MangaStatus.UNKNOWN] when the source did not report one. */
    val status: MangaStatus = MangaStatus.UNKNOWN,
    /** Synopsis as the source emits it, or null when unknown. */
    val description: String? = null,
    /**
     * Raw genre string exactly as the source emits it, comma separated ("Action, Drama").
     * Kept raw because the backup format and the database store the raw string; use [genres].
     */
    val genre: String? = null,
    /** Whether the library update pass may refresh this entry. */
    val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    /** True once [com.shinsaikou.source.Source.getMangaDetails] has filled the entry. */
    val initialized: Boolean = false,
    /** Source-private free-form metadata (tachiyomix 1.6 memo). Never shown; round-tripped as-is. */
    val memo: JsonObject = EMPTY_MEMO,
) {
    /** Split of [genre] with SManga.getGenres semantics: split on ",", trim, drop blanks, distinct. */
    fun genres(): List<String>? =
        genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()

    /**
     * Merge semantics of SManga.copyFrom used by upstream when details arrive: every non-null field of
     * [other] wins, [status] wins when not UNKNOWN, [updateStrategy] always wins, [initialized] is
     * OR'd. [url] and [title] are taken from [other] only when non-blank.
     */
    fun mergeDetails(other: MangaInfo): MangaInfo = copy(
        url = other.url.ifBlank { url },
        title = other.title.ifBlank { title },
        thumbnailUrl = other.thumbnailUrl ?: thumbnailUrl,
        artist = other.artist ?: artist,
        author = other.author ?: author,
        status = if (other.status != MangaStatus.UNKNOWN) other.status else status,
        description = other.description ?: description,
        genre = other.genre ?: genre,
        updateStrategy = other.updateStrategy,
        initialized = initialized || other.initialized,
        memo = if (other.memo.isEmpty()) memo else other.memo,
    )

    companion object {
        /** The default [memo]: an empty JSON object, shared because JsonObject is immutable. */
        val EMPTY_MEMO: JsonObject = JsonObject(emptyMap())
    }
}
