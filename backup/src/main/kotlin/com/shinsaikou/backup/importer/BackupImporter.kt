package com.shinsaikou.backup.importer

import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.source.repository.SourceAliasRepository
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSourceNames
import com.shinsaikou.tracking.TrackerIds
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.extensions.JsonObjectEmptyBytes
import tachiyomi.domain.extension.model.ExtensionStore
import okio.source
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.time.Clock

/**
 * Restores one backup file into the database.
 *
 * Three properties matter more than speed. The manga list is kept in file order for the whole run,
 * so a checkpoint index means the same manga on a resume as it did on the first attempt. A chunk
 * is one transaction, and a chunk that throws is retried row by row so one malformed entry costs
 * one manga instead of a hundred. Inside one manga, the row itself is the only write that has to
 * succeed: chapters, tracking, history, categories and excluded scanlators each fail on their own.
 *
 * Every merge rule is written so a re-import converges. Read state, bookmarks and page progress
 * never regress, history duration is a maximum rather than a sum, and the excluded scanlator set
 * is additive.
 */
@Inject
class BackupImporter(
    private val decoder: BackupDecoder,
    private val validator: BackupFileValidator,
    private val handler: DatabaseHandler,
    private val writer: BackupDatabaseWriter,
    private val categoryRepository: CategoryRepository,
    private val extensionStoreRepository: ExtensionStoreRepository,
    private val sourceAliasRepository: SourceAliasRepository,
    private val stubSourceNames: StubSourceNames,
    private val sourceManager: SourceManager,
    private val preferenceRestorer: PreferenceRestorer,
    private val updateManga: UpdateManga,
    private val fetchInterval: FetchInterval,
    private val libraryPreferences: LibraryPreferences,
    private val checkpoints: ImportCheckpointStore,
    private val libraryUpdateScheduler: LibraryUpdateScheduler,
    private val backupScheduler: BackupScheduler,
    private val syncRestorePreferences: SyncRestorePreferences,
    private val json: Json,
) {

    /**
     * Imports the private copy at [file]. The run resumes when the stored checkpoint describes the
     * same file and the same options and has not reached [ImportPhase.DONE]; otherwise it starts
     * fresh and the stale checkpoint is discarded. Cancellation is checked between chunks, and a
     * cancel that lands inside a chunk rolls that chunk back. A cancel after validation sets the
     * sync restore flag. The checkpoint is saved after each committed chunk of the manga list, so a
     * cancel there leaves one to resume from; a cancel before the first chunk commits leaves only the
     * checkpoint the run started with (none on a fresh run), and the next run redoes the steps before
     * the manga list, which are safe to repeat.
     *
     * The file is read as a stream (phase 5 unit SC2), twice: the header pass decodes every entry
     * one at a time before the first write, so a file whose frames break writes nothing, as when the
     * file was decoded whole; the manga list then reads the entries again. An entry whose own bytes
     * do not decode is passed over there, counted as failed and reported, and the rest restore.
     */
    suspend fun import(
        file: File,
        options: RestoreOptions,
        progress: (ImportProgress) -> Unit,
    ): ImportReport = withContext(Dispatchers.IO) {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val fileSha256 = file.sha256Hex()
        val fileSize = file.length()
        val optionFlags = options.asBooleanArray().toList()

        val stored = checkpoints.load()?.takeIf {
            it.fileSha256 == fileSha256 &&
                it.fileSize == fileSize &&
                it.options == optionFlags &&
                it.phase != ImportPhase.DONE
        }
        if (stored == null) {
            checkpoints.clear()
            checkpoints.errorLog.delete()
        }

        val run = Run(
            fileSha256 = fileSha256,
            fileSize = fileSize,
            optionFlags = optionFlags,
            startedAt = stored?.startedAt ?: startedAt,
            startCursor = stored?.mangaCursor ?: 0,
            resumed = stored != null,
        )

        // 1. Read the header once: every field but the library entries, and how many entries there
        //    are. Step 7 reads the entries themselves in file order, a chunk at a time, so the file
        //    is never held whole and the cursor keeps its meaning.
        val header = decoder.header(file.source())
        val backup = header.backup
        run.sourceLabels = backup.backupSources.associate { it.sourceId to it.name }
        run.mangaTotal = header.mangaCount
        run.undecodable = header.undecodable.toSet()

        // 2. Validate, and seed the tracker lists the report carries. The validator waits for the
        //    source registry first, which step 3 relies on: an import WorkManager resumes can start
        //    the process, and before the extension host has loaded every installed source reads as
        //    missing and would be remembered as a stub.
        val validation = validator.validate(header)
        run.unsupportedTrackers = validation.unsupportedTrackers
        run.trackersNotSignedIn = validation.missingTrackerIds

        try {
            // 3. Remember a name for every source no provider serves, so library rows stay readable.
            rememberStubSources(backup, run)

            // 4 to 6 are idempotent, so a resumed run simply re-executes them.
            restoreCategories(backup, options, run)
            restorePreferences(backup, options, run)
            restoreExtensionStores(backup, options, run)

            // 7. The only phase with a cursor.
            if (options.libraryEntries) {
                decoder.entries(file.source(), skip = run.startCursor, passOverUndecodable = true).use { entries ->
                    restoreMangaList(entries, run.mangaTotal, options, run, progress)
                }
            }
        } catch (e: CancellationException) {
            // A cancel inside a chunk, or in an earlier step, throws out of it. What committed
            // before it stays, so the run finishes as a cancelled one before the cancel goes on.
            run.cancelled = true
            finish(run, failure = e)
            throw e
        } catch (e: BackupDecodeException) {
            // Not expected: the header pass walked every frame before the first write. Should step 7
            // still meet one it cannot read (the file changed under the run), the chunks before it
            // are committed and unlogged, so sync must still treat this as a restore, and the report
            // says what was restored. The checkpoint stays, and the worker does not retry.
            withContext(NonCancellable) {
                run.flushErrors()
                writeReport(run.toReport(readErrorLog()))
                markRestored(failure = e)
            }
            throw e
        } catch (e: Throwable) {
            // Storage, or a full heap: the worker retries from the checkpoint. A chunk that
            // committed before it is unlogged like any restored row, so sync must treat the run as
            // a restore whether or not the retry ever finishes.
            withContext(NonCancellable) {
                run.flushErrors()
                if (run.entriesWritten) markRestored(failure = e)
            }
            throw e
        }

        finish(run)
    }

    private suspend fun rememberStubSources(backup: Backup, run: Run) {
        backup.backupSources.forEach { source ->
            if (source.sourceId == SourceIds.LOCAL) return@forEach
            if (sourceManager.get(source.sourceId) != null) return@forEach
            // Listed whether or not it is named yet: a resumed run finds its own first attempt's
            // stubs already remembered, and its report must still say the extension is missing.
            run.missingSources += source.sourceId
            if (stubSourceNames.find(source.sourceId) != null) return@forEach
            stubSourceNames.remember(source.sourceId, source.name, lang = "")
            run.stubSourcesRemembered += source.sourceId
        }
    }

    /**
     * Live categories are matched by name, never by id: the ids in a foreign file belong to that
     * install. A match is reused untouched, so a re-import never renames or reflags a category.
     *
     * A category the restore creates holds no clock (phase 5 unit CT): the file carries no parent
     * (decision F10), so the create's stamp would let its "no parent" beat, on the next sync, a
     * parent another device set before it, and flatten that device's tree. With no clock, every
     * value another device holds for the same category wins the merge, and the next local edit
     * stamps it as usual. The create is still logged, so the category is pushed.
     *
     * An entry names its categories by order value, and a file an older build wrote can give two
     * categories one value (sync can leave a tie, and the export wrote the values as stored). The
     * file cannot say which of them an entry was in, so the value maps to every category that has
     * it: an entry may gain a category it was not in, but none is emptied into another, and the
     * report names the categories.
     */
    private suspend fun restoreCategories(backup: Backup, options: RestoreOptions, run: Run) {
        if (!options.categories) return
        run.phase = ImportPhase.CATEGORIES

        handler.await(inTransaction = true) {
            val live = categoryRepository.getAll().filterNot { it.isSystemCategory }
            val byName = live.associateBy { it.name }.toMutableMap()
            var nextOrder = (live.maxOfOrNull { it.order } ?: -1L) + 1L

            backup.backupCategories.sortedBy { it.order }.forEach { backupCategory ->
                try {
                    val existing = byName[backupCategory.name]
                    val resolved = if (existing != null) {
                        existing
                    } else {
                        categoryRepository.insert(
                            Category(
                                id = 0L,
                                name = backupCategory.name,
                                order = nextOrder++,
                                flags = backupCategory.flags,
                                parentId = null,
                            ),
                        )
                        val inserted = categoryRepository.getAll()
                            .first { it.name == backupCategory.name && !it.isSystemCategory }
                        categoriesQueries.restoreForgetModifiedAt(categoryId = inserted.id)
                        byName[backupCategory.name] = inserted
                        run.categoriesCreated++
                        inserted
                    }
                    run.categoryIdsByBackupOrder.getOrPut(backupCategory.order) { mutableListOf() } += resolved.id
                    run.categoryIdByBackupId[backupCategory.id] = resolved.id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    run.record(ImportError.Kind.CATEGORY, backupCategory.name, null, e)
                }
            }

            // Said once: a resumed run has the first attempt's line in its log already.
            if (!run.resumed) recordSharedOrders(backup, run)

            // Upstream's rule: more than one distinct flag set means the user configured categories
            // individually, so the per-category display switch is turned on.
            if (categoryRepository.getAll().map { it.flags }.distinct().size > 1) {
                libraryPreferences.categorizedDisplaySettings.set(true)
            }
        }
        run.flushErrors()
    }

    private fun recordSharedOrders(backup: Backup, run: Run) {
        run.categoryIdsByBackupOrder.forEach { (order, ids) ->
            if (ids.distinct().size < 2) return@forEach
            run.record(
                kind = ImportError.Kind.CATEGORY,
                subject = backup.backupCategories.filter { it.order == order }.joinToString { it.name },
                sourceLabel = null,
                message = "These categories share one position in the backup, which cannot tell them apart, " +
                    "so each of their entries was put in all of them",
            )
        }
    }

    private suspend fun restorePreferences(backup: Backup, options: RestoreOptions, run: Run) {
        if (!options.appSettings && !options.sourceSettings) return
        run.phase = ImportPhase.PREFERENCES

        if (options.appSettings) {
            run.preferencesWritten += preferenceRestorer
                .restoreApp(backup.backupPreferences, run.categoryIdByBackupId, run.errors)
            // The restored interval keys only take effect once the two schedulers re-read them.
            libraryUpdateScheduler.setup()
            backupScheduler.setup()
        }
        if (options.sourceSettings) {
            run.sourcePreferenceFilesWritten += preferenceRestorer
                .restoreSource(backup.backupSourcePreferences, run.errors)
        }
        run.flushErrors()
    }

    private suspend fun restoreExtensionStores(backup: Backup, options: RestoreOptions, run: Run) {
        if (!options.extensionStores) return
        run.phase = ImportPhase.EXTENSION_STORES

        backup.backupExtensionStores.chunked(STORE_BATCH_SIZE).forEach { chunk ->
            handler.await(inTransaction = true) {
                chunk.forEach { store ->
                    try {
                        // A configured repository keeps the signing key it was added with: a backup
                        // that names another key for the same index is refused for that row, so a
                        // shared or crafted file cannot change which extensions are trusted. A blank
                        // key in the backup is no claim at all: the row's other settings restore and
                        // the configured key stays.
                        val configuredKey = extensionStoreRepository.get(store.indexUrl)
                            ?.signingKey?.takeIf { it.isNotBlank() }
                        if (configuredKey != null && store.signingKey.isNotBlank() &&
                            configuredKey != store.signingKey
                        ) {
                            run.record(
                                ImportError.Kind.EXTENSION_STORE,
                                store.indexUrl,
                                null,
                                "This repository is already configured with another signing key; kept the configured one",
                            )
                            return@forEach
                        }
                        extensionStoreRepository.upsert(
                            // The signing key is stored and never logged.
                            ExtensionStore(
                                indexUrl = store.indexUrl,
                                name = store.name,
                                badgeLabel = store.badgeLabel ?: store.name,
                                signingKey = configuredKey ?: store.signingKey,
                                contact = ExtensionStore.Contact(store.contactWebsite, store.contactDiscord),
                                isLegacy = store.isLegacy ?: true,
                                extensionListUrl = store.extensionListUrl,
                            ),
                        )
                        run.extensionStoresUpserted++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        run.record(ImportError.Kind.EXTENSION_STORE, store.indexUrl, null, e)
                    }
                }
            }
        }
        run.flushErrors()
    }

    /**
     * [entries] in file order from the run's cursor, [MANGA_BATCH_SIZE] to a transaction, against
     * [total] entries in the file; the cursor counts the undecodable entries the list passes over
     * too. A pipeline: a reader on [Dispatchers.IO] decodes the next chunks into a channel of
     * [DECODE_AHEAD] while this coroutine commits the current one, since the database has one
     * writer and only the decode can run beside it. A failure on either side ends
     * both. The reader's own failure closes the channel with it, so the chunks it decoded before
     * the failure are committed and then the failure is thrown here; a failure or a cancel here
     * cancels the reader.
     */
    private suspend fun restoreMangaList(
        entries: Sequence<BackupManga>,
        total: Int,
        options: RestoreOptions,
        run: Run,
        progress: (ImportProgress) -> Unit,
    ) = coroutineScope {
        run.phase = ImportPhase.MANGA
        val timeZone = TimeZone.currentSystemDefault()
        val dateTime = Clock.System.now().toLocalDateTime(timeZone)
        val fetchWindow = fetchInterval.getWindow(dateTime.date, timeZone)
        val context = MangaContext(options, run, timeZone, dateTime, fetchWindow)

        val chunks = Channel<List<BackupManga>>(DECODE_AHEAD)
        val reader = launch(Dispatchers.IO) {
            val failure = try {
                entries.chunked(MANGA_BATCH_SIZE).forEach { chunks.send(it) }
                null
            } catch (e: CancellationException) {
                // The reader's own cancel (R121, R127): close the channel and let the cancel go on.
                chunks.close(e)
                throw e
            } catch (e: Throwable) {
                e
            }
            chunks.close(failure)
        }

        try {
            var cursor = run.startCursor
            for (chunk in chunks) {
                if (!currentCoroutineContext().isActive) {
                    run.cancelled = true
                    return@coroutineScope
                }
                val tally = run.tally()

                try {
                    handler.await(inTransaction = true) {
                        chunk.forEach { restoreManga(it, context) }
                    }
                    run.entriesWritten = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The only failure mode that reaches here is the transaction itself. Log it: a
                    // restore that silently falls back is a restore that is slow for no visible reason.
                    logcat(LogPriority.WARN, e) { "Backup chunk ${cursor / MANGA_BATCH_SIZE} fell back to row by row" }
                    run.restore(tally)
                    run.record(
                        kind = ImportError.Kind.BATCH_FALLBACK,
                        subject = "chunk ${cursor / MANGA_BATCH_SIZE}",
                        sourceLabel = null,
                        error = e,
                    )
                    chunk.forEach { entry ->
                        try {
                            handler.await(inTransaction = true) { restoreManga(entry, context) }
                            run.entriesWritten = true
                        } catch (rowError: CancellationException) {
                            throw rowError
                        } catch (rowError: Exception) {
                            run.mangaFailed++
                            run.record(
                                kind = ImportError.Kind.MANGA_ROW,
                                subject = entry.title,
                                sourceLabel = run.labelOf(entry.source),
                                error = rowError,
                            )
                        }
                    }
                }

                val end = run.frameAfter(cursor, chunk.size)
                run.passOver(cursor, end)
                cursor = end
                saveCheckpoint(run, cursor)
                run.flushErrors()
                progress(ImportProgress(ImportPhase.MANGA, cursor, total, chunk.lastOrNull()?.title))
            }
            // The entries after the last one that decoded.
            run.passOver(cursor, total)
        } finally {
            reader.cancel()
        }
    }

    /** One manga, inside the chunk transaction. */
    private suspend fun restoreManga(entry: BackupManga, context: MangaContext) {
        val run = context.run
        // Before any lookup: the id in the file may belong to a source this install renamed.
        val resolvedSource = sourceAliasRepository.resolve(entry.source)
        val decoded = decodeManga(entry, run)
        val manga = decoded.copy(source = resolvedSource)
        val dbManga = writer.findManga(manga.url, resolvedSource)

        val mangaId: Long
        if (dbManga == null) {
            val now = Clock.System.now().toEpochMilliseconds()
            mangaId = writer.insertManga(
                manga.copy(dateAdded = if (manga.dateAdded == 0L) now else manga.dateAdded),
            )
            // The insert cannot set is_syncing itself, so it is set here, before the category and
            // scanlator writes whose triggers would otherwise bump the row's version.
            writer.markSyncing(mangaId)
            run.mangaInserted++
        } else {
            writer.updateManga(mergeManga(dbManga, manga), isSyncing = true)
            // A tombstone loses to an explicit library entry. This follows the update rather than
            // preceding it, because the update binds every column and would write it back. A
            // library add over a live non-favourite row takes the same call, which stamps the
            // restore clock the latch keeps the trigger from stamping, so a removal another device
            // made before this restore does not win on the next sync (R87).
            if (entry.favorite && (dbManga.deletedAt != null || !dbManga.favorite)) writer.undelete(dbManga.id)
            mangaId = dbManga.id
            run.mangaMerged++
        }
        restoreDetailsOverride(entry, dbManga, mangaId)

        val label = run.labelOf(entry.source)
        step(run, ImportError.Kind.CATEGORIES_OF_MANGA, entry.title, label) {
            restoreCategoriesOfManga(entry, mangaId, context)
        }
        step(run, ImportError.Kind.CHAPTERS, entry.title, label) {
            restoreChapters(entry, mangaId, run)
        }
        step(run, ImportError.Kind.TRACKING, entry.title, label) {
            restoreTracking(entry, mangaId, run)
        }
        step(run, ImportError.Kind.HISTORY, entry.title, label) {
            restoreHistory(entry, mangaId, run)
        }
        step(run, ImportError.Kind.EXCLUDED_SCANLATORS, entry.title, label) {
            if (entry.excludedScanlators.isNotEmpty()) {
                writer.addExcludedScanlators(mangaId, entry.excludedScanlators.toSet())
            }
        }

        updateManga.awaitUpdateFetchInterval(
            manga = manga.copy(id = mangaId),
            timeZone = context.timeZone,
            dateTime = context.dateTime,
            window = context.fetchWindow,
        )
        // The chapters this entry inserted went in under their own latch, so none was logged; it
        // is lowered once here, whatever the chapter step did, as the manga's is below.
        writer.lowerChaptersLatch(mangaId)
        writer.resetIsSyncing(mangaId)
    }

    /**
     * The edited title and description another reader's file carries (R162 item 8), by
     * [mergeOverride]. The row's latch is up on both paths, so neither the edit's clock nor the
     * version moves, as with the notes a restore writes.
     */
    private suspend fun restoreDetailsOverride(entry: BackupManga, dbManga: Manga?, mangaId: Long) {
        val title = mergeOverride(dbManga?.titleOverride, entry.customTitle)
        val description = mergeOverride(dbManga?.descriptionOverride, entry.customDescription)
        if (title != dbManga?.titleOverride || description != dbManga?.descriptionOverride) {
            writer.setDetailsOverride(mangaId, title, description)
        }
    }

    /**
     * `getMangaImpl` decodes the memo blob, which is the one field a corrupt file can make throw.
     * Undecodable bytes become an empty object and an error, rather than losing the whole row.
     */
    private fun decodeManga(entry: BackupManga, run: Run): Manga = try {
        entry.getMangaImpl()
    } catch (e: Exception) {
        run.record(ImportError.Kind.MANGA_ROW, entry.title, run.labelOf(entry.source), e)
        entry.memo = JsonObjectEmptyBytes
        entry.getMangaImpl()
    }

    private suspend fun restoreCategoriesOfManga(entry: BackupManga, mangaId: Long, context: MangaContext) {
        if (!context.options.categories) return
        // The field holds category order values, not ids.
        val ids = entry.categories.flatMap { context.run.categoryIdsByBackupOrder[it].orEmpty() }.distinct()
        // Nothing resolved means the file's categories are not in this library; leave the manga's
        // own set alone rather than emptying it.
        if (ids.isEmpty()) return
        writer.setMangaCategories(mangaId, ids)
    }

    /**
     * A file chapter whose url no live row has but a tombstone does restores that row (phase 4 unit
     * BF, R120 carry-over 2), where it used to be inserted as a second row beside it, which sync,
     * finding a chapter by its manga and url, then saw twice. The tombstone's values merge with the
     * file's by [mergeChapter], and the one update that writes them clears `deleted_at`, even when
     * nothing else changes. That update runs with the latch down: it is a local undelete, so the
     * row's triggers stamp its restore clock past the tombstone, which the other devices hold and
     * which would otherwise win the next sync and delete the row again (R82's chapter rule, as R87
     * decided for a restored entry), and its version moves once for it, as a relisting's does
     * (R120): the tombstone's version plus one, by the trigger alone, never the file's version,
     * since the source values written are the tombstone's own. A url with a live row uses the live
     * row and leaves any tombstone of it alone.
     */
    private suspend fun restoreChapters(entry: BackupManga, mangaId: Long, run: Run) {
        if (entry.chapters.isEmpty()) return
        val existing = writer.chaptersByUrl(mangaId)
        // Only asked for when the file carries a url no live row has.
        val deleted = if (entry.chapters.all { it.url in existing }) {
            emptyMap()
        } else {
            writer.deletedChaptersByUrl(mangaId)
        }
        val toInsert = mutableListOf<Chapter>()
        val toUpdate = mutableListOf<Chapter>()
        val toRestore = mutableListOf<Chapter>()

        // A file can carry the same chapter twice; the url is the row identity, so the second
        // copy is dropped rather than inserted alongside the first.
        entry.chapters.distinctBy { it.url }.forEach { backupChapter ->
            val chapter = try {
                backupChapter.toChapterImpl()
            } catch (e: Exception) {
                run.record(ImportError.Kind.CHAPTERS, backupChapter.url, run.labelOf(entry.source), e)
                return@forEach
            }.copy(mangaId = mangaId)

            val db = existing[chapter.url]
            val tombstone = if (db == null) deleted[chapter.url] else null
            when {
                tombstone != null -> toRestore += mergeChapter(chapter, tombstone)
                db == null -> toInsert += chapter
                isUnchanged(chapter, db) -> Unit
                else -> toUpdate += mergeChapter(chapter, db)
            }
        }

        if (toInsert.isNotEmpty()) writer.insertChapters(toInsert)
        if (toUpdate.isNotEmpty()) {
            writer.updateChapters(toUpdate, isSyncing = true)
            writer.resetChaptersIsSyncing(toUpdate.map { it.id })
        }
        if (toRestore.isNotEmpty()) writer.updateChapters(toRestore, isSyncing = false, clearDeletedAt = true)
        run.chaptersWritten += toInsert.size + toUpdate.size + toRestore.size
    }

    private suspend fun restoreTracking(entry: BackupManga, mangaId: Long, run: Run) {
        if (entry.tracking.isEmpty()) return
        val existing = writer.tracksByTrackerId(mangaId)
        val toInsert = mutableListOf<Track>()

        entry.tracking.forEach { backupTrack ->
            val trackerId = backupTrack.syncId.toLong()
            if (trackerId !in TrackerIds.ALL) {
                if (run.unknownTrackers.add(trackerId)) {
                    run.record(
                        kind = ImportError.Kind.UNKNOWN_TRACKER_SKIPPED,
                        subject = trackerId.toString(),
                        sourceLabel = null,
                        message = "The backup references a tracker this build does not know",
                    )
                }
                return@forEach
            }

            val track = backupTrack.getTrackImpl().copy(mangaId = mangaId)
            val db = existing[trackerId]
            if (db == null) {
                toInsert += track
                return@forEach
            }
            val merged = mergeTrack(track, db)
            if (merged != db) {
                writer.updateTrack(merged)
                run.tracksWritten++
            }
        }

        if (toInsert.isNotEmpty()) {
            writer.insertTracks(toInsert)
            run.tracksWritten += toInsert.size
        }
    }

    /**
     * Runs after the chapter step, so a history entry can point at a chapter just inserted, or at a
     * tombstone that step restored, which reads as live by then.
     */
    private suspend fun restoreHistory(entry: BackupManga, mangaId: Long, run: Run) {
        if (entry.history.isEmpty()) return
        val chapters = writer.chaptersByUrl(mangaId)
        val stored = writer.historyByChapterId(mangaId)

        entry.history.forEach { history ->
            if (history.lastRead == 0L) return@forEach
            // A chapter the backup no longer carries: dropped, as upstream does.
            val chapter = chapters[history.url] ?: return@forEach
            val existing = stored[chapter.id]

            val readAt: Long
            val duration: Long
            if (existing == null) {
                readAt = history.lastRead
                duration = history.readDuration
            } else {
                readAt = max(history.lastRead, existing.readAt?.time ?: 0L)
                // The upsert adds to the stored duration, so the delta is what reaches the maximum.
                duration = max(history.readDuration, existing.readDuration) - existing.readDuration
            }
            writer.upsertHistory(chapter.id, readAt, duration)
            run.historyWritten++
        }
    }

    /**
     * Ends the run: the sync flag, then its report and the checkpoint cleared unless it was
     * cancelled. The flag commits while the checkpoint and the import file are still there, so a
     * kill between the two leaves a run that resumes with the flag set, never a cleared checkpoint
     * and no flag. A commit the preference file refuses is thrown once the report is written.
     * [failure] is the cancel being thrown, if any; see [markRestored].
     */
    private suspend fun finish(run: Run, failure: Throwable? = null): ImportReport = withContext(NonCancellable) {
        run.flushErrors()
        val report = run.toReport(readErrorLog())
        // A cancelled run has written rows too. Sync's next run pulls again from the start and
        // makes a full export, since a restore's merges into existing rows are never logged.
        val refused = if (failure != null) {
            markRestored(failure)
            null
        } else {
            try {
                markRestored(failure = null)
                null
            } catch (io: IOException) {
                io
            }
        }
        if (!run.cancelled) {
            run.phase = ImportPhase.DONE
            checkpoints.clear()
            checkpoints.importFile.delete()
        }
        writeReport(report)
        refused?.let { throw it }
        report
    }

    /**
     * Commits the sync flag. With [failure], the exception the run is ending with, a commit the
     * preference file refuses is added to it as suppressed, so the run still throws its own failure
     * (a cancel stays a cancel); without one, the refused commit throws itself.
     */
    private fun markRestored(failure: Throwable?) {
        if (failure == null) {
            syncRestorePreferences.markRestored()
            return
        }
        try {
            syncRestorePreferences.markRestored()
        } catch (io: IOException) {
            failure.addSuppressed(io)
        }
    }

    private fun saveCheckpoint(run: Run, cursor: Int) {
        checkpoints.save(
            ImportCheckpoint(
                fileSha256 = run.fileSha256,
                fileSize = run.fileSize,
                options = run.optionFlags,
                phase = run.phase,
                mangaCursor = cursor,
                mangaTotal = run.mangaTotal,
                startedAt = run.startedAt,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun readErrorLog(): List<ImportError> {
        val log = checkpoints.errorLog
        if (!log.exists()) return emptyList()
        return log.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                json.decodeFromString(ImportError.serializer(), line)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun writeReport(report: ImportReport) {
        val file = checkpoints.reportFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ImportReport.serializer(), report))
    }

    private inline fun step(
        run: Run,
        kind: ImportError.Kind,
        subject: String,
        sourceLabel: String?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            run.record(kind, subject, sourceLabel, e)
        }
    }

    /** Everything one run accumulates. Not a field of the importer: the class is app-scoped. */
    private inner class Run(
        val fileSha256: String,
        val fileSize: Long,
        val optionFlags: List<Boolean>,
        val startedAt: Long,
        val startCursor: Int,
        val resumed: Boolean,
    ) {
        var phase: ImportPhase = ImportPhase.CATEGORIES
        var cancelled: Boolean = false

        /** Whether a chunk, or a row of a fallen-back chunk, of this attempt has committed. */
        var entriesWritten: Boolean = false
        var mangaTotal: Int = 0
        var mangaInserted: Int = 0
        var mangaMerged: Int = 0
        var mangaFailed: Int = 0
        var chaptersWritten: Int = 0
        var historyWritten: Int = 0
        var tracksWritten: Int = 0
        var categoriesCreated: Int = 0
        var preferencesWritten: Int = 0
        var sourcePreferenceFilesWritten: Int = 0
        var extensionStoresUpserted: Int = 0

        var sourceLabels: Map<Long, String> = emptyMap()
        var unsupportedTrackers: List<Long> = emptyList()
        var trackersNotSignedIn: List<Long> = emptyList()
        val unknownTrackers: MutableSet<Long> = mutableSetOf()
        val stubSourcesRemembered: MutableList<Long> = mutableListOf()
        val missingSources: MutableList<Long> = mutableListOf()
        val categoryIdsByBackupOrder: MutableMap<Long, MutableList<Long>> = mutableMapOf()

        /** The entries, by index among the file's entries, that do not decode; the header lists them. */
        var undecodable: Set<Int> = emptySet()
        val categoryIdByBackupId: MutableMap<Long, Long> = mutableMapOf()

        /** Errors of the current batch. Appended to the log and cleared by [flushErrors]. */
        val errors: MutableList<ImportError> = mutableListOf()

        fun labelOf(sourceId: Long): String = sourceLabels[sourceId] ?: sourceId.toString()

        /**
         * The cursor once [decoded] more entries are read from [cursor]: the entry list passes over
         * the undecodable entries, which the cursor counts all the same.
         */
        fun frameAfter(cursor: Int, decoded: Int): Int {
            var frame = cursor
            var left = decoded
            while (left > 0) {
                if (frame !in undecodable) left--
                frame++
            }
            return frame
        }

        /** Counts and reports each undecodable entry from [from] up to [until]. */
        fun passOver(from: Int, until: Int) {
            undecodable.filter { it in from until until }.sorted().forEach { index ->
                mangaFailed++
                record(
                    kind = ImportError.Kind.MANGA_ROW,
                    subject = "Entry ${index + 1} of $mangaTotal",
                    sourceLabel = null,
                    message = "This entry could not be read, so it was skipped",
                )
            }
        }

        fun record(kind: ImportError.Kind, subject: String, sourceLabel: String?, error: Throwable) {
            record(kind, subject, sourceLabel, error.message ?: error::class.simpleName.orEmpty())
        }

        fun record(kind: ImportError.Kind, subject: String, sourceLabel: String?, message: String) {
            errors += ImportError(
                kind = kind,
                subject = subject,
                sourceLabel = sourceLabel,
                message = message,
                at = Clock.System.now().toEpochMilliseconds(),
            )
        }

        /**
         * Appends the batch's errors to the log. A resumed run keeps the errors of the attempt
         * before it, which is why the final report reads the file instead of this list.
         */
        fun flushErrors() {
            if (errors.isEmpty()) return
            val log = checkpoints.errorLog
            log.parentFile?.mkdirs()
            log.appendText(
                errors.joinToString(separator = "") {
                    json.encodeToString(ImportError.serializer(), it) + "\n"
                },
            )
            errors.clear()
        }

        fun tally(): IntArray = intArrayOf(
            mangaInserted, mangaMerged, mangaFailed, chaptersWritten, historyWritten, tracksWritten,
        )

        fun restore(tally: IntArray) {
            mangaInserted = tally[0]
            mangaMerged = tally[1]
            mangaFailed = tally[2]
            chaptersWritten = tally[3]
            historyWritten = tally[4]
            tracksWritten = tally[5]
        }

        fun toReport(errorLog: List<ImportError>) = ImportReport(
            fileSha256 = fileSha256,
            startedAt = startedAt,
            finishedAt = Clock.System.now().toEpochMilliseconds(),
            cancelled = cancelled,
            resumed = resumed,
            mangaTotal = mangaTotal,
            mangaInserted = mangaInserted,
            mangaMerged = mangaMerged,
            mangaFailed = mangaFailed,
            chaptersWritten = chaptersWritten,
            historyWritten = historyWritten,
            tracksWritten = tracksWritten,
            categoriesCreated = categoriesCreated,
            preferencesWritten = preferencesWritten,
            sourcePreferenceFilesWritten = sourcePreferenceFilesWritten,
            extensionStoresUpserted = extensionStoresUpserted,
            stubSourcesRemembered = stubSourcesRemembered.toList(),
            unsupportedTrackers = unsupportedTrackers,
            unknownTrackers = unknownTrackers.sorted(),
            errors = errorLog,
            trackersNotSignedIn = trackersNotSignedIn,
            missingSources = missingSources.distinct(),
        )
    }

    private inner class MangaContext(
        val options: RestoreOptions,
        val run: Run,
        val timeZone: TimeZone,
        val dateTime: LocalDateTime,
        val fetchWindow: Pair<Long, Long>,
    )

    companion object {
        const val MANGA_BATCH_SIZE = 100

        /** Decoded chunks waiting for the writer, beyond the one it is committing. */
        const val DECODE_AHEAD = 2
        private const val STORE_BATCH_SIZE = 100

        /**
         * Upstream's `restoreExistingManga` plus `Manga.copyFrom`. Metadata follows the higher
         * version and ties keep the database row, because the row is what the user is looking at.
         * Every column the backup format has no field for keeps the database value.
         */
        internal fun mergeManga(db: Manga, backup: Manga): Manga {
            val fromBackup = backup.version > db.version
            return db.copy(
                favorite = db.favorite || backup.favorite,
                initialized = db.initialized || backup.initialized,
                author = if (fromBackup) backup.author else db.author,
                artist = if (fromBackup) backup.artist else db.artist,
                description = if (fromBackup) backup.description else db.description,
                genre = if (fromBackup) backup.genre else db.genre,
                thumbnailUrl = if (fromBackup) backup.thumbnailUrl else db.thumbnailUrl,
                status = if (fromBackup) backup.status else db.status,
                version = max(db.version, backup.version),
                // The favourite clock stays the row's, as the update stores it (it binds no such
                // column): upstream's files carry seconds, and this clock is milliseconds that the sync
                // merge compares. A favourite the merge turns on is stamped by the row's trigger.
                favoriteModifiedAt = db.favoriteModifiedAt,
                // The undelete is a separate write that follows this one, so the merged row
                // keeps whatever it read.
                deletedAt = db.deletedAt,
            )
        }

        /**
         * An override from the file fills only one the row does not have. A stored override is the
         * owner's edit and is kept, an empty description included: the editor stores one when the
         * owner clears the source's text. A blank field in the file is no edit.
         */
        internal fun mergeOverride(stored: String?, fromFile: String?): String? =
            stored ?: fromFile?.takeUnless { it.isBlank() }

        /** True when the two rows differ only in fields a restore is not allowed to move. */
        internal fun isUnchanged(backup: Chapter, db: Chapter): Boolean =
            backup.copy(id = 0L, mangaId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L) ==
                db.copy(id = 0L, mangaId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L)

        /**
         * `copyFrom` takes name, url, dateUpload, chapterNumber, scanlator and volume from the
         * database row, so a source rename survives a restore. Read state, bookmarks and page
         * progress only ever move forward.
         *
         * Each of those three moves forward on its own axis, which is the one place this departs
         * from contract 06 section 1.4.3 and from upstream's `restoreExistingManga`. Their two-branch
         * rule resolves the page only when one side is at zero or one side is fully read, so two
         * sides that have both advanced kept the backup's page: a device on page 50 that imported
         * a file written on page 3 went back to page 3. `max` is the same resolution `version` and
         * `bookmark` already use, it is idempotent, and it agrees with the old rule everywhere the
         * old rule had an answer.
         */
        internal fun mergeChapter(backup: Chapter, db: Chapter): Chapter =
            backup.copyFrom(db).copy(
                id = db.id,
                read = backup.read || db.read,
                bookmark = backup.bookmark || db.bookmark,
                lastPageRead = max(backup.lastPageRead, db.lastPageRead),
                version = max(backup.version, db.version),
                deletedAt = db.deletedAt,
            )

        /**
         * Contract 06 section 5.1's track rule, as phase 3 decision Q6 completes it:
         * `lastChapterRead` by max, and every other field (status, score, both dates, privacy,
         * title, total chapters, remote url, remote id and library id) from the side that read
         * further, since that side knows the tracker's entry as it is now. On a tie the database
         * row is kept whole, as upstream's `4b48a84ec` keeps it (triage E86): the importer used to
         * take the backup's remote id and library id onto the row's own title and url, which bound
         * the row to one remote entry by id and another by name, so updates went to an entry the
         * sheet did not show.
         */
        internal fun mergeTrack(backup: Track, db: Track): Track {
            val winner = if (backup.lastChapterRead > db.lastChapterRead) backup else db
            return winner.copy(
                id = db.id,
                mangaId = db.mangaId,
                trackerId = db.trackerId,
                lastChapterRead = max(backup.lastChapterRead, db.lastChapterRead),
            )
        }
    }
}
