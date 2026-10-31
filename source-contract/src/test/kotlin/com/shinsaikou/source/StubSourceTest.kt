package com.shinsaikou.source

import com.shinsaikou.source.model.MangaInfo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StubSourceTest {

    @Test
    fun `unknown builds a stub named after the id`() {
        val stub = StubSource.unknown(42L)

        stub.id shouldBe 42L
        stub.name shouldBe "42"
        stub.lang shouldBe ""
    }

    @Test
    fun `toString shows the remembered name and language`() {
        StubSource(id = 7L, name = "Remembered", lang = "en").toString() shouldBe "Remembered (EN)"
    }

    @Test
    fun `toString omits the empty bracket when no language is remembered`() {
        StubSource.unknown(42L).toString() shouldBe "42"
        StubSource(id = 7L, name = "Remembered", lang = "").toString() shouldBe "Remembered"
    }

    @Test
    fun `toString falls back to the id when no name is remembered`() {
        StubSource(id = 7L, name = "", lang = "").toString() shouldBe "7"
    }

    @Test
    fun `equality is by id alone`() {
        StubSource(1L, "a", "en") shouldBe StubSource(1L, "b", "fr")
        StubSource(1L, "a", "en").hashCode() shouldBe StubSource(1L, "b", "fr").hashCode()
    }

    @Test
    fun `every fetch reports the source id`() {
        val stub = StubSource.unknown(99L)

        val thrown = assertThrows<SourceNotInstalledException> {
            runBlocking { stub.getMangaDetails(MangaInfo(url = "/m", title = "t")) }
        }
        thrown.sourceId shouldBe 99L
    }
}
