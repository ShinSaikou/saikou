package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderHideThreshold
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Phase 4 unit SR's restore check: every key behind the unit's settings rows comes back from a
 * upstream backup today, through the whole import, as each key's upstream type, and reads back through
 * Saikou's own preference classes as the value the file held. The restorer writes every app key a
 * file carries, so the unit adds none to it; this holds that for the 24 keys, and for the app lock's
 * three (unit AL, R109 carry-over 3), which its device check had shown only by writing the
 * preferences file.
 */
class SettingsRowsRestoreTest {

    @TempDir
    lateinit var tempDir: File

    /** Each key with a value other than its default, typed as upstream's backup creator writes it. */
    private val rowPreferences = listOf(
        // Reader
        BackupPreference("pref_default_reading_mode_key", IntPreferenceValue(ReadingMode.WEBTOON.flagValue)),
        BackupPreference("pref_default_orientation_type_key", IntPreferenceValue(ReaderOrientation.LOCKED_LANDSCAPE.flagValue)),
        BackupPreference("skip_read", BooleanPreferenceValue(true)),
        BackupPreference("skip_filtered", BooleanPreferenceValue(false)),
        BackupPreference("skip_dupe", BooleanPreferenceValue(true)),
        BackupPreference("reader_volume_keys", BooleanPreferenceValue(true)),
        BackupPreference("reader_volume_keys_inverted", BooleanPreferenceValue(true)),
        BackupPreference("pref_double_tap_anim_speed", IntPreferenceValue(250)),
        BackupPreference("pref_show_reading_mode", BooleanPreferenceValue(false)),
        BackupPreference("reader_navigation_overlay_on_start", BooleanPreferenceValue(true)),
        BackupPreference("reader_hide_threshold", StringPreferenceValue("HIGHEST")),
        BackupPreference("create_folder_per_manga", BooleanPreferenceValue(true)),
        // Library; the default category is the file's category 2, "Completed".
        BackupPreference("default_category", IntPreferenceValue(2)),
        BackupPreference("categorized_display", BooleanPreferenceValue(true)),
        BackupPreference("mark_duplicate_read_chapter_read", StringSetPreferenceValue(setOf("existing", "new"))),
        // Appearance
        BackupPreference("app_date_format", StringPreferenceValue("yyyy-MM-dd")),
        BackupPreference("relative_time_v2", BooleanPreferenceValue(false)),
        // Advanced
        BackupPreference("verbose_logging", BooleanPreferenceValue(true)),
        BackupPreference("doh_provider", IntPreferenceValue(4)),
        BackupPreference("default_user_agent", StringPreferenceValue("Fixture/1.0")),
        BackupPreference("pref_update_library_manga_titles", BooleanPreferenceValue(true)),
        BackupPreference("disallow_non_ascii_filenames", BooleanPreferenceValue(true)),
        BackupPreference("pref_hardware_bitmap_threshold", IntPreferenceValue(4096)),
        // Security and privacy
        BackupPreference("hide_notification_content", BooleanPreferenceValue(true)),
        // The app lock's three (R109): upstream's getBoolean, getInt and getEnum, an enum stored by name.
        BackupPreference("use_biometric_lock", BooleanPreferenceValue(true)),
        BackupPreference("lock_app_after", IntPreferenceValue(5)),
        BackupPreference("secure_screen_v2", StringPreferenceValue("ALWAYS")),
    )

    @Test
    fun `every settings row's key comes back from an upstream backup as the value it held`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val backup = SyntheticBackupFixture.backup(mangaCount = 2, chaptersPerManga = 1)
                .copy(backupPreferences = rowPreferences)
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup))

            val report = env.importer.import(file, RestoreOptions()) {}

            val store = env.preferenceStore
            val reader = ReaderPreferences(store)
            val library = LibraryPreferences(store)
            val ui = UiPreferences(store)
            val network = NetworkPreferences(store)
            val base = BasePreferences(store)
            val security = SecurityPreferences(store)
            val completed = env.categoryRepository.getAll().single { it.name == "Completed" }
            rowPreferences.size shouldBe 27
            assertSoftly {
                report.errors.shouldBeEmpty()
                report.preferencesWritten shouldBe 27
                reader.defaultReadingMode.get() shouldBe ReadingMode.WEBTOON.flagValue
                reader.defaultOrientationType.get() shouldBe ReaderOrientation.LOCKED_LANDSCAPE.flagValue
                reader.skipRead.get() shouldBe true
                reader.skipFiltered.get() shouldBe false
                reader.skipDupe.get() shouldBe true
                reader.readWithVolumeKeys.get() shouldBe true
                reader.readWithVolumeKeysInverted.get() shouldBe true
                reader.doubleTapAnimSpeed.get() shouldBe 250
                reader.showReadingMode.get() shouldBe false
                reader.showNavigationOverlayOnStart.get() shouldBe true
                reader.readerHideThreshold.get() shouldBe ReaderHideThreshold.HIGHEST
                reader.folderPerManga.get() shouldBe true
                library.defaultCategory.get() shouldBe completed.id.toInt()
                library.categorizedDisplaySettings.get() shouldBe true
                library.markDuplicateReadChapterAsRead.get() shouldBe setOf("existing", "new")
                ui.dateFormat.get() shouldBe "yyyy-MM-dd"
                ui.relativeTime.get() shouldBe false
                network.verboseLogging.get() shouldBe true
                network.dohProvider.get() shouldBe 4
                network.defaultUserAgent.get() shouldBe "Fixture/1.0"
                library.updateMangaTitles.get() shouldBe true
                library.disallowNonAsciiFilenames.get() shouldBe true
                base.hardwareBitmapThreshold.get() shouldBe 4096
                security.hideNotificationContent.get() shouldBe true
                security.useAuthenticator.get() shouldBe true
                security.lockAppAfter.get() shouldBe 5
                security.secureScreen.get() shouldBe SecurityPreferences.SecureScreenMode.ALWAYS
            }
        }
    }
}
