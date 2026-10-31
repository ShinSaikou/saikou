package com.shinsaikou.backup.fixture

import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.tracking.TrackerIds
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSink
import okio.buffer
import tachiyomi.core.common.preference.Preference

/**
 * The owner's own `.tachibk` is not on the build machine and must never be committed, so every
 * test runs against a file this object generates. It is deterministic for a seed, and it carries
 * every shape the real format allows: a source no provider serves, a tracker id this build does
 * not know, a stray status code, a category order with no category, a history entry pointing at a
 * chapter that is not there, and a duplicated chapter url.
 *
 * Every name here is invented. `CONTRIBUTING.md` binds fixtures exactly as it binds shipped code.
 */
object SyntheticBackupFixture {

    const val SOURCE_A_NAME = "Fixture Source A"
    const val SOURCE_B_NAME = "Fixture Source B"

    val SOURCE_A: Long = SourceIds.deriveHttpSourceId(SOURCE_A_NAME, "en", 1)
    val SOURCE_B: Long = SourceIds.deriveHttpSourceId(SOURCE_B_NAME, "en", 1)

    /** An id no test ever installs, so the missing-source and stub-name paths are exercised. */
    const val SOURCE_MISSING: Long = 0x7000_0000_0000_0001L

    /** Outside [TrackerIds.ALL], so its rows must be skipped and reported. */
    const val UNKNOWN_TRACKER: Long = 99L

    const val COLLIDING_PREFERENCE_KEY = "pref_type_collision"
    const val EXTENSION_REPO_URL = "https://example.invalid/repo"
    const val SIGNING_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

    val CATEGORY_NAMES = listOf("Reading", "Completed", "On hold")

    private val SOURCES = listOf(SOURCE_A, SOURCE_B, SOURCE_MISSING, SourceIds.LOCAL)
    private val MEMOS = listOf("{}", """{"k":{"nested":[1,2]}}""")

    fun backup(
        seed: Long = 1L,
        mangaCount: Int = 350,
        chaptersPerManga: Int = 12,
        privateSettings: Boolean = false,
    ): Backup {
        val manga = (1..mangaCount).map { buildManga(seed, it, chaptersPerManga) }
        return Backup(
            backupManga = manga,
            backupCategories = CATEGORY_NAMES.mapIndexed { index, name ->
                BackupCategory(name = name, order = index.toLong(), id = (index + 1).toLong(), flags = index * 7L)
            },
            backupSources = listOf(
                BackupSource(name = SOURCE_A_NAME, sourceId = SOURCE_A),
                BackupSource(name = SOURCE_B_NAME, sourceId = SOURCE_B),
                BackupSource(name = SOURCE_MISSING.toString(), sourceId = SOURCE_MISSING),
            ),
            backupPreferences = appPreferences(privateSettings),
            backupSourcePreferences = sourcePreferences(),
            backupExtensionStores = extensionStores(),
        )
    }

    /** The bytes an upstream install would hand over. Raw is what some sync transports write. */
    fun tachibk(backup: Backup, gzip: Boolean = true): ByteArray {
        val encoded = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (!gzip) return encoded
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.write(encoded) }
        return compressed.readByteArray()
    }

    private fun buildManga(seed: Long, index: Int, chaptersPerManga: Int): BackupManga {
        val source = SOURCES[(index + seed.toInt()) % SOURCES.size]
        val readEntry = index % 4 == 0
        val chapters = buildChapters(index, chaptersPerManga)

        return BackupManga(
            source = source,
            url = "/manga/$index",
            title = "Fixture Manga $index",
            artist = "Artist $index",
            author = "Author $index",
            description = "Description $index",
            // Duplicates and blanks survive the round trip untouched.
            genre = listOf("Genre A", "Genre A", "", "Genre ${index % 5}"),
            status = statusCode(index),
            thumbnailUrl = "https://example.invalid/cover/$index.jpg",
            dateAdded = 1_600_000_000_000L + index,
            viewer = if (index == 1) 2 else 0,
            chapters = chapters,
            // Order values, not ids. Order 9 exists in no category, and is ignored on import.
            categories = listOf((index % 3).toLong(), 9L),
            tracking = buildTracking(index),
            favorite = !readEntry,
            chapterFlags = chapterFlags(index),
            viewer_flags = if (index == 1) null else viewerFlags(index),
            history = buildHistory(index, chapters, readEntry),
            lastModifiedAt = 1_700_000_000L + index,
            favoriteModifiedAt = if (index % 3 == 0) 1_700_000_100L + index else null,
            excludedScanlators = if (index % 6 == 0) listOf("Scanlator $index") else emptyList(),
            version = (index % 4).toLong(),
            notes = if (index % 10 == 0) "Note $index" else "",
            initialized = index % 2 == 0,
            memo = MEMOS[index % MEMOS.size].encodeToByteArray(),
        )
    }

    /** Every declared code, plus a stray the enum does not know. */
    private fun statusCode(index: Int): Int {
        val codes = MangaStatus.entries.map { it.code } + 9
        return codes[index % codes.size]
    }

    /** Cycles every reading mode and orientation pair, and sets one bit above both groups. */
    private fun viewerFlags(index: Int): Int {
        val modes = ReadingMode.entries
        val orientations = ReaderOrientation.entries
        val mode = modes[index % modes.size].flagValue
        val orientation = orientations[(index / modes.size) % orientations.size].flagValue
        val extra = if (index % 17 == 0) 0x40 else 0
        return mode or orientation or extra
    }

    /** One value out of every mask group of `chapter_flags`. */
    private fun chapterFlags(index: Int): Int {
        val sortDirection = if (index % 2 == 0) 0x1 else 0x0
        val unread = listOf(0x0, 0x2, 0x4)[index % 3]
        val downloaded = listOf(0x0, 0x8, 0x10)[index % 3]
        val bookmarked = listOf(0x0, 0x20, 0x40)[index % 3]
        val sorting = listOf(0x0, 0x100, 0x200, 0x300)[index % 4]
        val display = if (index % 5 == 0) 0x100000 else 0x0
        return sortDirection or unread or downloaded or bookmarked or sorting or display
    }

    private fun buildChapters(index: Int, count: Int): List<BackupChapter> {
        val chapters = (1..count).map { number ->
            BackupChapter(
                url = "/manga/$index/ch/$number",
                name = "Chapter $number",
                scanlator = if (number % 2 == 1) "Scanlator $number" else null,
                read = number <= count / 2,
                bookmark = number % 5 == 0,
                lastPageRead = if (number == count / 2 + 1) 7L else 0L,
                dateFetch = 1_650_000_000_000L + number,
                dateUpload = if (number % 3 == 0) 0L else 1_640_000_000_000L + number,
                // Three unrecognised numbers, which is the sentinel a source with no numbering uses.
                chapterNumber = if (number in 1..3 && index % 7 == 0) -1f else number.toFloat(),
                sourceOrder = number.toLong(),
                lastModifiedAt = 1_700_000_000L + number,
                version = 0L,
                memo = MEMOS[number % MEMOS.size].encodeToByteArray(),
            )
        }
        // One duplicated url, kept to prove the importer tolerates it rather than throwing.
        return chapters + chapters.first()
    }

    private fun buildHistory(
        index: Int,
        chapters: List<BackupChapter>,
        readEntry: Boolean,
    ): List<BackupHistory> {
        val read = chapters.filter { it.read }.distinctBy { it.url }
        if (read.isEmpty() && !readEntry) return emptyList()
        return read.mapIndexed { position, chapter ->
            BackupHistory(
                url = chapter.url,
                lastRead = 1_660_000_000_000L + position,
                readDuration = 60_000L * (position + 1),
            )
        } + BackupHistory(url = "/manga/$index/ch/missing", lastRead = 1_660_000_000_000L, readDuration = 1L)
    }

    /**
     * One row per known tracker id, spread over the library, plus the deprecated integer media id
     * and one id this build does not know.
     */
    private fun buildTracking(index: Int): List<BackupTracking> {
        val known = TrackerIds.ALL.sorted()
        val trackerId = known[index % known.size]
        val useIntMediaId = index % known.size == 0

        @Suppress("DEPRECATION")
        val row = BackupTracking(
            syncId = trackerId.toInt(),
            libraryId = index.toLong(),
            mediaIdInt = if (useIntMediaId) 4242 else 0,
            trackingUrl = "https://example.invalid/track/$index",
            title = "Fixture Manga $index",
            lastChapterRead = (index % 20).toFloat(),
            totalChapters = 100,
            score = 7.5f,
            status = 1,
            startedReadingDate = 1_600_000_000_000L,
            finishedReadingDate = 0L,
            private = false,
            mediaId = if (useIntMediaId) 0L else 9_000L + index,
        )

        return if (index % 11 == 0) {
            listOf(row, row.copy(syncId = UNKNOWN_TRACKER.toInt(), mediaId = 1L))
        } else {
            listOf(row)
        }
    }

    private fun appPreferences(privateSettings: Boolean): List<BackupPreference> {
        val base = listOf(
            BackupPreference("fixture_int", IntPreferenceValue(12)),
            BackupPreference("fixture_long", LongPreferenceValue(13L)),
            BackupPreference("fixture_float", FloatPreferenceValue(1.5f)),
            BackupPreference("fixture_string", StringPreferenceValue("value")),
            BackupPreference("fixture_boolean", BooleanPreferenceValue(true)),
            BackupPreference("fixture_string_set", StringSetPreferenceValue(setOf("a", "b"))),
            BackupPreference("pref_enable_transitions_key", BooleanPreferenceValue(false)),
            BackupPreference("pref_reader_flash_duration", IntPreferenceValue(250)),
            BackupPreference("pref_reader_flash_mode", StringPreferenceValue("WHITE")),
            BackupPreference("default_category", IntPreferenceValue(2)),
            BackupPreference("library_update_categories", StringSetPreferenceValue(setOf("1", "2"))),
            BackupPreference("extension_repos", StringSetPreferenceValue(setOf(EXTENSION_REPO_URL))),
            BackupPreference(Preference.appStateKey("fixture_state"), IntPreferenceValue(1)),
            BackupPreference(COLLIDING_PREFERENCE_KEY, IntPreferenceValue(5)),
        )
        if (!privateSettings) return base

        // The five tracker credential keys, for tracker id 1 only.
        return base + listOf(
            BackupPreference(Preference.privateKey("track_token_1"), StringPreferenceValue("token")),
            BackupPreference(Preference.privateKey("pref_mangasync_username_1"), StringPreferenceValue("user")),
            BackupPreference(Preference.privateKey("pref_mangasync_displayname_1"), StringPreferenceValue("name")),
            BackupPreference(Preference.privateKey("pref_mangasync_password_1"), StringPreferenceValue("secret")),
            BackupPreference(Preference.privateKey("pref_tracker_auth_expired_1"), BooleanPreferenceValue(false)),
        )
    }

    private fun sourcePreferences(): List<BackupSourcePreferences> = listOf(
        BackupSourcePreferences(
            sourceKey = "source_$SOURCE_A",
            prefs = listOf(
                BackupPreference("fixture_source_string", StringPreferenceValue("a")),
                BackupPreference("fixture_source_boolean", BooleanPreferenceValue(true)),
            ),
        ),
        BackupSourcePreferences(
            sourceKey = "source_$SOURCE_MISSING",
            prefs = listOf(BackupPreference("fixture_source_int", IntPreferenceValue(3))),
        ),
    )

    private fun extensionStores(): List<BackupExtensionStore> = listOf(
        BackupExtensionStore(
            indexUrl = "https://example.invalid/one/index.min.json",
            name = "Fixture Store One",
            badgeLabel = "One",
            signingKey = SIGNING_KEY,
            contactWebsite = "https://example.invalid/one",
            contactDiscord = null,
            isLegacy = false,
            extensionListUrl = "https://example.invalid/one/list",
        ),
        BackupExtensionStore(
            indexUrl = "https://example.invalid/two/index.min.json",
            name = "Fixture Store Two",
            badgeLabel = null,
            signingKey = SIGNING_KEY,
            contactWebsite = "https://example.invalid/two",
            contactDiscord = "https://example.invalid/two/chat",
            isLegacy = null,
            extensionListUrl = null,
        ),
    )
}
