package com.shinsaikou.domain.manga.interactor

import com.shinsaikou.domain.manga.model.MangaDetailsOverrides
import com.shinsaikou.domain.manga.model.detailsOverrides
import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The owner's edit of an entry's details (phase 5 unit ED, R138 item 2; R165 item 4), the one
 * writer of the override columns outside a sync apply and a restore. The source's own values stay
 * in `title`, `description`, `author`, `artist`, `status` and `genre`, which a refresh keeps
 * writing, so clearing an override shows the source's current value at once. Returns the
 * repository's own success flag, as [SetMangaViewerFlags] does.
 */
@Inject
class SetMangaDetailsOverride(
    private val mangaRepository: MangaRepository,
) {

    /**
     * What the Edit details dialog holds when Save is pressed: each text as typed, [status] a
     * `MangaStatus` code or null for "From source", and [genres] the chips in their order.
     */
    data class Edit(
        val title: String,
        val description: String,
        val author: String,
        val artist: String,
        val status: Long?,
        val genres: List<String>,
    )

    /**
     * Saves [edit] whole, each text trimmed. A value equal to the source's (trimmed too, so a source
     * text that ends in a newline still matches what the dialog showed) clears that override, so an
     * edit typed back to the source's value is no edit. A blank author or artist clears it as well,
     * as does "From source" and an empty genre list; an empty description over a source's is an edit
     * to no text (R162 item 12). A blank title is refused: nothing is written and the result is false.
     */
    suspend fun await(mangaId: Long, edit: Edit): Boolean {
        val editedTitle = edit.title.trim()
        if (editedTitle.isEmpty()) return false
        val editedDescription = edit.description.trim()
        val manga = mangaRepository.getMangaById(mangaId)
        return mangaRepository.setDetailsOverride(
            mangaId = mangaId,
            overrides = MangaDetailsOverrides(
                title = editedTitle.takeUnless { it == manga.title.trim() },
                description = editedDescription.takeUnless { it == manga.description.orEmpty().trim() },
                author = nameOverride(edit.author, manga.author),
                artist = nameOverride(edit.artist, manga.artist),
                status = edit.status?.takeUnless { it == manga.status },
                genre = genreOverride(edit.genres, manga.genre),
            ),
        )
    }

    /**
     * A migration's carry of [from]'s edits onto [to] (`MigrateManga`), inside its transaction: an
     * edit [from] has replaces [to]'s, a detail [from] never edited keeps [to]'s own, and an edit
     * equal to [to]'s source value is no edit, as [await] reads one. Nothing is written when that
     * changes nothing, so an unedited entry logs no details change; true then, and otherwise the
     * repository's own flag.
     */
    suspend fun awaitCarry(from: Manga, to: Manga): Boolean {
        if (!from.detailsOverrides.any) return true
        val carried = MangaDetailsOverrides(
            title = (from.titleOverride ?: to.titleOverride)?.takeUnless { it == to.title.trim() },
            description = (from.descriptionOverride ?: to.descriptionOverride)
                ?.takeUnless { it == to.description.orEmpty().trim() },
            author = (from.authorOverride ?: to.authorOverride)?.let { nameOverride(it, to.author) },
            artist = (from.artistOverride ?: to.artistOverride)?.let { nameOverride(it, to.artist) },
            status = (from.statusOverride ?: to.statusOverride)?.takeUnless { it == to.status },
            genre = (from.genreOverride ?: to.genreOverride)?.let { genreOverride(it, to.genre) },
        )
        if (carried == to.detailsOverrides) return true
        return mangaRepository.setDetailsOverride(mangaId = to.id, overrides = carried)
    }

    /** Reset all: clears every override, so the entry shows the source's details again. */
    suspend fun awaitReset(mangaId: Long): Boolean {
        return mangaRepository.setDetailsOverride(mangaId = mangaId, overrides = MangaDetailsOverrides.NONE)
    }

    /** [value] trimmed, or null when it is blank or the source's [source] (trimmed). */
    private fun nameOverride(value: String, source: String?): String? =
        value.trim().takeUnless { it.isEmpty() || it == source.orEmpty().trim() }

    /**
     * [genres] trimmed, without blanks and repeats, as the entry screen lists them; null when that
     * leaves none or equals the source's [source] read the same way.
     */
    private fun genreOverride(genres: List<String>, source: List<String>?): List<String>? =
        genres.cleaned().takeUnless { it.isEmpty() || it == source.orEmpty().cleaned() }

    private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
