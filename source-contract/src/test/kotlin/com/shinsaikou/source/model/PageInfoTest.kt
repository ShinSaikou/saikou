package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageInfoTest {

    @Test
    fun `number is one based`() {
        PageInfo(index = 0).number shouldBe 1
        PageInfo(index = 41).number shouldBe 42
    }

    @Test
    fun `progress starts at zero`() {
        PageInfo(index = 0).progress.value shouldBe 0
    }

    @Test
    fun `reportProgress computes a percent when the length is known`() {
        val page = PageInfo(index = 0)

        page.reportProgress(bytesRead = 25, contentLength = 100, done = false)
        page.progress.value shouldBe 25

        page.reportProgress(bytesRead = 50, contentLength = 200, done = false)
        page.progress.value shouldBe 25
    }

    @Test
    fun `reportProgress is minus one when the length is unknown`() {
        val page = PageInfo(index = 0)

        page.reportProgress(bytesRead = 25, contentLength = -1, done = false)
        page.progress.value shouldBe -1

        page.reportProgress(bytesRead = 25, contentLength = 0, done = true)
        page.progress.value shouldBe -1
    }

    @Test
    fun `reportProgress is one hundred on done`() {
        val page = PageInfo(index = 0)

        page.reportProgress(bytesRead = 1, contentLength = 100, done = true)
        page.progress.value shouldBe 100
    }

    @Test
    fun `resetProgress returns to zero`() {
        val page = PageInfo(index = 0)
        page.reportProgress(bytesRead = 100, contentLength = 100, done = true)

        page.resetProgress()

        page.progress.value shouldBe 0
    }

    @Test
    fun `copy does not share the progress flow`() {
        val page = PageInfo(index = 0, url = "/p")
        page.reportProgress(bytesRead = 50, contentLength = 100, done = false)

        val copy = page.copy(imageUrl = "https://example.invalid/1.png")

        copy.progress.value shouldBe 0
        page.progress.value shouldBe 50
    }

    @Test
    fun `equality covers only the constructor fields`() {
        val page = PageInfo(index = 0, url = "/p")
        val other = PageInfo(index = 0, url = "/p")
        page.reportProgress(bytesRead = 50, contentLength = 100, done = false)

        page shouldBe other
        page.hashCode() shouldBe other.hashCode()
    }
}
