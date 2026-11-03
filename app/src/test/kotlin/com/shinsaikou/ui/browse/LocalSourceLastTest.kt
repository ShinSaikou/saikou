package com.shinsaikou.ui.browse

import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The Local source after every extension source: sorted by name it sat among them, under L, on the
 * owner's Sources page. Everything else keeps the order it came in.
 */
class LocalSourceLastTest {

    @Test
    fun `the Local source goes after every other source, which keep their order`() {
        val sources = listOf(source(3L, "Kappa"), source(SourceIds.LOCAL, "Local source"), source(1L, "Mu"), source(2L, "Alpha"))

        sources.sortedWith(localSourceLast()).map { it.id } shouldContainExactly listOf(3L, 1L, 2L, SourceIds.LOCAL)
    }

    @Test
    fun `followed by a name order, the Local source still reads last`() {
        val sources = listOf(source(1L, "Mu"), source(SourceIds.LOCAL, "Local source"), source(2L, "Kappa"))

        val sorted = sources.sortedWith(localSourceLast<CatalogueSource>().thenBy { it.name.lowercase() })

        sorted.map { it.id } shouldContainExactly listOf(2L, 1L, SourceIds.LOCAL)
    }

    private fun source(sourceId: Long, sourceName: String): CatalogueSource = mockk {
        every { id } returns sourceId
        every { name } returns sourceName
    }
}
