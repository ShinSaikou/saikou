package com.shinsaikou.source

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SourceIdsTest {

    @Test
    fun `derived id is stable across calls`() {
        val first = SourceIds.deriveHttpSourceId("Contract Test", "en", 1)
        val second = SourceIds.deriveHttpSourceId("Contract Test", "en", 1)

        first shouldBe second
    }

    @Test
    fun `derived id has the sign bit cleared`() {
        val id = SourceIds.deriveHttpSourceId("Contract Test", "en", 1)

        (id >= 0L) shouldBe true
    }

    @Test
    fun `only the name is lowercased`() {
        SourceIds.deriveHttpSourceId("Contract Test", "en", 1) shouldBe
            SourceIds.deriveHttpSourceId("contract test", "en", 1)
        SourceIds.deriveHttpSourceId("Contract Test", "EN", 1) shouldNotBe
            SourceIds.deriveHttpSourceId("Contract Test", "en", 1)
    }

    @Test
    fun `version id changes the derived id`() {
        SourceIds.deriveHttpSourceId("Contract Test", "en", 1) shouldNotBe
            SourceIds.deriveHttpSourceId("Contract Test", "en", 2)
    }

    @Test
    fun `local id is zero`() {
        SourceIds.LOCAL shouldBe 0L
    }
}
