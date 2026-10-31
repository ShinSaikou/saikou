package com.shinsaikou.ui.browse.extension

import app.cash.turbine.test
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The one list of sources this device lacks (unit restore-report): Install offered only when an
 * available extension lists the source's id, the names the registry remembers, the library's
 * counts, and the list following the registry as extensions install.
 */
class MissingExtensionsTest {

    private val fixture = MissingExtensionsFixture()

    @Test
    fun `install is offered only for a source an available extension lists, by name, with its entries`() = runTest {
        fixture.remember(SOURCE_A, "Alpha")
        fixture.remember(SOURCE_B, "Beta")
        val offering = fixture.available("dev.example.fixture.alpha", SOURCE_A, SOURCE_OTHER)
        fixture.availableFlow.value = listOf(fixture.available("dev.example.fixture.other", SOURCE_OTHER), offering)
        fixture.countsFlow.value = mapOf(SOURCE_A to 3L, SOURCE_OTHER to 9L)

        val rows = fixture.missingExtensions.forSources(listOf(SOURCE_B, SOURCE_A)).first()

        rows shouldContainExactly listOf(
            MissingExtensions.Row(SOURCE_A, "Alpha (EN)", 3L, offering, query = "Alpha"),
            MissingExtensions.Row(SOURCE_B, "Beta (EN)", 0L, null, query = "Beta"),
        )
    }

    @Test
    fun `a live source, the Local source and a repeated id are left out, and an unnamed source reads as its id with nothing to search for`() = runTest {
        fixture.liveFlow.value = listOf(fixture.live(SOURCE_A))

        val rows = fixture.missingExtensions.forSources(listOf(SOURCE_A, SourceIds.LOCAL, SOURCE_B, SOURCE_B)).first()

        rows.map { it.sourceId } shouldContainExactly listOf(SOURCE_B)
        rows.single().name shouldBe SOURCE_B.toString()
        rows.single().query shouldBe null
    }

    /** Installing again would not load it; the Extensions tab's Trust does, so the row opens it. */
    @Test
    fun `an extension already on disk and untrusted is not offered for install`() = runTest {
        fixture.availableFlow.value = listOf(fixture.available(PACKAGE, SOURCE_A))
        fixture.untrustedFlow.value = listOf(fixture.untrusted(PACKAGE))

        val rows = fixture.missingExtensions.forSources(listOf(SOURCE_A)).first()

        rows.single().available shouldBe null
    }

    @Test
    fun `nothing is listed before the registry's first load, and a source an install brings live leaves`() = runTest {
        fixture.initializedFlow.value = false
        fixture.availableFlow.value = listOf(fixture.available(PACKAGE, SOURCE_A))

        fixture.missingExtensions.forSources(listOf(SOURCE_A, SOURCE_B)).test {
            expectNoEvents()

            fixture.initializedFlow.value = true
            awaitItem().map { it.sourceId } shouldContainExactly listOf(SOURCE_A, SOURCE_B)

            fixture.liveFlow.value = listOf(fixture.live(SOURCE_A))
            awaitItem().map { it.sourceId } shouldContainExactly listOf(SOURCE_B)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val SOURCE_A = 7_310_000_000_000_101L
        const val SOURCE_B = 7_310_000_000_000_102L
        const val SOURCE_OTHER = 7_310_000_000_000_103L
        const val PACKAGE = "dev.example.fixture.alpha"
    }
}
