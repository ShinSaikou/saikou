package com.shinsaikou.domain.library.search

import com.shinsaikou.domain.library.model.LibraryItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import tachiyomi.domain.library.model.search.AndNode
import tachiyomi.domain.library.model.search.Comparator
import tachiyomi.domain.library.model.search.ComparisonField
import tachiyomi.domain.library.model.search.ComparisonQueryNode
import tachiyomi.domain.library.model.search.FieldQueryNode
import tachiyomi.domain.library.model.search.MangaField
import tachiyomi.domain.library.model.search.NotNode
import tachiyomi.domain.library.model.search.OrNode
import tachiyomi.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

@Execution(ExecutionMode.CONCURRENT)
class LibrarySearchMatcherTest {

    private val matcher = LibrarySearchMatcher()

    @Test
    fun `an empty query matches everything`() {
        matches("") shouldBe true
    }

    @Test
    fun `a general term searches title, credits, description, genre, source name and notes`() {
        matches("wander") shouldBe true // title
        matches("ohara") shouldBe true // author
        matches("nakano") shouldBe true // artist
        matches("long walk") shouldBe true // description
        matches("Slice of Life") shouldBe true // genre
        matches("example source") shouldBe true // source name
        matches("reread later") shouldBe true // notes
        matches("absent") shouldBe false
    }

    @Test
    fun `a leading hyphen negates a leaf`() {
        matches("-wander") shouldBe false
        matches("-absent") shouldBe true
    }

    @Test
    fun `implicit AND, explicit OR and parentheses`() {
        matches("wander ohara") shouldBe true
        matches("wander absent") shouldBe false
        matches("absent || ohara") shouldBe true
        matches("(absent || ohara) && wander") shouldBe true
    }

    @Test
    fun `field terms match the named field only`() {
        matches("title:wander") shouldBe true
        matches("title:ohara") shouldBe false
        matches("author:ohara") shouldBe true
        matches("artist:nakano") shouldBe true
        matches("desc:\"long walk\"") shouldBe true
        matches("note:reread") shouldBe true
    }

    @Test
    fun `an edited title or description matches as well as the source's text under it`() {
        val edited = manga.copy(titleOverride = "Map Walker", descriptionOverride = "My own summary.")

        matches("walker", manga = edited) shouldBe true
        matches("wander", manga = edited) shouldBe true
        matches("title:walker", manga = edited) shouldBe true
        matches("title:wander", manga = edited) shouldBe true
        matches("own summary", manga = edited) shouldBe true
        matches("desc:\"own summary\"", manga = edited) shouldBe true
        matches("desc:\"long walk\"", manga = edited) shouldBe true
        matches("title:summary", manga = edited) shouldBe false
        // The edit is this entry's alone.
        matches("walker") shouldBe false
    }

    @Test
    fun `a null field never matches`() {
        matches("author:ohara", manga = manga.copy(author = null)) shouldBe false
    }

    @Test
    fun `genre matches an exact entry first and falls back to contains`() {
        matches("genre:drama") shouldBe true
        matches("tag:\"slice of life\"") shouldBe true
        matches("genre:sli") shouldBe true
        matches("genre:horror") shouldBe false
    }

    @Test
    fun `source, language and source id read the resolved source`() {
        matches("source:example") shouldBe true
        matches("lang:en") shouldBe true
        matches("srcid:42") shouldBe true
        matches("lang:fr") shouldBe false
    }

    @Test
    fun `without an installed source, the source fields fall back to the id`() {
        matches("source:42", source = null) shouldBe true
        matches("lang:42", source = null) shouldBe true
        matches("srcid:42", source = null) shouldBe true
        matches("source:example", source = null) shouldBe false
    }

    @Test
    fun `comparisons read counts and intervals`() {
        matches("unread>3") shouldBe true
        matches("unread>7") shouldBe false
        matches("read=3") shouldBe true
        matches("total>=10") shouldBe true
        matches("id=7") shouldBe true
        matches("fi<=5") shouldBe true
        matches("fi<1") shouldBe false
    }

    @Test
    fun `date comparisons take a date or epoch millis`() {
        matches("added>=2024-01-01") shouldBe true
        matches("added>2026-01-01") shouldBe false
        matches("added>=$dateAddedMillis") shouldBe true
        matches("nu<2026-01-01") shouldBe true
    }

    @Test
    fun `an unparseable comparison value makes the node false`() {
        matches("unread>many") shouldBe false
        matches("added>notadate") shouldBe false
    }

    @Test
    fun `an unknown field degrades to a general term including the colon`() {
        matches("colour:blue") shouldBe false
        matches(
            "colour:blue",
            manga = manga.copy(description = "written as colour:blue on the cover"),
        ) shouldBe true
    }

    @Test
    fun `the parser builds the tree the matcher walks`() {
        val node = QueryNode.from("""title:"a b" -genre:c (unread>3 || read=0)""")

        val children = node.shouldBeInstanceOf<AndNode>().children
        children shouldBe listOf(
            FieldQueryNode(MangaField.TITLE, "a b", negated = false),
            FieldQueryNode(MangaField.GENRE, "c", negated = true),
            OrNode(
                listOf(
                    ComparisonQueryNode(
                        ComparisonField.UNREAD,
                        "3",
                        Comparator.GT,
                        negated = false,
                    ),
                    ComparisonQueryNode(
                        ComparisonField.READ,
                        "0",
                        Comparator.EQ,
                        negated = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a parenthesised group can be negated as a whole`() {
        val node = QueryNode.from("-(wander)")

        node.shouldBeInstanceOf<NotNode>()
        matcher.matches(node, item, source) shouldBe false
    }

    private fun matches(query: String, manga: Manga = this.manga, source: Source? = this.source): Boolean =
        matcher.matches(QueryNode.from(query), item(manga), source)

    private val dateAddedMillis =
        LocalDate.parse("2025-06-01").atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private val nextUpdateMillis =
        LocalDate.parse("2025-07-01").atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private val manga = Manga.create().copy(
        id = 7L,
        source = 42L,
        title = "The Wandering Cartographer",
        author = "Ohara",
        artist = "Nakano",
        description = "A long walk with a bad map.",
        genre = listOf("Drama", "Slice of Life"),
        notes = "Reread later",
        dateAdded = dateAddedMillis,
        nextUpdate = nextUpdateMillis,
        fetchInterval = 5,
    )

    private val source = Source(id = 42L, lang = "en", name = "Example Source", supportsLatest = true, isStub = false)

    private fun item(manga: Manga = this.manga) = LibraryItem(
        libraryManga = LibraryManga(
            manga = manga,
            categories = emptyList(),
            totalChapters = 10L,
            readCount = 3L,
            bookmarkCount = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        ),
        downloadCount = 0L,
        unreadCount = 7L,
        isLocal = false,
        sourceLanguage = "en",
    )

    private val item = item()
}
