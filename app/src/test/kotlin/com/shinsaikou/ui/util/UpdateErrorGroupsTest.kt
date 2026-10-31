package com.shinsaikou.ui.util

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.source.StubSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The grouping both error surfaces share. It is pure so the ordering the library sheet and the
 * updates dialog both show is asserted once, without a source manager or a composition.
 */
class UpdateErrorGroupsTest {

    @Test
    fun `errors group by source id and sort by source name then title`() {
        val errors = listOf(
            error(mangaId = 1L, title = "Zither", sourceId = 30L),
            error(mangaId = 2L, title = "apple", sourceId = 10L),
            error(mangaId = 3L, title = "Anvil", sourceId = 20L),
            error(mangaId = 4L, title = "Bellows", sourceId = 20L),
            error(mangaId = 5L, title = "Barrow", sourceId = 10L),
        )

        val groups = groupUpdateErrors(errors) { source(it) }

        groups.map { it.sourceName } shouldContainExactly listOf("Almanac", "Beacon", "Cartograph")
        groups[0].errors.map { it.title } shouldContainExactly listOf("apple", "Barrow")
        groups[1].errors.map { it.title } shouldContainExactly listOf("Anvil", "Bellows")
        groups[2].errors.map { it.title } shouldContainExactly listOf("Zither")
    }

    @Test
    fun `a source name is resolved once per group and not once per row`() {
        val errors = listOf(
            error(mangaId = 1L, title = "One", sourceId = 10L),
            error(mangaId = 2L, title = "Two", sourceId = 10L),
            error(mangaId = 3L, title = "Three", sourceId = 10L),
            error(mangaId = 4L, title = "Four", sourceId = 20L),
        )
        val calls = mutableListOf<Long>()

        groupUpdateErrors(errors) { sourceId ->
            calls += sourceId
            source(sourceId)
        }

        calls shouldContainExactly listOf(10L, 20L)
    }

    @Test
    fun `an empty error list produces no groups`() {
        val calls = mutableListOf<Long>()

        val groups = groupUpdateErrors(emptyList()) { sourceId ->
            calls += sourceId
            StubSource.unknown(sourceId)
        }

        groups shouldBe emptyList()
        calls shouldBe emptyList()
    }

    /**
     * Two sources that share one name are the multi-language case: one extension, one name, one
     * source per language. Each carries its own id, which is what the list keys its header on.
     */
    @Test
    fun `two sources sharing a name make two groups with distinct keys and labels`() {
        val errors = listOf(
            error(mangaId = 1L, title = "Anvil", sourceId = 40L),
            error(mangaId = 2L, title = "Bellows", sourceId = 41L),
            error(mangaId = 3L, title = "Barrow", sourceId = 40L),
        )
        val shared = mapOf(40L to StubSource(40L, "Dovecote", "en"), 41L to StubSource(41L, "Dovecote", "fr"))

        val groups = groupUpdateErrors(errors) { shared.getValue(it) }

        groups.map { it.sourceId } shouldContainExactly listOf(40L, 41L)
        groups.map { it.sourceName } shouldContainExactly listOf("Dovecote (EN)", "Dovecote (FR)")
        groups.map { "source-${it.sourceId}" }.toSet().size shouldBe 2
        groups[0].errors.map { it.mangaId } shouldContainExactly listOf(1L, 3L)
        groups[1].errors.map { it.mangaId } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a name no other group shares keeps its bare label`() {
        val errors = listOf(
            error(mangaId = 1L, title = "Anvil", sourceId = 40L),
            error(mangaId = 2L, title = "Bellows", sourceId = 41L),
            error(mangaId = 3L, title = "Zither", sourceId = 10L),
        )
        val sources = mapOf(
            10L to StubSource(10L, "Almanac", "en"),
            40L to StubSource(40L, "Dovecote", "en"),
            41L to StubSource(41L, "Dovecote", ""),
        )

        val groups = groupUpdateErrors(errors) { sources.getValue(it) }

        // Almanac shares its name with nobody, so it is left alone. The second Dovecote has no
        // remembered language, so it keeps its bare name the way a stub renders one, and the id
        // is still what tells the two groups apart.
        groups.map { it.sourceName } shouldContainExactly listOf("Almanac", "Dovecote", "Dovecote (EN)")
        groups.map { it.sourceId } shouldContainExactly listOf(10L, 41L, 40L)
    }

    /**
     * The notice above both error lists counts the entries a stopped site left unasked, and nothing
     * else: another failure on the same site, or a row stored before keys were, is not one of them.
     */
    @Test
    fun `stoppedEntries counts only the errors of a stopped site`() {
        val host = "example.invalid"
        val errors = listOf(
            error(mangaId = 1L, title = "Anvil", sourceId = 10L, key = ErrorKey.SITE_STOPPED, detail = host),
            error(mangaId = 2L, title = "Bellows", sourceId = 10L, key = ErrorKey.SITE_STOPPED, detail = host),
            error(mangaId = 3L, title = "Barrow", sourceId = 10L, key = ErrorKey.HTTP, detail = "429"),
            error(mangaId = 4L, title = "Zither", sourceId = 20L, key = ErrorKey.TIMEOUT),
            error(mangaId = 5L, title = "Dovetail", sourceId = 20L, key = ErrorKey.SITE_STOPPED),
            error(mangaId = 6L, title = "Ember", sourceId = 30L, key = null, detail = "stored before keys"),
            error(mangaId = 7L, title = "Flint", sourceId = 30L),
        )

        stoppedEntries(errors) shouldBe 3
    }

    @Test
    fun `stoppedEntries is zero when no site was stopped`() {
        stoppedEntries(emptyList()) shouldBe 0
        stoppedEntries(
            listOf(
                error(mangaId = 1L, title = "Anvil", sourceId = 10L, key = ErrorKey.HTTP, detail = "503"),
                error(mangaId = 2L, title = "Bellows", sourceId = 10L),
            ),
        ) shouldBe 0
    }

    private val sourceNames = mapOf(10L to "Almanac", 20L to "Beacon", 30L to "Cartograph")

    private fun source(id: Long) = StubSource(id = id, name = sourceNames.getValue(id), lang = "en")

    private fun error(
        mangaId: Long,
        title: String,
        sourceId: Long,
        key: ErrorKey? = ErrorKey.NO_CHAPTERS,
        detail: String? = null,
    ) = LibraryUpdateError(
        mangaId = mangaId,
        title = title,
        sourceId = sourceId,
        key = key,
        detail = detail,
        at = 1_000L,
    )
}
