package com.shinsaikou.domain.category

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/** Phase 3 Q7: one key per name, whatever the case or the surrounding spaces. */
class CategorySyncKeyTest {

    @Test
    fun `a name keys the same after trimming and lower-casing, and is a SHA-256 hex digest`() {
        CategorySyncKey.of(" Reading ") shouldBe CategorySyncKey.of("reading")
        CategorySyncKey.of("READING") shouldBe CategorySyncKey.of("reading")
        // Pinned: a key already stored on a device must keep matching the one a later build derives.
        CategorySyncKey.of("reading") shouldBe "d70afca615203838da3d858e60dda12f900e83e4a6fb03a63cfadb8576ca8985"
    }

    @Test
    fun `case folds with Kotlin's Unicode rules, beyond ASCII`() {
        CategorySyncKey.of("ÄRGER") shouldBe CategorySyncKey.of("ärger")
        CategorySyncKey.of("ΣΚΙΑ") shouldBe CategorySyncKey.of("σκια")
    }

    @Test
    fun `different names key differently, and a random key never has a derived key's shape`() {
        CategorySyncKey.of("Reading") shouldNotBe CategorySyncKey.of("Read ing")
        val random = CategorySyncKey.random()
        random shouldNotBe CategorySyncKey.random()
        random shouldMatch Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
