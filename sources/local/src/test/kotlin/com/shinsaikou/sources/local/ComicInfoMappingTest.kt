package com.shinsaikou.sources.local

import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo

/** The mappers `:core:metadata` could not keep, because it may not see the source contract. */
class ComicInfoMappingTest {

    @Test
    fun `a manga becomes the comic info a local folder stores`() {
        val info = MangaInfo(
            url = "Fixture Manga A",
            title = "Fixture Manga A",
            author = "Writer A",
            artist = "Artist A",
            description = "A synopsis",
            genre = "Action, Drama",
            status = MangaStatus.ON_HIATUS,
        ).toComicInfo()

        info.series?.value shouldBe "Fixture Manga A"
        info.writer?.value shouldBe "Writer A"
        info.penciller?.value shouldBe "Artist A"
        info.summary?.value shouldBe "A synopsis"
        info.genre?.value shouldBe "Action, Drama"
        info.publishingStatus?.value shouldBe "On hiatus"
        info.title.shouldBeNull()
        info.number.shouldBeNull()
    }

    @Test
    fun `genre is genre, tags and categories joined and de-duplicated`() {
        val merged = MangaInfo(url = "u", title = "t").copyFromComicInfo(
            comicInfo(genre = "Action", tags = "Drama", categories = "Action"),
        )

        merged.genre shouldBe "Action, Drama"
        merged.genres() shouldBe listOf("Action", "Drama")
    }

    @Test
    fun `artist is every credit but the writer, split and de-duplicated`() {
        val merged = MangaInfo(url = "u", title = "t").copyFromComicInfo(
            comicInfo(penciller = "Artist A, Artist B", inker = "Artist B", colorist = "Artist C"),
        )

        merged.artist shouldBe "Artist A, Artist B, Artist C"
    }

    @Test
    fun `an unknown publishing status maps back to unknown`() {
        val merged = MangaInfo(url = "u", title = "t", status = MangaStatus.ONGOING)
            .copyFromComicInfo(comicInfo(status = "Something else"))

        merged.status shouldBe MangaStatus.UNKNOWN
    }

    @Test
    fun `fields the file leaves out keep their current value`() {
        val original = MangaInfo(url = "u", title = "t", author = "Writer A", description = "A synopsis")

        val merged = original.copyFromComicInfo(comicInfo())

        merged.title shouldBe "t"
        merged.author shouldBe "Writer A"
        merged.description shouldBe "A synopsis"
    }

    private fun comicInfo(
        genre: String? = null,
        tags: String? = null,
        categories: String? = null,
        penciller: String? = null,
        inker: String? = null,
        colorist: String? = null,
        status: String? = null,
    ) = ComicInfo(
        title = null,
        series = null,
        number = null,
        summary = null,
        writer = null,
        penciller = penciller?.let { ComicInfo.Penciller(it) },
        inker = inker?.let { ComicInfo.Inker(it) },
        colorist = colorist?.let { ComicInfo.Colorist(it) },
        letterer = null,
        coverArtist = null,
        translator = null,
        genre = genre?.let { ComicInfo.Genre(it) },
        tags = tags?.let { ComicInfo.Tags(it) },
        web = null,
        publishingStatus = status?.let { ComicInfo.PublishingStatusTachiyomi(it) },
        categories = categories?.let { ComicInfo.CategoriesTachiyomi(it) },
        source = null,
    )
}
