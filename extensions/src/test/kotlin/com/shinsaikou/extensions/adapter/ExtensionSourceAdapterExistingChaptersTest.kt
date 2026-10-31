package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import rx.Observable
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * The ABI's `getMangaUpdate` takes the entry's existing chapters, and upstream's
 * `UpdateMangaFromRemote` passes them from the database. The adapter hands over the chapters it is
 * given, and drops the chapters of an update that did not ask for them, because the ABI default
 * (and catalog extensions written the same way) answers with that argument unchanged.
 */
class ExtensionSourceAdapterExistingChaptersTest {

    /** A lib-1.6 extension that records the chapters it was handed and answers per test. */
    private class RecordingSource(
        private val answer: (SManga, List<SChapter>, Boolean, Boolean) -> SMangaUpdate,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        var handed: List<SChapter>? = null

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            handed = chapters
            return answer(manga, chapters, fetchDetails, fetchChapters)
        }
    }

    /** A lib-1.4 extension: only the deprecated Observables, so the ABI default composes them. */
    private class Lib14Source(private val fetched: List<SChapter>) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source B"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
            Observable.just(SManga.create().apply { description = "Fixture description." })

        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(fetched)
    }

    private fun adapterFor(source: HttpSource, libVersion: Double = 1.6) =
        SourceAdapters.wrap(source, "com.shinsaikou.fixture", libVersion)

    private val entry = MangaInfo(url = "/series/1", title = "Entry Title")

    private val stored = listOf(
        ChapterInfo(
            url = "/series/1/2",
            name = "Chapter 2",
            chapterNumber = 2f,
            scanlator = "Fixture Group",
            dateUpload = 1_700_000_200_000L,
            memo = buildJsonObject { put("key", JsonPrimitive("two")) },
        ),
        ChapterInfo(url = "/series/1/1", name = "Chapter 1", chapterNumber = 1f, dateUpload = 1_700_000_100_000L),
    )

    private fun fetchedChapter(url: String) = SChapter.create().apply {
        this.url = url
        name = "Fetched"
    }

    @Test
    fun `getMangaUpdate hands the extension the stored chapters, in order, field for field`() = runTest {
        val source = RecordingSource { manga, _, _, _ -> SMangaUpdate(manga, listOf(fetchedChapter("/series/1/3"))) }

        adapterFor(source).getMangaUpdate(entry, stored, fetchDetails = false, fetchChapters = true)

        val handed = source.handed!!
        handed.map { it.url } shouldBe listOf("/series/1/2", "/series/1/1")
        handed.map { it.name } shouldBe listOf("Chapter 2", "Chapter 1")
        handed.map { it.chapter_number } shouldBe listOf(2f, 1f)
        handed.map { it.scanlator } shouldBe listOf("Fixture Group", null)
        handed.map { it.date_upload } shouldBe listOf(1_700_000_200_000L, 1_700_000_100_000L)
        handed.first().memo shouldBe stored.first().memo
    }

    @Test
    fun `an extension that echoes the stored chapters on a details only update gives no chapters`() = runTest {
        val details = SManga.create().apply { description = "Fixture description." }
        val source = RecordingSource { _, chapters, _, fetchChapters ->
            SMangaUpdate(details, if (fetchChapters) listOf(fetchedChapter("/series/1/3")) else chapters)
        }

        val update = adapterFor(source).getMangaUpdate(entry, stored, fetchDetails = true, fetchChapters = false)

        update.chapters shouldBe null
        update.manga?.description shouldBe "Fixture description."
    }

    @Test
    fun `an extension that answers with the stored chapters when its page cannot be read gives them back unchanged`() = runTest {
        // One page serves both halves, and when it cannot be parsed the extension returns the entry
        // and the chapters it was handed, whatever was asked. Back field for field, the update then
        // finds nothing changed, where an empty list used to end it as no chapters found.
        val source = RecordingSource { manga, chapters, _, _ -> SMangaUpdate(manga, chapters) }

        val update = adapterFor(source).getMangaUpdate(entry, stored, fetchDetails = false, fetchChapters = true)

        update.chapters shouldBe stored
    }

    @Test
    fun `a lib-1_4 extension returns what it fetched, not the stored chapters`() = runTest {
        val adapter = adapterFor(Lib14Source(listOf(fetchedChapter("/series/1/3"))), libVersion = 1.4)

        val both = adapter.getMangaUpdate(entry, stored, fetchDetails = true, fetchChapters = true)
        val detailsOnly = adapter.getMangaUpdate(entry, stored, fetchDetails = true, fetchChapters = false)

        both.chapters?.map { it.url } shouldBe listOf("/series/1/3")
        both.manga?.description shouldBe "Fixture description."
        detailsOnly.chapters shouldBe null
    }
}
