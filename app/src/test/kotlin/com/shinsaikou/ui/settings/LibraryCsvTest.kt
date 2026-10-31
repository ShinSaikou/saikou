package com.shinsaikou.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/** The library list's CSV, upstream's format: chosen columns, quoted only where it must be. */
class LibraryCsvTest {

    private fun entry(title: String, author: String? = null, artist: String? = null, titleOverride: String? = null) =
        Manga.create().copy(title = title, author = author, artist = artist, titleOverride = titleOverride)

    @Test
    fun `one line per entry in title order, with the shown title and empty fields for missing credits`() {
        val entries = listOf(
            entry("Wandering Cartographer", author = "Fixture Author", artist = "Fixture Artist"),
            entry("Source title", titleOverride = "Forest of Silence"),
        )

        libraryCsv(entries, LibraryCsvColumns()) shouldBe
            "Forest of Silence,,\r\nWandering Cartographer,Fixture Author,Fixture Artist"
    }

    @Test
    fun `a field with a comma, a quote or a line break is quoted with its quotes doubled`() {
        val entries = listOf(entry("Lanterns, at \"Dusk\"", author = "Line\nbreak", artist = "Plain"))

        libraryCsv(entries, LibraryCsvColumns()) shouldBe "\"Lanterns, at \"\"Dusk\"\"\",\"Line\nbreak\",Plain"
    }

    @Test
    fun `only the chosen columns are written, in upstream's order`() {
        val entries = listOf(entry("Old Harbor", author = "Fixture Author", artist = "Fixture Artist"))

        libraryCsv(entries, LibraryCsvColumns(title = true, author = false, artist = true)) shouldBe
            "Old Harbor,Fixture Artist"
        libraryCsv(entries, LibraryCsvColumns(title = false, author = true, artist = false)) shouldBe "Fixture Author"
    }
}
