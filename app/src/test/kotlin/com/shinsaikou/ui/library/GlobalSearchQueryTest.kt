package com.shinsaikou.ui.library

import com.shinsaikou.domain.library.search.genreSearchQuery
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which library searches the library offers to search every source for: plain words only, since a
 * source reads nothing of the library's own syntax.
 */
class GlobalSearchQueryTest {

    @Test
    fun `plain words are offered as they read`() {
        globalSearchQuery("blue lock") shouldBe "blue lock"
        globalSearchQuery("  frieren  ") shouldBe "frieren"
    }

    @Test
    fun `a quoted phrase is offered without its quotes`() {
        globalSearchQuery("\"the apothecary diaries\"") shouldBe "the apothecary diaries"
    }

    @Test
    fun `a blank search offers nothing`() {
        globalSearchQuery("").shouldBeNull()
        globalSearchQuery("   ").shouldBeNull()
    }

    @Test
    fun `the library's own syntax offers nothing`() {
        // What a genre tapped on an entry's screen searches.
        globalSearchQuery(genreSearchQuery("Action")).shouldBeNull()
        globalSearchQuery("author:someone").shouldBeNull()
        globalSearchQuery("unread>3").shouldBeNull()
        globalSearchQuery("-romance").shouldBeNull()
        globalSearchQuery("one || two").shouldBeNull()
        globalSearchQuery("title words genre:\"Drama\"").shouldBeNull()
    }
}
