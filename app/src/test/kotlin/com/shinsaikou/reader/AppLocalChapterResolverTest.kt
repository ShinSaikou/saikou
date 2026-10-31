package com.shinsaikou.reader

import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.reader.pipeline.LocalChapterFormat
import com.shinsaikou.source.SourceIds
import com.shinsaikou.sources.local.LocalSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.io.Format
import java.io.FileNotFoundException

class AppLocalChapterResolverTest {

    private val localSource = mockk<LocalSource>()
    private val dispatcher = StandardTestDispatcher()
    private val resolver = AppLocalChapterResolver(
        localSource,
        Dispatchers(io = dispatcher, default = dispatcher, main = dispatcher),
    )
    private val file = mockk<UniFile>()

    @Test
    fun `a chapter from any other source is not local`() = runTest(dispatcher) {
        resolver.resolve(manga(source = 9L), chapter()) shouldBe null
    }

    @Test
    fun `a directory chapter resolves to the reader's directory format`() = runTest(dispatcher) {
        coEvery { localSource.getFormat(any()) } returns Format.Directory(file)

        val resolved = resolver.resolve(manga(), chapter())

        resolved.shouldBeInstanceOf<LocalChapterFormat.Directory>().dir shouldBe file
    }

    @Test
    fun `an archive chapter resolves to the reader's archive format`() = runTest(dispatcher) {
        coEvery { localSource.getFormat(any()) } returns Format.Archive(file)

        resolver.resolve(manga(), chapter()).shouldBeInstanceOf<LocalChapterFormat.Archive>().file shouldBe file
    }

    @Test
    fun `an epub chapter resolves to the reader's epub format`() = runTest(dispatcher) {
        coEvery { localSource.getFormat(any()) } returns Format.Epub(file)

        resolver.resolve(manga(), chapter()).shouldBeInstanceOf<LocalChapterFormat.Epub>().file shouldBe file
    }

    @Test
    fun `a chapter whose file is gone resolves to null rather than throwing`() = runTest(dispatcher) {
        coEvery { localSource.getFormat(any()) } throws FileNotFoundException("gone")

        resolver.resolve(manga(), chapter()) shouldBe null
    }

    @Test
    fun `an entry the local source cannot recognise resolves to null`() = runTest(dispatcher) {
        coEvery { localSource.getFormat(any()) } throws Format.UnknownFormatException()

        resolver.resolve(manga(), chapter()) shouldBe null
    }

    private fun manga(source: Long = SourceIds.LOCAL) =
        Manga.create().copy(id = 1L, source = source, url = "Fixture", title = "Fixture")

    private fun chapter() =
        Chapter.create().copy(id = 2L, mangaId = 1L, url = "Fixture/chapter 1", name = "Chapter 1")
}
