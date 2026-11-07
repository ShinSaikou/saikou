package com.shinsaikou.di

import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.downloads.export.DownloadExport
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.storage.StorageMove
import com.shinsaikou.storage.StorageMoveScheduler
import com.shinsaikou.storage.StorageNotifier
import com.shinsaikou.ui.backup.BackupFileCopy
import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import coil3.ImageLoader
import com.shinsaikou.AppInfo
import com.shinsaikou.appupdate.AppUpdateChecker
import com.shinsaikou.appupdate.AppUpdateInstaller
import com.shinsaikou.appupdate.AppUpdateNotifier
import com.shinsaikou.appupdate.AppUpdateScheduler
import com.shinsaikou.appupdate.AppUpdateTriggers
import com.shinsaikou.backup.export.BackupExporter
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.BackupImportRequest
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.core.common.crash.StartMode
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.data.DatabaseInitializer
import com.shinsaikou.domain.library.interactor.GetLibraryItems
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.source.interactor.GetSourceMangaPage
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.repository.BookmarkNoteRepository
import com.shinsaikou.domain.chapter.repository.ScanlatorPriorityRepository
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.download.repository.DownloadQueueRepository
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.domain.preferences.UpdatesPreferences
import com.shinsaikou.domain.source.repository.SourceAliasRepository
import com.shinsaikou.domain.sync.repository.SyncChangeLogRepository
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.domain.sync.repository.SyncStoreSnapshot
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.DownloadIntents
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadManifestSweep
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.DownloadRoot
import com.shinsaikou.downloads.DownloadScheduler
import com.shinsaikou.downloads.DownloadWriteLock
import com.shinsaikou.downloads.MigrationDownloads
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.downloads.adoption.ChapterArchives
import com.shinsaikou.downloads.adoption.DownloadAdoption
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.di.InjektBootstrap
import com.shinsaikou.extensions.store.AddExtensionStore
import com.shinsaikou.extensions.store.UpdateExtensionStores
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.migration.MigrateManga
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.signin.GoogleTestBaseSetting
import com.shinsaikou.signin.SignInHold
import com.shinsaikou.reader.cache.ChapterCache
import com.shinsaikou.security.AppLock
import com.shinsaikou.source.SourceManager
import com.shinsaikou.sources.local.LocalSource
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SecretWriter
import com.shinsaikou.sync.SyncEngine
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.SyncNotices
import com.shinsaikou.sync.SyncRunRecord
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.sync.SyncSession
import com.shinsaikou.sync.SyncTriggers
import com.shinsaikou.sync.SyncWorkPreferences
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveAvailability
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.tracking.AddTracks
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.DelayedTrackingUpdate
import com.shinsaikou.tracking.MyAnimeListTestBaseSetting
import com.shinsaikou.tracking.TrackerHttpClient
import com.shinsaikou.tracking.TrackerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelMultibindings
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import kotlin.reflect.KClass
import kotlin.time.Clock

/**
 * The one dependency graph in the process. Contract 08 section 1.3.
 *
 * **Every accessor here is load bearing.** Metro validates lazily: a binding nothing asks for is
 * never checked, which is how eleven module merges stayed green while the graph filled with
 * dependencies no module provided. So this interface deliberately requests one root per subsystem,
 * including roots nothing in `:app` calls yet, and the build failing is the check.
 *
 * `:reader`'s graph extension arrives by aggregation: `ReaderGraph.Factory` is
 * `@ContributesTo(AppScope::class)`, so this interface implements it and `SaikouApp` hands the
 * merged graph out as the factory. There is no reader accessor here and no reader provider
 * anywhere in `:app` (ruling R22).
 */
@DependencyGraph(AppScope::class)
interface AppGraph : MetroViewModelMultibindings {

    val appInfo: AppInfo

    /**
     * The one ViewModel factory (section 1.5). `MainActivity` provides it as
     * `LocalMetroViewModelFactory`; requesting it here is what forces Metro to validate every
     * screen view model in the map, since nothing else in this interface names one.
     */
    val viewModelFactory: MetroViewModelFactory

    // Eager warm-up set: SaikouApp touches these on the IO dispatcher at startup.
    val networkHelper: NetworkHelper
    val database: Database
    val databaseInitializer: DatabaseInitializer
    val injektBootstrap: InjektBootstrap
    val sourceManager: SourceManager
    val extensionManager: ExtensionManager
    val localSource: LocalSource

    // Startup collaborators.
    val crashLog: CrashLog
    val imageLoader: ImageLoader
    val coverCache: CoverCache

    /** Read by `MainActivity` for the launch-time clear that `auto_clear_chapter_cache` asks for. */
    val chapterCache: ChapterCache
    val workerFactory: WorkerFactory
    val workManager: WorkManager
    val backupScheduler: BackupScheduler
    val appScope: CoroutineScope
    val dispatchers: Dispatchers
    val json: Json
    val preferenceStore: PreferenceStore

    /**
     * The multibound worker map itself, not only the factory over it. Asking for the factory alone
     * would resolve an empty map just as happily; asking for the map is what proves `:backup`,
     * `:library-update`, `:downloads`, `:app`'s sync and its expiry notice, `:app`'s sign-in hold and
     * `:app`'s tracker retry actually contributed their nine workers.
     */
    val workerProviders: Map<KClass<out ListenableWorker>, WorkerInstanceFactory<out ListenableWorker>>

    // Engines.
    val libraryUpdate: FlatLibraryUpdate
    val libraryUpdateScheduler: LibraryUpdateScheduler
    val backupImporter: BackupImporter
    val backupExporter: BackupExporter
    val backupImportRequest: BackupImportRequest
    val trackerManager: TrackerManager

    /**
     * The tracker progress push (phase 3 unit T1): the hook `:reader`'s committer and the chapter
     * list call, the retry store it records into, the scheduler and the pass behind the retry
     * worker, the client every tracker talks through, and the bind with its seed step the track
     * sheet (T3) calls. Roots so the graph checks them before a tracker exists.
     */
    val trackProgress: TrackProgress
    val delayedTrackingStore: DelayedTrackingStore
    val delayedTrackingScheduler: DelayedTrackingScheduler
    val delayedTrackingUpdate: DelayedTrackingUpdate
    val trackerHttpClient: TrackerHttpClient
    val addTracks: AddTracks

    /**
     * Migrating one entry (phase 4 unit MA): the fetch it shares with the manga screen and the
     * migration itself, roots so the graph checks them before a dialog asks.
     */
    val refreshMangaFromSource: RefreshMangaFromSource
    val migrateManga: MigrateManga

    /** The downloads a migration moves or copies (unit migrate-downloads), under the one write lock. */
    val migrationDownloads: MigrationDownloads

    /**
     * The address a debug build sends MyAnimeList's calls to while a test has set one (phase 3 unit
     * T2); the tracker itself is in [trackerManager] under its id.
     */
    val myAnimeListTestBase: MyAnimeListTestBaseSetting

    /**
     * Sync's secret store and its HTTP client (phase 3 unit S), which the WebDAV and Drive backends
     * and the OAuth helper inject. Nothing in `:app` calls either; they are roots so the graph
     * checks them before a backend asks.
     */
    val secretStore: SecretStore
    val secretWriter: SecretWriter
    val syncHttpClient: SyncHttpClient

    /**
     * The WebDAV sign-in and the backend built from its secrets (phase 3 unit F), over the two
     * roots above and the build's URL policy. Sync settings (unit G) is its first caller.
     */
    val webDavAccount: WebDavAccount

    /**
     * The Google Drive sign-in and the backend built from its secrets (phase 3 unit H), over the same
     * secret store and client, Google's client from the endpoints binding, and the one rule for
     * whether this build offers Drive. `SyncSession` picks it by the backend id's prefix.
     */
    val driveAccount: DriveAccount
    val driveAvailability: DriveAvailability

    /**
     * The graph's wall clock and the days the debug build moves it ahead, which only an
     * instrumentation test sets (unit H, Q12's 14-day row); a release build's clock never reads them.
     */
    val clock: Clock
    val clockOffset: ClockOffsetSetting

    /**
     * The sync engine (phase 3 unit E2) and the store it reaches the database through, which `:data`
     * binds for `:sync`. `SaikouApp` calls the engine at start-up to prune the log when no backend is
     * configured; G's worker runs it. The restore flag is written by `:backup`'s importer and read
     * by the engine, so both must see one instance.
     */
    val syncEngine: SyncEngine
    val syncStore: SyncStore
    val syncRestorePreferences: SyncRestorePreferences

    /**
     * The library read at one state with no writer waiting (phase 5 unit SC6): Sync settings'
     * fingerprint reads through it, and its device tests read it here.
     */
    val syncStoreSnapshot: SyncStoreSnapshot

    /**
     * The sync worker's collaborators (phase 3 unit G): the scheduler behind its two unique names,
     * the session that runs, signs in and signs out under one lock, the triggers `SaikouApp`
     * installs, the run record and the worker's own preferences. Sync settings (unit G's second
     * stage) reads and calls them.
     */
    val syncScheduler: SyncScheduler
    val syncSession: SyncSession
    val syncTriggers: SyncTriggers
    val syncRunRecord: SyncRunRecord
    val syncWorkPreferences: SyncWorkPreferences

    /** Sync's notices (fixes unit sync-notices), one per process, since its lock orders their changes. */
    val syncNotices: SyncNotices

    /**
     * The app lock and secure screen (phase 4 unit AL), one per process: `SaikouApp` installs it, and
     * `UnlockActivity` unlocks the same instance the callbacks read.
     */
    val appLock: AppLock

    /**
     * The browser sign-in (phase 3 unit O): the foreground hold and the flow over the loopback
     * listener that Google Drive (H) and the OAuth trackers (T1) sign in through, Google's client,
     * and the test base the debug build's endpoints read. The debug sign-in screen is the first
     * caller; H and T1 are the product's.
     */
    val signInHold: SignInHold
    val browserSignIn: BrowserSignIn
    val googleOAuthClient: GoogleOAuthClient
    val googleTestBase: GoogleTestBaseSetting

    /**
     * The in-app updater: the check behind the prompt and the Settings row, the foreground trigger
     * `SaikouApp` installs, the worker's scheduler and installer, and the notifier the install
     * receiver reads off the graph.
     */
    val appUpdateChecker: AppUpdateChecker
    val appUpdateTriggers: AppUpdateTriggers
    val appUpdateScheduler: AppUpdateScheduler
    val appUpdateInstaller: AppUpdateInstaller
    val appUpdateNotifier: AppUpdateNotifier

    /**
     * The download engine's three interfaces, which the chapter list and the download settings
     * reach, and the manifest sweep `SaikouApp` runs at start-up (phase 2 decision Q7).
     */
    val downloadQueue: DownloadQueue
    val downloadManifest: DownloadManifest
    val downloadRoot: DownloadRoot
    val downloadManifestSweep: DownloadManifestSweep

    /** `SaikouApp` has it follow "only download over Wi-Fi", so a waiting request takes a change. */
    val downloadScheduler: DownloadScheduler

    /** The folder-aware answer `:reader` and the chapter list read, bound by `:downloads`. */
    val downloadedChapters: DownloadedChapters

    /**
     * Upstream's automatic downloads (phase 3 unit AD), the one binding `:downloads` contributes and
     * the library update, the manga screen, the reader and the sync pull report to.
     */
    val autoDownload: AutoDownload

    /**
     * Upstream's delete after reading and after a manual mark read (phase 3 unit DR), the one binding
     * `:downloads` contributes and the reader's committer and `SetReadStatus` report to.
     */
    val readChapterCleanup: ReadChapterCleanup

    /** The queue screen the download notifications open, bound by `:app` for `:downloads`. */
    val downloadIntents: DownloadIntents

    /**
     * The adoption of upstream's downloads (phase 2 unit J): the run, the scheduler the Downloads
     * settings row starts it through, the store of its summary, and the archive check its copier
     * makes, which is native code and is exercised only on a device.
     */
    val downloadAdoption: DownloadAdoption
    val adoptionScheduler: AdoptionScheduler
    val adoptionSummaryStore: AdoptionSummaryStore
    val chapterArchives: ChapterArchives

    /**
     * The lock the download engine and the adoption's copier each take per chapter. It serializes
     * them only if the graph holds one, which nothing but a request through the graph can show.
     */
    val downloadWriteLock: DownloadWriteLock

    /**
     * The storage location (unit storage-location): the one folder and its derived folders, the
     * move out of the app folders with its scheduler and notifier, the downloads export with its
     * scheduler, and the copy behind Save a copy.
     */
    val storageLocation: StorageLocation
    val storageMove: StorageMove
    val storageMoveScheduler: StorageMoveScheduler
    val storageNotifier: StorageNotifier
    val downloadExport: DownloadExport
    val downloadExportScheduler: DownloadExportScheduler
    val backupFileCopy: BackupFileCopy

    // Screen-level use cases. Requesting these roots keeps lazy Metro validation honest as the
    // Compose shell becomes the primary consumer of the application graph.
    val getLibraryItems: GetLibraryItems
    val getUpdates: GetUpdates
    val getHistory: GetHistory
    val getCategories: GetCategories
    val getMangaWithChapters: GetMangaWithChapters
    val getSourceMangaPage: GetSourceMangaPage
    val updateManga: UpdateManga
    val createCategoryWithName: CreateCategoryWithName
    val renameCategory: RenameCategory
    val deleteCategory: DeleteCategory
    val reorderCategory: ReorderCategory
    /** Phase 5 unit CT: the Categories screen's "Move under" and "Move to top level". */
    val setCategoryParent: SetCategoryParent
    val addExtensionStore: AddExtensionStore
    val updateExtensionStores: UpdateExtensionStores

    // Preferences, read by MainActivity and the receiver directly (no activity injection, D1).
    val basePreferences: BasePreferences
    val securityPreferences: SecurityPreferences
    val backupPreferences: BackupPreferences
    val downloadPreferences: DownloadPreferences
    val extensionsPreferences: ExtensionsPreferences
    val libraryPreferences: LibraryPreferences
    val networkPreferences: NetworkPreferences
    val readerPreferences: ReaderPreferences
    val sourcePreferences: SourcePreferences
    val storagePreferences: StoragePreferences
    val trackPreferences: TrackPreferences
    val uiPreferences: UiPreferences
    val updatesPreferences: UpdatesPreferences

    // Every repository. Each is a `@ContributesBinding` in `:data` that nothing had yet requested.
    val bookmarkNoteRepository: BookmarkNoteRepository
    val categoryRepository: CategoryRepository
    val chapterRepository: ChapterRepository
    val downloadManifestRepository: DownloadManifestRepository
    val downloadQueueRepository: DownloadQueueRepository
    val extensionStoreRepository: ExtensionStoreRepository
    val historyRepository: HistoryRepository
    val mangaRepository: MangaRepository
    val mergedGroupRepository: MergedGroupRepository
    val scanlatorPriorityRepository: ScanlatorPriorityRepository
    val sourceAliasRepository: SourceAliasRepository
    val stubSourceRepository: StubSourceRepository
    val syncChangeLogRepository: SyncChangeLogRepository
    val trackRepository: TrackRepository
    val updatesRepository: UpdatesRepository

    // Read by NotificationReceiver, which has no constructor to inject into.
    val getChapter: GetChapter
    val setReadStatus: SetReadStatus

    /**
     * The process's one coordinator, the instance `NetworkHelper` hands the interceptor and the
     * solve banners read. `GateRowsTest` holds a solve on it so the budget row is measured against
     * the banner the application actually shows, not against a copy nothing on screen reads.
     */
    val cloudflareSolveCoordinator: CloudflareSolveCoordinator

    /**
     * [startMode] is read before the graph exists, from the flag the crash screen leaves, so every
     * binding sees the same answer for the life of the process.
     */
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application, @Provides startMode: StartMode): AppGraph
    }
}
