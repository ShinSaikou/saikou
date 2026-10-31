package com.shinsaikou.source

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SourceTest {

    private val manga = MangaInfo(url = "/m", title = "Title")

    private class RecordingSource : Source {
        override val id: Long = 1L
        override val name: String = "Recording"
        override val lang: String = "en"

        var detailCalls = 0
        var chapterCalls = 0

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo {
            detailCalls++
            return manga.copy(initialized = true)
        }

        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> {
            chapterCalls++
            return listOf(ChapterInfo(url = "/c", name = "Chapter 1"))
        }

        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = emptyList()

        override suspend fun getImage(page: PageInfo): ImageResult =
            ImageResult.ofBytes(ByteArray(0), contentType = null)
    }

    @Test
    fun `a source that names no website has no base URL by default`() {
        RecordingSource().baseUrl shouldBe null
    }

    @Test
    fun `the default combined refresh runs both fetches`() {
        val source = RecordingSource()

        val update = runBlocking {
            source.getMangaUpdate(manga, chapters = emptyList(), fetchDetails = true, fetchChapters = true)
        }

        update.manga?.initialized shouldBe true
        update.chapters?.size shouldBe 1
        source.detailCalls shouldBe 1
        source.chapterCalls shouldBe 1
    }

    @Test
    fun `a false flag leaves the matching field null`() {
        val source = RecordingSource()

        val update = runBlocking {
            source.getMangaUpdate(manga, chapters = emptyList(), fetchDetails = false, fetchChapters = true)
        }

        update.manga shouldBe null
        update.chapters?.size shouldBe 1
        source.detailCalls shouldBe 0
    }

    @Test
    fun `the browser URL hooks default to null`() {
        val source = RecordingSource()

        source.homeUrl shouldBe null
        source.requestHeaders shouldBe emptyMap()
        source.getMangaUrl(manga) shouldBe null
        source.getChapterUrl(ChapterInfo(url = "/c", name = "Chapter 1")) shouldBe null
    }
}
