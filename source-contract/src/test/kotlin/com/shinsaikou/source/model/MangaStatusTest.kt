package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaStatusTest {

    @Test
    fun `every code in zero to six maps to its own entry`() {
        MangaStatus.fromCode(0) shouldBe MangaStatus.UNKNOWN
        MangaStatus.fromCode(1) shouldBe MangaStatus.ONGOING
        MangaStatus.fromCode(2) shouldBe MangaStatus.COMPLETED
        MangaStatus.fromCode(3) shouldBe MangaStatus.LICENSED
        MangaStatus.fromCode(4) shouldBe MangaStatus.PUBLISHING_FINISHED
        MangaStatus.fromCode(5) shouldBe MangaStatus.CANCELLED
        MangaStatus.fromCode(6) shouldBe MangaStatus.ON_HIATUS
    }

    @Test
    fun `an unknown code falls back to UNKNOWN`() {
        MangaStatus.fromCode(99) shouldBe MangaStatus.UNKNOWN
        MangaStatus.fromCode(-1) shouldBe MangaStatus.UNKNOWN
    }

    @Test
    fun `the codes match the declaration order the database column stores`() {
        MangaStatus.entries.map { it.code } shouldBe listOf(0, 1, 2, 3, 4, 5, 6)
    }
}
