package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.UpdateStrategy as AbiUpdateStrategy

class AbiModelsTest {

    @Test
    fun `manga round trips every field`() {
        val info = MangaInfo(
            url = "/series/1",
            title = "Fixture Series A",
            thumbnailUrl = "https://example.invalid/cover.jpg",
            artist = "Artist A",
            author = "Author A",
            status = MangaStatus.ON_HIATUS,
            description = "A fixture.",
            genre = "Action, Drama",
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
            initialized = true,
            memo = JsonObject(mapOf("fixture.key" to JsonPrimitive(1))),
        )

        info.toAbi().toContract() shouldBe info
    }

    @Test
    fun `a status code the enum does not know becomes unknown`() {
        val manga = SManga.create().apply {
            url = "/series/2"
            title = "Fixture Series B"
            status = 99
        }

        manga.toContract().status shouldBe MangaStatus.UNKNOWN
    }

    @Test
    fun `chapter round trips including the unparsed number sentinel`() {
        val chapter = ChapterInfo(
            url = "/series/1/1",
            name = "Chapter 1",
            chapterNumber = ChapterInfo.UNKNOWN_NUMBER,
            scanlator = null,
            dateUpload = 0L,
        )

        val roundTripped = chapter.toAbi().toContract()
        roundTripped shouldBe chapter
        roundTripped.hasKnownNumber() shouldBe false
    }

    @Test
    fun `an empty memo survives both directions`() {
        val chapter = SChapter.create().apply {
            url = "/series/1/2"
            name = "Chapter 2"
            chapter_number = 2f
        }

        val contract = chapter.toContract()
        contract.memo shouldBe MangaInfo.EMPTY_MEMO
        contract.toAbi().memo shouldBe JsonObject(emptyMap())
    }

    @Test
    fun `update strategy maps both ways for every value`() {
        AbiUpdateStrategy.entries.forEach { it.toContract().toAbi() shouldBe it }
        UpdateStrategy.entries.forEach { it.toAbi().toContract() shouldBe it }
    }

    @Test
    fun `detailsToContract on an SManga with neither url nor title set reads both as blank`() {
        val details = SManga.create().apply {
            author = "Fixture Author"
        }

        val contract = details.detailsToContract()

        contract.url shouldBe ""
        contract.title shouldBe ""
        contract.author shouldBe "Fixture Author"
    }

    @Test
    fun `toContract on the same unset SManga throws UninitializedPropertyAccessException`() {
        val details = SManga.create().apply {
            author = "Fixture Author"
        }

        shouldThrow<UninitializedPropertyAccessException> { details.toContract() }
    }

    @Test
    fun `a listing page maps entry for entry`() {
        val page = MangasPage(
            mangas = listOf(
                SManga.create().apply {
                    url = "/series/3"
                    title = "Fixture Series C"
                },
            ),
            hasNextPage = true,
        )

        val contract = page.toContract()
        contract.hasNextPage shouldBe true
        contract.mangas.single().title shouldBe "Fixture Series C"
    }

    @Test
    fun `a page's urls pass through unchanged while its index comes from the given position, not from Page_index`() {
        // Page.index disagrees with the position on purpose, to prove the parameter wins.
        val contract = Page(index = 9, url = "/page/4", imageUrl = "https://example.invalid/4.jpg").toContract(index = 3)

        contract.index shouldBe 3
        contract.number shouldBe 4
        contract.url shouldBe "/page/4"
        contract.imageUrl shouldBe "https://example.invalid/4.jpg"
    }

    @Test
    fun `a page numbered from a source's own scheme is renumbered from its position`() {
        // This is the case that was visibly wrong: a source numbering its own pages from 1 (here
        // simulated as an arbitrary Page.index of 7) made PageInfo.number, the number shown to a
        // reader, off by one when it was read off Page.index instead of the list position.
        val contract = Page(index = 7, url = "/page/1", imageUrl = "https://example.invalid/1.jpg").toContract(index = 0)

        contract.index shouldBe 0
        contract.number shouldBe 1
    }
}
