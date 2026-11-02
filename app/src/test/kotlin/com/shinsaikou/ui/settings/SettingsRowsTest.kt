package com.shinsaikou.ui.settings

import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.core.common.network.PREF_DOH_CUSTOM
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.library.interactor.RebuildLibraryCounts
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderHideThreshold
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.i18n.R
import com.shinsaikou.image.GLUtil
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.settings.titleRes
import com.shinsaikou.reader.ui.stringRes
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.ui.main.BatteryExemption
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.category.model.Category
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Upstream's settings rows over keys Saikou already reads (phase 4 unit SR), one case per row: the
 * section reads the preference off [SettingsViewModel], the preference is upstream's key with upstream's
 * default, a choice row offers upstream's choices in upstream's order with its labels, and the value the
 * row writes is what the preference then holds. What each key does is its reader's own tests'.
 */
class SettingsRowsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val store = InMemoryPreferenceStore()
    private val resetCategoryFlags = mockk<ResetCategoryFlags>(relaxed = true)

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        uiPreferences = UiPreferences(store),
        libraryPreferences = LibraryPreferences(store),
        readerPreferences = ReaderPreferences(store),
        sourcePreferences = SourcePreferences(store),
        backupPreferences = BackupPreferences(store),
        extensionsPreferences = ExtensionsPreferences(store),
        basePreferences = BasePreferences(store),
        downloadPreferences = DownloadPreferences(store),
        storagePreferences = StoragePreferences(store),
        networkPreferences = NetworkPreferences(store),
        securityPreferences = SecurityPreferences(store),
        extensionManager = mockk<ExtensionManager> {
            every { availableExtensions } returns MutableStateFlow(emptyList())
            every { installedExtensions } returns MutableStateFlow(emptyList())
        },
        libraryUpdateScheduler = mockk<LibraryUpdateScheduler>(relaxed = true),
        backupScheduler = mockk<BackupScheduler>(relaxed = true),
        batteryExemption = mockk<BatteryExemption>(relaxed = true),
        chapterCache = mockk<ChapterCache>(relaxed = true),
        downloadManifestRepository = mockk<DownloadManifestRepository>(relaxed = true),
        downloadManifest = mockk<DownloadManifest>(relaxed = true),
        downloadManifestSweep = mockk<DownloadManifestSweep>(relaxed = true),
        adoptionScheduler = mockk<AdoptionScheduler>(relaxed = true) {
            every { state } returns MutableStateFlow(AdoptionScheduler.State(running = false))
        },
        adoptionSummaryStore = mockk<AdoptionSummaryStore>(relaxed = true) {
            every { summary } returns MutableStateFlow<AdoptionSummary?>(null)
        },
        downloadExportScheduler = mockk<DownloadExportScheduler>(relaxed = true) {
            every { state } returns MutableStateFlow(DownloadExportScheduler.State(running = false))
        },
        getCategories = mockk<GetCategories> { every { subscribe() } returns flowOf(emptyList()) },
        resetCategoryFlags = resetCategoryFlags,
        orphanDownloads = mockk<OrphanDownloads>(relaxed = true),
        resetViewerFlags = mockk<ResetViewerFlags>(relaxed = true),
        rebuildLibraryCounts = mockk<RebuildLibraryCounts>(relaxed = true),
        trustStore = mockk<TrustStore>(relaxed = true),
        appInfo = mockk(),
        trackerManager = TrackerManager(emptySet()),
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    /** [preference] is upstream's [key] with upstream's [default], and holds [written] once the row sets it. */
    private fun <T> Preference<T>.shouldBeUpstreams(key: String, default: T, written: T) {
        key() shouldBe key
        get() shouldBe default
        set(written)
        get() shouldBe written
    }

    /** A switch row: upstream's [key] and [default], and a tap turns it the other way. */
    private fun Preference<Boolean>.shouldBeUpstreamsSwitch(key: String, default: Boolean) {
        key() shouldBe key
        get() shouldBe default
        toggle()
        get() shouldBe !default
    }

    // ---- Reader ----

    @Test
    fun `default reading mode offers upstream's five modes and writes the mode's flag`() {
        defaultReadingModeChoices shouldContainExactly listOf(
            ReadingMode.LEFT_TO_RIGHT,
            ReadingMode.RIGHT_TO_LEFT,
            ReadingMode.VERTICAL,
            ReadingMode.WEBTOON,
            ReadingMode.CONTINUOUS_VERTICAL,
        )
        defaultReadingModeChoices.map { it.stringRes } shouldContainExactly listOf(
            R.string.left_to_right_viewer,
            R.string.right_to_left_viewer,
            R.string.vertical_viewer,
            R.string.webtoon_viewer,
            R.string.vertical_plus_viewer,
        )
        viewModel().readerPreferences.defaultReadingMode.shouldBeUpstreams(
            key = "pref_default_reading_mode_key",
            default = ReadingMode.RIGHT_TO_LEFT.flagValue,
            written = ReadingMode.WEBTOON.flagValue,
        )
    }

    @Test
    fun `double tap animation speed offers no animation, normal and fast, normal by default`() {
        doubleTapAnimSpeedChoices shouldContainExactly listOf(
            1 to R.string.double_tap_anim_speed_0,
            500 to R.string.double_tap_anim_speed_normal,
            250 to R.string.double_tap_anim_speed_fast,
        )
        viewModel().readerPreferences.doubleTapAnimSpeed.shouldBeUpstreams(
            key = "pref_double_tap_anim_speed",
            default = 500,
            written = 250,
        )
    }

    @Test
    fun `show reading mode is upstream's key and starts on`() {
        viewModel().readerPreferences.showReadingMode.shouldBeUpstreamsSwitch("pref_show_reading_mode", default = true)
    }

    @Test
    fun `show tap zones overlay is upstream's key and starts off`() {
        viewModel().readerPreferences.showNavigationOverlayOnStart
            .shouldBeUpstreamsSwitch("reader_navigation_overlay_on_start", default = false)
    }

    @Test
    fun `default rotation offers upstream's six orientations and writes the orientation's flag`() {
        defaultRotationChoices shouldContainExactly listOf(
            ReaderOrientation.FREE,
            ReaderOrientation.PORTRAIT,
            ReaderOrientation.LANDSCAPE,
            ReaderOrientation.LOCKED_PORTRAIT,
            ReaderOrientation.LOCKED_LANDSCAPE,
            ReaderOrientation.REVERSE_PORTRAIT,
        )
        defaultRotationChoices.map { it.stringRes } shouldContainExactly listOf(
            R.string.rotation_free,
            R.string.rotation_portrait,
            R.string.rotation_landscape,
            R.string.rotation_force_portrait,
            R.string.rotation_force_landscape,
            R.string.rotation_reverse_portrait,
        )
        viewModel().readerPreferences.defaultOrientationType.shouldBeUpstreams(
            key = "pref_default_orientation_type_key",
            default = ReaderOrientation.FREE.flagValue,
            written = ReaderOrientation.LOCKED_LANDSCAPE.flagValue,
        )
    }

    @Test
    fun `background color offers upstream's four backgrounds in its order, black by default`() {
        readerBackgroundChoices.map { it.first } shouldContainExactly listOf(1, 2, 0, 3)
        readerBackgroundChoices.map { it.second } shouldContainExactly listOf(
            R.string.black_background,
            R.string.gray_background,
            R.string.white_background,
            R.string.automatic_background,
        )
        viewModel().readerPreferences.readerTheme.shouldBeUpstreams(
            key = "pref_reader_theme_key",
            default = 1,
            written = 0,
        )
    }

    @Test
    fun `the tap zones row turns zones off with its last choice, which greys out the inversion`() {
        DISABLED_TAP_ZONES shouldBe ReaderSettingsOptions.TapZones.lastIndex
        ReaderSettingsOptions.TapZones[DISABLED_TAP_ZONES] shouldBe R.string.disabled_nav
    }

    @Test
    fun `skip chapters marked read is upstream's key and starts off`() {
        viewModel().readerPreferences.skipRead.shouldBeUpstreamsSwitch("skip_read", default = false)
    }

    @Test
    fun `skip filtered chapters is upstream's key and starts on`() {
        viewModel().readerPreferences.skipFiltered.shouldBeUpstreamsSwitch("skip_filtered", default = true)
    }

    @Test
    fun `skip duplicate chapters is upstream's key and starts off`() {
        viewModel().readerPreferences.skipDupe.shouldBeUpstreamsSwitch("skip_dupe", default = false)
    }

    @Test
    fun `the hide on scroll sensitivity offers highest to lowest, low by default`() {
        readerHideThresholdChoices shouldContainExactly listOf(
            ReaderHideThreshold.HIGHEST,
            ReaderHideThreshold.HIGH,
            ReaderHideThreshold.LOW,
            ReaderHideThreshold.LOWEST,
        )
        readerHideThresholdChoices.map { it.titleRes } shouldContainExactly listOf(
            R.string.pref_highest,
            R.string.pref_high,
            R.string.pref_low,
            R.string.pref_lowest,
        )
        viewModel().readerPreferences.readerHideThreshold.shouldBeUpstreams(
            key = "reader_hide_threshold",
            default = ReaderHideThreshold.LOW,
            written = ReaderHideThreshold.HIGHEST,
        )
    }

    @Test
    fun `volume keys is upstream's key and starts off`() {
        viewModel().readerPreferences.readWithVolumeKeys.shouldBeUpstreamsSwitch("reader_volume_keys", default = false)
    }

    @Test
    fun `invert volume keys is upstream's key and starts off`() {
        viewModel().readerPreferences.readWithVolumeKeysInverted
            .shouldBeUpstreamsSwitch("reader_volume_keys_inverted", default = false)
    }

    @Test
    fun `save pages into separate folders is upstream's key and starts off`() {
        viewModel().readerPreferences.folderPerManga.shouldBeUpstreamsSwitch("create_folder_per_manga", default = false)
    }

    // ---- Library ----

    @Test
    fun `default category offers always ask and then every category, always ask by default`() {
        val categories = listOf(
            Category(id = 0, name = "", order = 0, flags = 0),
            Category(id = 3, name = "Reading", order = 1, flags = 0),
            Category(id = 7, name = "Dropped", order = 2, flags = 0),
        )

        defaultCategoryChoices(categories) shouldContainExactly listOf(-1, 0, 3, 7)
        defaultCategoryChoices(emptyList()) shouldContainExactly listOf(-1)
        viewModel().libraryPreferences.defaultCategory.shouldBeUpstreams(key = "default_category", default = -1, written = 3)
    }

    @Test
    fun `per-category sort turned on writes the key and resets nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        val preference = viewModel.libraryPreferences.categorizedDisplaySettings
        preference.key() shouldBe "categorized_display"
        preference.get() shouldBe false

        viewModel.setCategorizedDisplaySettings(true)
        advanceUntilIdle()

        preference.get() shouldBe true
        coVerify(exactly = 0) { resetCategoryFlags.await() }
    }

    @Test
    fun `per-category sort turned off writes the key and resets every category's sort, as upstream's row does`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            viewModel.libraryPreferences.categorizedDisplaySettings.set(true)

            viewModel.setCategorizedDisplaySettings(false)
            advanceUntilIdle()

            viewModel.libraryPreferences.categorizedDisplaySettings.get() shouldBe false
            coVerify(exactly = 1) { resetCategoryFlags.await() }
        }

    @Test
    fun `mark duplicate read chapter as read offers after reading and after fetching, neither by default`() {
        markDuplicateReadChapterChoices shouldContainExactly listOf(
            "existing" to R.string.pref_mark_duplicate_read_chapter_read_existing,
            "new" to R.string.pref_mark_duplicate_read_chapter_read_new,
        )
        viewModel().libraryPreferences.markDuplicateReadChapterAsRead.shouldBeUpstreams(
            key = "mark_duplicate_read_chapter_read",
            default = emptySet(),
            written = emptySet<String>().toggled("new"),
        )
    }

    /** Phase 4 unit CS: the swipe to the left, whose default is the one an upstream user has there. */
    @Test
    fun `chapter on swipe to left offers upstream's four actions, bookmark by default`() {
        chapterSwipeActionChoices shouldContainExactly listOf(
            LibraryPreferences.ChapterSwipeAction.Disabled to R.string.disabled,
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark to R.string.action_bookmark,
            LibraryPreferences.ChapterSwipeAction.ToggleRead to R.string.action_mark_as_read,
            LibraryPreferences.ChapterSwipeAction.Download to R.string.action_download,
        )
        viewModel().libraryPreferences.swipeToStartAction.shouldBeUpstreams(
            key = "pref_chapter_swipe_start_action",
            default = LibraryPreferences.ChapterSwipeAction.ToggleBookmark,
            written = LibraryPreferences.ChapterSwipeAction.Download,
        )
    }

    /** The swipe to the right; Saikou's key is upstream's other one (R19), the default upstream's for this swipe. */
    @Test
    fun `chapter on swipe to right offers the same actions, mark as read by default`() {
        viewModel().libraryPreferences.swipeToEndAction.shouldBeUpstreams(
            key = "pref_chapter_swipe_end_action",
            default = LibraryPreferences.ChapterSwipeAction.ToggleRead,
            written = LibraryPreferences.ChapterSwipeAction.Disabled,
        )
    }

    // ---- Appearance ----

    @Test
    fun `date format offers upstream's six formats, the locale's own by default`() {
        dateFormatChoices shouldContainExactly listOf("", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy")
        viewModel().uiPreferences.dateFormat.shouldBeUpstreams(key = "app_date_format", default = "", written = "yyyy-MM-dd")
    }

    @Test
    fun `relative timestamps is upstream's key and starts on`() {
        viewModel().uiPreferences.relativeTime.shouldBeUpstreamsSwitch("relative_time_v2", default = true)
    }

    // ---- Advanced ----

    @Test
    fun `verbose logging is upstream's key and starts off outside a debug build`() {
        viewModel().networkPreferences.verboseLogging.shouldBeUpstreamsSwitch("verbose_logging", default = false)
    }

    @Test
    fun `DNS over HTTPS offers disabled, upstream's twelve providers in its order and Custom, disabled by default`() {
        dohProviderChoices shouldContainExactly listOf(
            -1 to null,
            1 to "Cloudflare",
            2 to "Google",
            3 to "AdGuard",
            4 to "Quad9",
            5 to "AliDNS",
            6 to "DNSPod",
            7 to "360",
            8 to "Quad 101",
            9 to "Mullvad",
            10 to "Control D",
            11 to "Njalla",
            12 to "Shecan",
            PREF_DOH_CUSTOM to null,
        )
        viewModel().networkPreferences.dohProvider.shouldBeUpstreams(key = "doh_provider", default = -1, written = 4)
    }

    @Test
    fun `the Custom DNS over HTTPS choice writes an https URL and chooses it, and refuses anything else`() {
        val viewModel = viewModel()
        val provider = viewModel.networkPreferences.dohProvider
        val url = viewModel.networkPreferences.dohProviderCustom
        url.key() shouldBe "doh_provider_custom"
        url.get() shouldBe ""
        provider.set(4)

        listOf("", "http://doh.example.invalid/dns-query", "doh.example.invalid", "https:///dns-query").forEach {
            viewModel.setCustomDoh(it) shouldBe false
        }
        provider.get() shouldBe 4
        url.isSet() shouldBe false

        // Written in its normal form, which is what the network client reads.
        viewModel.setCustomDoh(" HTTPS://Doh.Example.Invalid ") shouldBe true
        provider.get() shouldBe PREF_DOH_CUSTOM
        url.get() shouldBe "https://doh.example.invalid/"

        viewModel.setCustomDoh("https://doh.example.invalid:8443/dns-query") shouldBe true
        url.get() shouldBe "https://doh.example.invalid:8443/dns-query"

        // A refusal keeps the server already chosen, not only the preset before it.
        viewModel.setCustomDoh("http://other.example.invalid/dns-query") shouldBe false
        provider.get() shouldBe PREF_DOH_CUSTOM
        url.get() shouldBe "https://doh.example.invalid:8443/dns-query"
    }

    @Test
    fun `the default user agent row writes a valid header value and refuses one OkHttp would refuse`() {
        val viewModel = viewModel()
        val preference = viewModel.networkPreferences.defaultUserAgent
        val default = preference.defaultValue()
        preference.key() shouldBe "default_user_agent"
        preference.get() shouldBe default

        viewModel.setDefaultUserAgent("Fixture/1.0 (Linux; Android 16)") shouldBe true
        preference.get() shouldBe "Fixture/1.0 (Linux; Android 16)"

        viewModel.setDefaultUserAgent("Fixture/2.0\nInjected: yes") shouldBe false
        preference.get() shouldBe "Fixture/1.0 (Linux; Android 16)"
    }

    @Test
    fun `resetting the default user agent deletes the key, so the default is read again`() {
        val viewModel = viewModel()
        val preference = viewModel.networkPreferences.defaultUserAgent
        viewModel.setDefaultUserAgent("Fixture/1.0") shouldBe true

        viewModel.resetDefaultUserAgent()

        preference.isSet() shouldBe false
        preference.get() shouldBe preference.defaultValue()
    }

    @Test
    fun `update library manga titles to match source is upstream's key and starts off`() {
        viewModel().libraryPreferences.updateMangaTitles.shouldBeUpstreamsSwitch("pref_update_library_manga_titles", default = false)
    }

    @Test
    fun `disallow non-ASCII filenames is upstream's key and starts off`() {
        viewModel().libraryPreferences.disallowNonAsciiFilenames
            .shouldBeUpstreamsSwitch("disallow_non_ascii_filenames", default = false)
    }

    /** The choices are the device's texture limit and its steps down, read through EGL on a device. */
    @Test
    fun `the custom hardware bitmap threshold is upstream's key with the safe limit until the first launch sets it`() {
        viewModel().basePreferences.hardwareBitmapThreshold.shouldBeUpstreams(
            key = "pref_hardware_bitmap_threshold",
            default = GLUtil.SAFE_TEXTURE_LIMIT,
            written = 4096,
        )
    }

    // ---- Security and privacy ----

    @Test
    fun `require unlock is upstream's key and starts off`() {
        viewModel().securityPreferences.useAuthenticator.shouldBeUpstreamsSwitch("use_biometric_lock", default = false)
    }

    @Test
    fun `lock when idle offers Always, upstream's four delays and Never in its order, Always by default`() {
        lockAppAfterChoices shouldContainExactly listOf(0, 1, 2, 5, 10, -1)
        viewModel().securityPreferences.lockAppAfter.shouldBeUpstreams(key = "lock_app_after", default = 0, written = 5)
    }

    /**
     * Phase 4 unit MP1, R109 carry-over 1: upstream's `StatusWrapper` hides a row whose `enabled` is
     * false, so with no device credential neither lock row is on screen, and Lock when idle comes
     * only once the lock is on.
     */
    @Test
    fun `with no device credential neither lock row is shown, and lock when idle only once the lock is on`() {
        lockRows(authSupported = false, lockOn = false) shouldBe LockRows(requireUnlock = false, lockWhenIdle = false)
        // A restored backup can leave the lock on where no credential is set.
        lockRows(authSupported = false, lockOn = true) shouldBe LockRows(requireUnlock = false, lockWhenIdle = false)
        lockRows(authSupported = true, lockOn = false) shouldBe LockRows(requireUnlock = true, lockWhenIdle = false)
        lockRows(authSupported = true, lockOn = true) shouldBe LockRows(requireUnlock = true, lockWhenIdle = true)
    }

    @Test
    fun `hide notification content is upstream's key and starts off`() {
        viewModel().securityPreferences.hideNotificationContent.shouldBeUpstreamsSwitch("hide_notification_content", default = false)
    }

    @Test
    fun `secure screen offers Always, Incognito and Never in upstream's order with its labels, Incognito by default`() {
        secureScreenChoices shouldContainExactly listOf(
            SecurityPreferences.SecureScreenMode.ALWAYS,
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            SecurityPreferences.SecureScreenMode.NEVER,
        )
        secureScreenChoices.map { it.labelRes } shouldContainExactly listOf(
            R.string.lock_always,
            R.string.pref_incognito_mode,
            R.string.lock_never,
        )
        viewModel().securityPreferences.secureScreen.shouldBeUpstreams(
            key = "secure_screen_v2",
            default = SecurityPreferences.SecureScreenMode.INCOGNITO,
            written = SecurityPreferences.SecureScreenMode.ALWAYS,
        )
    }

    /** The key a delayed lock times from is app state, so no backup carries it (upstream's `appStateKey`). */
    @Test
    fun `the lock's close time is upstream's app state key`() {
        viewModel().securityPreferences.lastAppClosed.key() shouldBe Preference.appStateKey("last_app_closed")
    }
}
