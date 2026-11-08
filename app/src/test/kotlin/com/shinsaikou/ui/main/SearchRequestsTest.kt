package com.shinsaikou.ui.main

import android.content.Intent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The intents the Experimental search alias accepts, read into a [SearchRequest]. Intents are
 * mocked the way `MainIntentsTest` does it, since the unit tests run without Robolectric.
 */
class SearchRequestsTest {

    // -- of: one case per accepted action --------------------------------------------------------

    @Test
    fun `shared plain text is the query with no address`() {
        val intent = intent(Intent.ACTION_SEND, Intent.EXTRA_TEXT to "one piece")

        SearchRequests.of(intent) shouldBe SearchRequest("one piece")
    }

    @Test
    fun `shared text holding an address keeps the address and searches the words around it`() {
        val intent = intent(Intent.ACTION_SEND, Intent.EXTRA_TEXT to "Title https://www.example.org/manga/1")

        SearchRequests.of(intent) shouldBe SearchRequest("Title", listOf("https://www.example.org/manga/1"))
    }

    @Test
    fun `every address of shared text is kept in order and taken out of the words`() {
        val intent = intent(
            Intent.ACTION_SEND,
            Intent.EXTRA_TEXT to "see http://a.example.org/x and https://b.example.org/y",
        )

        SearchRequests.of(intent) shouldBe
            SearchRequest("see and", listOf("http://a.example.org/x", "https://b.example.org/y"))
    }

    @Test
    fun `a shared selection searches its words without their quotes and keeps the link`() {
        val link = "https://en.example.org/wiki/Vagabond_(manga)#:~:text=Vagabond"
        val intent = intent(Intent.ACTION_SEND, Intent.EXTRA_TEXT to "\"Vagabond\"\n$link")

        SearchRequests.of(intent) shouldBe SearchRequest("Vagabond", listOf(link))
    }

    @Test
    fun `curly double quotes round a shared selection are stripped`() {
        val link = "https://en.example.org/wiki/Vagabond#:~:text=Vagabond"

        SearchRequests.sharedText("\u201CVagabond\u201D\n$link") shouldBe SearchRequest("Vagabond", listOf(link))
    }

    @Test
    fun `a shared address with nothing else searches the address`() {
        SearchRequests.sharedText("  https://example.org/story \n") shouldBe
            SearchRequest("https://example.org/story", listOf("https://example.org/story"))
    }

    @Test
    fun `a share with no text extra is no request`() {
        SearchRequests.of(intent(Intent.ACTION_SEND)).shouldBeNull()
    }

    @Test
    fun `a share of blank text is no request`() {
        SearchRequests.of(intent(Intent.ACTION_SEND, Intent.EXTRA_TEXT to " \n ")).shouldBeNull()
    }

    @Test
    fun `a search reads the search manager query`() {
        val intent = intent(Intent.ACTION_SEARCH, "query" to "berserk")

        SearchRequests.of(intent) shouldBe SearchRequest("berserk")
    }

    @Test
    fun `the Google search action reads the search manager query`() {
        val intent = intent(SearchRequests.ACTION_GMS_SEARCH, "query" to "  berserk  ")

        SearchRequests.of(intent) shouldBe SearchRequest("berserk")
    }

    @Test
    fun `a search with a blank query is no request`() {
        SearchRequests.of(intent(Intent.ACTION_SEARCH, "query" to "   ")).shouldBeNull()
    }

    @Test
    fun `the extension action carries its filter as the extension package`() {
        val intent = intent(
            SearchRequests.ACTION_EXTENSION_SEARCH,
            SearchRequests.EXTRA_EXTENSION_QUERY to "vagabond",
            SearchRequests.EXTRA_EXTENSION_FILTER to " dev.example.ext ",
        )

        SearchRequests.of(intent) shouldBe SearchRequest("vagabond", extensionPackage = "dev.example.ext")
    }

    @Test
    fun `the extension action without a filter has no extension package`() {
        val intent = intent(SearchRequests.ACTION_EXTENSION_SEARCH, SearchRequests.EXTRA_EXTENSION_QUERY to "vagabond")

        SearchRequests.of(intent) shouldBe SearchRequest("vagabond")
    }

    @Test
    fun `a blank filter on the extension action is treated as none`() {
        val intent = intent(
            SearchRequests.ACTION_EXTENSION_SEARCH,
            SearchRequests.EXTRA_EXTENSION_QUERY to "vagabond",
            SearchRequests.EXTRA_EXTENSION_FILTER to "   ",
        )

        SearchRequests.of(intent) shouldBe SearchRequest("vagabond", extensionPackage = null)
    }

    @Test
    fun `the extension action without a query is no request even with a filter`() {
        val intent = intent(SearchRequests.ACTION_EXTENSION_SEARCH, SearchRequests.EXTRA_EXTENSION_FILTER to "dev.example.ext")

        SearchRequests.of(intent).shouldBeNull()
    }

    @Test
    fun `an unknown action is no request even with a query`() {
        SearchRequests.of(intent("com.example.OTHER", "query" to "berserk")).shouldBeNull()
    }

    @Test
    fun `a null action is no request`() {
        SearchRequests.of(intent(null, "query" to "berserk")).shouldBeNull()
    }

    @Test
    fun `a CharSequence extra that is not a String is read as its text`() {
        val styled = StringBuilder("styled title")
        val intent = intent(Intent.ACTION_SEARCH, "query" to styled)

        SearchRequests.of(intent) shouldBe SearchRequest("styled title")
    }

    // -- normalise -------------------------------------------------------------------------------

    @Test
    fun `normalise trims the ends`() {
        SearchRequests.normalise("  hello world \t") shouldBe "hello world"
    }

    @Test
    fun `normalise folds a run of line breaks and their spaces into one space`() {
        SearchRequests.normalise("first\r\n\r\n  second\nthird") shouldBe "first second third"
    }

    @Test
    fun `normalise caps the query at 500 characters`() {
        SearchRequests.normalise("a".repeat(900)) shouldBe "a".repeat(SearchRequests.MAX_QUERY_LENGTH)
    }

    @Test
    fun `normalise leaves a query of exactly 500 characters whole`() {
        SearchRequests.normalise("b".repeat(500)) shouldBe "b".repeat(500)
    }

    @Test
    fun `normalise trims again after the cap`() {
        // The 500th character is a space, so cutting there leaves trailing white space to remove.
        val text = "c".repeat(499) + " " + "d".repeat(50)

        SearchRequests.normalise(text) shouldBe "c".repeat(499)
    }

    @Test
    fun `normalise returns null for empty text`() {
        SearchRequests.normalise("").shouldBeNull()
    }

    @Test
    fun `normalise returns null for white space and line breaks only`() {
        SearchRequests.normalise(" \r\n \n\t ").shouldBeNull()
    }

    @Test
    fun `normalise returns null for null`() {
        SearchRequests.normalise(null).shouldBeNull()
    }

    /**
     * A whole page of mostly blank text, read on the main thread. The regular expressions this
     * replaced scanned a blank run again from each of its characters, which on 200,000 characters
     * takes minutes; one pass takes milliseconds, so the limit is generous and still catches that.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `a very long blank text is read in one pass and still finds the address at its end`() {
        val address = "https://example.org/at-the-end"
        val text = "Vagabond" + " \t".repeat(50_000) + "\n".repeat(50_000) + " ".repeat(49_992) + address

        text.length shouldBe 200_000 + address.length
        SearchRequests.sharedText(text) shouldBe SearchRequest("Vagabond", listOf(address))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `a very long blank query normalises to nothing in one pass`() {
        SearchRequests.normalise(" ".repeat(200_000)).shouldBeNull()
    }

    @Test
    fun `an address inside a run keeps the text before it as a word`() {
        SearchRequests.sharedText("see:https://example.org/x") shouldBe SearchRequest("see:", listOf("https://example.org/x"))
    }

    @Test
    fun `a scheme with nothing after it is a word and no address`() {
        SearchRequests.sharedText("try https:// later") shouldBe SearchRequest("try https:// later")
    }

    @Test
    fun `an upper case scheme is an address`() {
        SearchRequests.sharedText("HTTPS://EXAMPLE.ORG/X") shouldBe
            SearchRequest("HTTPS://EXAMPLE.ORG/X", listOf("HTTPS://EXAMPLE.ORG/X"))
    }

    @Test
    fun `normalise folds any run of white space into one space`() {
        SearchRequests.normalise("one  \t two") shouldBe "one two"
    }

    // -- unquote ---------------------------------------------------------------------------------

    @Test
    fun `unquote strips straight double and single quotes`() {
        SearchRequests.unquote("\"Vagabond\"") shouldBe "Vagabond"
        SearchRequests.unquote("'Vagabond'") shouldBe "Vagabond"
    }

    @Test
    fun `unquote strips curly double and single quotes`() {
        SearchRequests.unquote("\u201CVagabond\u201D") shouldBe "Vagabond"
        SearchRequests.unquote("\u2018Vagabond\u2019") shouldBe "Vagabond"
    }

    @Test
    fun `unquote strips one pair only`() {
        SearchRequests.unquote("\"\"Vagabond\"\"") shouldBe "\"Vagabond\""
    }

    @Test
    fun `unquote leaves quotes that do not pair up or do not surround the text`() {
        SearchRequests.unquote("\"Vagabond'") shouldBe "\"Vagabond'"
        SearchRequests.unquote("The \"Vagabond\" volume") shouldBe "The \"Vagabond\" volume"
        SearchRequests.unquote("\"") shouldBe "\""
    }

    // -- sameSite --------------------------------------------------------------------------------

    @Test
    fun `a leading www on the first host is ignored`() {
        SearchRequests.sameSite("www.example.org", "example.org") shouldBe true
    }

    @Test
    fun `a leading www on the second host is ignored`() {
        SearchRequests.sameSite("example.org", "www.example.org") shouldBe true
    }

    @Test
    fun `host case is ignored`() {
        SearchRequests.sameSite("WWW.Example.ORG", "example.org") shouldBe true
    }

    @Test
    fun `different hosts are different sites`() {
        SearchRequests.sameSite("example.org", "example.net") shouldBe false
    }

    @Test
    fun `a subdomain other than www is a different site`() {
        SearchRequests.sameSite("reader.example.org", "example.org") shouldBe false
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun intent(action: String?, vararg extras: Pair<String, CharSequence>): Intent {
        val byKey = extras.toMap()
        return mockk {
            every { this@mockk.action } returns action
            every { getCharSequenceExtra(any()) } answers { byKey[firstArg()] }
            every { getStringExtra(any()) } answers { byKey[firstArg()]?.toString() }
        }
    }
}
