package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.UiPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File

class PreferenceRestorerTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `each of the six leaf types round trips`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(
                BackupPreference("an_int", IntPreferenceValue(1)),
                BackupPreference("a_long", LongPreferenceValue(2L)),
                BackupPreference("a_float", FloatPreferenceValue(3.5f)),
                BackupPreference("a_string", StringPreferenceValue("four")),
                BackupPreference("a_boolean", BooleanPreferenceValue(true)),
                BackupPreference("a_string_set", StringSetPreferenceValue(setOf("five", "six"))),
            )

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            written shouldBe 6
            env.preferenceStore.values["an_int"] shouldBe 1
            env.preferenceStore.values["a_long"] shouldBe 2L
            env.preferenceStore.values["a_float"] shouldBe 3.5f
            env.preferenceStore.values["a_string"] shouldBe "four"
            env.preferenceStore.values["a_boolean"] shouldBe true
            env.preferenceStore.values["a_string_set"] shouldBe setOf("five", "six")
        }
    }

    @Test
    fun `a private key is written and an app state key is not`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val privateKey = Preference.privateKey("track_token_1")
            val stateKey = Preference.appStateKey("last_used_category")
            val prefs = listOf(
                BackupPreference(privateKey, StringPreferenceValue("token")),
                BackupPreference(stateKey, IntPreferenceValue(3)),
            )

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            env.preferenceStore.values[privateKey] shouldBe "token"
            env.preferenceStore.values.containsKey(stateKey) shouldBe false
        }
    }

    @Test
    fun `an enum name is stored verbatim, even one this build does not know`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(BackupPreference("pref_reader_flash_mode", StringPreferenceValue("CHARTREUSE")))

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            env.preferenceStore.values["pref_reader_flash_mode"] shouldBe "CHARTREUSE"
        }
    }

    @Test
    fun `an upstream backup's named theme reads back as that theme`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(BackupPreference("pref_app_theme", StringPreferenceValue("TAKO")))

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            UiPreferences(env.preferenceStore).appTheme.get() shouldBe AppTheme.TAKO
        }
    }

    @Test
    fun `a deprecated upstream theme reads back as the default theme`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(BackupPreference("pref_app_theme", StringPreferenceValue("HOT_PINK")))

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            env.preferenceStore.values["pref_app_theme"] shouldBe "HOT_PINK"
            UiPreferences(env.preferenceStore).appTheme.get() shouldBe AppTheme.DEFAULT
        }
    }

    @Test
    fun `a live value of another type is reported and left alone`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values["shared_key"] = "a string"
            val errors = mutableListOf<ImportError>()

            val written = env.preferenceRestorer
                .restoreApp(listOf(BackupPreference("shared_key", IntPreferenceValue(1))), emptyMap(), errors)

            written shouldBe 0
            env.preferenceStore.values["shared_key"] shouldBe "a string"
            errors.single().kind shouldBe ImportError.Kind.PREFERENCE_TYPE_MISMATCH
        }
    }

    @Test
    fun `source settings land in the named file even for a source that is not installed`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val key = "source_${SyntheticBackupFixture.SOURCE_MISSING}"
            val prefs = listOf(
                BackupSourcePreferences(key, listOf(BackupPreference("a_key", StringPreferenceValue("a value")))),
            )

            val files = env.preferenceRestorer.restoreSource(prefs, mutableListOf())

            files shouldBe 1
            env.sourcePreferenceFiles.getValue(key).values["a_key"] shouldBe "a value"
        }
    }

    @Test
    fun `a source settings entry that names any other file is refused`() = runTest {
        // A crafted backup could otherwise write the app's own settings file, app lock included,
        // with App settings unticked.
        BackupTestEnvironment(tempDir).use { env ->
            val names = listOf("com.shinsaikou_preferences", "source_", "source_12/../x", "source_1a")
            val prefs = names.map { name ->
                BackupSourcePreferences(name, listOf(BackupPreference("use_biometric_lock", BooleanPreferenceValue(false))))
            }
            val errors = mutableListOf<ImportError>()

            val files = env.preferenceRestorer.restoreSource(prefs, errors)

            files shouldBe 0
            env.sourcePreferenceFiles.keys.none { it in names } shouldBe true
            errors.map { it.kind }.toSet() shouldBe setOf(ImportError.Kind.SOURCE_PREFERENCE)
            errors.map { it.subject } shouldBe names
        }
    }

    @Test
    fun `a negative source id is still a source settings file`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val key = "source_-42"
            val prefs = listOf(BackupSourcePreferences(key, listOf(BackupPreference("a_key", StringPreferenceValue("v")))))

            env.preferenceRestorer.restoreSource(prefs, mutableListOf()) shouldBe 1
            env.sourcePreferenceFiles.getValue(key).values["a_key"] shouldBe "v"
        }
    }

    @Test
    fun `a category set preference is added to the live set rather than replacing it`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values["library_update_categories"] = setOf("7")
            val prefs = listOf(
                BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("1", "99"))),
            )

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 4L), mutableListOf())

            env.preferenceStore.values["library_update_categories"] shouldBe setOf("7", "4")
        }
    }

    @Test
    fun `an empty category set in the backup is restored as a key rather than dropped`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // A real 2 MB backup carried download_new_categories_exclude as an empty set, which
            // means no category is excluded from new downloads. That is a value worth keeping,
            // not the "nothing resolved" failure the old empty check mistook it for.
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(
                BackupPreference("download_new_categories_exclude", StringSetPreferenceValue(emptySet())),
            )

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 1
            env.preferenceStore.values.containsKey("download_new_categories_exclude") shouldBe true
            env.preferenceStore.values["download_new_categories_exclude"] shouldBe emptySet<String>()
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `a category set whose ids all fail to resolve is refused out loud`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(
                BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("1", "2"))),
            )

            // categoryIdByBackupId is empty, so neither backup id resolves.
            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 0
            env.preferenceStore.values.containsKey("library_update_categories") shouldBe false
            errors.single().kind shouldBe ImportError.Kind.PREFERENCE
            errors.single().subject shouldBe "library_update_categories"
        }
    }

    @Test
    fun `an unresolvable default category is refused out loud`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values["default_category"] = 3
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference("default_category", IntPreferenceValue(1)))

            // categoryIdByBackupId does not carry backup id 1, so it cannot resolve.
            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 0
            env.preferenceStore.values["default_category"] shouldBe 3
            errors.single().kind shouldBe ImportError.Kind.PREFERENCE
            errors.single().subject shouldBe "default_category"
        }
    }

    @Test
    fun `an empty set and an all unresolvable set are not the same outcome`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // This is the one assertion that fails if the empty guard is reinstated as
            // `if (mapped.isEmpty()) return false`: that guard cannot tell these two cases apart,
            // it treats a real empty selection as an unresolvable one and drops both.
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(
                BackupPreference("download_new_categories_exclude", StringSetPreferenceValue(emptySet())),
                BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("1"))),
            )

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 1
            env.preferenceStore.values.containsKey("download_new_categories_exclude") shouldBe true
            env.preferenceStore.values.containsKey("library_update_categories") shouldBe false
            errors.single().subject shouldBe "library_update_categories"
        }
    }

    // ---- The default category, 0, in every category key (phase 3 unit DR, brief item 9) ----

    /**
     * No backup lists the system category, so `categoryIdByBackupId` never holds 0. Before this unit
     * the 0 was dropped: excluded from delete after reading, an entry in no category lost its files.
     */
    @Test
    fun `the default category in the delete after reading exclusions survives a restore`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference("remove_exclude_categories", StringSetPreferenceValue(setOf("0", "1"))))

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 4L), errors)

            env.preferenceStore.values["remove_exclude_categories"] shouldBe setOf("0", "4")
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `a set holding the default category alone is restored rather than refused`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference("remove_exclude_categories", StringSetPreferenceValue(setOf("0"))))

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 1
            env.preferenceStore.values["remove_exclude_categories"] shouldBe setOf("0")
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `the new chapter download categories keep the default category`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(
                BackupPreference("download_new_categories", StringSetPreferenceValue(setOf("0", "1"))),
                BackupPreference("download_new_categories_exclude", StringSetPreferenceValue(setOf("0"))),
            )

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 4L), mutableListOf())

            env.preferenceStore.values["download_new_categories"] shouldBe setOf("0", "4")
            env.preferenceStore.values["download_new_categories_exclude"] shouldBe setOf("0")
        }
    }

    @Test
    fun `the library update categories keep the default category`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(
                BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("0", "1"))),
                BackupPreference("library_update_categories_exclude", StringSetPreferenceValue(setOf("0"))),
            )

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 4L), mutableListOf())

            env.preferenceStore.values["library_update_categories"] shouldBe setOf("0", "4")
            env.preferenceStore.values["library_update_categories_exclude"] shouldBe setOf("0")
        }
    }

    @Test
    fun `the default category key keeps the default category`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values["default_category"] = 3
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference("default_category", IntPreferenceValue(0)))

            val written = env.preferenceRestorer.restoreApp(prefs, mapOf(0L to 9L), errors)

            written shouldBe 1
            env.preferenceStore.values["default_category"] shouldBe 0
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `the default category key keeps always ask`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values["default_category"] = 3
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference("default_category", IntPreferenceValue(-1)))

            val written = env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 3L), errors)

            written shouldBe 1
            env.preferenceStore.values["default_category"] shouldBe -1
            errors shouldBe emptyList()
        }
    }

    // ---- The Updates filter's category lists (phase 4 unit UF, c2 g4 A point 1) ----

    @Test
    fun `both Updates filter category lists are remapped to the live ids`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(
                BackupPreference(UPDATES_INCLUDED, StringPreferenceValue("1,2")),
                BackupPreference(UPDATES_EXCLUDED, StringPreferenceValue("3")),
            )

            val written = env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 11L, 2L to 12L, 3L to 13L), errors)

            written shouldBe 2
            env.preferenceStore.values[UPDATES_INCLUDED] shouldBe "11,12"
            env.preferenceStore.values[UPDATES_EXCLUDED] shouldBe "13"
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `an Updates filter id that cannot be mapped is left out, the default category kept`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(
                BackupPreference(UPDATES_INCLUDED, StringPreferenceValue("0,1,99")),
                BackupPreference(UPDATES_EXCLUDED, StringPreferenceValue("98,2")),
            )

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 11L, 2L to 12L), errors)

            env.preferenceStore.values[UPDATES_INCLUDED] shouldBe "0,11"
            env.preferenceStore.values[UPDATES_EXCLUDED] shouldBe "12"
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `an Updates filter list none of whose ids resolve is refused out loud`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference(UPDATES_EXCLUDED, StringPreferenceValue("5,6")))

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 0
            env.preferenceStore.values.containsKey(UPDATES_EXCLUDED) shouldBe false
            errors.single().kind shouldBe ImportError.Kind.PREFERENCE
            errors.single().subject shouldBe UPDATES_EXCLUDED
        }
    }

    @Test
    fun `an Updates filter list that parses as no id is refused out loud`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference(UPDATES_INCLUDED, StringPreferenceValue("abc")))

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 0
            env.preferenceStore.values.containsKey(UPDATES_INCLUDED) shouldBe false
            errors.single().kind shouldBe ImportError.Kind.PREFERENCE
            errors.single().subject shouldBe UPDATES_INCLUDED
        }
    }

    @Test
    fun `an empty Updates filter list is restored as a key rather than refused`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val errors = mutableListOf<ImportError>()
            val prefs = listOf(BackupPreference(UPDATES_EXCLUDED, StringPreferenceValue("")))

            val written = env.preferenceRestorer.restoreApp(prefs, emptyMap(), errors)

            written shouldBe 1
            env.preferenceStore.values[UPDATES_EXCLUDED] shouldBe ""
            errors shouldBe emptyList()
        }
    }

    @Test
    fun `an Updates filter list is added to the live list, each id once`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.preferenceStore.values[UPDATES_INCLUDED] = "7,11"
            val prefs = listOf(BackupPreference(UPDATES_INCLUDED, StringPreferenceValue("1,2")))

            env.preferenceRestorer.restoreApp(prefs, mapOf(1L to 11L, 2L to 12L), mutableListOf())

            env.preferenceStore.values[UPDATES_INCLUDED] shouldBe "7,11,12"
        }
    }

    private companion object {
        const val UPDATES_INCLUDED = "pref_filter_updates_included_categories"
        const val UPDATES_EXCLUDED = "pref_filter_updates_excluded_categories"
    }
}
