package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * A details result is commonly built with `SManga.create()`, since the extension copies into the
 * entry it was handed and upstream never reads url there and reads an unset title as "". Only a
 * listing entry, which has no entry to copy into, still needs both fields: see
 * `AbiModelsTest`'s "a listing page maps entry for entry" style coverage for the strict side, and
 * [AbiModelsTest] for `detailsToContract` itself.
 */
class ExtensionSourceAdapterDetailsLeniencyTest {

    /** A lib-1.6 extension whose `getMangaUpdate` and `getPopularManga` are supplied per test. */
    private class FixtureHttpSource(
        private val update: (suspend (SManga, Boolean, Boolean) -> SMangaUpdate)? = null,
        private val popular: (suspend () -> MangasPage)? = null,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = update!!(manga, fetchDetails, fetchChapters)

        override suspend fun getPopularManga(page: Int): MangasPage = popular!!()
    }

    private fun adapterFor(source: HttpSource) = SourceAdapters.wrap(source, "com.shinsaikou.fixture", 1.6)

    private fun entry(url: String = "/series/1", title: String = "Entry Title") = MangaInfo(url = url, title = title)

    @Test
    fun `getMangaDetails on a details result with neither url nor title set keeps the entry's own and takes the rest`() = runTest {
        val details = SManga.create().apply {
            author = "Fixture Author"
            description = "Fixture description."
        }
        val adapter = adapterFor(
            FixtureHttpSource(
                update = { _, _, _ -> SMangaUpdate(details, emptyList()) },
            ),
        )

        val result = adapter.getMangaDetails(entry(url = "/series/1", title = "Entry Title"))

        result.url shouldBe "/series/1"
        result.title shouldBe "Entry Title"
        result.author shouldBe "Fixture Author"
        result.description shouldBe "Fixture description."
        result.initialized shouldBe true
    }

    @Test
    fun `getMangaDetails on a details result with only url unset keeps the entry's url and takes the new title`() = runTest {
        val details = SManga.create().apply {
            title = "New Title From Extension"
        }
        val adapter = adapterFor(
            FixtureHttpSource(
                update = { _, _, _ -> SMangaUpdate(details, emptyList()) },
            ),
        )

        val result = adapter.getMangaDetails(entry(url = "/series/1", title = "Entry Title"))

        result.url shouldBe "/series/1"
        result.title shouldBe "New Title From Extension"
    }

    @Test
    fun `getMangaUpdate with fetchDetails and fetchChapters keeps the entry's url and title and returns the chapters`() = runTest {
        val details = SManga.create().apply {
            description = "Fixture description."
        }
        val chapter = SChapter.create().apply {
            url = "/series/1/1"
            name = "Chapter 1"
        }
        val adapter = adapterFor(
            FixtureHttpSource(
                update = { _, _, _ -> SMangaUpdate(details, listOf(chapter)) },
            ),
        )

        val update = adapter.getMangaUpdate(
            entry(url = "/series/1", title = "Entry Title"),
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )

        update.manga?.url shouldBe "/series/1"
        update.manga?.title shouldBe "Entry Title"
        update.manga?.description shouldBe "Fixture description."
        update.chapters?.single()?.url shouldBe "/series/1/1"
    }

    @Test
    fun `a listing entry with no url still fails as an incomplete model`() = runTest {
        val page = MangasPage(
            mangas = listOf(SManga.create().apply { title = "Fixture Series" }),
            hasNextPage = false,
        )
        val adapter = adapterFor(
            FixtureHttpSource(
                popular = { page },
            ),
        )

        val thrown = shouldThrow<IllegalStateException> { adapter.getPopularManga(1) }

        thrown.message?.startsWith("Extension returned an incomplete model") shouldBe true
    }
}
