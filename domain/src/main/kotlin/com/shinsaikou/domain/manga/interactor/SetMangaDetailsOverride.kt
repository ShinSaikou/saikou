package com.shinsaikou.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * The owner's edit of an entry's title and description (phase 5 unit ED, R138 item 2), the one
 * writer of the override columns outside a sync apply. The source's own text stays in `title` and
 * `description`, which a refresh keeps writing, so clearing an override shows the source's current
 * text at once. Returns the repository's own success flag, as [SetMangaViewerFlags] does.
 */
@Inject
class SetMangaDetailsOverride(
    private val mangaRepository: MangaRepository,
) {

    /**
     * Saves [title] and [description] as the dialog holds them, each trimmed. A value equal to the
     * source's (trimmed too, so a source text that ends in a newline still matches what the dialog
     * showed) clears that override, so an edit typed back to the source's text is no edit. A blank
     * title is refused: nothing is written and the result is false.
     */
    suspend fun await(mangaId: Long, title: String, description: String): Boolean {
        val editedTitle = title.trim()
        if (editedTitle.isEmpty()) return false
        val editedDescription = description.trim()
        val manga = mangaRepository.getMangaById(mangaId)
        return mangaRepository.setDetailsOverride(
            mangaId = mangaId,
            titleOverride = editedTitle.takeUnless { it == manga.title.trim() },
            descriptionOverride = editedDescription.takeUnless { it == manga.description.orEmpty().trim() },
        )
    }

    /**
     * A migration's carry of [from]'s edits onto [to] (`MigrateManga`), inside its transaction: an
     * edit [from] has replaces [to]'s, a field [from] never edited keeps [to]'s own, and an edit
     * equal to [to]'s source text is no edit, as [await] reads one. Nothing is written when that
     * changes nothing, so an unedited entry logs no details change; true then, and otherwise the
     * repository's own flag.
     */
    suspend fun awaitCarry(from: Manga, to: Manga): Boolean {
        if (from.titleOverride == null && from.descriptionOverride == null) return true
        val title = (from.titleOverride ?: to.titleOverride)?.takeUnless { it == to.title.trim() }
        val description = (from.descriptionOverride ?: to.descriptionOverride)
            ?.takeUnless { it == to.description.orEmpty().trim() }
        if (title == to.titleOverride && description == to.descriptionOverride) return true
        return mangaRepository.setDetailsOverride(mangaId = to.id, titleOverride = title, descriptionOverride = description)
    }

    /** Clears both overrides: the entry shows the source's title and description again. */
    suspend fun awaitReset(mangaId: Long): Boolean {
        return mangaRepository.setDetailsOverride(mangaId = mangaId, titleOverride = null, descriptionOverride = null)
    }
}
