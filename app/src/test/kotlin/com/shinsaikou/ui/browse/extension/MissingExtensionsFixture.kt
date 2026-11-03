package com.shinsaikou.ui.browse.extension

import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.domain.extension.model.ExtensionStore
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * A [MissingExtensions] over flows a test drives: the registry (its first load, its live sources and
 * the names it remembers), the extension manager's available, installed, untrusted and unloaded
 * lists and install steps, and the library's entry count per source. Shared by the list's own test
 * and the restore report's.
 *
 * The flows carry a Flow suffix for `ExtensionsViewModelTest`'s reason: inside a mockk block a bare
 * name matching one of the mock's properties binds to the mock.
 */
class MissingExtensionsFixture {

    val initializedFlow = MutableStateFlow(true)
    val liveFlow = MutableStateFlow<List<Source>>(emptyList())
    val availableFlow = MutableStateFlow<List<Extension.Available>>(emptyList())
    val untrustedFlow = MutableStateFlow<List<Extension.Untrusted>>(emptyList())
    val installedFlow = MutableStateFlow<List<Extension.Installed>>(emptyList())
    val loadErrorsFlow = MutableStateFlow<List<LoadResult.Error>>(emptyList())
    val installStepsFlow = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    val countsFlow = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** What the registry remembers of each missing source; one with no entry reads as its id. */
    val remembered = mutableMapOf<Long, StubSource>()

    val sourceManager = mockk<SourceManager> {
        every { isInitialized } returns initializedFlow
        every { getSources() } returns liveFlow
        every { getOrStub(any()) } answers {
            val id = firstArg<Long>()
            liveFlow.value.firstOrNull { it.id == id } ?: remembered[id] ?: StubSource.unknown(id)
        }
    }.also { manager ->
        // Outside the block: there, `get` names the matcher scope's own.
        every { manager.get(any()) } answers { liveFlow.value.firstOrNull { it.id == firstArg<Long>() } }
    }

    /** How many times the screen under test asked the manager to fetch the indexes. */
    var refreshCalls = 0
        private set

    /** What a fetch does when asked: by default it succeeds and the list stays as it is. */
    var onRefresh: () -> Result<Unit> = { Result.success(Unit) }

    val extensionManager = mockk<ExtensionManager> {
        every { availableExtensions } returns availableFlow
        coEvery { refreshAvailable() } answers {
            refreshCalls++
            onRefresh()
        }
        every { untrustedExtensions } returns untrustedFlow
        every { installedExtensions } returns installedFlow
        every { loadErrors } returns loadErrorsFlow
        every { installSteps } returns installStepsFlow
        every { install(any()) } returns Job()
    }

    val mangaRepository = mockk<MangaRepository> {
        every { getSourceIdsWithFavoriteCountAsFlow() } returns countsFlow
    }

    val missingExtensions = MissingExtensions(sourceManager, extensionManager, mangaRepository)

    fun remember(id: Long, name: String, lang: String = "en") {
        remembered[id] = StubSource(id, name, lang)
    }

    fun live(id: Long): Source = mockk { every { this@mockk.id } returns id }

    fun available(packageName: String, vararg sourceIds: Long) = Extension.Available(
        packageName = packageName,
        name = "Fixture extension $packageName",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        sources = sourceIds.map { AvailableSource(id = it, name = "Fixture source $it", lang = "en", homeUrl = "") },
        apkUrl = "https://example.invalid/fixture.apk",
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = ExtensionStore(
            indexUrl = "https://example.invalid/index.json",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "fixture-signing-key",
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )

    fun untrusted(packageName: String) = Extension.Untrusted(
        packageName = packageName,
        name = "Fixture extension $packageName",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = "en",
        isNsfw = false,
        signatureHash = "fixture-hash",
    )
}
