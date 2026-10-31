package com.shinsaikou.ui.browse

import coil3.ImageLoader
import coil3.request.Options
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionIcons
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.StubSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * An icon names its extension by package or by source id and is resolved against the extensions
 * installed at the moment the image is asked for. The cases here are the ones where a wrong answer
 * shows the wrong icon: a stub source resolving to some extension, and an update keeping its old
 * memory cache key.
 *
 * fetch() itself needs a real Drawable and Bitmap, which a JVM test has no Robolectric for, so it
 * is covered only up to the point where the icon file is read.
 */
class ExtensionIconFetcherTest {

    private val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    private val extensionManager = mockk<ExtensionManager> {
        every { installedExtensions } returns installedFlow
    }
    private val icons = mockk<ExtensionIcons>()
    private val options = mockk<Options>()
    private val imageLoader = mockk<ImageLoader>()

    private val factory = ExtensionIconFetcher.Factory({ extensionManager }, { icons })
    private val keyer = ExtensionIconKeyer { extensionManager }

    @Test
    fun `OfPackage resolves the installed extension with that package`() {
        val one = installed("pkg.one", sourceIds = listOf(1L))
        val two = installed("pkg.two", sourceIds = listOf(2L))

        ExtensionIcon.OfPackage("pkg.two").resolve(listOf(one, two)) shouldBe two
    }

    @Test
    fun `OfSource resolves the extension whose sources contain that id`() {
        val one = installed("pkg.one", sourceIds = listOf(1L, 10L))
        val two = installed("pkg.two", sourceIds = listOf(2L, 20L))

        ExtensionIcon.OfSource(20L).resolve(listOf(one, two)) shouldBe two
    }

    @Test
    fun `OfPackage resolves to null when no installed extension has the package`() {
        ExtensionIcon.OfPackage("pkg.gone").resolve(listOf(installed("pkg.one", listOf(1L)))).shouldBeNull()
    }

    @Test
    fun `OfSource resolves to null for a source no installed extension provides`() {
        ExtensionIcon.OfSource(99L).resolve(listOf(installed("pkg.one", listOf(1L)))).shouldBeNull()
    }

    @Test
    fun `resolving against nothing installed gives null`() {
        ExtensionIcon.OfPackage("pkg.one").resolve(emptyList()).shouldBeNull()
        ExtensionIcon.OfSource(1L).resolve(emptyList()).shouldBeNull()
    }

    @Test
    fun `the keyer keys a package icon on the package and its version code`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L), versionCode = 7L))

        keyer.key(ExtensionIcon.OfPackage("pkg.one"), options) shouldBe "extension-icon:pkg.one:7"
    }

    @Test
    fun `the keyer gives a source icon the key of the extension that owns the source`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L, 2L), versionCode = 7L))

        keyer.key(ExtensionIcon.OfSource(2L), options) shouldBe "extension-icon:pkg.one:7"
    }

    @Test
    fun `the keyer returns a different key after the package is updated`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L), versionCode = 7L))
        val before = keyer.key(ExtensionIcon.OfPackage("pkg.one"), options)

        installedFlow.value = listOf(installed("pkg.one", listOf(1L), versionCode = 8L))
        val after = keyer.key(ExtensionIcon.OfPackage("pkg.one"), options)

        before shouldBe "extension-icon:pkg.one:7"
        after shouldBe "extension-icon:pkg.one:8"
    }

    @Test
    fun `the keyer returns null for an icon that does not resolve`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L)))

        keyer.key(ExtensionIcon.OfPackage("pkg.gone"), options).shouldBeNull()
        keyer.key(ExtensionIcon.OfSource(99L), options).shouldBeNull()
    }

    @Test
    fun `the factory creates no fetcher for an icon that does not resolve`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L)))

        factory.create(ExtensionIcon.OfPackage("pkg.gone"), options, imageLoader).shouldBeNull()
        factory.create(ExtensionIcon.OfSource(99L), options, imageLoader).shouldBeNull()
    }

    @Test
    fun `the factory creates a fetcher for a resolvable icon`() {
        installedFlow.value = listOf(installed("pkg.one", listOf(1L)))

        factory.create(ExtensionIcon.OfPackage("pkg.one"), options, imageLoader)
            .shouldNotBeNull()
            .shouldBeInstanceOf<ExtensionIconFetcher>()
    }

    @Test
    fun `the created fetcher reads the icon from the resolved extension's file`() = runTest {
        val wanted = installed("pkg.two", listOf(2L), extFile = File("two.ext"))
        installedFlow.value = listOf(installed("pkg.one", listOf(1L), extFile = File("one.ext")), wanted)
        coEvery { icons.load(any()) } returns null

        val fetcher = factory.create(ExtensionIcon.OfSource(2L), options, imageLoader).shouldNotBeNull()
        shouldThrow<FileNotFoundException> { fetcher.fetch() }

        coVerify(exactly = 1) { icons.load(File("two.ext")) }
    }

    private fun installed(
        packageName: String,
        sourceIds: List<Long>,
        versionCode: Long = 1L,
        extFile: File = File("$packageName.ext"),
    ) = Extension.Installed(
        packageName = packageName,
        name = packageName,
        versionName = "1.0",
        versionCode = versionCode,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sourceIds.map { StubSource(it, "Source $it", "en") },
        extFile = extFile,
    )
}
