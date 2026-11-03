package com.shinsaikou.domain.extension

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore

/** Every address and key is a placeholder (`CONTRIBUTING.md`). */
class ExtensionStoreDuplicatesTest {

    @Test
    fun `the addresses of one repository share a key`() {
        val keys = listOf(
            "https://example.invalid/repo",
            "https://example.invalid/repo/",
            "http://example.invalid/repo",
            "HTTPS://Example.Invalid/repo",
            "https://example.invalid/repo/repo.json",
            "https://example.invalid/repo/index.min.json",
            "https://example.invalid/repo/index.json",
            "https://example.invalid/repo/index.pb",
            "  https://example.invalid/repo//repo.json ",
        ).map(ExtensionStoreDuplicates::key)

        keys.distinct() shouldContainExactly listOf("example.invalid/repo")
    }

    @Test
    fun `two repositories on one host stay two`() {
        ExtensionStoreDuplicates.key("https://example.invalid/one/index.pb") shouldBe "example.invalid/one"
        ExtensionStoreDuplicates.key("https://example.invalid/two/index.pb") shouldBe "example.invalid/two"
    }

    @Test
    fun `paths that differ in case are two repositories`() {
        ExtensionStoreDuplicates.key("https://example.invalid/Repo/index.pb") shouldBe "example.invalid/Repo"
        ExtensionStoreDuplicates.key("https://example.invalid/repo/index.pb") shouldBe "example.invalid/repo"
    }

    @Test
    fun `a legacy row with no name folds into the named row of the same repository`() {
        val legacy = legacyRow("https://example.invalid/repo")
        val named = namedRow("https://example.invalid/repo/index.pb")

        val merge = ExtensionStoreDuplicates.merge(listOf(legacy, named, namedRow("https://example.invalid/other/index.pb")))

        merge.removed shouldContainExactly listOf(legacy.indexUrl)
        merge.updated.shouldBeEmpty()
    }

    @Test
    fun `the kept row takes what it lacks from the others but keeps how it is fetched`() {
        val keyed = namedRow("https://example.invalid/repo/index.pb").copy(
            badgeLabel = "",
            contact = ExtensionStore.Contact(website = "", discord = null),
        )
        val other = namedRow("http://example.invalid/repo/repo.json").copy(
            name = "",
            badgeLabel = "RB",
            signingKey = "",
            contact = ExtensionStore.Contact("https://example.invalid/site", "https://example.invalid/chat"),
            isLegacy = true,
            extensionListUrl = "https://example.invalid/repo/list.json",
        )

        val merge = ExtensionStoreDuplicates.merge(listOf(other, keyed))

        merge.removed shouldContainExactly listOf(other.indexUrl)
        merge.updated shouldContainExactly listOf(
            keyed.copy(
                badgeLabel = "RB",
                contact = ExtensionStore.Contact("https://example.invalid/site", "https://example.invalid/chat"),
            ),
        )
    }

    @Test
    fun `two rows with no name keep the one with a key and fill its name from neither`() {
        val unkeyed = legacyRow("https://example.invalid/repo")
        val keyed = unkeyed.copy(indexUrl = "https://example.invalid/repo/index.pb", signingKey = KEY)

        val merge = ExtensionStoreDuplicates.merge(listOf(unkeyed, keyed))

        merge.removed shouldContainExactly listOf(unkeyed.indexUrl)
        merge.updated.shouldBeEmpty()
    }

    @Test
    fun `rows that name two signing keys are left apart`() {
        val rows = listOf(
            namedRow("https://example.invalid/repo/index.pb"),
            namedRow("http://example.invalid/repo/index.pb").copy(signingKey = "1".repeat(64)),
        )

        val merge = ExtensionStoreDuplicates.merge(rows)

        merge.isEmpty shouldBe true
    }

    @Test
    fun `a table with one row per repository has nothing to merge`() {
        val rows = listOf(namedRow("https://example.invalid/one/index.pb"), legacyRow("https://example.invalid/two"))

        ExtensionStoreDuplicates.merge(rows).isEmpty shouldBe true
    }

    /** What the restore writes for an entry of upstream's legacy `extension_repos` set. */
    private fun legacyRow(base: String) = ExtensionStore(
        indexUrl = "$base/repo.json",
        name = "",
        badgeLabel = "",
        signingKey = "",
        contact = ExtensionStore.Contact(website = base, discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )

    private fun namedRow(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = "Fixture Repository",
        badgeLabel = "FR",
        signingKey = KEY,
        contact = ExtensionStore.Contact("https://example.invalid", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private companion object {
        val KEY = "0".repeat(64)
    }
}
