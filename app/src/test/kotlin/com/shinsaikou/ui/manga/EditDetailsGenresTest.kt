package com.shinsaikou.ui.manga

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * The Edit details dialog's genre field (R165 item 4): the add button and Save both take what is
 * typed through [withTypedGenres], so a genre typed but not yet added is not dropped on Save.
 */
class EditDetailsGenresTest {

    private val genres = listOf("Drama", "Mystery")

    @Test
    fun `a genre typed but not added is kept`() {
        withTypedGenres(genres, "Seafaring") shouldContainExactly listOf("Drama", "Mystery", "Seafaring")
    }

    @Test
    fun `several typed genres split at commas, trimmed, blanks left out`() {
        withTypedGenres(genres, " Letters , ,Seafaring ") shouldContainExactly
            listOf("Drama", "Mystery", "Letters", "Seafaring")
    }

    @Test
    fun `a genre already there is not added twice`() {
        withTypedGenres(genres, "Drama, Letters") shouldContainExactly listOf("Drama", "Mystery", "Letters")
    }

    @Test
    fun `an empty field changes nothing`() {
        withTypedGenres(genres, "  ") shouldContainExactly genres
        withTypedGenres(emptyList(), "") shouldContainExactly emptyList()
    }
}
