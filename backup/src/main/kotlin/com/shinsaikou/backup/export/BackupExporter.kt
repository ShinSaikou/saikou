package com.shinsaikou.backup.export

import android.content.Context
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.StubSourceNames
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import com.shinsaikou.backup.UpstreamPreferenceKeys
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSink
import okio.GzipSink
import okio.Sink
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.model.Manga
import java.io.Closeable
import java.io.File

/**
 * Builds and writes a `.tachibk`. The output is stock protobuf over the vendored wire models,
 * gzipped, which is what upstream writes and what upstream reads.
 */
@Inject
class BackupExporter(
    private val parser: ProtoBuf,
    private val reader: BackupLibraryReader,
    private val categoryRepository: CategoryRepository,
    private val extensionStoreRepository: ExtensionStoreRepository,
    private val sourceManager: SourceManager,
    private val stubSourceNames: StubSourceNames,
    private val preferenceStore: PreferenceStore,
    private val context: Context,
    private val decoder: BackupDecoder,
) {

    /**
     * Writes everything [options] asks for into a private gzipped file, one library entry at a
     * time, and reads the file back; [write] then copies it to its target. Close the result, which
     * deletes the file. No I/O beyond database and preference reads and that file.
     *
     * It first waits for the source registry. The automatic backup can start the process, and
     * until the extension host's first load has ended the registry holds the Local source alone:
     * every extension source would be written under a stub name and its settings left out of the
     * file, with nothing to say so. A failed load ends the wait too (with nothing from it loaded).
     *
     * Phase 5 unit SC2: the whole [Backup] of a 40,000-entry library, and its encoding, outgrew a
     * 192 MB heap. The library is read [EXPORT_PAGE] entries at a time through [BackupLibraryReader],
     * and each entry is encoded as the field 1 frame it is in a whole [Backup]'s encoding: its tag,
     * its length as a varint, its bytes. The other fields follow as a [Backup] with no entries, whose
     * encoding is those fields alone. The bytes are the whole object's encoding, entries first, which
     * is the field order kotlinx writes and upstream's: 1, 2, 101, 104, 105, 106.
     *
     * Each build first deletes the staged files older than [STALE_AFTER_MILLIS]: those of a process
     * that died mid-export, and the result of a build whose caller was cancelled after this returned
     * and so never closed it. The sweep is what reclaims both; the age spares an export running beside
     * this one.
     */
    suspend fun build(options: BackupOptions): StagedBackup = withContext(Dispatchers.IO) {
        sourceManager.isInitialized.first { it }

        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val staleBefore = System.currentTimeMillis() - STALE_AFTER_MILLIS
        dir.listFiles { file -> file.isStaged() && file.lastModified() < staleBefore }?.forEach { it.delete() }
        val file = File.createTempFile(STAGED_PREFIX, STAGED_SUFFIX, dir)
        try {
            val count = encode(options, file)
            verify(file, count)
            StagedBackup(file, count)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    /**
     * Copies [staged] to [sink] and closes [sink]. Returns the number of bytes written. [build] has
     * read the file back already: a file that cannot be read back is a file the owner would only
     * discover on the day they needed it, so it never reaches a target.
     */
    suspend fun write(staged: StagedBackup, sink: Sink): Long = withContext(Dispatchers.IO) {
        staged.file.source().use { source -> sink.buffer().use { it.writeAll(source) } }
    }

    /** Writes the file [build] stages. Returns the number of library entries in it. */
    private suspend fun encode(options: BackupOptions, file: File): Int {
        var count = 0
        val sources = sortedSetOf<Long>()
        // Read once, so the category list and every entry agree on each category's place.
        val categories = if (options.categories) categoryPlaces() else emptyList()
        val places = categories.withIndex().associate { (place, category) -> category.id to place.toLong() }
        GzipSink(file.sink()).buffer().use { out ->
            if (options.libraryEntries) {
                var afterId = Long.MIN_VALUE
                do {
                    val page = reader.libraryPage(afterId, withRead = options.readEntries, limit = EXPORT_PAGE)
                    buildPage(page, options, places).forEach { entry ->
                        val bytes = parser.encodeToByteArray(BackupManga.serializer(), entry)
                        out.writeByte(MANGA_TAG)
                        out.writeVarint(bytes.size)
                        out.write(bytes)
                    }
                    count += page.size
                    page.mapTo(sources) { it.source }
                    afterId = page.lastOrNull()?.id ?: afterId
                } while (page.size == EXPORT_PAGE)
            }

            val rest = parser.encodeToByteArray(Backup.serializer(), rest(options, sources, categories))
            check(count > 0 || rest.isNotEmpty()) { "The backup encoded to zero bytes" }
            out.write(rest)
        }
        return count
    }

    /**
     * The self-check, a streaming re-read as a restore reads: the header, whose entry count must be
     * [count], then every entry decoded one at a time and counted again. Neither holds the file.
     */
    private fun verify(file: File, count: Int) {
        val header = decoder.header(file.source())
        check(header.mangaCount == count) { "The backup holds ${header.mangaCount} entries, $count were written" }
        check(header.undecodable.isEmpty()) { "The backup's entries ${header.undecodable} do not decode" }
        val read = decoder.entries(file.source()).use { it.count() }
        check(read == count) { "The backup's $read entries decoded, $count were written" }
    }

    /**
     * The live categories in the order the file numbers them: by sort value, then by id. A restore
     * finds an entry's category by the number alone, ours as upstream's does, and sync can leave two
     * categories with one sort value, so the file carries each category's place in this order
     * instead, 0 upwards and never shared.
     */
    private suspend fun categoryPlaces(): List<Category> = categoryRepository.getAll()
        .filterNot { it.isSystemCategory }
        .sortedWith(compareBy({ it.order }, { it.id }))

    /** Every field but the entries, as a [Backup] whose `backupManga` is empty. */
    private suspend fun rest(options: BackupOptions, sources: Set<Long>, categories: List<Category>) = Backup(
        backupManga = emptyList(),
        backupCategories = categories.mapIndexed { place, category -> category.toBackupCategory(place.toLong()) },
        backupSources = sources.map { id -> BackupSource(name = sourceName(id), sourceId = id) },
        backupPreferences = if (options.appSettings) {
            UpstreamPreferenceKeys.toUpstream(preferenceStore.getAll().toBackupPreferences(options.privateSettings))
        } else {
            emptyList()
        },
        backupSourcePreferences = if (options.sourceSettings) buildSourcePreferences(options) else emptyList(),
        backupExtensionStores = if (options.extensionStores) buildExtensionStores() else emptyList(),
    )

    /**
     * The name a source is written under: the live source's, else the one remembered for it, as its
     * stub shows it, else the id. Not `getOrStub`: in a process an automatic backup started, the
     * registry has looked up no stub yet, and answers with the bare id for every source whose
     * extension is gone.
     */
    private suspend fun sourceName(id: Long): String = sourceManager.get(id)?.toString()
        ?: stubSourceNames.find(id)?.let { StubSource(id, it.name, it.lang).toString() }
        ?: id.toString()

    data class Summary(val bytes: Long, val mangaCount: Int, val fileName: String)

    /**
     * The entries of [page], in its order, each built as the export always built one: one query per
     * table for the whole page through [reader] (phase 5 unit SC2), where it used to be six per entry.
     */
    private suspend fun buildPage(
        page: List<Manga>,
        options: BackupOptions,
        places: Map<Long, Long>,
    ): List<BackupManga> {
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.id }
        val excluded = reader.excludedScanlators(ids)
        val chapters = if (options.chapters) reader.chapters(ids) else emptyMap()
        // Places in the file's category order, never ids: the ids belong to this install.
        val categories = if (options.categories) reader.categoryIds(ids) else emptyMap()
        val tracks = if (options.tracking) reader.tracks(ids) else emptyMap()
        // A soft-deleted chapter is not in the file, and neither is its history: a history entry
        // whose url names no chapter of the entry is dropped by the restore anyway.
        val history = if (options.history) reader.history(ids) else emptyMap()

        return page.map { manga ->
            val entry = manga.toBackupManga(excluded[manga.id].orEmpty())
            chapters[manga.id]?.let { rows -> entry.chapters = rows.map { it.toBackupChapter() } }
            categories[manga.id]?.let { rows -> entry.categories = rows.mapNotNull(places::get) }
            tracks[manga.id]?.let { rows -> entry.tracking = rows.map { it.toBackupTracking() } }
            history[manga.id]?.let { rows -> entry.history = rows.map { (url, row) -> row.toBackupHistory(url) } }
            entry
        }
    }

    /**
     * Source settings are read straight out of the `source_<id>` file the extension ABI fixes, so
     * the exporter never needs to know what an extension is (contract 06 decision F8).
     */
    private suspend fun buildSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> =
        sourceManager.getSources().first()
            .filterIsInstance<ConfigurableSource>()
            .map { source ->
                val key = "source_${source.id}"
                val prefs = context.getSharedPreferences(key, Context.MODE_PRIVATE)
                    .all
                    .toBackupPreferences(options.privateSettings)
                BackupSourcePreferences(key, prefs)
            }
            .filter { it.prefs.isNotEmpty() }

    private suspend fun buildExtensionStores(): List<BackupExtensionStore> =
        extensionStoreRepository.getAll().map { store ->
            BackupExtensionStore(
                indexUrl = store.indexUrl,
                name = store.name,
                badgeLabel = store.badgeLabel,
                signingKey = store.signingKey,
                contactWebsite = store.contact.website,
                contactDiscord = store.contact.discord,
                isLegacy = store.isLegacy,
                extensionListUrl = store.extensionListUrl,
            )
        }

    /**
     * App state never travels. Private keys travel only when the owner asked for them, which is
     * what carries tracker logins; the export screen says so, because a file without them looks
     * exactly like a file from an install that never logged in.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(includePrivate: Boolean): List<BackupPreference> = this
        .filterKeys { !Preference.isAppState(it) }
        .filterKeys { includePrivate || !Preference.isPrivate(it) }
        .mapNotNull { (key, value) ->
            when (value) {
                is Int -> BackupPreference(key, IntPreferenceValue(value))
                is Long -> BackupPreference(key, LongPreferenceValue(value))
                is Float -> BackupPreference(key, FloatPreferenceValue(value))
                is String -> BackupPreference(key, StringPreferenceValue(value))
                is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
                is Set<*> -> (value as? Set<String>)?.let { BackupPreference(key, StringSetPreferenceValue(it)) }
                else -> null
            }
        }
        .sortedBy { it.key }

    /** Protobuf's base-128 varint, which is how a frame's length is written. */
    private fun BufferedSink.writeVarint(value: Int) {
        var rest = value
        while (rest and 0x7f.inv() != 0) {
            writeByte((rest and 0x7f) or 0x80)
            rest = rest ushr 7
        }
        writeByte(rest)
    }

    private companion object {
        /**
         * Entries per page. SyncStore.readLibrary's page is 500 and the push's 20; the export's
         * was measured at both (phase 5 unit SC2's report).
         */
        const val EXPORT_PAGE = 500

        /** Field 1 with wire type 2, length-delimited: the tag before each entry. */
        const val MANGA_TAG = BackupDecoder.MANGA_FIELD shl 3 or 2

        /** Under the cache directory; each [build] stages its own file there. */
        const val STAGING_DIR = "backup-export"

        const val STAGED_PREFIX = "export-"
        const val STAGED_SUFFIX = ".tachibk"

        /** A staged file this old is no export's still running: the 40,000-entry build takes about a minute. */
        const val STALE_AFTER_MILLIS = 60 * 60 * 1000L

        fun File.isStaged(): Boolean = isFile && name.startsWith(STAGED_PREFIX) && name.endsWith(STAGED_SUFFIX)
    }
}

/**
 * A backup [BackupExporter.build] wrote and read back, ready for [BackupExporter.write]. Closing it
 * deletes the file.
 */
class StagedBackup internal constructor(
    internal val file: File,
    /** How many library entries the file holds. */
    val mangaCount: Int,
) : Closeable {

    override fun close() {
        file.delete()
    }
}
