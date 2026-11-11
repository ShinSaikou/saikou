package com.shinsaikou

import com.shinsaikou.appupdate.AppUpdateWorker
import com.shinsaikou.ui.appupdate.AppUpdateViewModel
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.ui.backup.ImportReportViewModel
import com.shinsaikou.ui.browse.extension.MissingExtensions
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.downloads.export.DownloadExportWorker
import com.shinsaikou.storage.StorageMoveWorker
import com.shinsaikou.extensionupdate.ExtensionUpdateWorker
import com.shinsaikou.storage.StorageNotifier
import com.shinsaikou.ui.backup.KeptBackupCopyViewModel
import com.shinsaikou.ui.storage.StorageLocationViewModel
import com.shinsaikou.reader.SavedPictures
import com.shinsaikou.ui.manga.MangaCoverActions
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.ui.settings.AdoptionSummaryViewModel
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.shinsaikou.backup.export.BackupExportWorker
import com.shinsaikou.backup.importer.BackupImportWorker
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.data.sync.SyncStoreSnapshotImpl
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.library.interactor.RebuildLibraryCounts
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.AutoDownloadRules
import com.shinsaikou.downloads.DownloadWorker
import com.shinsaikou.downloads.DownloadWriteLock
import com.shinsaikou.downloads.FilesDirDownloadRoot
import com.shinsaikou.downloads.FolderDownloadManifest
import com.shinsaikou.downloads.LocatedDownloadedChapters
import com.shinsaikou.downloads.MigrationDownloads
import com.shinsaikou.downloads.OrphanDownloads
import com.shinsaikou.downloads.ReadChapterCleanupRules
import com.shinsaikou.downloads.WorkManagerDownloadQueue
import com.shinsaikou.downloads.adoption.AdoptionWorker
import com.shinsaikou.downloads.adoption.LibArchiveChapterArchives
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.libraryupdate.LibraryUpdateWorker
import com.shinsaikou.migration.MigrateManga
import com.shinsaikou.notification.AppDownloadIntents
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.state.ProgressCommitter
import com.shinsaikou.signin.SignInHoldWorker
import com.shinsaikou.sync.CommittingSecretWriter
import com.shinsaikou.sync.KeystoreSecretStore
import com.shinsaikou.sync.SyncExpiryNoticeWorker
import com.shinsaikou.sync.SyncWorker
import com.shinsaikou.tracking.DelayedTrackingUpdateWorker
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackProgressRecorder
import com.shinsaikou.tracking.WorkManagerDelayedTrackingScheduler
import com.shinsaikou.tracking.anilist.Anilist
import com.shinsaikou.tracking.comick.Comick
import com.shinsaikou.tracking.comick.ComickApi
import com.shinsaikou.tracking.mangabaka.MangaBaka
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListClient
import com.shinsaikou.ui.backup.BackupViewModel
import com.shinsaikou.ui.browse.ExtensionStoresViewModel
import com.shinsaikou.ui.browse.ExtensionsViewModel
import com.shinsaikou.ui.browse.SourcesViewModel
import com.shinsaikou.ui.browse.detail.ExtensionDetailViewModel
import com.shinsaikou.ui.browse.extension.ExtensionFilterViewModel
import com.shinsaikou.ui.browse.global.GlobalSearchViewModel
import com.shinsaikou.ui.browse.settings.SourceSettingsViewModel
import com.shinsaikou.ui.browse.source.SourceBrowseViewModel
import com.shinsaikou.ui.browse.source.SourcesFilterViewModel
import com.shinsaikou.ui.category.CategoriesViewModel
import com.shinsaikou.ui.crash.CrashLogViewModel
import com.shinsaikou.ui.download.DownloadQueueViewModel
import com.shinsaikou.ui.history.HistoryViewModel
import com.shinsaikou.ui.library.LibraryViewModel
import com.shinsaikou.ui.main.MainViewModel
import com.shinsaikou.ui.manga.MangaDetailViewModel
import com.shinsaikou.ui.manga.TrackSheetViewModel
import com.shinsaikou.ui.migration.MigrateDialogViewModel
import com.shinsaikou.ui.migration.config.MigrationConfigViewModel
import com.shinsaikou.ui.migration.list.MigrationListViewModel
import com.shinsaikou.ui.migration.picker.MigratePickerViewModel
import com.shinsaikou.ui.migration.sources.MigrateSourcesViewModel
import com.shinsaikou.ui.settings.SettingsViewModel
import com.shinsaikou.ui.settings.SyncSettingsViewModel
import com.shinsaikou.ui.settings.TrackingSettingsViewModel
import com.shinsaikou.ui.stats.StatsScreenState
import com.shinsaikou.ui.stats.StatsViewModel
import com.shinsaikou.ui.storage.ClearDatabaseViewModel
import com.shinsaikou.ui.storage.StorageDownloadsViewModel
import com.shinsaikou.ui.tracking.TrackerCompareViewModel
import com.shinsaikou.ui.updates.UpdatesViewModel
import com.shinsaikou.ui.webview.WebViewViewModel
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The one test that asks the whole application graph for everything it holds at once.
 *
 * Metro validates lazily. A binding nothing requests is never checked, which is how a build stays
 * green while a provider no accessor names is missing or broken, and it is why [AppGraph] declares
 * one root per subsystem instead of only the roots `:app` happens to call. Nothing reads those
 * roots until something does; this is that something.
 *
 * It cannot run on the JVM. The factory takes a real `Application`, and the singletons behind the
 * roots open the SQLite file, build the OkHttp client and the Coil loader, and reach WorkManager
 * and Injekt, none of which exist on the unit test classpath. Contract 01-build 8.1 states the same
 * conclusion: the graph smoke is instrumentation because `createGraphFactory` needs Android.
 *
 * Everything is asserted against the process's own graph, `SaikouApp.graph`. The app under test
 * already built one in `onCreate` and its singletons own process-wide resources: the database file,
 * the Coil disk cache directory, the OkHttp cache directory, the crash handler. A second graph that
 * resolved those would be a second owner of each, in one process, over the same paths. So the fresh
 * graph the contract calls for is built once, in [theGraphFactoryBuildsAnEquivalentGraph], and only
 * its multibinding maps are read: those hold providers, and constructing them constructs no
 * singleton.
 */
@RunWith(AndroidJUnit4::class)
class AppGraphTest {

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    @Test
    fun theGraphExposesItsRoots() {
        assertNotNull(graph.appInfo)
        assertNotNull(graph.viewModelFactory)
        assertTrue("AppInfo resolved no app name", graph.appInfo.name.isNotBlank())
        assertTrue("AppInfo resolved no version name", graph.appInfo.versionName.isNotBlank())
    }

    /**
     * The warm-up set of `SaikouApp.onCreate`, resolved on the same dispatcher it uses. What is
     * under test is that resolution returns rather than throws: a binding that is absent, cyclic or
     * whose construction fails on a device surfaces here and nowhere earlier.
     */
    @Test
    fun theEagerSingletonsResolveOnIo() {
        runBlocking(Dispatchers.IO) {
            assertNotNull(graph.networkHelper)
            assertNotNull(graph.database)
            assertNotNull(graph.databaseInitializer)
            assertNotNull(graph.injektBootstrap)
            assertNotNull(graph.extensionManager)
            assertNotNull(graph.sourceManager)
            assertNotNull(graph.localSource)
        }
    }

    /**
     * Every failure is collected before the test fails, because one broken binding is usually a
     * whole subsystem that never merged, and a report naming the first accessor alone would hide
     * the rest of them behind a second run.
     */
    @Test
    fun everyGraphAccessorResolves() {
        val failures = runBlocking(Dispatchers.IO) {
            graphAccessors().mapNotNull { accessor ->
                try {
                    if (accessor.invoke(graph) == null) "${accessor.name} resolved to null" else null
                } catch (error: InvocationTargetException) {
                    "${accessor.name} threw ${error.cause}"
                }
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun viewModelProvidersHoldEveryScreenViewModel() {
        val providers = graph.viewModelProviders
        val missing = SCREEN_VIEW_MODELS.filterNot { providers.containsKey(it) }
        assertEquals("view models absent from the map", emptyList<KClass<out ViewModel>>(), missing)
    }

    @Test
    fun manualAssistedFactoryProvidersHoldEveryArgumentTakingScreen() {
        val providers = graph.manualAssistedFactoryProviders
        val missing = MANUAL_ASSISTED_FACTORIES.filterNot { providers.containsKey(it) }
        assertEquals("assisted factories absent from the map", emptyList<KClass<*>>(), missing)

        // The map is keyed by the factory class rather than by the view model it builds, so a
        // factory filed under another key would be present and still be the wrong binding.
        for (key in MANUAL_ASSISTED_FACTORIES) {
            val factory = providers.getValue(key).invoke()
            assertTrue(
                "${key.java.name} resolved to ${factory.javaClass.name}",
                key.java.isInstance(factory),
            )
        }
    }

    /**
     * The reader's view model is not in [AppGraph.manualAssistedFactoryProviders] and cannot be.
     * Its factory is a plain Metro assisted factory on a graph extension scoped to one open reader,
     * and the app graph hands out that extension's factory instead (ruling R22). Creating the
     * extension here is the only point at which `:app` ever checks the reader's own bindings.
     */
    @Test
    fun theReaderGraphExtensionResolvesItsViewModelFactory() {
        val readerGraph = app.readerGraphFactory.createReaderGraph()
        assertNotNull(readerGraph.readerViewModelFactory)
        assertNotNull(readerGraph.chapterWebActions)
        assertNotNull(readerGraph.pageImageActions)
        // The reader's banner is only honest if it reads the coordinator the network stack writes.
        assertSame(graph.cloudflareSolveCoordinator, readerGraph.cloudflareSolveCoordinator)
    }

    /**
     * The download engine's three bindings, resolved through the process's own graph. Each has to
     * be the engine's implementation and one instance per process: a second queue would hold its
     * own view of the worker, and a second manifest its own locator. The root has to be the folder
     * the backup rules exclude.
     */
    @Test
    fun theDownloadBindingsResolveToTheEngine() {
        runBlocking(Dispatchers.IO) {
            assertTrue(
                "DownloadQueue resolved to ${graph.downloadQueue.javaClass.name}",
                graph.downloadQueue is WorkManagerDownloadQueue,
            )
            assertTrue(
                "DownloadManifest resolved to ${graph.downloadManifest.javaClass.name}",
                graph.downloadManifest is FolderDownloadManifest,
            )
            assertTrue(
                "DownloadRoot resolved to ${graph.downloadRoot.javaClass.name}",
                graph.downloadRoot is FilesDirDownloadRoot,
            )
            assertSame(graph.downloadQueue, graph.downloadQueue)
            assertSame(graph.downloadManifest, graph.downloadManifest)
            assertEquals(File(app.filesDir, "downloads"), graph.downloadRoot.root())
            assertNotNull(graph.downloadManifestSweep)
        }
    }

    /**
     * The folder-aware answer `:reader` asks for through `:domain` and `:downloads` binds. The
     * reader's resolver is built inside its graph extension, so the extension is created here too:
     * a resolver that could not be built would otherwise surface only when a chapter opened.
     */
    @Test
    fun theDownloadedChaptersBindingResolvesToTheLocator() {
        runBlocking(Dispatchers.IO) {
            assertTrue(
                "DownloadedChapters resolved to ${graph.downloadedChapters.javaClass.name}",
                graph.downloadedChapters is LocatedDownloadedChapters,
            )
            assertSame(graph.downloadedChapters, graph.downloadedChapters)
            assertNotNull(app.readerGraphFactory.createReaderGraph().readerViewModelFactory)
        }
    }

    /**
     * The intent the download notifications carry. `DownloadNotifier` is built inside the worker,
     * so without this a missing binding would surface only when a download started.
     */
    @Test
    fun theDownloadIntentsBindingOpensTheQueue() {
        assertTrue(
            "DownloadIntents resolved to ${graph.downloadIntents.javaClass.name}",
            graph.downloadIntents is AppDownloadIntents,
        )
        assertSame(graph.downloadIntents, graph.downloadIntents)
        assertNotNull(graph.downloadIntents.openQueue())
    }

    /**
     * Auto-download (phase 3 unit AD): one binding, `:downloads`' rules, and the one instance the
     * library update, the sync engine and the reader's committer each hold. `:library-update`,
     * `:sync` and `:reader` reach it through `:domain` alone, so a reporter built with another
     * instance, or none, would show only here.
     */
    @Test
    fun theAutoDownloadBindingIsTheOneEveryReporterHolds() {
        runBlocking(Dispatchers.IO) {
            val rules = graph.autoDownload
            assertTrue("AutoDownload resolved to ${rules.javaClass.name}", rules is AutoDownloadRules)
            assertSame(rules, graph.autoDownload)
            assertSame(rules, fieldOfType(graph.libraryUpdate, AutoDownload::class.java))
            assertSame(rules, fieldOfType(graph.syncEngine, AutoDownload::class.java))
        }
        val readerGraph = app.readerGraphFactory.createReaderGraph()
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var reader: ReaderViewModel? = null
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    readerGraph.readerViewModelFactory.create(SavedStateHandle()) as T
            }
            reader = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        }
        try {
            val committer = fieldOfType(checkNotNull(reader), ProgressCommitter::class.java)
            assertSame(graph.autoDownload, fieldOfType(committer, AutoDownload::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * Delete after reading (phase 3 unit DR): one binding, `:downloads`' rules, and the one instance
     * `SetReadStatus` and the reader's committer each hold. `:domain` and `:reader` reach it through
     * `:domain`'s interface alone, so a caller built with another instance would show only here.
     */
    @Test
    fun theReadChapterCleanupBindingIsTheOneTheReaderAndSetReadStatusHold() {
        runBlocking(Dispatchers.IO) {
            val rules = graph.readChapterCleanup
            assertTrue("ReadChapterCleanup resolved to ${rules.javaClass.name}", rules is ReadChapterCleanupRules)
            assertSame(rules, graph.readChapterCleanup)
            assertSame(rules, fieldOfType(graph.setReadStatus, ReadChapterCleanup::class.java))
        }
        val readerGraph = app.readerGraphFactory.createReaderGraph()
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var reader: ReaderViewModel? = null
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    readerGraph.readerViewModelFactory.create(SavedStateHandle()) as T
            }
            reader = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        }
        try {
            val committer = fieldOfType(checkNotNull(reader), ProgressCommitter::class.java)
            assertSame(graph.readChapterCleanup, fieldOfType(committer, ReadChapterCleanup::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The periodic library update armed through the graph's scheduler with Only on Wi-Fi set (phase
     * 3 unit AD). From API 28 WorkManager keeps the network request and hands it to JobScheduler, so
     * the request must carry the Wi-Fi transport in both, which it did not before this unit; below
     * 28 WorkManager keeps the network type alone, which is what upstream gets there too. The owner's
     * two settings are put back and the work re-armed or cancelled to match them.
     */
    @Test
    fun thePeriodicLibraryUpdateCarriesTheWifiRequestWhenOnlyOnWifiIsSet() {
        val preferences = graph.libraryPreferences
        val interval = preferences.autoUpdateInterval.get()
        val restrictions = preferences.autoUpdateDeviceRestrictions.get()
        val workManager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            preferences.autoUpdateInterval.set(12)
            preferences.autoUpdateDeviceRestrictions.set(setOf("wifi"))
            graph.libraryUpdateScheduler.setup()

            val deadline = System.currentTimeMillis() + 10_000L
            var constraints = workManager.getWorkInfosForUniqueWork(LibraryUpdateWorker.WORK_NAME_AUTO).get()
                .singleOrNull { !it.state.isFinished }?.constraints
            while (constraints == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                constraints = workManager.getWorkInfosForUniqueWork(LibraryUpdateWorker.WORK_NAME_AUTO).get()
                    .singleOrNull { !it.state.isFinished }?.constraints
            }
            checkNotNull(constraints) { "the periodic library update was not armed" }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val request = checkNotNull(constraints.requiredNetworkRequest) { "the work kept no network request" }
                assertTrue(
                    "the work's network request has no Wi-Fi transport: $request",
                    request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                )
                val jobs = shell("dumpsys jobscheduler ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}")
                val wifiLines = jobs.lines().filter { "NetworkRequest" in it && "WIFI" in it }
                Log.i("SaikouAutoDownload", "jobscheduler network lines: ${wifiLines.size}")
                wifiLines.forEach { Log.i("SaikouAutoDownload", "jobscheduler: ${it.trim()}") }
                assertTrue("no scheduled job of this app requires a Wi-Fi network", wifiLines.isNotEmpty())
            } else {
                assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
            }
        } finally {
            preferences.autoUpdateInterval.set(interval)
            preferences.autoUpdateDeviceRestrictions.set(restrictions)
            graph.libraryUpdateScheduler.setup()
        }
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    /**
     * The adoption of upstream's downloads, resolved through the process's own graph: one of each, the
     * archive check bound to the libarchive reader the reader itself opens archives with, and the
     * adoption notifications' intent, which `DownloadNotifier` builds only once a run starts. The
     * write lock the copier shares with the download engine is one instance, or each would hold
     * its own and nothing would keep the two off one chapter.
     */
    @Test
    fun theAdoptionBindingsResolve() {
        assertSame(graph.downloadWriteLock, graph.downloadWriteLock)
        assertSame(graph.downloadAdoption, graph.downloadAdoption)
        assertSame(graph.adoptionScheduler, graph.adoptionScheduler)
        assertSame(graph.adoptionSummaryStore, graph.adoptionSummaryStore)
        assertTrue(
            "ChapterArchives resolved to ${graph.chapterArchives.javaClass.name}",
            graph.chapterArchives is LibArchiveChapterArchives,
        )
        assertEquals(File(app.filesDir, "adoption/last-summary.json"), graph.adoptionSummaryStore.file)
        assertNotNull(graph.downloadIntents.openDownloadSettings())
    }

    /**
     * The storage location's bindings (unit storage-location), one of each where they are
     * singletons, with no location set on a fresh install: the status says so, no folder is handed
     * out, and the Local source still reads the app folder. The move's notification registers its
     * channel, since WorkManager posts it through `startForeground`.
     */
    @Test
    fun theStorageLocationBindingsResolve() {
        assertSame(graph.storageLocation, graph.storageLocation)
        assertSame(graph.storageMove, graph.storageMove)
        assertSame(graph.storageMoveScheduler, graph.storageMoveScheduler)
        assertSame(graph.downloadExport, graph.downloadExport)
        assertSame(graph.downloadExportScheduler, graph.downloadExportScheduler)
        assertNotNull(graph.backupFileCopy)
        if (graph.storagePreferences.storageLocation.get().isBlank()) {
            assertEquals(StorageLocation.Status.NOT_SET, graph.storageLocation.status())
            assertNull(graph.storageLocation.folder(StorageLocation.AUTOBACKUP))
        }
        val notification = graph.storageNotifier.progressNotification(null)
        assertEquals(StorageNotifier.CHANNEL, notification.channelId)
    }

    /**
     * The export's library reader (phase 5 unit SC2) is a constructor binding of the exporter, so the
     * exporter resolves it from the graph, and its paged statements run on the device's SQLite: an
     * export of whatever this install holds is staged, read back and copied to a file.
     */
    @Test
    fun theBackupExporterStagesThroughItsLibraryReader() {
        runBlocking(Dispatchers.IO) {
            val target = File(app.cacheDir, "app-graph-export.tachibk")
            try {
                val bytes = graph.backupExporter.build(BackupOptions()).use { staged ->
                    graph.backupExporter.write(staged, target.sink())
                }
                assertTrue("the export wrote $bytes bytes", bytes > 0 && target.length() == bytes)
            } finally {
                target.delete()
            }
        }
    }

    /**
     * Sync's bindings (phase 3 units S, E2 and F), one of each per process: a second secret store would be
     * harmless, but a second client would hold its own copy of what it took out of the shared one.
     * The client has to be derived from the graph's own `NetworkHelper`, which still carries the
     * challenge solver the sync client drops.
     */
    @Test
    fun theSyncBindingsResolve() {
        runBlocking(Dispatchers.IO) {
            assertTrue(
                "SecretStore resolved to ${graph.secretStore.javaClass.name}",
                graph.secretStore is KeystoreSecretStore,
            )
            assertSame(graph.secretStore, graph.secretStore)
            // The store's writes commit (fix round 1 item 5).
            assertTrue(graph.secretWriter is CommittingSecretWriter)
            assertSame(graph.syncHttpClient, graph.syncHttpClient)
            val sync = graph.syncHttpClient.client
            assertTrue(graph.networkHelper.client.interceptors.any { it is CloudflareInterceptor })
            assertTrue(sync.interceptors.none { it is CloudflareInterceptor })
            assertSame(graph.networkHelper.client.connectionPool, sync.connectionPool)
        }
        // The WebDAV sign-in (unit F) over the same secret store and client, one per process.
        assertSame(graph.webDavAccount, graph.webDavAccount)
        // The Drive sign-in (unit H) holds the access token in memory and refreshes it under one
        // lock, so a second account would refresh beside the first. With no stored sign-in it
        // builds no backend.
        assertSame(graph.driveAccount, graph.driveAccount)
        runBlocking(Dispatchers.IO) {
            if (graph.driveAccount.signedInAs() == null) assertNull(graph.driveAccount.backend())
        }
        // The engine holds the process-wide lock runs take (Q10), so a second engine would let two
        // runs overlap; its store is :data's.
        assertSame(graph.syncEngine, graph.syncEngine)
        // The importer sets the restore flag and the engine clears it: one instance for both.
        assertSame(graph.syncRestorePreferences, graph.syncRestorePreferences)
        assertTrue(
            "SyncStore resolved to ${graph.syncStore.javaClass.name}",
            graph.syncStore is SyncStoreImpl,
        )
        // The fingerprint's read snapshot (phase 5 unit SC6) is :data's, a connection of its own per read.
        assertTrue(
            "SyncStoreSnapshot resolved to ${graph.syncStoreSnapshot.javaClass.name}",
            graph.syncStoreSnapshot is SyncStoreSnapshotImpl,
        )
        // The worker's collaborators (unit G): the session's lock is what keeps a run and a
        // sign-out apart, and the scheduler, triggers, record and preferences are one of each too.
        assertSame(graph.syncSession, graph.syncSession)
        assertSame(graph.syncScheduler, graph.syncScheduler)
        assertSame(graph.syncTriggers, graph.syncTriggers)
        assertSame(graph.syncRunRecord, graph.syncRunRecord)
        assertSame(graph.syncWorkPreferences, graph.syncWorkPreferences)
        // Sync's notices (fixes unit sync-notices): one lock orders a run's notice and a sign-out's.
        assertSame(graph.syncNotices, graph.syncNotices)
    }

    /**
     * The app lock (phase 4 unit AL): one per process, since `UnlockActivity` unlocks the instance
     * `SaikouApp` installed on the process lifecycle and the activity callbacks read.
     */
    @Test
    fun theAppLockIsOnePerProcess() {
        assertSame(graph.appLock, graph.appLock)
    }

    /**
     * The tracker progress push (phase 3 unit T1). The hook is one `@ContributesBinding` from
     * `:tracking`, never a set that could resolve empty (R26); the scheduler is `:app`'s over
     * WorkManager; the store is one per process, since its lock is what keeps a push raised during
     * a send from being removed with it; and the client is derived from the graph's own
     * `NetworkHelper`, which still carries the challenge solver the tracker client drops. The
     * manager holds MyAnimeList (phase 3 unit T2), AniList (R162 item 8), MangaBaka and Comick, by
     * id and class, in id order. MyAnimeList is configured exactly when the build carries its client
     * id, with no test base set, and on the fake's client once one is, with every address moved;
     * AniList exactly when the build carries its own, whose page asks AniList for a token on the
     * trackers' loopback; MangaBaka exactly when the build carries its own, whose page asks MangaBaka
     * for a code on that loopback with an S256 challenge; and Comick the same way, its page also
     * naming the library API as the resource.
     */
    @Test
    fun theTrackingBindingsResolve() {
        runBlocking(Dispatchers.IO) {
            assertTrue(
                "TrackProgress resolved to ${graph.trackProgress.javaClass.name}",
                graph.trackProgress is TrackProgressRecorder,
            )
            assertTrue(
                "DelayedTrackingScheduler resolved to ${graph.delayedTrackingScheduler.javaClass.name}",
                graph.delayedTrackingScheduler is WorkManagerDelayedTrackingScheduler,
            )
            assertSame(graph.delayedTrackingStore, graph.delayedTrackingStore)
            assertSame(graph.trackerHttpClient, graph.trackerHttpClient)
            val tracker = graph.trackerHttpClient.client
            assertTrue(graph.networkHelper.client.interceptors.any { it is CloudflareInterceptor })
            assertTrue(tracker.interceptors.none { it is CloudflareInterceptor })
            assertSame(graph.networkHelper.client.connectionPool, tracker.connectionPool)
            assertNotNull(graph.delayedTrackingUpdate)
            assertNotNull(graph.addTracks)
            assertEquals(
                listOf(TrackerIds.MYANIMELIST, TrackerIds.ANILIST, TrackerIds.MANGABAKA, TrackerIds.COMICK),
                graph.trackerManager.trackers.map { it.id },
            )
            val myAnimeList = graph.trackerManager.get(TrackerIds.MYANIMELIST)
            assertTrue("tracker 1 is ${myAnimeList?.javaClass?.name}", myAnimeList is MyAnimeList)
            val anilist = graph.trackerManager.get(TrackerIds.ANILIST)
            assertTrue("tracker 2 is ${anilist?.javaClass?.name}", anilist is Anilist)
            assertEquals(BuildConfig.ANILIST_CLIENT_ID.isNotBlank(), (anilist as Anilist).isConfigured)
            val anilistPage = anilist.authorizationUrl(REDIRECT, "state", Pkce.generate())
            assertEquals("https://anilist.co/api/v2/oauth/authorize", anilistPage.newBuilder().query(null).build().toString())
            assertEquals(BuildConfig.ANILIST_CLIENT_ID, anilistPage.queryParameter("client_id"))
            assertEquals("token", anilistPage.queryParameter("response_type"))
            assertNull(anilistPage.queryParameter("redirect_uri"))
            val mangaBaka = graph.trackerManager.get(TrackerIds.MANGABAKA)
            assertTrue("tracker 11 is ${mangaBaka?.javaClass?.name}", mangaBaka is MangaBaka)
            assertEquals(BuildConfig.MANGABAKA_CLIENT_ID.isNotBlank(), (mangaBaka as MangaBaka).isConfigured)
            val mangaBakaPkce = Pkce.generate()
            val mangaBakaPage = mangaBaka.authorizationUrl(REDIRECT, "state", mangaBakaPkce)
            assertEquals("https://mangabaka.org/auth/oauth2/authorize", mangaBakaPage.newBuilder().query(null).build().toString())
            assertEquals(BuildConfig.MANGABAKA_CLIENT_ID, mangaBakaPage.queryParameter("client_id"))
            assertEquals("code", mangaBakaPage.queryParameter("response_type"))
            assertEquals(REDIRECT, mangaBakaPage.queryParameter("redirect_uri"))
            assertEquals(mangaBakaPkce.challenge, mangaBakaPage.queryParameter("code_challenge"))
            assertEquals("S256", mangaBakaPage.queryParameter("code_challenge_method"))
            val comick = graph.trackerManager.get(TrackerIds.COMICK)
            assertTrue("tracker 12 is ${comick?.javaClass?.name}", comick is Comick)
            assertEquals(BuildConfig.COMICK_CLIENT_ID.isNotBlank(), (comick as Comick).isConfigured)
            val comickPkce = Pkce.generate()
            val comickPage = comick.authorizationUrl(REDIRECT, "state", comickPkce)
            assertEquals("https://comick.dev/api/auth/oauth2/authorize", comickPage.newBuilder().query(null).build().toString())
            assertEquals(BuildConfig.COMICK_CLIENT_ID, comickPage.queryParameter("client_id"))
            assertEquals("code", comickPage.queryParameter("response_type"))
            assertEquals(REDIRECT, comickPage.queryParameter("redirect_uri"))
            assertEquals(ComickApi.RESOURCE, comickPage.queryParameter("resource"))
            assertEquals(comickPkce.challenge, comickPage.queryParameter("code_challenge"))
            assertEquals("S256", comickPage.queryParameter("code_challenge_method"))
            assertSame(graph.myAnimeListTestBase, graph.myAnimeListTestBase)
            val previous = graph.myAnimeListTestBase.value
            try {
                graph.myAnimeListTestBase.value = null
                val tracker = myAnimeList as MyAnimeList
                assertEquals(BuildConfig.MAL_CLIENT_ID.isNotBlank(), tracker.isConfigured)
                val pkce = Pkce.generate()
                val page = tracker.authorizationUrl(REDIRECT, "state", pkce)
                assertEquals("myanimelist.net", page.host)
                assertEquals(BuildConfig.MAL_CLIENT_ID, page.queryParameter("client_id"))

                graph.myAnimeListTestBase.value = "http://10.0.2.2:18801"
                assertTrue("a test base configures MyAnimeList", tracker.isConfigured)
                val onTestBase = tracker.authorizationUrl(REDIRECT, "state", pkce)
                assertEquals("http://10.0.2.2:18801/v1/oauth2/authorize", onTestBase.newBuilder().query(null).build().toString())
                assertEquals(MyAnimeListClient.TEST_BASE_CLIENT_ID, onTestBase.queryParameter("client_id"))
            } finally {
                graph.myAnimeListTestBase.value = previous
            }
        }
    }

    /**
     * The hook on the reader's own path (phase 3 unit T1 stage 2): a reader view model built by the
     * reader's graph extension, as `ReaderActivity` builds one, holds a committer whose `TrackProgress`
     * is `:tracking`'s one binding. Metro checks that path when it compiles; this proves the object the
     * reader really gets. The fields are found by type, not by name, so a renamed field still counts.
     * The view model is given no manga or chapter, so it fails its session at once and loads nothing;
     * its store is cleared on the main thread.
     */
    @Test
    fun theReaderPathReachesTheOneTrackProgressBinding() {
        val readerGraph = app.readerGraphFactory.createReaderGraph()
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var reader: ReaderViewModel? = null
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    readerGraph.readerViewModelFactory.create(SavedStateHandle()) as T
            }
            reader = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        }
        try {
            val committer = fieldOfType(checkNotNull(reader), ProgressCommitter::class.java)
            val hook = fieldOfType(committer, TrackProgress::class.java)
            assertTrue("the reader's committer holds ${hook.javaClass.name}", hook is TrackProgressRecorder)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * Tracking settings' view model built the way its section builds it (phase 3 unit T1), through
     * the graph's factory, as [theSyncSettingsViewModelBuildsThroughTheViewModelMap] builds Sync's.
     * Its rows are the configured trackers: MyAnimeList's exactly when the build carries its client
     * id (phase 3 unit T2), with no test base set, and AniList's (R162 item 8), MangaBaka's and
     * Comick's exactly when the build carries their own, compared against `BuildConfig` as Drive's
     * offer is.
     */
    @Test
    fun theTrackingSettingsViewModelBuildsThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        val previous = graph.myAnimeListTestBase.value
        graph.myAnimeListTestBase.value = null
        try {
            instrumentation.runOnMainSync {
                built = ViewModelProvider(store, graph.viewModelFactory)[TrackingSettingsViewModel::class.java]
            }
            assertTrue(
                "the view model map built ${built?.javaClass?.name}",
                built is TrackingSettingsViewModel,
            )
            val expected = listOfNotNull(
                TrackerIds.MYANIMELIST.takeIf { BuildConfig.MAL_CLIENT_ID.isNotBlank() },
                TrackerIds.ANILIST.takeIf { BuildConfig.ANILIST_CLIENT_ID.isNotBlank() },
                TrackerIds.MANGABAKA.takeIf { BuildConfig.MANGABAKA_CLIENT_ID.isNotBlank() },
                TrackerIds.COMICK.takeIf { BuildConfig.COMICK_CLIENT_ID.isNotBlank() },
            )
            assertEquals(expected, (built as TrackingSettingsViewModel).state.value.trackers.map { it.id })
        } finally {
            graph.myAnimeListTestBase.value = previous
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The browser sign-in's bindings (phase 3 unit O): one hold per process, since a second would
     * serve a worker the first never ends, and one browser sign-in, which cancels the one in flight
     * (fix round 1 item 2); the Google client over the graph's sync client; and, in this debug build
     * with no test base set, Google's own endpoints, which a test base then replaces for the next
     * client the graph builds, with the fixture's test client.
     */
    @Test
    fun theSignInBindingsResolve() {
        assertSame(graph.signInHold, graph.signInHold)
        assertSame(graph.googleTestBase, graph.googleTestBase)
        assertSame(graph.browserSignIn, graph.browserSignIn)
        val previous = graph.googleTestBase.value
        try {
            graph.googleTestBase.value = null
            assertTrue(graph.googleOAuthClient.endpoints.isGoogle)
            // Whether Drive is offered reads the endpoints on each call (unit H): with no test base
            // it is the build's client id, which the gate's build leaves empty.
            assertEquals(BuildConfig.DRIVE_CLIENT_ID.isNotBlank(), graph.driveAvailability.offered)
            graph.googleTestBase.value = "http://10.0.2.2:18797"
            assertEquals("http://10.0.2.2:18797/token", graph.googleOAuthClient.endpoints.token.toString())
            assertTrue(graph.googleOAuthClient.isConfigured)
            assertTrue(graph.driveAvailability.offered)
        } finally {
            graph.googleTestBase.value = previous
        }
    }

    /**
     * The graph's wall clock (unit H, Q12): one offset per process, which this debug build's clock
     * reads on every call, so a clock handed out before the offset was set moves with it.
     */
    @Test
    fun theClockAndItsTestOffsetResolve() {
        assertSame(graph.clockOffset, graph.clockOffset)
        val clock = graph.clock
        val previous = graph.clockOffset.days
        try {
            graph.clockOffset.days = 0
            assertTrue((clock.now() - Clock.System.now()).absoluteValue < 1.minutes)
            graph.clockOffset.days = 14
            val ahead = clock.now() - Clock.System.now()
            assertTrue("the clock is $ahead ahead", ahead > 14.days - 1.minutes && ahead < 14.days + 1.minutes)
        } finally {
            graph.clockOffset.days = previous
        }
    }

    /**
     * The source filter screen's view model (phase 5 unit BF) built the way its screen builds it,
     * through the graph's factory, as [theSyncSettingsViewModelBuildsThroughTheViewModelMap] builds
     * Sync's: the key proves the contribution landed (R26), the build that the interactors, the
     * registry and the extension manager behind it resolve. Its state leaves loading once the
     * registry has loaded, which on any device holds at least the Local source.
     */
    @Test
    fun theSourcesFilterViewModelBuildsThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        try {
            instrumentation.runOnMainSync {
                built = ViewModelProvider(store, graph.viewModelFactory)[SourcesFilterViewModel::class.java]
            }
            val filter = built
            assertTrue("the view model map built ${filter?.javaClass?.name}", filter is SourcesFilterViewModel)
            filter as SourcesFilterViewModel
            val deadline = SystemClock.uptimeMillis() + STATS_LOAD_TIMEOUT_MILLIS
            val loading = SourcesFilterViewModel.State.Loading
            while (filter.state.value == loading && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("state is ${filter.state.value}", filter.state.value is SourcesFilterViewModel.State.Success)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The extension filter screen's view model (phase 5 unit BX), built as the source filter's is
     * above: the key proves the contribution landed (R26), the build that `GetExtensionLanguages`,
     * `ToggleLanguage` and the extension manager behind them resolve. Its state leaves loading on
     * the first emission of the available list and `source_languages`, both of which hold a value
     * from the start, so it is a success whether or not any index was fetched.
     */
    @Test
    fun theExtensionFilterViewModelBuildsThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        try {
            instrumentation.runOnMainSync {
                built = ViewModelProvider(store, graph.viewModelFactory)[ExtensionFilterViewModel::class.java]
            }
            val filter = built
            assertTrue("the view model map built ${filter?.javaClass?.name}", filter is ExtensionFilterViewModel)
            filter as ExtensionFilterViewModel
            val deadline = SystemClock.uptimeMillis() + STATS_LOAD_TIMEOUT_MILLIS
            val loading = ExtensionFilterViewModel.State.Loading
            while (filter.state.value == loading && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("state is ${filter.state.value}", filter.state.value is ExtensionFilterViewModel.State.Success)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * Sync settings' view model built the way its screen builds it: through the graph's factory,
     * which answers out of the view model map. A key in the map proves the contribution landed;
     * building it proves every binding behind it resolves on a device (Metro validates lazily, and a
     * view model that compiled and was contributed has still crashed the first screen that asked
     * for it). The store is cleared on the main thread, which cancels the flows it collects.
     */
    @Test
    fun theSyncSettingsViewModelBuildsThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        instrumentation.runOnMainSync {
            built = ViewModelProvider(store, graph.viewModelFactory)[SyncSettingsViewModel::class.java]
        }
        try {
            assertTrue(
                "the view model map built ${built?.javaClass?.name}",
                built is SyncSettingsViewModel,
            )
            val provided = graph.viewModelProviders.getValue(SyncSettingsViewModel::class)
            assertNotNull(provided)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The Categories screen's view model built through the graph's factory (phase 5 unit CT, R26):
     * its new constructor argument, [SetCategoryParent], the first caller that interactor has, is
     * resolved behind it, and the graph's own root for it resolves to the same class.
     */
    @Test
    fun theCategoriesViewModelBuildsThroughTheViewModelMapWithItsParentWriter() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        instrumentation.runOnMainSync {
            built = ViewModelProvider(store, graph.viewModelFactory)[CategoriesViewModel::class.java]
        }
        try {
            assertTrue("the view model map built ${built?.javaClass?.name}", built is CategoriesViewModel)
            assertNotNull(fieldOfType(built!!, SetCategoryParent::class.java))
            assertTrue(graph.setCategoryParent is SetCategoryParent)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The statistics screen's view model (phase 4 unit SS) built the way its screen builds it,
     * through the graph's factory, and then left to load: its one pass reads the library, history,
     * every track row, the download manifest and the library update's queue on this device's
     * database, so a binding behind any of them that does not resolve, or a query that throws, ends
     * the test here rather than on the first open of the screen.
     */
    @Test
    fun theStatsViewModelBuildsThroughTheViewModelMapAndLoads() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        instrumentation.runOnMainSync {
            built = ViewModelProvider(store, graph.viewModelFactory)[StatsViewModel::class.java]
        }
        try {
            val stats = built
            assertTrue("the view model map built ${stats?.javaClass?.name}", stats is StatsViewModel)
            stats as StatsViewModel
            val deadline = SystemClock.uptimeMillis() + STATS_LOAD_TIMEOUT_MILLIS
            while (stats.state.value is StatsScreenState.Loading && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("state is ${stats.state.value}", stats.state.value is StatsScreenState.Success)
            assertNotNull(fieldOfType(stats, FlatLibraryUpdate::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The storage screen's three view models (phase 4 unit ST) built through the graph's factory and
     * left to load on this device's database: the list of downloads not in the library through
     * `OrphanDownloads`, clear database through the new count query and the source manager, and the
     * settings view model's storage rows, whose constructor now takes `OrphanDownloads`,
     * `ResetViewerFlags` and `TrustStore`. A binding behind any of them that does not resolve, or a
     * query that throws, ends the test here rather than on the first open of a screen.
     */
    @Test
    fun theStorageViewModelsBuildThroughTheViewModelMapAndLoad() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var downloads: ViewModel? = null
        var clearDatabase: ViewModel? = null
        var settings: ViewModel? = null
        instrumentation.runOnMainSync {
            val provider = ViewModelProvider(store, graph.viewModelFactory)
            downloads = provider[StorageDownloadsViewModel::class.java]
            clearDatabase = provider[ClearDatabaseViewModel::class.java]
            settings = provider[SettingsViewModel::class.java]
        }
        try {
            assertTrue("built ${downloads?.javaClass?.name}", downloads is StorageDownloadsViewModel)
            assertTrue("built ${clearDatabase?.javaClass?.name}", clearDatabase is ClearDatabaseViewModel)
            assertTrue("built ${settings?.javaClass?.name}", settings is SettingsViewModel)
            val storageDownloads = downloads as StorageDownloadsViewModel
            val clear = clearDatabase as ClearDatabaseViewModel
            val settingsViewModel = settings as SettingsViewModel
            assertNotNull(fieldOfType(settingsViewModel, TrustStore::class.java))
            assertNotNull(fieldOfType(settingsViewModel, ResetViewerFlags::class.java))
            assertNotNull(fieldOfType(settingsViewModel, RebuildLibraryCounts::class.java))
            assertNotNull(fieldOfType(settingsViewModel, OrphanDownloads::class.java))
            instrumentation.runOnMainSync { settingsViewModel.refreshStorage() }
            val deadline = SystemClock.uptimeMillis() + STATS_LOAD_TIMEOUT_MILLIS
            while (SystemClock.uptimeMillis() < deadline && (
                    storageDownloads.state.value.titles == null ||
                        clear.state.value !is ClearDatabaseViewModel.State.Ready ||
                        settingsViewModel.state.value.downloadsSize == null
                    )
            ) {
                Thread.sleep(50)
            }
            assertNotNull("downloads not in the library never loaded", storageDownloads.state.value.titles)
            assertTrue("clear database is ${clear.state.value}", clear.state.value is ClearDatabaseViewModel.State.Ready)
            assertNotNull("the storage row never loaded", settingsViewModel.state.value.downloadsSize)
            assertNotNull(settingsViewModel.state.value.orphanTitles)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The Sources page's view model built through the view model map, as
     * [theSyncSettingsViewModelBuildsThroughTheViewModelMap] builds Sync's, so the pin toggle it
     * gained in phase 4 unit BD and the long press's Disable (`ToggleSource`, phase 4 unit MP2)
     * resolve through the app's graph. A pin through it lands in upstream's `pinned_catalogues` and a
     * disable in upstream's `hidden_catalogues`, each as the id's text; the id is one no source has,
     * and each is taken off again, so both sets end as they began.
     */
    @Test
    fun theSourcesViewModelBuildsThroughTheViewModelMapAndPinsInUpstreamsKey() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        val pins = graph.sourcePreferences.pinnedSources
        val before = pins.get()
        val disabled = graph.sourcePreferences.disabledSources
        val disabledBefore = disabled.get()
        instrumentation.runOnMainSync {
            built = ViewModelProvider(store, graph.viewModelFactory)[SourcesViewModel::class.java]
        }
        try {
            assertTrue("the view model map built ${built?.javaClass?.name}", built is SourcesViewModel)
            val viewModel = built as SourcesViewModel
            viewModel.togglePin(UNKNOWN_SOURCE_ID)
            assertEquals(before + UNKNOWN_SOURCE_ID.toString(), pins.get())
            viewModel.togglePin(UNKNOWN_SOURCE_ID)
            assertEquals(before, pins.get())
            viewModel.toggleSource(UNKNOWN_SOURCE_ID)
            assertEquals(disabledBefore + UNKNOWN_SOURCE_ID.toString(), disabled.get())
            viewModel.toggleSource(UNKNOWN_SOURCE_ID)
            assertEquals(disabledBefore, disabled.get())
        } finally {
            pins.set(before)
            disabled.set(disabledBefore)
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The migrate dialog's view model (phase 4 unit MA) built through the view model map, with the
     * migration and the fetch it shares with the manga screen as roots, so the download queue and
     * manifest, the cover cache, the tracking queue and the transaction runner behind them resolve
     * through the app's graph before the dialog is first opened.
     */
    @Test
    fun theMigrateDialogViewModelBuildsThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var built: ViewModel? = null
        instrumentation.runOnMainSync {
            built = ViewModelProvider(store, graph.viewModelFactory)[MigrateDialogViewModel::class.java]
        }
        try {
            assertTrue("the view model map built ${built?.javaClass?.name}", built is MigrateDialogViewModel)
            assertNotNull(fieldOfType(built as MigrateDialogViewModel, MigrateManga::class.java))
            assertNotNull(graph.migrateManga)
            assertNotNull(graph.refreshMangaFromSource)
            // The downloads a migration carries over (unit migrate-downloads): one instance, the
            // migration's own, holding the graph's one write lock.
            assertSame(graph.migrationDownloads, graph.migrationDownloads)
            assertSame(graph.migrationDownloads, fieldOfType(graph.migrateManga, MigrationDownloads::class.java))
            assertSame(graph.downloadWriteLock, fieldOfType(graph.migrationDownloads, DownloadWriteLock::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The batch migration's four view models (phase 4 unit MB) built through the app's graph: the
     * config screen's and the Migrate tab's out of the view model map, and the list's and the
     * picker's out of their assisted factories, the way their screens build them. The list's takes
     * MA's migration, and the tab's the sort setter provided in `InteractorBindings`, so both resolve
     * before a screen first opens. The list is built with no entries and the picker on a source no
     * provider serves, so neither reads the network nor writes anything.
     */
    @Test
    fun theMigrationViewModelsBuildThroughTheViewModelMap() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factories = graph.manualAssistedFactoryProviders
        val built = mutableListOf<ViewModel>()
        instrumentation.runOnMainSync {
            val provider = ViewModelProvider(store, graph.viewModelFactory)
            built += provider[MigrationConfigViewModel::class.java]
            built += provider[MigrateSourcesViewModel::class.java]
            val list = factories.getValue(MigrationListViewModel.Factory::class).invoke() as MigrationListViewModel.Factory
            val picker = factories.getValue(MigratePickerViewModel.Factory::class).invoke() as MigratePickerViewModel.Factory
            built += ViewModelProvider(store, factoryOf { list.create(emptyList(), null, null) })[MigrationListViewModel::class.java]
            built += ViewModelProvider(store, factoryOf { picker.create(UNKNOWN_SOURCE_ID, emptyList()) })[MigratePickerViewModel::class.java]
        }
        try {
            assertEquals(
                listOf(
                    MigrationConfigViewModel::class.java,
                    MigrateSourcesViewModel::class.java,
                    MigrationListViewModel::class.java,
                    MigratePickerViewModel::class.java,
                ),
                built.map { it.javaClass },
            )
            assertNotNull(fieldOfType(built[1], SetMigrateSorting::class.java))
            assertNotNull(fieldOfType(built[2], MigrateManga::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /** A provider factory that answers with what [build] makes, so the store owns and clears it. */
    private fun factoryOf(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
    }

    /**
     * The key set, not only membership. `SaikouWorkerFactory` answers WorkManager out of this map
     * and nothing else, so a key that should not be there is as wrong as one that is missing: the
     * map is the complete list of workers the app can build by hand.
     */
    @Test
    fun workerProvidersHoldTheThirteenWorkers() {
        val providers = graph.workerProviders
        for (worker in WORKERS) {
            assertNotNull("no worker factory bound for ${worker.java.name}", providers[worker])
        }
        assertEquals(WORKERS.toSet(), providers.keys)
    }

    /**
     * Contract 08 section 7 asks for the graph the factory builds, so this builds one and compares
     * what it multibinds against the live graph. Only the three maps are read from it: their values
     * are providers, so no singleton of a second graph is ever constructed and the process keeps
     * one owner of the database, the caches and the crash handler.
     */
    @Test
    fun theGraphFactoryBuildsAnEquivalentGraph() {
        val fresh = createGraphFactory<AppGraph.Factory>().create(app, StartMode.NORMAL)

        assertEquals(graph.viewModelProviders.keys, fresh.viewModelProviders.keys)
        assertEquals(graph.assistedFactoryProviders.keys, fresh.assistedFactoryProviders.keys)
        assertEquals(
            graph.manualAssistedFactoryProviders.keys,
            fresh.manualAssistedFactoryProviders.keys,
        )
    }

    /** The one field of [owner]'s class whose type is [type], read by reflection. */
    private fun <T : Any> fieldOfType(owner: Any, type: Class<T>): T {
        val field = owner.javaClass.declaredFields.single { type.isAssignableFrom(it.type) }
        field.isAccessible = true
        return checkNotNull(type.cast(field.get(owner))) { "${field.name} of ${owner.javaClass.name} is null" }
    }

    /**
     * Every zero argument accessor the interface declares, the inherited multibinding maps
     * included. Reflection rather than a written list, because a written list is the thing that
     * goes stale: a root added to [AppGraph] tomorrow is swept the day it lands, with no edit here.
     */
    private fun graphAccessors(): List<Method> =
        AppGraph::class.java.methods.filter {
            it.parameterTypes.isEmpty() &&
                !Modifier.isStatic(it.modifiers) &&
                !it.isSynthetic &&
                it.returnType != Void.TYPE
        }

    /**
     * The restore report's view model and the Backup screen's (unit restore-report) built through
     * the view model map: the report's holds the checkpoint store, whose report reader it calls on
     * opening, and the one list of sources this device lacks, which answers for a source no
     * provider serves once the registry has loaded; the Backup screen's holds the validator its
     * check before a restore runs.
     */
    @Test
    fun theRestoreReportBindingsResolve() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var report: ViewModel? = null
        var backup: ViewModel? = null
        instrumentation.runOnMainSync {
            val provider = ViewModelProvider(store, graph.viewModelFactory)
            report = provider[ImportReportViewModel::class.java]
            backup = provider[BackupViewModel::class.java]
        }
        try {
            assertTrue("built ${report?.javaClass?.name}", report is ImportReportViewModel)
            assertTrue("built ${backup?.javaClass?.name}", backup is BackupViewModel)
            val reportViewModel = report as ImportReportViewModel
            assertNotNull(fieldOfType(reportViewModel, ImportCheckpointStore::class.java))
            assertNotNull(fieldOfType(backup as BackupViewModel, BackupFileValidator::class.java))
            val missing = fieldOfType(reportViewModel, MissingExtensions::class.java)
            val rows = runBlocking(Dispatchers.IO) {
                withTimeout(STATS_LOAD_TIMEOUT_MILLIS) { missing.forSources(listOf(UNKNOWN_SOURCE_ID)).first() }
            }
            assertEquals(listOf(UNKNOWN_SOURCE_ID), rows.map { it.sourceId })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * Where a saved picture goes (unit saved-pages-gallery): the reader's page actions and the
     * entry screen's cover actions hold the one [SavedPictures], so a saved page and a saved cover
     * always land in the same place. The entry screen's view model is built on an id no entry has,
     * so it reads its not-found state and writes nothing.
     */
    @Test
    fun savedPagesAndSavedCoversShareTheOneSavedPictures() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factory = graph.manualAssistedFactoryProviders.getValue(MangaDetailViewModel.Factory::class).invoke()
            as MangaDetailViewModel.Factory
        // An id no entry has.
        val unknownMangaId = -4_242_424_243L
        lateinit var detail: MangaDetailViewModel
        instrumentation.runOnMainSync {
            detail = ViewModelProvider(store, factoryOf { factory.create(unknownMangaId, false) })[
                MangaDetailViewModel::class.java,
            ]
        }
        try {
            val pageActions = app.readerGraphFactory.createReaderGraph().pageImageActions
            val fromReader = fieldOfType(pageActions, SavedPictures::class.java)
            val fromCover = fieldOfType(fieldOfType(detail, MangaCoverActions::class.java), SavedPictures::class.java)
            assertSame(fromReader, fromCover)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    /**
     * The copy summary's view model (unit adoption-unmatched-reasons) built through the view model
     * map: it holds the summary store the Downloads section's row reads and the scheduler its Copy
     * again starts, the graph's own of each.
     */
    @Test
    fun theCopySummaryBindingsResolve() {
        val store = ViewModelStore()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var summary: ViewModel? = null
        instrumentation.runOnMainSync {
            summary = ViewModelProvider(store, graph.viewModelFactory)[AdoptionSummaryViewModel::class.java]
        }
        try {
            assertTrue("built ${summary?.javaClass?.name}", summary is AdoptionSummaryViewModel)
            val viewModel = summary as AdoptionSummaryViewModel
            assertSame(graph.adoptionSummaryStore, fieldOfType(viewModel, AdoptionSummaryStore::class.java))
            assertSame(graph.adoptionScheduler, fieldOfType(viewModel, AdoptionScheduler::class.java))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private companion object {

        const val STATS_LOAD_TIMEOUT_MILLIS = 30_000L

        /** The tracker loopback's redirect; building a page binds nothing. */
        const val REDIRECT = "http://127.0.0.1:18796/callback"

        /** A source id no provider serves, so a pin on it changes no list on the device. */
        const val UNKNOWN_SOURCE_ID = -4_242_424_242L

        /**
         * Read off the `@ViewModelKey` contributions under `app/src/main/kotlin/com/shinsaikou/ui`, not
         * off contract 08 section 7. The contract names `BackupCreateViewModel` and four screens
         * the tree does not carry, and a name no class carries cannot be imported at all. The list
         * has to be raised as screens land: a view model absent from it is a view model this test
         * cannot miss.
         */
        val SCREEN_VIEW_MODELS: List<KClass<out ViewModel>> = listOf(
            BackupViewModel::class,
            CategoriesViewModel::class,
            CrashLogViewModel::class,
            DownloadQueueViewModel::class,
            ExtensionStoresViewModel::class,
            ExtensionsViewModel::class,
            HistoryViewModel::class,
            LibraryViewModel::class,
            MainViewModel::class,
            SettingsViewModel::class,
            SourcesViewModel::class,
            SyncSettingsViewModel::class,
            TrackingSettingsViewModel::class,
            // The statistics screen (phase 4 unit SS).
            StatsViewModel::class,
            // The storage screen and clear database (phase 4 unit ST).
            StorageDownloadsViewModel::class,
            ClearDatabaseViewModel::class,
            UpdatesViewModel::class,
            // The migrate dialog's (phase 4 unit MA).
            MigrateDialogViewModel::class,
            // The batch migration's config screen and Browse's Migrate tab (phase 4 unit MB).
            MigrationConfigViewModel::class,
            MigrateSourcesViewModel::class,
            // Browse's source filter screen (phase 5 unit BF).
            SourcesFilterViewModel::class,
            // Browse's extension filter screen (phase 5 unit BX).
            ExtensionFilterViewModel::class,
            // The storage location picker and the sync safety backup's Save a copy (unit storage-location).
            StorageLocationViewModel::class,
            KeptBackupCopyViewModel::class,
            // The in-app updater's prompt and its Settings rows.
            AppUpdateViewModel::class,
        )

        /**
         * Raised as each argument taking screen lands. This list is the only place the assisted
         * half of the view model map is checked against a real graph: Metro resolves multibindings
         * lazily, so a factory that lost its `@ContributesIntoMap` still compiles, still ships, and
         * fails only when someone opens that screen.
         */
        val MANUAL_ASSISTED_FACTORIES: List<KClass<out ManualViewModelAssistedFactory>> = listOf(
            ExtensionDetailViewModel.Factory::class,
            GlobalSearchViewModel.Factory::class,
            MangaDetailViewModel.Factory::class,
            SourceBrowseViewModel.Factory::class,
            SourceSettingsViewModel.Factory::class,
            WebViewViewModel.Factory::class,
            // The track sheet's (phase 3 unit T3).
            TrackSheetViewModel.Factory::class,
            // Compare with MyAnimeList (phase 4 unit TC).
            TrackerCompareViewModel.Factory::class,
            // The migration list's and the Migrate tab's per-source picker (phase 4 unit MB).
            MigrationListViewModel.Factory::class,
            MigratePickerViewModel.Factory::class,
        )

        val WORKERS: List<KClass<out ListenableWorker>> = listOf(
            BackupExportWorker::class,
            BackupImportWorker::class,
            DownloadWorker::class,
            AdoptionWorker::class,
            LibraryUpdateWorker::class,
            SyncWorker::class,
            SyncExpiryNoticeWorker::class,
            SignInHoldWorker::class,
            DelayedTrackingUpdateWorker::class,
            // The storage location's move and the downloads export (unit storage-location).
            StorageMoveWorker::class,
            DownloadExportWorker::class,
            // The in-app updater's download and install.
            AppUpdateWorker::class,
            // Settings > Experimental's automatic extension updates.
            ExtensionUpdateWorker::class,
        )
    }
}
