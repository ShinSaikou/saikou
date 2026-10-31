package com.shinsaikou.core.metadata.comicinfo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import java.io.ByteArrayOutputStream

class ComicInfoXmlTest {

    private val comicInfoXml = ComicInfoXml()

    @Test
    fun `a fully populated ComicInfo survives a round trip`() {
        val encoded = encode(FULL)

        comicInfoXml.decode(encoded.byteInputStream()) shouldBe FULL
    }

    @Test
    fun `every element keeps the name the ComicInfo schema uses`() {
        val encoded = encode(FULL)

        listOf(
            "<Title>Chapter 1</Title>",
            "<Series>A Series</Series>",
            "<Number>1</Number>",
            "<Summary>A summary</Summary>",
            "<Writer>A writer</Writer>",
            "<Penciller>A penciller</Penciller>",
            "<Inker>An inker</Inker>",
            "<Colorist>A colorist</Colorist>",
            "<Letterer>A letterer</Letterer>",
            "<CoverArtist>A cover artist</CoverArtist>",
            "<Translator>A translator</Translator>",
            "<Genre>Action, Drama</Genre>",
            "<Tags>tag</Tags>",
            "<Web>https://example.invalid/chapter</Web>",
        ).forEach { encoded shouldContain it }

        // The three non-standard elements keep their own prefixes.
        encoded shouldContain ">Ongoing</ty:PublishingStatusTachiyomi>"
        encoded shouldContain ">A category</ty:Categories>"
        encoded shouldContain ">A source</mh:SourceMihon>"

        // The declaration carries the charset and the root the two schema attributes, as upstream writes them.
        encoded shouldContain "encoding='UTF-8'"
        encoded shouldContain """xmlns:xsd="http://www.w3.org/2001/XMLSchema""""
        encoded shouldContain """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance""""
    }

    @Test
    fun `a null field writes no element and reads back as null`() {
        val sparse = ComicInfo(
            title = null,
            series = ComicInfo.Series("A Series"),
            number = null,
            summary = null,
            writer = null,
            penciller = null,
            inker = null,
            colorist = null,
            letterer = null,
            coverArtist = null,
            translator = null,
            genre = null,
            tags = null,
            web = null,
            publishingStatus = null,
            categories = null,
            source = null,
        )

        val encoded = encode(sparse)

        encoded shouldContain "<Series>A Series</Series>"
        comicInfoXml.decode(encoded.byteInputStream()) shouldBe sparse
    }

    @Test
    fun `an unknown child element is ignored instead of throwing`() {
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ComicInfo>
              <Series>A Series</Series>
              <PageCount>18</PageCount>
              <Pages>
                <Page Image="0" ImageSize="1234"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()

        val info = comicInfoXml.decode(document.byteInputStream())

        info.series?.value shouldBe "A Series"
        info.title shouldBe null
    }

    @Test
    fun `publishing status maps both ways and falls back to unknown`() {
        ComicInfoPublishingStatus.toComicInfoValue(1) shouldBe "Ongoing"
        ComicInfoPublishingStatus.toComicInfoValue(6) shouldBe "On hiatus"
        ComicInfoPublishingStatus.toComicInfoValue(99) shouldBe "Unknown"
        ComicInfoPublishingStatus.toStatusCode("Publishing finished") shouldBe 4
        ComicInfoPublishingStatus.toStatusCode("Serialised") shouldBe 0
        ComicInfoPublishingStatus.toStatusCode(null) shouldBe 0
    }

    private fun encode(info: ComicInfo): String =
        ByteArrayOutputStream().also { comicInfoXml.encode(info, it) }.toString(Charsets.UTF_8.name())

    private companion object {
        val FULL = ComicInfo(
            title = ComicInfo.Title("Chapter 1"),
            series = ComicInfo.Series("A Series"),
            number = ComicInfo.Number("1"),
            summary = ComicInfo.Summary("A summary"),
            writer = ComicInfo.Writer("A writer"),
            penciller = ComicInfo.Penciller("A penciller"),
            inker = ComicInfo.Inker("An inker"),
            colorist = ComicInfo.Colorist("A colorist"),
            letterer = ComicInfo.Letterer("A letterer"),
            coverArtist = ComicInfo.CoverArtist("A cover artist"),
            translator = ComicInfo.Translator("A translator"),
            genre = ComicInfo.Genre("Action, Drama"),
            tags = ComicInfo.Tags("tag"),
            web = ComicInfo.Web("https://example.invalid/chapter"),
            publishingStatus = ComicInfo.PublishingStatusTachiyomi("Ongoing"),
            categories = ComicInfo.CategoriesTachiyomi("A category"),
            source = ComicInfo.SourceName("A source"),
        )
    }
}
