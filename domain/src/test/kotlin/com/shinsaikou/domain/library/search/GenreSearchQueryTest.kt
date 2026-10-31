package com.shinsaikou.domain.library.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.search.FieldQueryNode
import tachiyomi.domain.library.model.search.MangaField
import tachiyomi.domain.library.model.search.QueryNode

/** A genre chip's search parses back to one genre term holding the whole genre. */
class GenreSearchQueryTest {

    @Test
    fun `a genre with spaces and punctuation parses back to one genre term`() {
        genreSearchQuery("Slice of Life") shouldBe "genre:\"Slice of Life\""
        parsed("Slice of Life") shouldBe FieldQueryNode(MangaField.GENRE, "Slice of Life", negated = false)
        parsed(" Sci-Fi (Space) ") shouldBe FieldQueryNode(MangaField.GENRE, "Sci-Fi (Space)", negated = false)
    }

    @Test
    fun `a genre holding quotes still parses back to one genre term`() {
        parsed("Boys' Love") shouldBe FieldQueryNode(MangaField.GENRE, "Boys' Love", negated = false)
        parsed("The \"Classic\"") shouldBe FieldQueryNode(MangaField.GENRE, "The \"Classic\"", negated = false)
        parsed("It's \"Both\"") shouldBe FieldQueryNode(MangaField.GENRE, "It's Both", negated = false)
    }

    private fun parsed(genre: String) = QueryNode.from(genreSearchQuery(genre))
}
