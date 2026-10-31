package com.shinsaikou.ui.settings

import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryUpdateRestrictions
import com.shinsaikou.i18n.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The rows phase 3 unit AD adds to the Downloads and Library sections: each row's preference is
 * upstream's key with upstream's default, and each choice row offers upstream's choices in upstream's order with
 * its labels. What a write does beyond the preference is [SettingsViewModelTest]'s.
 */
class AutoDownloadSettingsTest {

    private val store = InMemoryPreferenceStore()
    private val downloadPreferences = DownloadPreferences(store)
    private val libraryPreferences = LibraryPreferences(store)

    @Test
    fun `download new chapters is upstream's key and starts off`() {
        downloadPreferences.downloadNewChapters.key() shouldBe "download_new"
        downloadPreferences.downloadNewChapters.get() shouldBe false
    }

    @Test
    fun `skip duplicate read chapters is upstream's key and starts off`() {
        downloadPreferences.downloadNewUnreadChaptersOnly.key() shouldBe "download_new_unread_chapters_only"
        downloadPreferences.downloadNewUnreadChaptersOnly.get() shouldBe false
    }

    @Test
    fun `the new chapter categories start with nothing included and nothing excluded`() {
        downloadPreferences.downloadNewChapterCategories.get() shouldBe emptySet()
        downloadPreferences.downloadNewChapterCategoriesExclude.get() shouldBe emptySet()
    }

    @Test
    fun `auto download while reading offers disabled, 2, 3, 5 and 10 on upstream's key, disabled by default`() {
        autoDownloadWhileReadingChoices shouldContainExactly listOf(0, 2, 3, 5, 10)
        downloadPreferences.autoDownloadWhileReading.key() shouldBe "auto_download_while_reading"
        downloadPreferences.autoDownloadWhileReading.get() shouldBe 0
    }

    @Test
    fun `the device restrictions are upstream's three with its labels, wifi only by default`() {
        libraryUpdateDeviceRestrictionChoices shouldContainExactly listOf(
            LibraryUpdateRestrictions.DEVICE_ONLY_ON_WIFI to R.string.connected_to_wifi,
            LibraryUpdateRestrictions.DEVICE_NETWORK_NOT_METERED to R.string.network_not_metered,
            LibraryUpdateRestrictions.DEVICE_CHARGING to R.string.charging,
        )
        libraryPreferences.autoUpdateDeviceRestrictions.key() shouldBe "library_update_restriction"
        libraryPreferences.autoUpdateDeviceRestrictions.get() shouldBe setOf("wifi")
    }

    @Test
    fun `the device restrictions are offered only while an automatic interval is set`() {
        deviceRestrictionsEnabled(0) shouldBe false
        deviceRestrictionsEnabled(12) shouldBe true
        deviceRestrictionsEnabled(48) shouldBe true
    }

    @Test
    fun `the update categories start with nothing included and nothing excluded`() {
        libraryPreferences.updateCategories.get() shouldBe emptySet()
        libraryPreferences.updateCategoriesExclude.get() shouldBe emptySet()
    }

    @Test
    fun `smart update is upstream's four skips with its labels, all on by default`() {
        libraryUpdateSmartUpdateChoices shouldContainExactly listOf(
            LibraryUpdateRestrictions.MANGA_HAS_UNREAD to R.string.pref_update_only_completely_read,
            LibraryUpdateRestrictions.MANGA_NON_READ to R.string.pref_update_only_started,
            LibraryUpdateRestrictions.MANGA_NON_COMPLETED to R.string.pref_update_only_non_completed,
            LibraryUpdateRestrictions.MANGA_OUTSIDE_RELEASE_PERIOD to R.string.pref_update_only_in_release_period,
        )
        libraryPreferences.autoUpdateMangaRestrictions.key() shouldBe "library_update_manga_restriction"
        libraryPreferences.autoUpdateMangaRestrictions.get() shouldBe libraryUpdateSmartUpdateChoices.map { it.first }.toSet()
    }

    @Test
    fun `a chip adds its key when it was off and removes it when it was on`() {
        setOf("wifi").toggled("ac") shouldBe setOf("wifi", "ac")
        setOf("wifi", "ac").toggled("wifi") shouldBe setOf("ac")
        emptySet<String>().toggled("wifi") shouldBe setOf("wifi")
    }
}
