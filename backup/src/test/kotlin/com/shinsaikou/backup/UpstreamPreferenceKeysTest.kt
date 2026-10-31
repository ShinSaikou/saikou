package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences.ChapterSwipeAction
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.TriState
import java.io.File

/**
 * Ruling R19: an upstream import preserves observed behaviour, not stored keys. Upstream crosses the two
 * chapter swipe properties to their keys and Saikou does not, so a verbatim restore would give the
 * owner their start action on end swipes. The custom interval filter key gained a `_v2` suffix.
 */
class UpstreamPreferenceKeysTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the swipe action an upstream user saw on a start swipe is what a start swipe does here`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // An upstream install where the user set "Chapter on swipe to left" to Download. Upstream
            // stores that choice under the end key.
            val prefs = listOf(
                BackupPreference("pref_chapter_swipe_end_action", StringPreferenceValue("Download")),
                BackupPreference("pref_chapter_swipe_start_action", StringPreferenceValue("ToggleBookmark")),
            )

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            val library = LibraryPreferences(env.preferenceStore)
            library.swipeToStartAction.get() shouldBe ChapterSwipeAction.Download
            library.swipeToEndAction.get() shouldBe ChapterSwipeAction.ToggleBookmark
        }
    }

    @Test
    fun `a swipe key the backup does not carry keeps its own default`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(
                BackupPreference("pref_chapter_swipe_end_action", StringPreferenceValue("Download")),
            )

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            val library = LibraryPreferences(env.preferenceStore)
            library.swipeToStartAction.get() shouldBe ChapterSwipeAction.Download
            // Untouched, so the swipe to the right keeps its default, Mark as read, as it had in
            // upstream, whose key for that swipe the file did not carry.
            library.swipeToEndAction.get() shouldBe ChapterSwipeAction.ToggleRead
            env.preferenceStore.values.containsKey("pref_chapter_swipe_end_action") shouldBe false
        }
    }

    /**
     * Phase 4 unit CS, challenge c2 g3 A item 1. An upstream user who never touched either setting has a
     * backup with neither key, and marks read by swiping a chapter right and bookmarks it by swiping
     * left (upstream's property defaults, its labels "Chapter on swipe to right" and "to left"). The
     * assertion is by gesture: [LibraryPreferences.swipeToEndAction] is the swipe to the right, the
     * row's start actions, as `MangaDetailViewModel` wires it and upstream's `MangaViewModel` does.
     */
    @Test
    fun `an upstream backup with no swipe keys marks read on a swipe right and bookmarks on a swipe left`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(BackupPreference("pref_reader_flash_duration", StringPreferenceValue("250")))

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            val library = LibraryPreferences(env.preferenceStore)
            val swipeRight = library.swipeToEndAction.get()
            val swipeLeft = library.swipeToStartAction.get()
            swipeRight shouldBe ChapterSwipeAction.ToggleRead
            swipeLeft shouldBe ChapterSwipeAction.ToggleBookmark
            env.preferenceStore.values.containsKey("pref_chapter_swipe_start_action") shouldBe false
            env.preferenceStore.values.containsKey("pref_chapter_swipe_end_action") shouldBe false
        }
    }

    @Test
    fun `the upstream spelling of the custom interval filter reads into the v2 key`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val prefs = listOf(
                BackupPreference("pref_filter_library_interval_custom", StringPreferenceValue("ENABLED_IS")),
            )

            env.preferenceRestorer.restoreApp(prefs, emptyMap(), mutableListOf())

            LibraryPreferences(env.preferenceStore).filterIntervalCustom.get() shouldBe TriState.ENABLED_IS
            env.preferenceStore.values.containsKey("pref_filter_library_interval_custom") shouldBe false
        }
    }

    @Test
    fun `toUpstream exchanges the two swipe keys`() {
        val prefs = listOf(
            BackupPreference("pref_chapter_swipe_start_action", StringPreferenceValue("Download")),
            BackupPreference("pref_chapter_swipe_end_action", StringPreferenceValue("ToggleBookmark")),
        )

        val written = UpstreamPreferenceKeys.toUpstream(prefs)

        written.map { it.key to it.value } shouldBe listOf(
            "pref_chapter_swipe_end_action" to StringPreferenceValue("Download"),
            "pref_chapter_swipe_start_action" to StringPreferenceValue("ToggleBookmark"),
        )
    }

    @Test
    fun `toUpstream renames the v2 interval key back to the plain spelling`() {
        val prefs = listOf(
            BackupPreference("pref_filter_library_interval_custom_v2", StringPreferenceValue("ENABLED_IS")),
        )

        val written = UpstreamPreferenceKeys.toUpstream(prefs)

        written shouldBe listOf(
            BackupPreference("pref_filter_library_interval_custom", StringPreferenceValue("ENABLED_IS")),
        )
    }

    @Test
    fun `toUpstream then toSaikou is the identity on the swipe and interval keys`() {
        val saikouSide = listOf(
            BackupPreference("pref_chapter_swipe_start_action", StringPreferenceValue("Download")),
            BackupPreference("pref_chapter_swipe_end_action", StringPreferenceValue("ToggleBookmark")),
            BackupPreference("pref_filter_library_interval_custom_v2", StringPreferenceValue("ENABLED_IS")),
        )

        val roundTripped = UpstreamPreferenceKeys.toSaikou(UpstreamPreferenceKeys.toUpstream(saikouSide))

        roundTripped shouldBe saikouSide
    }

    @Test
    fun `toSaikou then toUpstream is the identity on the swipe and interval keys`() {
        val upstreamSide = listOf(
            BackupPreference("pref_chapter_swipe_start_action", StringPreferenceValue("Download")),
            BackupPreference("pref_chapter_swipe_end_action", StringPreferenceValue("ToggleBookmark")),
            BackupPreference("pref_filter_library_interval_custom", StringPreferenceValue("ENABLED_IS")),
        )

        val roundTripped = UpstreamPreferenceKeys.toUpstream(UpstreamPreferenceKeys.toSaikou(upstreamSide))

        roundTripped shouldBe upstreamSide
    }

    @Test
    fun `a key absent from the list is not invented by either direction`() {
        val onlyStart = listOf(
            BackupPreference("pref_chapter_swipe_start_action", StringPreferenceValue("Download")),
        )

        val toUpstreamResult = UpstreamPreferenceKeys.toUpstream(onlyStart)
        val toSaikouResult = UpstreamPreferenceKeys.toSaikou(onlyStart)

        toUpstreamResult.map { it.key } shouldBe listOf("pref_chapter_swipe_end_action")
        toSaikouResult.map { it.key } shouldBe listOf("pref_chapter_swipe_end_action")
    }

    @Test
    fun `keys neither function names pass through untouched in both directions`() {
        val other = listOf(BackupPreference("pref_reader_flash_duration", StringPreferenceValue("250")))

        UpstreamPreferenceKeys.toUpstream(other) shouldBe other
        UpstreamPreferenceKeys.toSaikou(other) shouldBe other
    }
}
