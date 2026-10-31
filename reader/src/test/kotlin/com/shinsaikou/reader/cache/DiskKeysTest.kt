package com.shinsaikou.reader.cache

import com.shinsaikou.reader.pipeline.SplitPlanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class DiskKeysTest {

    /** DiskLruCache validates every key against this and throws at runtime otherwise. */
    private val legalKey = Regex("[a-z0-9_-]{1,64}")

    @Test
    fun `an image key is 32 lowercase hex characters`() {
        val key = DiskKeys.image("page-one")
        key.length shouldBe 32
        key shouldMatch legalKey
    }

    @Test
    fun `a part key is the parent key plus a three digit suffix`() {
        val parent = DiskKeys.image("page-one")
        DiskKeys.part("page-one", 0) shouldBe parent + "_p000"
        DiskKeys.part("page-one", 7) shouldBe parent + "_p007"
        DiskKeys.part("page-one", 998) shouldBe parent + "_p998"
    }

    @Test
    fun `a part key is still legal and still inside the length budget`() {
        val key = DiskKeys.part("page-one", 123)
        key.length shouldBe 37
        key shouldMatch legalKey
    }

    @Test
    fun `a part key can never collide with a whole page key`() {
        // MD5 hex carries no underscore, so no whole key has the shape <32 hex>_p<3 digits>.
        val shape = Regex("[0-9a-f]{32}_p[0-9]{3}")
        repeat(64) { index ->
            DiskKeys.image("page-$index").shouldMatch(Regex("[0-9a-f]{32}"))
            DiskKeys.part("page-$index", index).shouldMatch(shape)
        }
    }

    @Test
    fun `a part index outside the plan's range is a programmer error`() {
        shouldThrow<IllegalArgumentException> { DiskKeys.part("page-one", -1) }
        shouldThrow<IllegalArgumentException> { DiskKeys.part("page-one", SplitPlanner.MAX_PARTS) }
    }

    @Test
    fun `keys are stable across calls`() {
        DiskKeys.image("page-one") shouldBe DiskKeys.image("page-one")
        DiskKeys.pageList(12, "/chapter/3") shouldBe DiskKeys.pageList(12, "/chapter/3")
        DiskKeys.part("page-one", 3) shouldBe DiskKeys.part("page-one", 3)
    }

    @Test
    fun `the page list key separates its two fields`() {
        // Upstream concatenates them, so manga 12 with url /3 and manga 1 with url 2/3 collide.
        DiskKeys.pageList(12, "/3") shouldBe DiskKeys.pageList(12, "/3")
        (DiskKeys.pageList(12, "/3") == DiskKeys.pageList(1, "2/3")) shouldBe false
    }

    @Test
    fun `a page list key is domain separated from an image key of the same text`() {
        (DiskKeys.pageList(12, "/3") == DiskKeys.image("12|/3")) shouldBe false
    }
}
