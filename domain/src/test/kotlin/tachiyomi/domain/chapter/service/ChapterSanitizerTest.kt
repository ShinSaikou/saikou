// Fresh: exercises the vendored ChapterSanitizer now that it is reachable from SyncChaptersWithSource.
package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.service.ChapterSanitizer.sanitize

@Execution(ExecutionMode.CONCURRENT)
class ChapterSanitizerTest {

    private val nbsp = " "
    private val ideographicSpace = "　"

    @Test
    fun `removes the manga title from the front of the chapter name`() {
        "Placeholder Title Chapter 5".sanitize("Placeholder Title") shouldBe "Chapter 5"
    }

    @Test
    fun `leaves a name alone when it does not start with the title`() {
        "Chapter 5: Placeholder Title Returns".sanitize("Placeholder Title") shouldBe
            "Chapter 5: Placeholder Title Returns"
    }

    @Test
    fun `trims a separator left behind after the prefix is removed`() {
        "Placeholder Title - Chapter 5".sanitize("Placeholder Title") shouldBe "Chapter 5"
        "Placeholder Title: Chapter 5".sanitize("Placeholder Title") shouldBe "Chapter 5"
        "Placeholder Title_Chapter 5".sanitize("Placeholder Title") shouldBe "Chapter 5"
        "Placeholder Title, Chapter 5".sanitize("Placeholder Title") shouldBe "Chapter 5"
    }

    @Test
    fun `trims leading and trailing plain whitespace`() {
        "  Chapter 5  ".sanitize("") shouldBe "Chapter 5"
    }

    @Test
    fun `a name that is exactly the title sanitizes to an empty string`() {
        "Placeholder Title".sanitize("Placeholder Title") shouldBe ""
    }

    @Test
    fun `an empty title leaves the name untouched apart from trimming`() {
        "Chapter 5".sanitize("") shouldBe "Chapter 5"
    }

    @Test
    fun `the no-break space code point U+00A0 is trimmed after prefix removal`() {
        val name = "Placeholder Title$nbsp" + "Chapter 5"

        name.sanitize("Placeholder Title") shouldBe "Chapter 5"
    }

    @Test
    fun `the ideographic space code point U+3000 is trimmed after prefix removal`() {
        val name = "Placeholder Title$ideographicSpace" + "Chapter 5"

        name.sanitize("Placeholder Title") shouldBe "Chapter 5"
    }

    @Test
    fun `Kotlin's own parameterless trim already removes a leading no-break space`() {
        // Kotlin's Char.isWhitespace() treats any Unicode space separator, including U+00A0, as
        // whitespace, so the plain trim() at the start of sanitize() already strips it here. The
        // CHAPTER_TRIM_CHARS array only earns its keep for the trim(vararg) call that runs after
        // removePrefix, once the leading whitespace is no longer at the very start of the string
        // (see the test above): trim(vararg) matches literal characters, not whitespace category.
        val name = "$nbsp" + "Chapter 5"

        name.trim() shouldBe "Chapter 5"
        name.sanitize("") shouldBe "Chapter 5"
    }
}
