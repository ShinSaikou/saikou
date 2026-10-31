package com.shinsaikou.extensions

import com.shinsaikou.source.SourceIds
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `SourceIds.deriveHttpSourceId` exists so the data layer can reason about ids the host never
 * assigns. It has to agree with the ABI's own `generateId` byte for byte, or a backup import would
 * rewrite a source id that is in fact unchanged.
 */
class SourceIdsAgreementTest {

    private class FixtureSource(
        override val name: String,
        override val lang: String,
        override val versionId: Int,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val supportsLatest: Boolean = false
        override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
    }

    @Test
    fun `the derived id equals the id the ABI generates`() {
        listOf(
            Triple("Fixture Source A", "en", 1),
            Triple("Fixture Source A", "en", 2),
            Triple("FIXTURE source a", "en", 1),
            Triple("Fixture Source B", "all", 1),
            Triple("Fixture Source B", "", 7),
        ).forEach { (name, lang, versionId) ->
            SourceIds.deriveHttpSourceId(name, lang, versionId) shouldBe FixtureSource(name, lang, versionId).id
        }
    }

    @Test
    fun `only the name is lowercased, so language case changes the id`() {
        val lower = SourceIds.deriveHttpSourceId("Fixture Source A", "en", 1)
        val upper = SourceIds.deriveHttpSourceId("Fixture Source A", "EN", 1)

        (lower == upper) shouldBe false
    }

    @Test
    fun `the sign bit is always clear`() {
        (SourceIds.deriveHttpSourceId("Fixture Source C", "en", 1) >= 0) shouldBe true
    }
}
