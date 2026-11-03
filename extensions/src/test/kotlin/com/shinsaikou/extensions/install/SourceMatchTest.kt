package com.shinsaikou.extensions.install

import com.shinsaikou.domain.extension.SourceExtensionInstaller.WantedSource
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore

/** Backup sources against index extensions. Every id, name and address is made up. */
class SourceMatchTest {

    @Test
    fun `a source is matched to the extension that lists its id`() {
        val one = available("fixture.one", 11L)
        val two = available("fixture.two", 21L, 22L)

        val match = SourceMatch.match(listOf(wanted(22L), wanted(11L)), listOf(one, two), onDisk = emptySet())

        match.toInstall shouldContainExactly listOf(two, one)
        match.alreadyInstalled.shouldBeEmpty()
        match.notFound.shouldBeEmpty()
    }

    @Test
    fun `the id decides, not the name`() {
        val extension = available("fixture.one", 11L).let {
            it.copy(sources = listOf(AvailableSource(11L, "Same Name", "en", ""), AvailableSource(12L, "Same Name", "fr", "")))
        }

        val match = SourceMatch.match(listOf(WantedSource(13L, "Same Name")), listOf(extension), onDisk = emptySet())

        match.toInstall.shouldBeEmpty()
        match.notFound shouldContainExactly listOf(WantedSource(13L, "Same Name"))
    }

    @Test
    fun `several sources of one extension install it once`() {
        val multi = available("fixture.multi", 31L, 32L, 33L)

        val match = SourceMatch.match(listOf(wanted(31L), wanted(33L), wanted(32L)), listOf(multi), onDisk = emptySet())

        match.toInstall shouldContainExactly listOf(multi)
    }

    @Test
    fun `an extension already on the device is skipped, loaded or not`() {
        val installed = available("fixture.installed", 41L)
        val missing = available("fixture.missing", 51L)

        val match = SourceMatch.match(
            listOf(wanted(41L), wanted(51L)),
            listOf(installed, missing),
            onDisk = setOf("fixture.installed"),
        )

        match.toInstall shouldContainExactly listOf(missing)
        match.alreadyInstalled shouldContainExactly listOf(installed)
    }

    @Test
    fun `sources no index lists are reported by the backup's names`() {
        val match = SourceMatch.match(
            listOf(WantedSource(61L, "Fixture Gone"), wanted(11L), WantedSource(62L, "Fixture Also Gone")),
            listOf(available("fixture.one", 11L)),
            onDisk = emptySet(),
        )

        match.notFound shouldContainExactly listOf(WantedSource(61L, "Fixture Gone"), WantedSource(62L, "Fixture Also Gone"))
    }

    @Test
    fun `with no index read, every source is not found`() {
        val match = SourceMatch.match(listOf(wanted(11L), wanted(11L)), available = emptyList(), onDisk = emptySet())

        match.toInstall.shouldBeEmpty()
        match.notFound shouldContainExactly listOf(wanted(11L))
    }

    private fun wanted(id: Long) = WantedSource(id, "Fixture Source $id")

    private fun available(packageName: String, vararg sourceIds: Long) = Extension.Available(
        packageName = packageName,
        name = "Fixture $packageName",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = sourceIds.map { AvailableSource(it, "Fixture Source $it", "en", "") },
        apkUrl = "https://example.invalid/apk/$packageName.apk",
        iconUrl = "https://example.invalid/icon/$packageName.png",
        jarUrl = null,
        store = STORE,
    )

    private companion object {
        val STORE = ExtensionStore(
            indexUrl = "https://example.invalid/index.pb",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "0".repeat(64),
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
