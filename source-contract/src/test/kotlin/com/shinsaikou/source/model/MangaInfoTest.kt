package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class MangaInfoTest {

    private val base = MangaInfo(
        url = "/manga/1",
        title = "Title",
        thumbnailUrl = "https://example.invalid/cover.png",
        artist = "Artist",
        author = "Author",
        status = MangaStatus.ONGOING,
        description = "Description",
        genre = "Action, Drama",
        initialized = true,
    )

    @Test
    fun `genres splits trims drops blanks and de-duplicates`() {
        base.copy(genre = " Action ,Drama,  , Action ,Comedy").genres() shouldBe
            listOf("Action", "Drama", "Comedy")
    }

    @Test
    fun `genres is null when the source reported no genre`() {
        base.copy(genre = null).genres() shouldBe null
    }

    @Test
    fun `genres is empty when the genre string holds only separators`() {
        base.copy(genre = " , , ").genres() shouldBe emptyList()
    }

    @Test
    fun `mergeDetails keeps input fields when the details are null`() {
        val details = MangaInfo(url = "", title = "", initialized = true)

        val merged = base.mergeDetails(details)

        merged.url shouldBe base.url
        merged.title shouldBe base.title
        merged.thumbnailUrl shouldBe base.thumbnailUrl
        merged.artist shouldBe base.artist
        merged.author shouldBe base.author
        merged.description shouldBe base.description
        merged.genre shouldBe base.genre
        merged.memo shouldBe base.memo
    }

    @Test
    fun `mergeDetails lets every non-null field of the details win`() {
        val details = MangaInfo(
            url = "/manga/2",
            title = "New title",
            thumbnailUrl = "https://example.invalid/new.png",
            artist = "New artist",
            author = "New author",
            status = MangaStatus.COMPLETED,
            description = "New description",
            genre = "Comedy",
        )

        val merged = base.mergeDetails(details)

        merged.url shouldBe "/manga/2"
        merged.title shouldBe "New title"
        merged.thumbnailUrl shouldBe "https://example.invalid/new.png"
        merged.artist shouldBe "New artist"
        merged.author shouldBe "New author"
        merged.status shouldBe MangaStatus.COMPLETED
        merged.description shouldBe "New description"
        merged.genre shouldBe "Comedy"
    }

    @Test
    fun `mergeDetails keeps a known status when the details report UNKNOWN`() {
        base.mergeDetails(MangaInfo(url = "", title = "")).status shouldBe MangaStatus.ONGOING
    }

    @Test
    fun `mergeDetails always takes the update strategy of the details`() {
        val details = MangaInfo(url = "", title = "", updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE)

        base.mergeDetails(details).updateStrategy shouldBe UpdateStrategy.ONLY_FETCH_ONCE
    }

    @Test
    fun `mergeDetails ORs initialized`() {
        val uninitialized = base.copy(initialized = false)

        uninitialized.mergeDetails(MangaInfo(url = "", title = "")).initialized shouldBe false
        uninitialized.mergeDetails(MangaInfo(url = "", title = "", initialized = true))
            .initialized shouldBe true
        base.mergeDetails(MangaInfo(url = "", title = "")).initialized shouldBe true
    }

    @Test
    fun `mergeDetails keeps the old memo when the details carry an empty one`() {
        val memo = JsonObject(mapOf("k" to JsonPrimitive("v")))
        val newMemo = JsonObject(mapOf("k" to JsonPrimitive("w")))

        base.copy(memo = memo).mergeDetails(MangaInfo(url = "", title = "")).memo shouldBe memo
        base.copy(memo = memo)
            .mergeDetails(MangaInfo(url = "", title = "", memo = newMemo)).memo shouldBe newMemo
    }

    @Test
    fun `the default memo is an empty JSON object`() {
        MangaInfo(url = "/m", title = "t").memo shouldBe MangaInfo.EMPTY_MEMO
        MangaInfo.EMPTY_MEMO.isEmpty() shouldBe true
    }
}
