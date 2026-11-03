package com.shinsaikou.gate

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.source.toDomainChapter
import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.sources.local.LocalSource
import com.shinsaikou.sync.SyncLibrarySeeder
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.ui.browse.FixtureStore
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.util.Args
import com.shinsaikou.util.ForceStopOutcome
import com.shinsaikou.util.GateFragments
import com.shinsaikou.util.StagePreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.migration.models.MigrationFlag
import okio.GzipSink
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import tachiyomi.domain.track.model.Track

/**
 * The gate rows that have no other source. Contract 01 section 7.3 and section 8.3.
 *
 * Most rows of `tools/gate/rows.json` are measured from outside: a cold start is an `am start`, a
 * jank percent is a Perfetto window, a compatibility ratio is the host job's report. A handful are
 * not measurable from outside at all, because what they measure is a decision the application makes
 * in its own process, and those are the ones here. Each writes a fragment to
 * `/data/local/tmp/saikou-gate/<id>.json` shaped like one `rows[]` entry, which is what
 * `collect.py --instrumentation` pulls.
 *
 * ### Two rules this class is built around
 *
 * **The measurement is asserted before it is published.** A fragment is an output, not a check, and
 * `release_gate.py` comparing a number against a threshold days later is not this suite reporting
 * anything. So every row is asserted here against the same threshold `rows.json` carries, and the
 * fragment is written after the assertion passes. A row that fails fails the suite too.
 *
 * **The device this run is measuring is told to it, never guessed.** `rows.json` pairs a row with
 * one gate device, and a fragment claiming a device the run was not on is worse than no fragment:
 * `release_gate.py` would compare a 4 GB phone's number against the 2 GB phone's threshold. So
 * every test starts with `saikou.device.id`, the instrumentation argument form of the bench scripts'
 * `SAIKOU_DEVICE_ID`, and returns early without it, which makes an ordinary
 * `connectedDebugAndroidTest` on a developer's device a no-op. That matters more here than
 * elsewhere: two of these rows write thousands of rows into the database of whatever device they
 * run on. Under `saikou.strict` [Args] throws instead of returning null (R28), so a gate run that
 * forgot to name its device fails loudly rather than collecting nothing.
 *
 * Rows whose `rows.json` entry names the device `any` are published as `any`. Rows bound to one
 * device are published under the id this run was given, so running the suite on the wrong AVD
 * produces a fragment `release_gate.py` will not pair with anything, and the row is reported
 * missing rather than passing against the wrong threshold.
 */
@RunWith(AndroidJUnit4::class)
class GateRowsTest {

    /** Outermost: [MainActivity] asks for `POST_NOTIFICATIONS` and the dialog would block the run. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /**
     * Empty, because only two rows open an activity and each launches it through `ActivityScenario`
     * with its own setup around the launch. An activity rule would start one for every test here.
     */
    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val app = context.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    /**
     * `cloudflare_single_inflight_per_host`, from one run of the coordinator.
     *
     * A challenge solve parks an OkHttp dispatcher thread while a WebView runs, so several requests
     * to one challenged host without coordination park several threads and raise several WebViews
     * on the same challenge. The row asserts the coordinator's promise: one solve in flight per
     * host. The published state is still checked here, because a waiter count and a budget are part
     * of that promise, but it no longer publishes `cloudflare_budget_visible`: bookkeeping that is
     * right says nothing about whether anyone can see it. [theSolveBudgetIsOnScreenWhileASolveRuns]
     * publishes that row from the screen.
     *
     * A fresh coordinator is enough for this row, which is about the locking inside the class and
     * not about the instance the application wires. Locks are per host, so the application's own
     * instance would have done as well: a held solve on [SYNTHETIC_HOST] cannot make a real host
     * wait.
     */
    @Test
    fun theCloudflareRowsAreMeasured() {
        Args.argument(DEVICE_ARGUMENT) ?: return

        val coordinator = CloudflareSolveCoordinator()
        val inSolve = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        val solves = AtomicInteger()

        val first = Thread {
            coordinator.coalesce(
                host = SYNTHETIC_HOST,
                waitBudget = CloudflareSolveCoordinator.WAITER_BUDGET,
                alreadySolved = { false },
                solve = {
                    recordPeak(concurrent, peak)
                    solves.incrementAndGet()
                    inSolve.countDown()
                    release.await(SOLVE_HOLD_MILLIS, TimeUnit.MILLISECONDS)
                    concurrent.decrementAndGet()
                },
            )
        }
        first.start()
        assertTrue("the first solve never started", inSolve.await(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val waiters = (1 until SOLVER_COUNT).map {
            Thread {
                coordinator.coalesce(
                    host = SYNTHETIC_HOST,
                    waitBudget = CloudflareSolveCoordinator.WAITER_BUDGET,
                    alreadySolved = { false },
                    solve = {
                        recordPeak(concurrent, peak)
                        solves.incrementAndGet()
                        concurrent.decrementAndGet()
                    },
                )
            }.apply { start() }
        }

        // The waiter count is published as each waiter queues, so the sample is taken after they
        // have had time to queue and while the first solve is still holding the lock. At least one
        // waiter rather than all of them: how many of three threads the scheduler has started by
        // now is a fact about the machine, and the row is about the budget being visible at all.
        Thread.sleep(SAMPLE_DELAY_MILLIS)
        val published = coordinator.inFlight.value[SYNTHETIC_HOST]
        assertNotNull("no solve was visible while one was running", published)
        val budgetVisible = published!!.budgetMs > 0 &&
            published.startedAtElapsedMs > 0 &&
            published.waiters >= 1

        release.countDown()
        (waiters + first).forEach { it.join(THREAD_TIMEOUT_MILLIS) }

        assertEquals("every caller solved", SOLVER_COUNT, solves.get())
        assertEquals("more than one solve ran at once for one host", 1, peak.get())
        assertTrue(
            "the running solve published no usable budget: $published",
            budgetVisible,
        )
        assertTrue("a solve was still published after every caller returned", coordinator.inFlight.value.isEmpty())

        publish("cloudflare_single_inflight_per_host", ANY_DEVICE, "true", "bool", CLOUDFLARE_METHOD)
    }

    /**
     * `cloudflare_budget_visible`: while a solve runs, the person waiting sees which host it is,
     * how long it has run and the budget it runs against, in the activity the application launches.
     *
     * The solve is held on the application's own coordinator, reached through the graph, so the
     * banner under test is the one `MainViewModel` feeds and `SaikouAppNav` places. Deleting the
     * banner from the scaffold, or wiring it to a coordinator nothing publishes to, fails this at
     * the first wait. That is the point of moving the row here: the old publish came from a fresh
     * coordinator's state, which would have stayed true with no banner in the app at all.
     *
     * Holding a solve on the production instance is safe because the coordinator locks per host.
     * [SYNTHETIC_HOST] is reserved and can never be a host a real request goes to, so nothing the
     * application does during this test waits behind it.
     *
     * The elapsed figure has to change while the test watches. A banner that rendered the entry
     * once and never ticked would show the host and a budget and still be a silent park after its
     * first frame.
     */
    @Test
    fun theSolveBudgetIsOnScreenWhileASolveRuns() {
        Args.argument(DEVICE_ARGUMENT) ?: return

        val coordinator = graph.cloudflareSolveCoordinator
        val budgetSeconds = CloudflareSolveCoordinator.SOLVER_BUDGET.inWholeSeconds.toInt()
        val inSolve = CountDownLatch(1)
        val release = CountDownLatch(1)
        val solver = Thread {
            coordinator.coalesce(
                host = SYNTHETIC_HOST,
                waitBudget = CloudflareSolveCoordinator.WAITER_BUDGET,
                alreadySolved = { false },
                solve = {
                    inSolve.countDown()
                    release.await(BANNER_HOLD_MILLIS, TimeUnit.MILLISECONDS)
                },
            )
        }
        val banner = hasText(SYNTHETIC_HOST, substring = true)

        // The same first-launch seeding as measureBrowseOpen: a dialog over the activity would
        // not hide the banner from the semantics tree, but it is not the screen the row describes.
        graph.basePreferences.batteryExemptionRequested.set(true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
                compose.onAllNodesWithText(context.getString(R.string.library_title))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertTrue("the banner showed before any solve was held", compose.onAllNodes(banner).fetchSemanticsNodes().isEmpty())

            solver.start()
            assertTrue("the held solve never started", inSolve.await(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            compose.waitUntil(timeoutMillis = BANNER_TIMEOUT_MILLIS) {
                compose.onAllNodes(banner).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(banner).assertIsDisplayed()
            compose.onNode(hasText(context.getString(R.string.action_open_in_web_view))).assertIsDisplayed()

            // The whole line, formatted by the app's own plural with the coordinator's budget, at
            // some second of the wait. So the host, the running figure and the budget are all
            // asserted, and a banner printing a literal budget or a stale one would not match.
            val first = textOf(banner)
            val expected = (0..budgetSeconds).map { elapsed ->
                context.resources.getQuantityString(
                    R.plurals.solve_banner_waiting_format,
                    budgetSeconds,
                    SYNTHETIC_HOST,
                    elapsed,
                    budgetSeconds,
                )
            }
            assertTrue("the banner read \"$first\", which is no second of a $budgetSeconds second budget", first in expected)

            compose.waitUntil(timeoutMillis = TICK_TIMEOUT_MILLIS) { textOf(banner) != first }
            val later = textOf(banner)
            assertTrue("the banner changed to \"$later\", which is not a later second", later in expected)
            logcat(LOG_TAG, LogPriority.INFO) { "solve banner ticked from \"$first\" to \"$later\"" }

            release.countDown()
            solver.join(THREAD_TIMEOUT_MILLIS)
            compose.waitUntil(timeoutMillis = BANNER_TIMEOUT_MILLIS) {
                compose.onAllNodes(banner).fetchSemanticsNodes().isEmpty()
            }
            assertTrue("a solve was still published after it returned", SYNTHETIC_HOST !in coordinator.inFlight.value)
        } finally {
            release.countDown()
            solver.join(THREAD_TIMEOUT_MILLIS)
            scenario.close()
        }

        publish("cloudflare_budget_visible", ANY_DEVICE, "true", "bool", BANNER_METHOD)
    }

    /** The text of the one node [matcher] finds, empty when it has gone. */
    private fun textOf(matcher: SemanticsMatcher): String =
        compose.onAllNodes(matcher).fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            .orEmpty()

    /**
     * `restore_3000_ms` and `restore_3000_oom_count`, from a cold restore of a file this test wrote.
     *
     * The file is built rather than exported from the device, so the row measures the same 3,000
     * entries on every machine instead of whatever library the device happens to hold. It is the
     * stock protobuf encoding of the object, gzipped, which is what the production exporter writes
     * for it (phase 5 unit SC2 moved that exporter from an object to the database, so it can no
     * longer write an object it did not read); the importer's own header read refuses a file that
     * does not decode before anything is timed.
     *
     * Only `libraryEntries` is restored. The other four options would write the file's categories
     * and preferences over the device's, and this row is about the entry path.
     */
    @Test
    fun theRestoreRowsAreMeasured() {
        val device = Args.argument(DEVICE_ARGUMENT) ?: return

        val file = File(context.cacheDir, RESTORE_FILE_NAME)
        val backup = Backup(
            backupManga = (0 until RESTORE_ENTRIES).map { index ->
                BackupManga(
                    source = RESTORE_SOURCE_ID,
                    url = "$RESTORE_URL_PREFIX$index",
                    title = "$RESTORE_TITLE_PREFIX$index",
                    favorite = false,
                )
            },
        )

        var outOfMemory = 0
        var elapsed = 0L
        try {
            runBlocking {
                file.delete()
                GzipSink(file.sink()).buffer().use { it.write(ProtoBuf.encodeToByteArray(Backup.serializer(), backup)) }
            }

            val startedAt = SystemClock.elapsedRealtime()
            val report = try {
                runBlocking {
                    graph.backupImporter.import(
                        file,
                        RestoreOptions(
                            libraryEntries = true,
                            categories = false,
                            appSettings = false,
                            extensionStores = false,
                            sourceSettings = false,
                            installExtensions = false,
                        ),
                        progress = {},
                    )
                }
            } catch (error: OutOfMemoryError) {
                outOfMemory = 1
                logcat(LOG_TAG, LogPriority.ERROR) { "the restore ran out of memory: ${error.message}" }
                null
            }
            elapsed = SystemClock.elapsedRealtime() - startedAt

            assertEquals("the restore ran out of memory", 0, outOfMemory)
            assertNotNull("the restore produced no report", report)
            val finished = report!!
            assertEquals("the importer read a different number of entries", RESTORE_ENTRIES, finished.mangaTotal)
            assertEquals("${finished.mangaFailed} entries failed to restore", 0, finished.mangaFailed)
            assertTrue(
                "the restore took $elapsed ms against a budget of $RESTORE_BUDGET_MILLIS ms",
                elapsed <= RESTORE_BUDGET_MILLIS,
            )
            logcat(LOG_TAG, LogPriority.INFO) {
                "restored $RESTORE_ENTRIES entries in $elapsed ms, ${finished.errors.size} errors"
            }
        } finally {
            file.delete()
            // The synthetic source id is this test's alone, and none of its rows was ever a
            // favourite, so this removes exactly what the restore added.
            runBlocking {
                graph.mangaRepository.deleteNonLibraryManga(listOf(RESTORE_SOURCE_ID), keepReadManga = false)
            }
        }

        publish("restore_3000_ms", device, elapsed.toString(), "ms", RESTORE_METHOD)
        publish("restore_3000_oom_count", device, outOfMemory.toString(), "count", RESTORE_METHOD)
    }

    /**
     * `browse_source_open_ms_20k_history`: the Browse tab opening on a device whose history table
     * holds twenty thousand rows.
     *
     * The history rows are the condition, not the input. The claim the row makes is end to end, so
     * what is timed is the tab opening in the real activity, from the tap to the point where the
     * page has nothing left loading. Timing a query instead would time the one thing on the screen
     * that provably does not read history, and would pass whatever the table held.
     *
     * The seed is the bulk of this test's runtime: twenty thousand chapters and twenty thousand
     * history upserts, each of the latter its own transaction with a change-log trigger behind it.
     * Minutes, not seconds, and it is the price of the row meaning anything.
     */
    @Test
    fun theBrowseOpenRowIsMeasured() {
        val device = Args.argument(DEVICE_ARGUMENT) ?: return
        // Browse builds the Extensions page's view model, which fetches every configured index while
        // nothing is available; a store other than the browse fixture's keeps the tab closed and the
        // row unpublished (phase 5 unit TG2, SourcesFilterDeviceTest's guard).
        if (runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }.isNotEmpty()) {
            return skip(
                "theBrowseOpenRowIsMeasured",
                "an extension store other than the browse fixture store is configured, " +
                    "Browse was not opened so that nothing is fetched",
            )
        }

        val elapsed = try {
            seedHistory()
            measureBrowseOpen()
        } finally {
            runBlocking {
                graph.mangaRepository.deleteNonLibraryManga(listOf(BROWSE_SOURCE_ID), keepReadManga = false)
            }
        }

        assertTrue(
            "browse opened in $elapsed ms against a budget of $BROWSE_BUDGET_MILLIS ms",
            elapsed <= BROWSE_BUDGET_MILLIS,
        )
        publish("browse_source_open_ms_20k_history", device, elapsed.toString(), "ms", BROWSE_METHOD)
    }

    /**
     * `library_update_cancel_ms`: how long a running library update takes to stop once it is
     * cancelled, while its fetch is held in a read that blocks a real thread (ruling R47).
     *
     * A library entry on `LocalSource.ID` resolves to the real Local source, whose update path is
     * blocking file I/O: `getChapterList` reads each chapter folder's `ComicInfo.xml` through
     * `openInputStream()`. Here that file is a FIFO made with [Os.mkfifo], so the fetch thread opens
     * it and then sits in `read` until this test writes, which is the stall a real source produces
     * on a socket that never answers. No product code is involved: the entry is seeded the way
     * `ReaderSmokeTest` seeds one, and the pass is the one the graph hands the worker. A debug-only
     * source provider whose only consumer is this test is the shape R39 deleted, and a fake that
     * suspends stalls in a way no real source does, which is why the JVM case in
     * `FlatLibraryUpdateTest` blocks a thread too.
     *
     * The FIFO cannot sit under the source's default root. That root is the app's directory on
     * external storage, which API 30 and later serve through the platform's FUSE daemon, and the
     * daemon refuses `mknod`: `mkfifo` there fails with EACCES. So for the length of this test the
     * source's own root setting, `storage_local_dir`, names a directory under the app's internal
     * files as a `file:` URI, and is put back afterwards. `LocalSourceFileSystem` hands that URI
     * to `UniFile.fromUri`, which answers a `file:` URI with the same `RawFile` the default root
     * is, so the read under test is the one an owner's default library takes; only the directory
     * differs.
     *
     * The entry is the only member of a category of its own and the run is scoped to it, so the
     * queue is that one entry whatever the device's library holds, and the queue the pass reports
     * is asserted to be exactly that. The cancel lands only once a thread is seen inside
     * `FileInputStream.read` under a `LocalSource` frame, so what is timed is a cancel against a
     * fetch that is really in the read rather than one still on its way to it. The value is the
     * time from the cancel to the pass returning, an end only the pass produces.
     *
     * A pass that ignores the cancel would wait on the read for as long as the FIFO stays silent.
     * The wait is bounded at [CANCEL_WAIT_MILLIS], well past the threshold; after it the FIFO is
     * released so the pass can finish, and the row fails with the time the pass actually took
     * instead of hanging the suite.
     *
     * The row is phase 2, so it measures only when the run says it is for phase 2 or later
     * ([Args.gatePhase]). `gate.sh` runs this class in step 5 in every phase, and a phase 0 or
     * phase 1 run keeps the verdict it always had: this returns early there with a `Skipped:` line.
     * Phase 2's own step passes the argument and fails on that line.
     */
    @Test
    fun theLibraryUpdateCancelRowIsMeasured() {
        val method = "theLibraryUpdateCancelRowIsMeasured"
        val device = Args.argument(DEVICE_ARGUMENT) ?: return skip(method, "no $DEVICE_ARGUMENT argument")
        phaseSkipReason(CANCEL_ROW_PHASE)?.let { return skip(method, it) }
        // A run that fails below must not leave an earlier run's number to be collected in its place.
        GateFragments.remove(CANCEL_ROW)

        val entryName = "$CANCEL_FIXTURE_PREFIX${System.nanoTime()}"
        val root = File(context.filesDir, CANCEL_ROOT)
        val chapterDirectory = File(File(root, entryName), CANCEL_CHAPTER_NAME)
        val fifo = File(chapterDirectory, COMIC_INFO_FILE)
        // The key is LocalSourcePreferences.localSourceDirectory's. Were it to drift, the source
        // would not list the fixture from this root and the seed below fails by saying so.
        val rootSetting = graph.preferenceStore.getString(Preference.appStateKey(LOCAL_ROOT_SETTING), "")
        val previousRoot = if (rootSetting.isSet()) rootSetting.get() else null

        var mangaId = -1L
        var categoryId = -1L
        val writerEnd = AtomicReference<FileOutputStream?>()
        val readerOpened = CountDownLatch(1)
        // Opening a FIFO for writing blocks until something opens it for reading, so this thread
        // gets past the open exactly when the fetch has opened ComicInfo.xml.
        val writer = Thread({
            writerEnd.set(FileOutputStream(fifo))
            readerOpened.countDown()
        }, "gate-cancel-fifo-writer")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var fetchThread: Thread? = null
        val elapsed: Long
        try {
            // Anything an earlier run left under the root goes first, so the listing is this run's.
            root.deleteRecursively()
            assertTrue("could not create the fixture directory", chapterDirectory.mkdirs())
            // An image that sorts ahead of the FIFO, so the cover the source takes from the chapter
            // once the read returns is this file, and nothing opens the FIFO a second time.
            writePage(File(chapterDirectory, CANCEL_PAGE_NAME))
            Os.mkfifo(fifo.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            rootSetting.set(Uri.fromFile(root).toString())

            runBlocking {
                // The pass resolves the entry through the source manager, which answers every id
                // with a stub until its providers have published, and a stub is recorded as a
                // failure without any read at all.
                withTimeoutOrNull(CANCEL_OPEN_TIMEOUT_MILLIS) { graph.sourceManager.isInitialized.first { it } }
                    ?: throw AssertionError("the source manager never published its sources")
                val listed = graph.localSource.getSearchManga(1, entryName, FilterList.EMPTY)
                val info = listed.mangas.firstOrNull { it.title == entryName }
                assertNotNull("the local source did not list the fixture from the root this test set", info)
                mangaId = graph.mangaRepository
                    .insertNetworkManga(listOf(info!!.toDomainManga(LocalSource.ID)))
                    .single()
                    .id
                graph.mangaRepository.update(MangaUpdate(id = mangaId, favorite = true))

                val categoryName = "$CANCEL_CATEGORY_PREFIX$entryName"
                assertEquals(CreateCategoryWithName.Result.Success, graph.createCategoryWithName.await(categoryName))
                categoryId = graph.categoryRepository.getAll().single { it.name == categoryName }.id
                graph.mangaRepository.setMangaCategories(mangaId, listOf(categoryId))
            }
            writer.start()

            // The first report names the entry the update starts and the size of its queue.
            val firstProgress = AtomicReference<FlatLibraryUpdate.Progress>()
            val outcome = AtomicReference<FlatLibraryUpdate.Outcome>()
            val failure = AtomicReference<Throwable>()
            val finishedAt = AtomicLong()
            val run = scope.launch {
                try {
                    val result = graph.libraryUpdate.run(
                        categoryId = categoryId,
                        automatic = false,
                        refreshMetadata = false,
                    ) { progress -> firstProgress.compareAndSet(null, progress) }
                    finishedAt.set(SystemClock.elapsedRealtime())
                    outcome.set(result)
                } catch (e: CancellationException) {
                    finishedAt.set(SystemClock.elapsedRealtime())
                    throw e
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }

            assertTrue(
                "the update never opened the FIFO (after $CANCEL_OPEN_TIMEOUT_MILLIS ms); failure: ${failure.get()}",
                readerOpened.await(CANCEL_OPEN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )
            assertEquals("the update's queue is not one entry", 1, firstProgress.get()?.total)
            assertEquals("the update did not start the seeded entry", mangaId, firstProgress.get()?.current?.id)
            val blocked = awaitTheBlockedRead()
            fetchThread = blocked
            assertTrue("the update finished before it was cancelled: ${outcome.get()}, ${failure.get()}", run.isActive)

            val cancelledAt = SystemClock.elapsedRealtime()
            run.cancel()
            val stopped = runBlocking { withTimeoutOrNull(CANCEL_WAIT_MILLIS) { run.join() } } != null
            if (!stopped) {
                logcat(LOG_TAG, LogPriority.WARN) {
                    "the update was still running $CANCEL_WAIT_MILLIS ms after the cancel; releasing the read"
                }
                releaseTheRead(writerEnd)
                runBlocking { withTimeoutOrNull(THREAD_TIMEOUT_MILLIS) { run.join() } }
            }

            assertEquals("the update failed instead of stopping", null, failure.get())
            val end = finishedAt.get()
            assertTrue("the update never returned, even after the read was released", end > 0)
            elapsed = end - cancelledAt
            assertTrue(
                "the update took $elapsed ms to stop after the cancel, against a budget of $CANCEL_BUDGET_MILLIS ms",
                elapsed <= CANCEL_BUDGET_MILLIS,
            )
            assertEquals("the update did not report the run as cancelled", true, outcome.get()?.cancelled)
            logcat(LOG_TAG, LogPriority.INFO) {
                "the update stopped $elapsed ms after the cancel, with ${blocked.name} still blocked in the read"
            }
        } finally {
            // Whatever happened above, nothing may stay blocked on the FIFO: a writer still waiting
            // for a reader is let through by a non-blocking read end, and a fetch still waiting in
            // the read is given a whole ComicInfo.xml and the end of the file.
            if (writer.isAlive && readerOpened.count > 0L) {
                runCatching { Os.close(Os.open(fifo.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)) }
            }
            if (writer.isAlive) writer.join(THREAD_TIMEOUT_MILLIS)
            releaseTheRead(writerEnd)
            fetchThread?.let(::awaitTheReadReleased)
            scope.cancel()
            runBlocking {
                if (categoryId > 0) graph.categoryRepository.delete(categoryId)
                if (mangaId > 0) graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis())
            }
            if (previousRoot == null) rootSetting.delete() else rootSetting.set(previousRoot)
            root.deleteRecursively()
        }

        publish(CANCEL_ROW, device, elapsed.toString(), "ms", CANCEL_METHOD)
    }

    /**
     * The thread the fetch is parked on: one inside `FileInputStream.read` under a `LocalSource`
     * frame. Polled, because the fetch opens the FIFO an instant before it reads from it.
     */
    private fun awaitTheBlockedRead(): Thread {
        val deadline = SystemClock.uptimeMillis() + CANCEL_OPEN_TIMEOUT_MILLIS
        while (true) {
            val blocked = Thread.getAllStackTraces().entries.firstOrNull { (_, stack) -> isBlockedInTheRead(stack) }
            if (blocked != null) {
                logcat(LOG_TAG, LogPriority.INFO) { "the fetch is blocked in the read on ${blocked.key.name}" }
                return blocked.key
            }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError(
                    "no thread was seen in FileInputStream.read under LocalSource " +
                        "(after $CANCEL_OPEN_TIMEOUT_MILLIS ms)",
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * Waits a bounded time for [thread] to leave the read once the FIFO has been released, so the
     * fixture is deleted after the orphaned fetch is done with it. Logged rather than asserted:
     * the row is measured by then, and a fetch the pass dropped is allowed to finish late.
     */
    private fun awaitTheReadReleased(thread: Thread) {
        val deadline = SystemClock.uptimeMillis() + THREAD_TIMEOUT_MILLIS
        while (isBlockedInTheRead(thread.stackTrace)) {
            if (SystemClock.uptimeMillis() > deadline) {
                logcat(LOG_TAG, LogPriority.WARN) { "${thread.name} was still in the read after the release" }
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun isBlockedInTheRead(stack: Array<StackTraceElement>): Boolean =
        stack.any { it.className == FileInputStream::class.java.name && it.methodName == "read" } &&
            stack.any { it.className.startsWith(LocalSource::class.java.name) }

    /** Gives a fetch waiting on the FIFO a whole `ComicInfo.xml` and the end of the file, once. */
    private fun releaseTheRead(writerEnd: AtomicReference<FileOutputStream?>) {
        writerEnd.getAndSet(null)?.let { stream ->
            runCatching { stream.use { it.write(RELEASED_COMIC_INFO.toByteArray()) } }
        }
    }

    /**
     * `download_queue_add_ms_at_1000`: how long Download on a chapter selection takes to reach the
     * queue on a device that already holds [ADD_DOWNLOADED] downloaded chapters.
     *
     * The seed is an entry of a source no provider serves, with [ADD_DOWNLOADED] chapters downloaded
     * and [ADD_BATCH] more that are not. Each downloaded chapter has a manifest row written through
     * the repository the engine records a finished chapter with, and a folder where the engine would
     * have left it, so the folder-aware reads count it as downloaded and the manifest sweep the
     * worker runs keeps it. The timed call is [DownloadQueue.enqueue] with `autoStart = true`,
     * exactly what the chapter list's Download action calls, handed the whole chapter list of the
     * entry, so every downloaded chapter is checked and skipped and the rest are queued; the clock
     * stops when the queue reads back every chapter that was not downloaded. An enqueue whose cost
     * grows with the manifest (a scan of every row per chapter instead of a keyed read) pays that
     * cost [ADD_DOWNLOADED] plus [ADD_BATCH] times here.
     *
     * The worker the call starts fails the queued chapters, because their source is not installed;
     * that happens after the clock stops, and the cleanup removes them with everything else.
     *
     * A phase 2 row, so it measures only when the run names phase 2 ([Args.gatePhase]), as
     * [theLibraryUpdateCancelRowIsMeasured] does.
     */
    @Test
    fun theDownloadQueueAddRowIsMeasured() {
        val method = "theDownloadQueueAddRowIsMeasured"
        val device = Args.argument(DEVICE_ARGUMENT) ?: return skip(method, "no $DEVICE_ARGUMENT argument")
        phaseSkipReason(DOWNLOAD_ROWS_PHASE)?.let { return skip(method, it) }
        GateFragments.remove(ADD_ROW)

        val stamp = System.nanoTime()
        var queued: List<Long> = emptyList()
        var downloaded: List<Long> = emptyList()
        val elapsed: Long
        try {
            elapsed = runBlocking {
                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = ADD_SOURCE_ID,
                            url = "$ADD_URL_PREFIX$stamp",
                            title = "$ADD_TITLE_PREFIX$stamp",
                        ),
                    ),
                ).single()
                val chapters = graph.chapterRepository.addAll(
                    (0 until ADD_DOWNLOADED + ADD_BATCH).map { index ->
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "${manga.url}/$index",
                            name = "$ADD_CHAPTER_PREFIX${index + 1}",
                            chapterNumber = (index + 1).toDouble(),
                            sourceOrder = (ADD_DOWNLOADED + ADD_BATCH - index).toLong(),
                        )
                    },
                )
                assertEquals("the seed did not insert every chapter", ADD_DOWNLOADED + ADD_BATCH, chapters.size)
                downloaded = chapters.take(ADD_DOWNLOADED).map { it.id }
                queued = chapters.drop(ADD_DOWNLOADED).map { it.id }
                seedDownloads(manga, chapters.take(ADD_DOWNLOADED))
                assertTrue(
                    "a seeded chapter does not read as downloaded, so the seed is not what the app counts",
                    graph.downloadManifest.isDownloaded(downloaded.first()) &&
                        graph.downloadManifest.isDownloaded(downloaded.last()),
                )
                assertTrue(
                    "a chapter the seed left out reads as downloaded",
                    !graph.downloadManifest.isDownloaded(queued.first()),
                )

                val startedAt = SystemClock.elapsedRealtimeNanos()
                graph.downloadQueue.enqueue(manga, chapters, autoStart = true)
                val rows = graph.downloadQueue.subscribe().first { rows ->
                    rows.map { it.chapterId }.containsAll(queued)
                }
                val took = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLI
                val wrong = rows.map { it.chapterId }.filter { it in downloaded }
                assertTrue("the enqueue queued ${wrong.size} chapters that were already downloaded", wrong.isEmpty())
                took
            }
            assertTrue(
                "the enqueue took $elapsed ms against a budget of $ADD_BUDGET_MILLIS ms",
                elapsed <= ADD_BUDGET_MILLIS,
            )
            logcat(LOG_TAG, LogPriority.INFO) {
                "queued $ADD_BATCH chapters beside $ADD_DOWNLOADED downloaded ones in $elapsed ms"
            }
        } finally {
            runBlocking {
                if (queued.isNotEmpty()) graph.downloadQueue.remove(queued)
                if (downloaded.isNotEmpty()) graph.downloadManifestRepository.remove(downloaded)
                graph.downloadRoot.sourceDir(ADD_SOURCE_FOLDER).deleteRecursively()
                // The source id is this test's alone and the entry was never a favourite, so this
                // removes exactly the seeded entry, and its chapters with it.
                graph.mangaRepository.deleteNonLibraryManga(listOf(ADD_SOURCE_ID), keepReadManga = false)
            }
        }

        publish(ADD_ROW, device, elapsed.toString(), "ms", ADD_METHOD)
    }

    /**
     * A folder and a manifest row per chapter, named as the engine names them. The folder is under
     * [ADD_SOURCE_FOLDER], which `DownloadLocator` finds among the source folders when the source
     * itself is not installed, as it does for any download whose extension was removed.
     */
    private suspend fun seedDownloads(manga: Manga, chapters: List<Chapter>) {
        val disallowNonAscii = graph.libraryPreferences.disallowNonAsciiFilenames.get()
        val mangaDir = graph.downloadRoot.mangaDir(ADD_SOURCE_FOLDER, manga.title)
        val now = System.currentTimeMillis()
        chapters.forEach { chapter ->
            val dirName = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii)
            assertTrue("could not create a seeded chapter folder", File(mangaDir, dirName).mkdirs())
            graph.downloadManifestRepository.recordDownload(
                DownloadManifestEntry(
                    id = 0,
                    chapterId = chapter.id,
                    mangaId = manga.id,
                    sourceId = manga.source,
                    mangaTitle = manga.title,
                    mangaUrl = manga.url,
                    chapterUrl = chapter.url,
                    chapterName = chapter.name,
                    scanlator = chapter.scanlator,
                    chapterDirName = dirName,
                    pageCount = ADD_PAGES_PER_CHAPTER,
                    sizeBytes = 0,
                    dateDownloaded = now,
                ),
            )
        }
        assertEquals(
            "the manifest does not hold every seeded chapter",
            chapters.size,
            graph.downloadManifestRepository.getByMangaId(manga.id).size,
        )
    }

    /**
     * `download_queue_survives_force_stop`, part one of two (phase 2 decision Q8 (a)(i)). It leaves
     * downloads mid-flight and records the queue; `tools/ci/gate.sh`'s `force_stop_between` then
     * force-stops the package and runs [theDownloadQueueForceStopPartTwoFindsTheQueueAndResumesIt].
     *
     * The fixture is unit A's extension of twenty synthetic sources, whose file the gate pushes and
     * names with [FIXTURE_APK_ARGUMENT]. It is installed through the application's own private
     * install and trusted through the extension manager, the path the extension list takes, as
     * `BenchExtensionsTest` installs its sample. The pages come from `download_hold_server.py`, the
     * frozen fixture server wrapped with a hold: the first page of a chapter is served and every
     * later one is held open until part two releases it.
     *
     * One entry of each of [FORCE_STOP_SOURCES] sources, found through the source's own listing,
     * and its [FORCE_STOP_CHAPTERS_PER_ENTRY] oldest chapters from the source's own chapter list,
     * are queued through [DownloadQueue.enqueue] with `autoStart = true`, the chapter action's call.
     * The test waits until the first chapter is downloading with its first page on disk and the
     * server is holding its other pages, then writes the whole queue, the server's held count and
     * this process's id to [ForceStopOutcome], and returns without cleaning anything up: what it
     * leaves is what the force-stop is about. The queue must hold nothing else when it starts, so
     * the queue part two compares is this test's alone.
     */
    @Test
    fun theDownloadQueueForceStopPartOneLeavesDownloadsMidFlight() {
        val method = "theDownloadQueueForceStopPartOneLeavesDownloadsMidFlight"
        Args.argument(DEVICE_ARGUMENT) ?: return skip(method, "no $DEVICE_ARGUMENT argument")
        phaseSkipReason(DOWNLOAD_ROWS_PHASE)?.let { return skip(method, it) }
        val apk = Args.argument(FIXTURE_APK_ARGUMENT) ?: return skip(method, "no $FIXTURE_APK_ARGUMENT argument")
        GateFragments.remove(FORCE_STOP_ROW)
        ForceStopOutcome.remove(FORCE_STOP_ROW)

        var sources: List<CatalogueSource> = emptyList()
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(FORCE_STOP_SEED_TIMEOUT_MILLIS) {
                    sources = installTheFixtureExtension(apk)
                    clearTheDownloadFixture(sources)
                    val before = graph.downloadQueue.subscribe().first()
                    assertTrue(
                        "the download queue holds ${before.size} rows before the seed, so the queue compared " +
                            "after the force-stop would not be this test's alone",
                        before.isEmpty(),
                    )
                    val status = holdStatus()
                    assertTrue("the fixture server is not holding page requests: $status", status.getBoolean("holding"))

                    val seeded = sources.map { seedFixtureEntry(it) }
                    seeded.forEach { (manga, chapters) -> graph.downloadQueue.enqueue(manga, chapters, autoStart = true) }
                    val chapterIds = seeded.flatMap { (_, chapters) -> chapters.map { it.id } }
                    val (rows, held) = awaitMidFlight(chapterIds)
                    ForceStopOutcome.write(FORCE_STOP_ROW, outcomeJson(rows, held, sources))
                    logcat(LOG_TAG, LogPriority.INFO) {
                        "left ${rows.size} chapters queued, ${rows.first().downloadedPages} of " +
                            "${rows.first().totalPages} pages of the first on disk and $held page requests held"
                    }
                }
            }
        } catch (e: Throwable) {
            // Part two will not run after a failure here, so nothing is left to download in the
            // background of whatever runs next on this device.
            runCatching { runBlocking(Dispatchers.IO) { clearTheDownloadFixture(sources) } }
            throw e
        }
        // Part two's cold start loads the fixture extension only if the trust recorded above is on
        // disk, and the process ends as this returns (phase 5 unit TG4).
        StagePreferences.commit()
    }

    /**
     * `download_queue_survives_force_stop`, part two of two. It runs in a new process after the
     * gate has force-stopped the package, and publishes `true` only when all of this holds:
     *
     * - [ForceStopOutcome] holds part one's queue; no file ends the run, because without it there
     *   is nothing to compare (R36);
     * - this is not the process part one ran in, and on API 30 and later the platform recorded that
     *   one's end as a force-stop;
     * - the queue the restarted application reads is part one's exactly: the same chapters in the
     *   same order, each in the same state with the same pages done;
     * - after the hold is released, every one of those chapters reaches the manifest with every
     *   page in its folder, recorded after this process started.
     *
     * Nothing here enqueues, starts or schedules anything. A force-stop drops the application's
     * scheduled jobs until it starts again, and a resume this test caused would prove nothing about
     * the one the application makes; the only thing done to the device between the comparison and
     * the wait is the release of the fixture server's hold. The seed, its downloads and the fixture
     * extension are removed afterwards, whatever happened.
     */
    @Test
    fun theDownloadQueueForceStopPartTwoFindsTheQueueAndResumesIt() {
        val method = "theDownloadQueueForceStopPartTwoFindsTheQueueAndResumesIt"
        val device = Args.argument(DEVICE_ARGUMENT) ?: return skip(method, "no $DEVICE_ARGUMENT argument")
        phaseSkipReason(DOWNLOAD_ROWS_PHASE)?.let { return skip(method, it) }
        val startedAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        val text = ForceStopOutcome.read(FORCE_STOP_ROW)
            ?: throw AssertionError(
                "no outcome at ${ForceStopOutcome.path(FORCE_STOP_ROW)}: part one did not run to its end, so there " +
                    "is no queue to compare and the row cannot be measured (R36)",
            )
        val outcome = JSONObject(text)
        val sourceIds = outcome.getJSONArray("sourceIds").let { ids -> (0 until ids.length()).map { ids.getLong(it) } }
        val extension = outcome.getString("extension")
        try {
            assertEquals("the outcome is another row's", FORCE_STOP_ROW, outcome.getString("row"))
            assertEquals("the outcome's schema", ForceStopOutcome.SCHEMA, outcome.getInt("schema"))
            val partOnePid = outcome.getInt("pid")
            assertTrue(
                "part two runs in part one's process ($partOnePid), so nothing stopped it in between",
                partOnePid != Process.myPid(),
            )
            assertPartOneWasForceStopped(partOnePid)
            val expected = outcome.getJSONArray("queue").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }

            runBlocking(Dispatchers.IO) {
                // The queue as the restarted application has it, before anything here touches it.
                val rows = graph.downloadQueue.subscribe().first()
                assertEquals(
                    "the queue after the force-stop holds other chapters, or another order",
                    expected.map { it.getLong("chapterId") },
                    rows.map { it.chapterId },
                )
                rows.zip(expected).forEach { (row, was) ->
                    val what = "chapter ${row.chapterId}"
                    assertEquals("$what: entry", was.getLong("mangaId"), row.mangaId)
                    assertEquals("$what: state", was.getString("state"), row.state.name)
                    assertEquals("$what: queue position", was.getLong("queueOrder"), row.queueOrder)
                    assertEquals("$what: pages done", was.getLong("downloadedPages"), row.downloadedPages)
                    assertEquals("$what: pages", was.getLong("totalPages"), row.totalPages)
                }
                logcat(LOG_TAG, LogPriority.INFO) { "the queue of ${rows.size} chapters survived the force-stop" }

                releaseTheHold()
                awaitResumed(rows.map { it.chapterId })
                rows.forEach { row ->
                    val entry = graph.downloadManifest.getEntry(row.chapterId)
                    assertNotNull("chapter ${row.chapterId} has no manifest row", entry)
                    val pages = graph.downloadManifest.getPages(row.chapterId).orEmpty()
                    assertEquals("chapter ${row.chapterId}: pages in its folder", entry!!.pageCount.toInt(), pages.size)
                    assertTrue(
                        "chapter ${row.chapterId}: a download of ${entry.pageCount} pages, where part one saw " +
                            "${row.totalPages}",
                        row.totalPages == 0L || entry.pageCount == row.totalPages,
                    )
                    assertTrue(
                        "chapter ${row.chapterId} was recorded at ${entry.dateDownloaded}, before this process " +
                            "started at $startedAt",
                        entry.dateDownloaded >= startedAt,
                    )
                }
                logcat(LOG_TAG, LogPriority.INFO) { "every queued chapter downloaded after the restart" }
            }
        } finally {
            runCatching { releaseTheHold() }
            runBlocking(Dispatchers.IO) {
                val sources = sourceIds.mapNotNull { graph.sourceManager.get(it) as? CatalogueSource }
                clearTheDownloadFixture(sources)
                removeTheFixtureExtension(extension)
            }
            ForceStopOutcome.remove(FORCE_STOP_ROW)
        }

        publish(FORCE_STOP_ROW, device, "true", "bool", FORCE_STOP_METHOD)
    }

    /**
     * Installs the fixture extension from [apkPath] through the private install, trusts it through
     * the manager, and returns the first [count] of its sources by name once the source
     * manager answers for each of them. A second run on the same device finds it installed.
     */
    private suspend fun installTheFixtureExtension(apkPath: String, count: Int = FORCE_STOP_SOURCES): List<CatalogueSource> {
        val copy = File(context.cacheDir, FIXTURE_APK_COPY)
        File(apkPath).copyTo(copy, overwrite = true)
        try {
            val loader = fixtureLoader()
            val archive = loader.readArchive(copy)
            assertNotNull("$apkPath is not a readable extension package", archive)
            val manager = graph.extensionManager
            manager.isInitialized.first { it }
            val current = manager.installedExtensions.value.firstOrNull { it.packageName == archive!!.packageName }
            if (current == null || current.versionCode != archive!!.versionCode) {
                val outcome = loader.installPrivateExtensionFile(copy)
                assertTrue("the private install refused the fixture: $outcome", outcome is InstallOutcome.Installed)
                val result = manager.reload(archive!!.packageName)
                if (result is LoadResult.Untrusted) manager.trust(result.extension)
            }
            val installed = manager.installedExtensions.first { list -> list.any { it.packageName == archive!!.packageName } }
                .first { it.packageName == archive!!.packageName }
            val sources = installed.sources.sortedBy { it.name }.take(count)
            assertEquals("the fixture extension has fewer sources than the row uses", count, sources.size)
            graph.sourceManager.isInitialized.first { it }
            return sources.map { source ->
                val live = graph.sourceManager.getSources().first { list -> list.any { it.id == source.id } }
                    .first { it.id == source.id }
                assertTrue("fixture source ${source.id} is not a catalogue source", live is CatalogueSource)
                live as CatalogueSource
            }
        } finally {
            copy.delete()
        }
    }

    /** Uninstalls [packageName] through the manager and drops the trust part one recorded for it. */
    private suspend fun removeTheFixtureExtension(packageName: String) {
        graph.extensionManager.uninstall(packageName)
        val trusted = graph.extensionsPreferences.trustedExtensions
        trusted.set(trusted.get().filterNot { it.substringBefore(':') == packageName }.toSet())
    }

    /**
     * Built here as `BenchExtensionsTest` builds it: the loader is not an [AppGraph] root, and it is
     * stateless over the private directory, so this instance writes where the manager's reads.
     */
    private fun fixtureLoader() = ExtensionLoader(
        context = context,
        trustStore = TrustStore(graph.extensionStoreRepository, graph.extensionsPreferences),
        libVersionPolicy = LibVersionPolicy(graph.extensionsPreferences),
        injektBootstrap = graph.injektBootstrap,
    )

    /**
     * The first entry of [source]'s own listing, inserted as the browse screen inserts one, and its
     * oldest [FORCE_STOP_CHAPTERS_PER_ENTRY] chapters from the source's own chapter list.
     */
    private suspend fun seedFixtureEntry(source: CatalogueSource): Pair<Manga, List<Chapter>> {
        val info = source.getPopularManga(1).mangas.firstOrNull()
        assertNotNull("fixture source ${source.id} listed no entry", info)
        val manga = graph.mangaRepository.insertNetworkManga(listOf(info!!.toDomainManga(source.id))).single()
        val listed = source.getChapterList(info).sortedBy { it.chapterNumber }.take(FORCE_STOP_CHAPTERS_PER_ENTRY)
        assertEquals("fixture source ${source.id} listed too few chapters", FORCE_STOP_CHAPTERS_PER_ENTRY, listed.size)
        val chapters = graph.chapterRepository.addAll(
            listed.mapIndexed { index, chapter ->
                chapter.toDomainChapter(manga.id).copy(sourceOrder = (listed.size - index).toLong())
            },
        ).sortedBy { it.chapterNumber }
        assertEquals("the seed did not insert every chapter", FORCE_STOP_CHAPTERS_PER_ENTRY, chapters.size)
        return manga to chapters
    }

    /**
     * Polls until the queue is exactly [chapterIds], the first chapter is downloading with some of
     * its pages done and not all, and the server holds at least one page request open. Returns the
     * queue and the held count read in the same pass.
     */
    private suspend fun awaitMidFlight(chapterIds: List<Long>): Pair<List<DownloadQueueEntry>, Int> {
        val deadline = SystemClock.uptimeMillis() + MID_FLIGHT_TIMEOUT_MILLIS
        var rows: List<DownloadQueueEntry> = emptyList()
        var held = 0
        while (SystemClock.uptimeMillis() < deadline) {
            rows = graph.downloadQueue.subscribe().first()
            held = holdStatus().getInt("held")
            val first = rows.firstOrNull()
            if (rows.map { it.chapterId } == chapterIds &&
                first != null &&
                first.state == DownloadState.DOWNLOADING &&
                first.downloadedPages in 1 until first.totalPages &&
                held > 0
            ) {
                return rows to held
            }
            delay(DOWNLOAD_POLL_MILLIS)
        }
        throw AssertionError(
            "the downloads never stood mid-flight within $MID_FLIGHT_TIMEOUT_MILLIS ms: held $held, queue " +
                rows.joinToString { "${it.chapterId} ${it.state} ${it.downloadedPages}/${it.totalPages}" },
        )
    }

    /**
     * Polls until none of [chapterIds] is queued and every one reads as downloaded. A chapter that
     * fails ends the wait at once, since nothing retries it without the owner.
     */
    private suspend fun awaitResumed(chapterIds: List<Long>) {
        val deadline = SystemClock.uptimeMillis() + RESUME_TIMEOUT_MILLIS
        var waiting: List<DownloadQueueEntry> = emptyList()
        while (SystemClock.uptimeMillis() < deadline) {
            waiting = graph.downloadQueue.subscribe().first().filter { it.chapterId in chapterIds }
            val failed = waiting.firstOrNull { it.state == DownloadState.ERROR }
            if (failed != null) throw AssertionError("chapter ${failed.chapterId} failed to download: ${failed.errorMessage}")
            if (waiting.isEmpty() && chapterIds.all { graph.downloadManifest.isDownloaded(it) }) return
            delay(DOWNLOAD_POLL_MILLIS)
        }
        throw AssertionError(
            "the queue did not resume within $RESUME_TIMEOUT_MILLIS ms of the release; still queued: " +
                waiting.joinToString { "${it.chapterId} ${it.state} ${it.downloadedPages}/${it.totalPages}" },
        )
    }

    /**
     * On API 30 and later, the platform's record of how part one's process ended, which must be a
     * force-stop (`REASON_USER_REQUESTED`, the reason `am force-stop` records): a process that is
     * still alive has no record, and one that crashed or was killed for memory has another reason.
     * The end of an instrumentation run is itself a force-stop ("stop <package> due to finished
     * inst"), and on the API 36 image it lands before the gate's own `am force-stop`, which then
     * finds the package stopped; both take the platform's one force-stop path.
     */
    private fun assertPartOneWasForceStopped(pid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exit = activityManager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
        assertNotNull("the platform has no record of part one's process $pid ending", exit)
        assertEquals(
            "part one's process $pid ended other than by a force-stop: ${exit!!.description}",
            ApplicationExitInfo.REASON_USER_REQUESTED,
            exit.reason,
        )
        logcat(LOG_TAG, LogPriority.INFO) { "part one's process $pid ended with reason ${exit.reason}: ${exit.description}" }
    }

    /**
     * Removes whatever an earlier run of the force-stop row left: the queue rows and the downloads
     * of [sources]' entries, their partial folders, and the entries themselves, which were never
     * favourites. Queue rows go first, so the worker stops the chapter in flight before its folder
     * is deleted.
     */
    private suspend fun clearTheDownloadFixture(sources: List<CatalogueSource>) {
        if (sources.isEmpty()) return
        val ids = sources.map { it.id }.toSet()
        val queued = graph.downloadQueue.subscribe().first()
            .filter { row -> runCatching { graph.mangaRepository.getMangaById(row.mangaId) }.getOrNull()?.source in ids }
            .map { it.chapterId }
        graph.downloadQueue.remove(queued)
        val downloaded = graph.downloadManifestRepository.getAll().filter { it.sourceId in ids }.map { it.chapterId }
        graph.downloadManifest.delete(downloaded)
        sources.forEach { graph.downloadRoot.sourceDir(it.name).deleteRecursively() }
        graph.mangaRepository.deleteNonLibraryManga(ids.toList(), keepReadManga = false)
    }

    /** Part one's record: the queue in order, the held count, and what part two cleans up. */
    private fun outcomeJson(rows: List<DownloadQueueEntry>, held: Int, sources: List<CatalogueSource>): String =
        JSONObject()
            .put("schema", ForceStopOutcome.SCHEMA)
            .put("row", FORCE_STOP_ROW)
            .put("package", context.packageName)
            .put("pid", Process.myPid())
            .put("writtenAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("held", held)
            .put(
                "queue",
                JSONArray(
                    rows.map { row ->
                        JSONObject()
                            .put("chapterId", row.chapterId)
                            .put("mangaId", row.mangaId)
                            .put("state", row.state.name)
                            .put("queueOrder", row.queueOrder)
                            .put("downloadedPages", row.downloadedPages)
                            .put("totalPages", row.totalPages)
                    },
                ),
            )
            .put("sourceIds", JSONArray(sources.map { it.id }))
            .put("extension", checkNotNull(extensionOf(sources.first())))
            .toString()

    private fun extensionOf(source: CatalogueSource): String? =
        graph.extensionManager.installedExtensions.value
            .firstOrNull { extension -> extension.sources.any { it.id == source.id } }
            ?.packageName

    /** The fixture server's hold, as `download_hold_server.py` reports it. */
    private fun holdStatus(): JSONObject = JSONObject(fixtureServer(HOLD_STATUS_PATH))

    /** Lets every held page request through, and every later one. */
    private fun releaseTheHold() {
        val status = JSONObject(fixtureServer(HOLD_RELEASE_PATH))
        assertTrue("the fixture server is still holding after the release: $status", !status.getBoolean("holding"))
    }

    private fun fixtureServer(path: String): String {
        val connection = URL("$FIXTURE_SERVER$path").openConnection() as HttpURLConnection
        connection.connectTimeout = FIXTURE_SERVER_TIMEOUT_MILLIS
        connection.readTimeout = FIXTURE_SERVER_TIMEOUT_MILLIS
        return try {
            assertEquals("the fixture server answered $path with", HttpURLConnection.HTTP_OK, connection.responseCode)
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** A real PNG: the source sniffs magic bytes. */
    private fun writePage(target: File) {
        val bitmap = Bitmap.createBitmap(PAGE_SIZE, PAGE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(PAGE_COLOR)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Why a row of [rowPhase] does not measure in this run, or null when it does. A run given no
     * `saikou.gate.phase` binds as phase 0 ([Args.gatePhase]) and says it was given none, since step 5
     * of every phase runs this class without it and "phase 0" there reads as the phase of the run.
     */
    private fun phaseSkipReason(rowPhase: Int): String? {
        val given = Args.gatePhase
        if ((given ?: 0) >= rowPhase) return null
        val run = if (given == null) "the run was given no gate phase" else "this run is for phase $given"
        return "the row is phase $rowPhase and $run"
    }

    /** Writes the line a gate step counts, then returns: an early return must say why. */
    private fun skip(method: String, reason: String) {
        Log.i(LOG_TAG, "Skipped: $method: $reason")
    }

    /**
     * Enters a solve and remembers the highest number of solves that were ever inside at once. The
     * high water mark is the assertion: sampling how many are inside right now would answer with
     * whatever the scheduler happened to be doing at that instant.
     */
    private fun recordPeak(concurrent: AtomicInteger, peak: AtomicInteger) {
        val inside = concurrent.incrementAndGet()
        peak.getAndUpdate { previous -> maxOf(previous, inside) }
    }

    /**
     * Twenty thousand history rows behind [BROWSE_SOURCE_ID], written through the repositories
     * rather than through the generated queries, so the seed goes down the path the application
     * writes history with and cannot drift from it.
     */
    private fun seedHistory() {
        val stamp = System.currentTimeMillis()
        val readAt = Date(stamp)
        runBlocking {
            val entries = graph.mangaRepository.insertNetworkManga(
                (0 until BROWSE_ENTRIES).map { index ->
                    Manga.create().copy(
                        source = BROWSE_SOURCE_ID,
                        url = "$BROWSE_URL_PREFIX$stamp/$index",
                        title = "$BROWSE_TITLE_PREFIX$index",
                    )
                },
            )
            assertEquals("the seed did not insert every entry", BROWSE_ENTRIES, entries.size)

            var rows = 0
            entries.forEach { entry ->
                val chapters = graph.chapterRepository.addAll(
                    (0 until BROWSE_CHAPTERS_PER_ENTRY).map { index ->
                        Chapter.create().copy(
                            mangaId = entry.id,
                            url = "${entry.url}/$index",
                            name = "$BROWSE_CHAPTER_PREFIX$index",
                            chapterNumber = (index + 1).toDouble(),
                            sourceOrder = index.toLong(),
                        )
                    },
                )
                chapters.forEach { chapter ->
                    graph.historyRepository.upsertHistory(HistoryUpdate(chapter.id, readAt, 1L))
                    rows++
                }
            }
            assertEquals("the seed did not write every history row", BROWSE_HISTORY_ROWS, rows)
            logcat(LOG_TAG, LogPriority.INFO) { "seeded $rows history rows" }
        }
    }

    /**
     * From the tap on the Browse tab to the moment nothing on screen is loading. The library wait
     * that precedes it is the app starting, which the cold start row already measures and this one
     * must not include.
     */
    private fun measureBrowseOpen(): Long {
        // MainViewModel shows the battery exemption dialog the first time this preference is
        // unset, which is exactly the state a gate device starts in, and that dialog would sit
        // over the activity for the tap this test is timing. Seeding it here, before the launch
        // this call site performs, leaves the row measuring a tab tap
        // rather than a dialog dismissal.
        graph.basePreferences.batteryExemptionRequested.set(true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
                compose.onAllNodesWithText(context.getString(R.string.library_title))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            awaitNothingLoading()

            val browse = hasText(context.getString(R.string.tab_browse)) and isSelectable()
            val startedAt = SystemClock.elapsedRealtime()
            compose.onNode(browse).performClick()
            awaitNothingLoading()
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            compose.onNode(browse).assertIsSelected()
            logcat(LOG_TAG, LogPriority.INFO) { "browse opened in $elapsed ms" }
            return elapsed
        } finally {
            scenario.close()
        }
    }

    /** No indeterminate progress bar anywhere, which is every loading state the shell composes. */
    private fun awaitNothingLoading() {
        compose.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Phase 4 unit MA's device check, which is no gate row and publishes nothing: one entry migrated
     * and another copied through the duplicate dialog onto the same title on another source, as an
     * owner does it. It needs the fixture extension, named by [FIXTURE_APK_ARGUMENT] as for the
     * force-stop row, and the frozen fixture server (`update_fixture_server.py`) on port 18794; the
     * gate names neither, so in the gate this logs a `Skipped:` line and returns. The argument is read
     * directly, as unit MB's checks do, so a strict run without it skips instead of failing.
     *
     * For each of the first two entries of the fixture, the old entry is the first fixture source's
     * entry stored under the second source's title of that entry, in the library with a category, a
     * note, a custom cover, a bound track, chapters 1 to 3 read, chapter 2 bookmarked and chapter 1
     * downloaded from the server; the target is the second source's entry, inserted as the browse
     * screen inserts one. The target's own screen is opened, Add to library answers with the
     * duplicate dialog, and its Migrate opens the migrate dialog with every flag ticked:
     *
     * - entry 1 is migrated with every flag on. The target then holds the read chapters, the
     *   bookmark, the category, the track, the note and a copy of the cover, the old entry has left
     *   the library, and its download has moved to the target's chapter 1 (Downloaded chapters, unit
     *   migrate-downloads): gone from the old chapter's manifest row and folder;
     * - entry 2 is copied with Delete downloaded unticked. The target holds the same with a copy of
     *   the download, and the old entry keeps its place in the library, its category and its download.
     *
     * Screens of each step go to [MIGRATE_SCREENS_DIR]. The migration flags key is put back, and the
     * entries, their downloads, the category and the fixture extension are removed, whatever happened.
     */
    @Test
    fun theDuplicateDialogMigratesAndCopiesAnEntryOntoTheSameTitleOnAnotherSource() {
        val method = "theDuplicateDialogMigratesAndCopiesAnEntryOntoTheSameTitleOnAnotherSource"
        val apk = InstrumentationRegistry.getArguments().getString(FIXTURE_APK_ARGUMENT)
        if (apk.isNullOrBlank()) return skip(method, "no $FIXTURE_APK_ARGUMENT argument")
        val flags = graph.sourcePreferences.migrationFlags
        val storedFlags = flags.get()
        var sources: List<CatalogueSource> = emptyList()
        var categoryId = -1L
        shell("rm -rf $MIGRATE_SCREENS_DIR")
        shell("mkdir -p $MIGRATE_SCREENS_DIR")
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()
        try {
            val (migrated, copied) = runBlocking(Dispatchers.IO) {
                withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) {
                    sources = installTheFixtureExtension(apk).take(2)
                    clearTheMigrationFixture(sources, categoryId = -1L)
                    val categoryName = "$MIGRATE_CATEGORY_PREFIX${System.nanoTime()}"
                    assertEquals(CreateCategoryWithName.Result.Success, graph.createCategoryWithName.await(categoryName))
                    categoryId = graph.categoryRepository.getAll().single { it.name == categoryName }.id
                    seedMigrationPair(sources[0], sources[1], entry = 1, categoryId) to
                        seedMigrationPair(sources[0], sources[1], entry = 2, categoryId)
                }
            }

            flags.set(MigrationFlag.entries.toSet())
            answerTheDuplicateDialog(migrated.target, untick = null, button = R.string.migrate, screens = "migrate")
            runBlocking(Dispatchers.IO) {
                assertTheTargetHoldsTheOldEntry(migrated, categoryId)
                val old = graph.mangaRepository.getMangaById(migrated.old.id)
                assertTrue("the migrated entry is still in the library", !old.favorite)
                assertTrue(
                    "the migrated entry's download is still in the manifest",
                    !graph.downloadManifest.isDownloaded(migrated.downloadedChapterId),
                )
                assertTrue("the migrated entry's download is still on disk", !migrated.downloadDir.exists())
                assertTheTargetHasTheDownload(migrated)
            }
            assertEquals("the migration flags key after Migrate", MigrationFlag.entries.toSet(), flags.get())
            logcat(LOG_TAG, LogPriority.INFO) { "migrated entry ${migrated.old.id} onto ${migrated.target.id}" }

            flags.set(MigrationFlag.entries.toSet())
            answerTheDuplicateDialog(copied.target, untick = R.string.delete_downloaded, button = R.string.copy, screens = "copy")
            runBlocking(Dispatchers.IO) {
                assertTheTargetHoldsTheOldEntry(copied, categoryId)
                val old = graph.mangaRepository.getMangaById(copied.old.id)
                assertTrue("the copied entry left the library", old.favorite)
                assertEquals(
                    "the copied entry's categories",
                    listOf(categoryId),
                    graph.categoryRepository.getCategoriesByMangaId(old.id).map { it.id },
                )
                assertTrue("the copied entry's download left the manifest", graph.downloadManifest.isDownloaded(copied.downloadedChapterId))
                assertTrue("the copied entry's download left the disk", copied.downloadDir.exists())
                assertTheTargetHasTheDownload(copied)
            }
            assertEquals(
                "the migration flags key after Copy",
                MigrationFlag.entries.toSet() - MigrationFlag.REMOVE_DOWNLOAD,
                flags.get(),
            )
            logcat(LOG_TAG, LogPriority.INFO) { "copied entry ${copied.old.id} onto ${copied.target.id}" }
        } finally {
            flags.set(storedFlags)
            runBlocking(Dispatchers.IO) {
                clearTheMigrationFixture(sources, categoryId)
                sources.firstOrNull()?.let { extensionOf(it) }?.let { removeTheFixtureExtension(it) }
            }
        }
    }

    /** One old entry and its target, as [seedMigrationPair] left them. */
    private class MigrationPair(
        val old: Manga,
        val target: Manga,
        val remoteId: Long,
        val downloadedChapterId: Long,
        val downloadDir: File,
    )

    /**
     * The old entry: [from]'s entry number [entry] under [to]'s title of it, in the library with
     * [categoryId], a note, a custom cover, a track, every chapter [from] lists, chapters 1 to 3
     * read, chapter 2 bookmarked and chapter 1 downloaded. The target: [to]'s entry, not in the
     * library and with no chapters, as the browse screen leaves one.
     */
    private suspend fun seedMigrationPair(from: CatalogueSource, to: CatalogueSource, entry: Int, categoryId: Long): MigrationPair {
        val url = "/manga/$entry"
        val fromInfo = from.getPopularManga(1).mangas.firstOrNull { it.url == url }
        val toInfo = to.getPopularManga(1).mangas.firstOrNull { it.url == url }
        assertNotNull("fixture source ${from.id} did not list $url", fromInfo)
        assertNotNull("fixture source ${to.id} did not list $url", toInfo)
        val inserted = graph.mangaRepository.insertNetworkManga(
            listOf(fromInfo!!.toDomainManga(from.id).copy(title = toInfo!!.title)),
        ).single()
        graph.mangaRepository.update(
            MangaUpdate(
                id = inserted.id,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
                notes = "$MIGRATE_NOTE_PREFIX$entry",
            ),
        )
        val listed = from.getChapterList(fromInfo)
        val chapters = graph.chapterRepository.addAll(
            listed.mapIndexed { index, chapter -> chapter.toDomainChapter(inserted.id).copy(sourceOrder = index.toLong()) },
        )
        val byNumber = chapters.associateBy { it.chapterNumber }
        (1..3).forEach { number -> assertNotNull("fixture entry $url has no chapter $number", byNumber[number.toDouble()]) }
        graph.chapterRepository.updateAll(
            (1..3).map { number ->
                ChapterUpdate(id = byNumber.getValue(number.toDouble()).id, read = true, bookmark = number == 2)
            },
        )
        graph.mangaRepository.setMangaCategories(inserted.id, listOf(categoryId))
        val remoteId = MIGRATE_REMOTE_ID + entry
        graph.trackRepository.insert(
            Track(
                id = 0,
                mangaId = inserted.id,
                trackerId = TrackerIds.MYANIMELIST,
                remoteId = remoteId,
                libraryId = null,
                title = toInfo.title,
                lastChapterRead = 3.0,
                totalChapters = 0,
                status = 1,
                score = 0.0,
                remoteUrl = "",
                startDate = 0,
                finishDate = 0,
                private = false,
            ),
        )
        graph.coverCache.getCustomCoverFile(inserted.id).also { it.parentFile?.mkdirs() }.let { writePage(it) }

        val old = graph.mangaRepository.getMangaById(inserted.id)
        val first = byNumber.getValue(1.0)
        graph.downloadQueue.enqueue(old, listOf(first), autoStart = true)
        awaitResumed(listOf(first.id))
        val dir = graph.downloadManifest.getChapterDir(first.id)
        assertNotNull("chapter ${first.id} is downloaded with no folder", dir)

        val target = graph.mangaRepository.insertNetworkManga(listOf(toInfo.toDomainManga(to.id))).single()
        return MigrationPair(old, target, remoteId, first.id, dir!!)
    }

    /**
     * Opens [target]'s own screen, taps Add to library, and answers the duplicate dialog with Migrate,
     * then the migrate dialog with [button] after unticking [untick], and waits for the target to be
     * in the library with no dialog left.
     */
    private fun answerTheDuplicateDialog(target: Manga, untick: Int?, button: Int, screens: String) {
        // Launched plain and sent the entry intent once the library is up: launched with it, the
        // arrival navigated before the host had its graph under the test's dispatcher. The entry
        // intent reaches onNewIntent, which makes it the activity's intent, and the scenario only
        // follows an activity whose intent is the one it started, so its own is put back to close.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var launched: Intent? = null
        try {
            awaitText(R.string.library_title)
            scenario.onActivity {
                launched = it.intent
                it.startActivity(MainIntents.newIntent(it, MainDestination.ENTRY, target.id))
            }
            val add = hasText(context.getString(R.string.action_add_to_library)) and hasClickAction()
            compose.waitUntil(timeoutMillis = MIGRATE_UI_TIMEOUT_MILLIS) { compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
            migrateScreen("$screens-1-entry")
            compose.onNode(add).performClick()

            awaitText(R.string.manga_duplicate_title)
            migrateScreen("$screens-2-duplicate")
            compose.onNode(inDialog(R.string.migrate)).performClick()

            awaitText(R.string.migration_dialog_what_to_include)
            listOf(
                R.string.chapters,
                R.string.categories,
                R.string.custom_cover,
                R.string.downloaded_chapters,
                R.string.action_notes,
                R.string.delete_downloaded,
            ).forEach { label -> compose.onNode(inDialog(label)).assertIsDisplayed() }
            if (untick != null) compose.onNode(inDialog(untick)).performClick()
            migrateScreen("$screens-3-flags")
            compose.onNode(inDialog(button)).performClick()

            compose.waitUntil(timeoutMillis = MIGRATE_RUN_TIMEOUT_MILLIS) {
                compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() &&
                    runBlocking(Dispatchers.IO) { graph.mangaRepository.getMangaById(target.id).favorite }
            }
            val remove = hasText(context.getString(R.string.action_remove_from_library)) and hasClickAction()
            compose.waitUntil(timeoutMillis = MIGRATE_UI_TIMEOUT_MILLIS) { compose.onAllNodes(remove).fetchSemanticsNodes().isNotEmpty() }
            migrateScreen("$screens-4-done")
        } finally {
            launched?.let { intent -> scenario.onActivity { it.intent = intent } }
            scenario.close()
        }
    }

    /** The old entry's downloaded chapter 1, carried onto the target's chapter 1 (unit migrate-downloads). */
    private suspend fun assertTheTargetHasTheDownload(pair: MigrationPair) {
        val chapter = graph.chapterRepository.getChapterByMangaId(pair.target.id).single { it.chapterNumber == 1.0 }
        assertTrue("the target's chapter 1 is not downloaded", graph.downloadManifest.isDownloaded(chapter.id))
    }

    /** What both a migration and a copy carry onto the target, read back from the database. */
    private suspend fun assertTheTargetHoldsTheOldEntry(pair: MigrationPair, categoryId: Long) {
        val target = graph.mangaRepository.getMangaById(pair.target.id)
        assertTrue("the target is not in the library", target.favorite)
        assertEquals("the target's note", pair.old.notes, target.notes)
        val chapters = graph.chapterRepository.getChapterByMangaId(target.id).associateBy { it.chapterNumber }
        (1..3).forEach { number ->
            val chapter = chapters[number.toDouble()]
            assertNotNull("the target has no chapter $number", chapter)
            assertTrue("the target's chapter $number is not read", chapter!!.read)
            assertEquals("the target's chapter $number bookmark", number == 2, chapter.bookmark)
        }
        assertTrue("the target's chapter 4 is read, or missing", chapters[4.0]?.read == false)
        assertEquals(
            "the target's categories",
            listOf(categoryId),
            graph.categoryRepository.getCategoriesByMangaId(target.id).map { it.id },
        )
        val track = graph.trackRepository.getTracksByMangaId(target.id).singleOrNull()
        assertNotNull("the target has no track", track)
        assertEquals("the target's track", pair.remoteId, track!!.remoteId)
        assertEquals("the target's track progress", 3.0, track.lastChapterRead, 0.0)
        assertTrue("the target has no custom cover", graph.coverCache.getCustomCoverFile(target.id).exists())
    }

    /**
     * Takes every entry of [sources] out of the library and removes what the check or an earlier run
     * of it left: custom covers, queue rows, downloads and the entries, then [categoryId].
     */
    private suspend fun clearTheMigrationFixture(sources: List<CatalogueSource>, categoryId: Long) {
        if (sources.isNotEmpty()) {
            val ids = sources.map { it.id }.toSet()
            val favorites = graph.mangaRepository.getFavorites().filter { it.source in ids }.map { it.id }
            (favorites + graph.mangaRepository.getLiveNonLibraryMangaIds(ids.toList()))
                .forEach { graph.coverCache.getCustomCoverFile(it).delete() }
            graph.mangaRepository.updateAll(favorites.map { MangaUpdate(id = it, favorite = false) })
            clearTheDownloadFixture(sources)
        }
        if (categoryId > 0) graph.categoryRepository.delete(categoryId)
    }

    private fun inDialog(label: Int): SemanticsMatcher =
        hasText(context.getString(label)) and hasClickAction() and hasAnyAncestor(isDialog())

    private fun awaitText(label: Int) {
        val text = hasText(context.getString(label))
        compose.waitUntil(timeoutMillis = MIGRATE_UI_TIMEOUT_MILLIS) { compose.onAllNodes(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun migrateScreen(name: String) {
        compose.waitForIdle()
        shell("screencap -p $MIGRATE_SCREENS_DIR/$name.png")
        Log.i(LOG_TAG, "screen: $MIGRATE_SCREENS_DIR/$name.png")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    /** Writes one `rows[]` entry where `collect.py --instrumentation` pulls from; see [GateFragments.publish]. */
    private fun publish(id: String, device: String, value: String, unit: String, method: String) =
        GateFragments.publish(id, device, value, unit, method)

    // Phase 4 unit MB's device checks (design g7 final section 20). None is a gate row and none
    // publishes anything. Each needs `saikou.migrate.batch`, the fixture extension named by
    // FIXTURE_APK_ARGUMENT, and `tools/bench/migrate_fixture_server.py` on port 18794; the gate
    // names none of them, so in the gate each logs a `Skipped:` line and returns. A check installs
    // the fixture extension, seeds the library it needs, drives the screens as an owner does, and
    // reads the outcome back from the database and the server's counts; whatever happened, it takes
    // its entries out of the library, removes the extension and puts the migration keys back.
    // Screens of each step go to MIGRATE_BATCH_SCREENS_DIR/<method>.

    /**
     * Section 20.2 step 1: Browse's Migrate page lists A and the second old source with their
     * counts, and its help action opens upstream's guide in a browser (caught before it leaves the app,
     * so nothing reaches the network); a source's row opens its entries and Continue reaches the
     * config screen; the library search for A's name, Select all and Migrate reach the config screen
     * with A's entries, and the same once the extension is gone (5.4); and the manga screen's
     * Migrate with one entry skips the sheet and reaches manual search with the entry's title.
     */
    @Test
    fun theMigrateEntryPointsReachTheConfigScreen() = migrateBatchCheck("theMigrateEntryPointsReachTheConfigScreen") { run ->
        val onA = run.fixture.onA("b").take(ENTRY_POINT_A_ENTRIES)
        val onOverflow = (1..ENTRY_POINT_OVERFLOW_ENTRIES).map { run.fixture.entry(MigrateFixture.OLD_OVERFLOW, it) }
        val seeded = runBlocking(Dispatchers.IO) { run.seed(onA + onOverflow) }
        val first = seeded.first()

        withMain(MainDestination.BROWSE) {
            clickFirst(hasText(context.getString(R.string.label_migration)) and hasClickAction())
            awaitNode(hasText(run.name(MigrateFixture.OLD_A)))
            awaitNode(hasText(run.name(MigrateFixture.OLD_OVERFLOW)))
            assertRowCount(run.name(MigrateFixture.OLD_A), onA.size)
            assertRowCount(run.name(MigrateFixture.OLD_OVERFLOW), onOverflow.size)
            batchScreen("1-migrate-tab")

            val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, null, true)
            try {
                clickFirst(hasContentDescription(context.getString(R.string.migration_help_guide)))
                assertTrue("the help action started no browser", instrumentation.checkMonitorHit(monitor, 1))
            } finally {
                instrumentation.removeMonitor(monitor)
            }

            clickFirst(hasText(run.name(MigrateFixture.OLD_A)))
            awaitNode(hasText(onA[1].title))
            compose.onAllNodes(hasText(onA[1].title)).onFirst().performClick()
            compose.onAllNodes(hasText(onA[0].title)).onFirst().performClick()
            batchScreen("2-picker-ticked")
            clickFirst(hasText(context.getString(R.string.migrationConfigScreen_continueButtonText)), unmerged = true)
            awaitNode(hasText(context.getString(R.string.migrationConfigScreen_selectedHeader)))
            batchScreen("3-config-from-picker")
        }

        for (installed in listOf(true, false)) {
            if (!installed) runBlocking(Dispatchers.IO) { run.sources[1]?.let { extensionOf(it) }?.let { removeTheFixtureExtension(it) } }
            withMain(MainDestination.LIBRARY) {
                clickFirst(hasContentDescription(context.getString(R.string.action_search)))
                awaitNode(hasText(context.getString(R.string.library_search_hint)))
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(run.name(MigrateFixture.OLD_A))
                awaitNoNode(hasText(onOverflow.first().title))
                selectAllInLibrary(onA.map { it.title }, onA.size)
                batchScreen(if (installed) "4-library-select-all" else "6-library-select-all-uninstalled")
                migrateTheSelection()
                batchScreen(if (installed) "5-config-from-library" else "7-config-from-library-uninstalled")
            }
        }
        val reinstalled = runBlocking(Dispatchers.IO) {
            installTheFixtureExtension(checkNotNull(InstrumentationRegistry.getArguments().getString(FIXTURE_APK_ARGUMENT)), MigrateFixture.SOURCES)
        }
        assertEquals(run.sources.values.map { it.id }, reinstalled.map { it.id })

        withEntry(first.id) {
            clickFirst(hasContentDescription(context.getString(R.string.action_menu_overflow_description)))
            clickFirst(hasText(context.getString(R.string.migrate)) and hasClickAction())
            awaitNode(hasText(context.getString(R.string.migrationConfigScreen_selectedHeader)))
            clickFirst(hasText(context.getString(R.string.migrationConfigScreen_continueButtonText)), unmerged = true)
            awaitNode(hasText(context.getString(R.string.global_search_title)))
            awaitNode(hasText(first.title) and hasSetTextAction())
            assertTrue(
                "the one-entry Migrate opened the sheet",
                compose.onAllNodes(hasText(context.getString(R.string.migrationConfigScreen_dataToMigrateHeader))).fetchSemanticsNodes().isEmpty(),
            )
            batchScreen("8-manual-search-from-entry")
        }
    }

    /**
     * Section 20.2 step 2: the config screen offers every fixture source, ticked in name order the
     * first time; a drag of the fourth selected source above the third is saved to
     * `migration_sources` at once; and the sheet opens for two entries with the Delete downloads
     * switch on, takes keywords, and starts the list, whose searches carry the keywords.
     */
    @Test
    fun theConfigScreenSavesADragAndTheSheetStartsTheList() = migrateBatchCheck("theConfigScreenSavesADragAndTheSheetStartsTheList") { run ->
        val entries = run.fixture.onA("b").take(2)
        runBlocking(Dispatchers.IO) { run.seed(entries) }
        // The first time: no saved order, and the flags at their default.
        graph.sourcePreferences.migrationSources.set(emptyList())
        graph.sourcePreferences.migrationFlags.delete()
        val searchesBefore = run.fixture.state().getJSONObject("answered").getJSONObject("search")
            .let { counts -> counts.keys().asSequence().sumOf { counts.getInt(it) } }

        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(entries.map { it.title }, entries.size)
            migrateTheSelection()
            (1..MigrateFixture.SOURCES).forEach { awaitNode(hasText(run.name(it))) }
            val firstSaved = graph.sourcePreferences.migrationSources.get()
            batchScreen("1-config-first-time")
            dragAbove(run.name(4), run.name(3))
            compose.waitUntil(MIGRATE_UI_TIMEOUT_MILLIS) {
                val saved = graph.sourcePreferences.migrationSources.get()
                saved.indexOf(run.id(4)) in 0 until saved.indexOf(run.id(3))
            }
            val saved = graph.sourcePreferences.migrationSources.get()
            logcat(LOG_TAG, LogPriority.INFO) { "config: saved at open ${firstSaved.size} sources, after the drag ${saved.size}" }
            batchScreen("2-config-after-drag")

            continueToTheList {
                awaitNode(hasText(context.getString(R.string.migrationConfigScreen_removeDownloadsTitle)))
                assertTrue(
                    "Delete downloads of current entry is off the first time the sheet opens",
                    MigrationFlag.REMOVE_DOWNLOAD in graph.sourcePreferences.migrationFlags.get(),
                )
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(SHEET_KEYWORDS)
                batchScreen("3-sheet")
            }
            awaitListFinished(entries.size * saved.size * PER_SEARCH_MILLIS + MIGRATE_UI_TIMEOUT_MILLIS)
            batchScreen("4-list")
        }
        // Every search the list sent carries the sheet's keywords after the entry's title.
        val state = run.fixture.state()
        val counts = state.getJSONObject("answered").getJSONObject("search")
        val sent = counts.keys().asSequence().sumOf { counts.getInt(it) } - searchesBefore
        val searches = state.getJSONArray("searches").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("q") } }
        assertTrue("the list sent no search", sent > 0)
        val queries = searches.takeLast(minOf(sent, searches.size))
        assertTrue(
            "searches without the keywords: ${queries.filterNot { it.endsWith(" $SHEET_KEYWORDS") }.size} of ${queries.size}",
            queries.all { query -> entries.any { query == "${it.title} $SHEET_KEYWORDS" } },
        )
    }

    /**
     * Section 20.2 steps 3 and 12: with B then C, the default search finds the B-only title on B,
     * the C-only title on C and the shared title on B, and finds neither the title on no target nor
     * the bracketed one; the advanced search finds the bracketed title and still not the other; the
     * chapter-number match takes the shared title from C; "hide unmatched" drops the row without a
     * match and "hide without updates" the match with fewer chapters than the entry. Each run ends
     * in Migrate all, and the database says where each entry went. The window keeps the screen on
     * while the list searches and not once it has closed.
     */
    @Test
    fun theMigrationSearchFindsWhatEachModeShouldFind() = migrateBatchCheck("theMigrationSearchFindsWhatEachModeShouldFind") { run ->
        val b = run.fixture.onA("b")
        val both = run.fixture.onA("both")
        val none = run.fixture.onA("none").first()
        val bracketed = run.fixture.onA("bracketed").first()
        val c = run.fixture.onA("c").first()

        // Default mode: B, C, the shared one on B; nothing for the other two.
        val first = listOf(b[0], c, both[0], none, bracketed)
        runBlocking(Dispatchers.IO) { run.seed(first) }
        run.configure(targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C))
        runBatch(run, first, "default", checkKeepScreenOn = true)
        runBlocking(Dispatchers.IO) {
            assertEquals(MigrateFixture.TARGET_B, run.migratedTo(b[0]))
            assertEquals(MigrateFixture.TARGET_C, run.migratedTo(c))
            assertEquals(MigrateFixture.TARGET_B, run.migratedTo(both[0]))
            assertTrue("the title on no target left the library", run.isFavourite(none))
            assertTrue("the bracketed title was found by the default search", run.isFavourite(bracketed))
        }
        assertTrue("the screen is still kept on after the list closed", !keepScreenOnShown())

        // Advanced search mode: the bracketed title is found on B, the other still nowhere.
        run.configure(targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C), deep = true)
        runBatch(run, listOf(none, bracketed), "deep")
        runBlocking(Dispatchers.IO) {
            assertEquals(MigrateFixture.TARGET_B, run.migratedTo(bracketed))
            assertTrue("the title on no target left the library in advanced mode", run.isFavourite(none))
        }

        // Chapter-number matching with "hide unmatched": the shared titles go to C, and the row
        // without a match leaves the list.
        val byChapters = listOf(both[1], both[2])
        runBlocking(Dispatchers.IO) { run.seed(byChapters) }
        run.configure(
            targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C),
            byChapters = true,
            hideUnmatched = true,
        )
        runBatch(run, listOf(none) + byChapters, "chapters-hide-unmatched", expectShown = byChapters)
        runBlocking(Dispatchers.IO) {
            byChapters.forEach { assertEquals("${it.entry} by chapter number", MigrateFixture.TARGET_C, run.migratedTo(it)) }
        }

        // "Hide without updates": the shared title matched on B (10 chapters, the entry has 12)
        // leaves the list, the B-only title (15) stays, as does the row without a match.
        val updates = listOf(both[3], b[1])
        runBlocking(Dispatchers.IO) { run.seed(updates) }
        run.configure(targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C), hideWithoutUpdates = true)
        runBatch(run, listOf(none) + updates, "hide-without-updates", expectShown = listOf(none, b[1]))
        runBlocking(Dispatchers.IO) {
            assertEquals(MigrateFixture.TARGET_B, run.migratedTo(b[1]))
            assertTrue("the match without updates was migrated", run.isFavourite(both[3]))
        }
    }

    /**
     * Section 20.2 step 4: from the list, manual search picks a target and returns to the list, and
     * so does a pick from a source's listing opened through a group's "more"; a pick of the entry
     * itself or of another entry of the batch is refused and leaves manual search on screen; and one
     * entry migrated from its own screen through manual search and MA's dialog ends on the target's
     * screen, from which back leaves no search screen.
     */
    @Test
    fun theManualPicksReturnToTheListOrMigrateOneEntry() = migrateBatchCheck("theManualPicksReturnToTheListOrMigrateOneEntry") { run ->
        val none = run.fixture.onA("none").take(2)
        val pickFromSearch = run.fixture.onA("b")[5]
        val pickFromMore = run.fixture.onA("b")[6]
        val single = run.fixture.onA("b")[7]
        val batch = none
        runBlocking(Dispatchers.IO) { run.seed(batch) }
        // A among the targets, so manual search lists the batch's own entries to be refused.
        run.configure(targets = listOf(MigrateFixture.OLD_A, MigrateFixture.TARGET_B))

        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(batch.map { it.title }, batch.size)
            migrateTheSelection()
            continueToTheList {}
            awaitListFinished(MIGRATE_RUN_TIMEOUT_MILLIS)

            // A pick of the entry itself, then of the other entry of the batch: both refused.
            openManualSearch(batch[0].title)
            awaitNode(hasText("\u25B6 ${run.name(MigrateFixture.OLD_A)}"))
            batchScreen("1-manual-search-own-source-marked")
            clickResult(batch[0].title)
            awaitNode(hasText(context.getString(R.string.global_search_title)))
            searchFor(batch[1].title)
            clickResult(batch[1].title)
            awaitNode(hasText(context.getString(R.string.global_search_title)))
            batchScreen("2-batch-entry-refused")
            pressBack(it)
            awaitNode(listTitle())

            // A pick from the search returns to the list with the pick as the row's match; B alone
            // is searched from here on.
            run.configure(targets = listOf(MigrateFixture.TARGET_B))
            openManualSearch(batch[0].title)
            searchFor(pickFromSearch.title)
            clickResult(pickFromSearch.title)
            awaitNode(listTitle())
            awaitNode(hasText(pickFromSearch.title))
            batchScreen("3-picked-from-search")

            // A pick from a group's "more", through the source's listing in pick mode.
            openManualSearch(batch[1].title)
            searchFor(pickFromMore.title)
            awaitNode(hasText(context.getString(R.string.global_search_source_more)) and hasClickAction())
            compose.onAllNodes(hasText(context.getString(R.string.global_search_source_more)) and hasClickAction()).onFirst().performClick()
            awaitNode(hasText(pickFromMore.title))
            batchScreen("4-listing-in-pick-mode")
            clickResult(pickFromMore.title)
            awaitNode(listTitle())
            awaitNode(hasText(pickFromMore.title))
            batchScreen("5-picked-from-more")
        }

        // One entry from its own screen: manual search, MA's dialog, the target's screen, then back.
        val old = runBlocking(Dispatchers.IO) { run.seed(listOf(single)).single() }
        run.configure(targets = listOf(MigrateFixture.TARGET_B))
        withEntry(old.id) {
            clickFirst(hasContentDescription(context.getString(R.string.action_menu_overflow_description)))
            clickFirst(hasText(context.getString(R.string.migrate)) and hasClickAction())
            clickFirst(hasText(context.getString(R.string.migrationConfigScreen_continueButtonText)), unmerged = true)
            awaitNode(hasText(context.getString(R.string.global_search_title)))
            clickResult(single.title)
            awaitText(R.string.migration_dialog_what_to_include)
            batchScreen("6-single-dialog")
            compose.onNode(inDialog(R.string.migrate)).performClick()
            val target = runBlocking(Dispatchers.IO) { run.target(single, MigrateFixture.TARGET_B) }
            compose.waitUntil(MIGRATE_RUN_TIMEOUT_MILLIS) {
                runBlocking(Dispatchers.IO) { target != null && graph.mangaRepository.getMangaById(target.id).favorite }
            }
            val remove = hasText(context.getString(R.string.action_remove_from_library)) and hasClickAction()
            awaitNode(remove)
            batchScreen("7-single-target-screen")
            pressBack(it)
            awaitNode(hasText(context.getString(R.string.action_add_to_library)) and hasClickAction())
            assertTrue(
                "back from the target's screen found a search screen",
                compose.onAllNodes(hasText(context.getString(R.string.global_search_title))).fetchSemanticsNodes().isEmpty(),
            )
            batchScreen("8-back-to-the-old-entry")
        }
    }

    /**
     * Section 20.2 step 5: Migrate all with every flag on carries each entry's read chapters,
     * bookmark, category, track, note and cover onto its target on B, takes the old entries out of
     * the library, deletes their downloads, and closes the screen; Copy all on a second batch with
     * Delete downloads switched off in the sheet carries the same and keeps the old entries and their
     * downloads. MA's own assertions are reused for each target.
     */
    @Test
    fun theMigrateAllAndCopyAllRunsCarryTheEntriesOver() = migrateBatchCheck("theMigrateAllAndCopyAllRunsCarryTheEntriesOver") { run ->
        val entries = run.fixture.onA("b").drop(10).take(4)
        val categoryName = "$MIGRATE_CATEGORY_PREFIX${System.nanoTime()}"
        var categoryId = -1L
        try {
            val seeded = runBlocking(Dispatchers.IO) {
                withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) {
                    assertEquals(CreateCategoryWithName.Result.Success, graph.createCategoryWithName.await(categoryName))
                    categoryId = graph.categoryRepository.getAll().single { it.name == categoryName }.id
                    entries.map { entry -> entry to run.seedDetail(entry, categoryId) }
                }
            }
            for ((copy, pairs) in listOf(false to seeded.take(2), true to seeded.drop(2))) {
                run.configure(targets = listOf(MigrateFixture.TARGET_B))
                // This batch alone is in the library while it runs; the migrate batch's targets
                // leave it before the copy batch.
                runBlocking(Dispatchers.IO) {
                    run.onlyInLibrary(pairs.map { it.first })
                    graph.mangaRepository.updateAll(pairs.map { (_, detail) -> MangaUpdate(id = detail.old.id, favorite = true) })
                }
                val name = if (copy) "copy" else "migrate"
                withMain(MainDestination.LIBRARY) {
                    selectAllInLibrary(pairs.map { it.first.title }, pairs.size)
                    migrateTheSelection()
                    continueToTheList {
                        awaitNode(hasText(context.getString(R.string.migrationConfigScreen_removeDownloadsTitle)))
                        assertTrue(
                            "Delete downloads of current entry is off when the sheet opens",
                            MigrationFlag.REMOVE_DOWNLOAD in graph.sourcePreferences.migrationFlags.get(),
                        )
                        if (copy) {
                            clickFirst(hasText(context.getString(R.string.migrationConfigScreen_removeDownloadsTitle)))
                            compose.waitUntil(MIGRATE_UI_TIMEOUT_MILLIS) {
                                MigrationFlag.REMOVE_DOWNLOAD !in graph.sourcePreferences.migrationFlags.get()
                            }
                        }
                        batchScreen("$name-1-sheet")
                    }
                    awaitListFinished(MIGRATE_RUN_TIMEOUT_MILLIS)
                    batchScreen("$name-2-list")
                    migrateAll(copy)
                    awaitNoNode(listTitle(), MIGRATE_RUN_TIMEOUT_MILLIS)
                    batchScreen("$name-3-closed")
                }
                runBlocking(Dispatchers.IO) {
                    for ((entry, detail) in pairs) {
                        val target = checkNotNull(run.target(entry, MigrateFixture.TARGET_B)) { "no target for ${entry.entry}" }
                        assertTheTargetHoldsTheOldEntry(
                            MigrationPair(detail.old, target, detail.remoteId, detail.downloadedChapterId, detail.downloadDir),
                            categoryId,
                        )
                        val old = graph.mangaRepository.getMangaById(detail.old.id)
                        assertEquals("${entry.entry}: the old entry's place in the library after $name", copy, old.favorite)
                        assertEquals(
                            "${entry.entry}: the old entry's download after $name",
                            copy,
                            graph.downloadManifest.isDownloaded(detail.downloadedChapterId) && detail.downloadDir.exists(),
                        )
                    }
                }
            }
        } finally {
            if (categoryId > 0) runBlocking(Dispatchers.IO) { graph.categoryRepository.delete(categoryId) }
        }
    }

    /**
     * Section 20.2 step 6: in a 60-entry Migrate all whose targets' chapter lists fail for the
     * entries numbered 40 to 45, the screen stays with those six listed, each still in the library
     * on its old source with its download, the rest written, and the snackbar counts six. Then a
     * window of searches on B answering 503: the entries it hit fall through to C, as many as the
     * server answered 503.
     */
    @Test
    fun theFailedWritesStayListedAndAnUnavailableSourceFallsThrough() = migrateBatchCheck("theFailedWritesStayListedAndAnUnavailableSourceFallsThrough") { run ->
        val sixty = run.fixture.onA("b").take(FAILURE_BATCH)
        val failing = sixty.filter { it.entry in FAILING_ENTRIES }
        val seeded = runBlocking(Dispatchers.IO) {
            withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) {
                val all = run.seed(sixty)
                failing.associateWith { entry -> download(all.single { it.url == entry.url }) }
            }
        }
        run.configure(targets = listOf(MigrateFixture.TARGET_B))
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(sixty.map { it.title }, sixty.size)
            migrateTheSelection()
            continueToTheList {}
            awaitListFinished(sixty.size * PER_ENTRY_MILLIS)
            val spec = failing.joinToString(",") { "${MigrateFixture.TARGET_B}:${it.onB}" }
            run.fixture.control("fail_chapters=$spec")
            migrateAll(copy = false)
            awaitNode(
                hasText(context.resources.getQuantityString(R.plurals.migration_failed_count, failing.size, failing.size)),
                sixty.size * PER_ENTRY_MILLIS,
            )
            awaitNode(hasText(context.getString(R.string.migrationListScreenTitleWithProgress, failing.size, failing.size)))
            batchScreen("1-six-failed")
        }
        run.fixture.control("fail_chapters=")
        runBlocking(Dispatchers.IO) {
            for (entry in sixty) {
                val old = checkNotNull(graph.mangaRepository.getMangaByUrlAndSourceId(entry.url, run.id(MigrateFixture.OLD_A)))
                val failed = entry in failing
                assertEquals("${entry.entry}: still in the library", failed, old.favorite)
                assertEquals("${entry.entry}: its target in the library", !failed, run.target(entry, MigrateFixture.TARGET_B)?.favorite == true)
            }
            seeded.forEach { (entry, download) ->
                assertTrue("${entry.entry}: its download is gone", graph.downloadManifest.isDownloaded(download.first) && download.second.exists())
            }
            // The six stay listed; the next check starts from a library without them.
            graph.mangaRepository.updateAll(
                failing.mapNotNull { graph.mangaRepository.getMangaByUrlAndSourceId(it.url, run.id(MigrateFixture.OLD_A)) }
                    .map { MangaUpdate(id = it.id, favorite = false) },
            )
        }

        val shared = run.fixture.onA("both").take(UNAVAILABLE_BATCH)
        runBlocking(Dispatchers.IO) { run.seed(shared).also { run.onlyInLibrary(shared) } }
        run.configure(targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C))
        val answeredBefore = run.fixture.state().getInt("unavailableAnswered")
        run.fixture.control("unavailable=${MigrateFixture.TARGET_B}:$UNAVAILABLE_WINDOW")
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(shared.map { it.title }, shared.size)
            migrateTheSelection()
            continueToTheList {}
            awaitListFinished(shared.size * PER_ENTRY_MILLIS)
            batchScreen("2-unavailable-window")
            migrateAll(copy = false)
            awaitNoNode(listTitle(), shared.size * PER_ENTRY_MILLIS)
        }
        val unavailable = run.fixture.state().getInt("unavailableAnswered") - answeredBefore
        assertEquals("503s the window answered", UNAVAILABLE_WINDOW, unavailable)
        val onC = runBlocking(Dispatchers.IO) { shared.count { run.migratedTo(it) == MigrateFixture.TARGET_C } }
        val onB = runBlocking(Dispatchers.IO) { shared.count { run.migratedTo(it) == MigrateFixture.TARGET_B } }
        assertEquals("entries that fell through to C", UNAVAILABLE_WINDOW, onC)
        assertEquals("entries found on B", shared.size - UNAVAILABLE_WINDOW, onB)
    }

    /**
     * Section 20.2 step 7: the duplicate pair, one title on A and on the second old source, both in
     * the library and migrated together with A, the second old source and B as targets. Neither is
     * taken as the other's target (M4), so both match the same entry on B; the first written moves
     * onto it and the second is held (M6): it stays in the library and in the list, whose match now
     * carries the in-library mark, and the snackbar counts one held.
     */
    @Test
    fun theDuplicatePairIsNotItsOwnTargetAndASharedTargetIsHeld() = migrateBatchCheck("theDuplicatePairIsNotItsOwnTargetAndASharedTargetIsHeld") { run ->
        val pair = listOf(
            run.fixture.entry(MigrateFixture.OLD_A, 1),
            run.fixture.entry(MigrateFixture.OLD_OVERFLOW, run.fixture.entries.getValue(MigrateFixture.OLD_OVERFLOW).values.single { it.role == "duplicate" }.entry),
        )
        assertEquals(pair[0].title, pair[1].title)
        runBlocking(Dispatchers.IO) { run.seed(pair) }
        run.configure(targets = listOf(MigrateFixture.OLD_A, MigrateFixture.OLD_OVERFLOW, MigrateFixture.TARGET_B))
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(pair.map { it.title }, pair.size)
            migrateTheSelection()
            continueToTheList {}
            awaitListFinished(MIGRATE_RUN_TIMEOUT_MILLIS)
            batchScreen("1-both-matched")
            migrateAll(copy = false)
            awaitNode(hasText(context.resources.getQuantityString(R.plurals.migration_held_count, 1, 1)), MIGRATE_RUN_TIMEOUT_MILLIS)
            awaitNode(hasText(context.getString(R.string.migrationListScreenTitleWithProgress, 1, 1)))
            batchScreen("2-second-held")
        }
        runBlocking(Dispatchers.IO) {
            val target = checkNotNull(run.target(pair[0], MigrateFixture.TARGET_B))
            assertTrue("the shared target is not in the library", target.favorite)
            val olds = pair.map { checkNotNull(graph.mangaRepository.getMangaByUrlAndSourceId(it.url, run.id(it.source))) }
            assertEquals("old entries still in the library", 1, olds.count { it.favorite })
            val sameSource = graph.mangaRepository.getFavorites().filter { it.title == pair[0].title }
            assertTrue(
                "an entry of the pair was migrated onto the other: ${sameSource.map { it.source }}",
                sameSource.none { it.source == run.id(MigrateFixture.OLD_OVERFLOW) && it.url != pair[1].url },
            )
        }
    }

    /**
     * Section 20.2 step 8: Cancel in Migrate all's progress dialog once some entries are written:
     * those have left the list and the library, the rest stay in both, and the screen stays.
     */
    @Test
    fun theCancelledRunKeepsTheUnwrittenEntries() = migrateBatchCheck("theCancelledRunKeepsTheUnwrittenEntries") { run ->
        val entries = run.fixture.onA("b").drop(20).take(CANCEL_BATCH)
        val seeded = runBlocking(Dispatchers.IO) { run.seed(entries) }
        run.configure(targets = listOf(MigrateFixture.TARGET_B))
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(entries.map { it.title }, entries.size)
            migrateTheSelection()
            continueToTheList {}
            awaitListFinished(entries.size * PER_ENTRY_MILLIS)
            migrateAll(copy = false)
            compose.waitUntil(MIGRATE_RUN_TIMEOUT_MILLIS) {
                runBlocking(Dispatchers.IO) { seeded.count { !run.isFavourite(it) } >= CANCEL_AFTER }
            }
            compose.onNode(inDialog(R.string.action_cancel)).performClick()
            awaitNoNode(isDialog())
            batchScreen("1-cancelled")
            val written = runBlocking(Dispatchers.IO) { seeded.count { !run.isFavourite(it) } }
            assertTrue("every entry was written before the cancel", written < entries.size)
            awaitNode(listTitle(entries.size - written, entries.size - written))
            // The grid composes only its first rows: some kept entry is among them, and no written
            // entry is listed anywhere.
            val (left, gone) = runBlocking(Dispatchers.IO) { seeded.partition { run.isFavourite(it) } }
            awaitNode(hasAnyText(left.map { it.title }.toSet()))
            awaitNoNode(hasAnyText(gone.map { it.title }.toSet()))
            logcat(LOG_TAG, LogPriority.INFO) { "cancel: $written of ${entries.size} written, the rest listed" }
        }
    }

    /**
     * Section 20.2 step 9: the screen rotated while the list searches and again while Migrate all
     * writes. Nothing restarts: every entry is searched on B once, each target's chapter list is
     * fetched once by the search and once by the write, and every entry ends migrated once. Section
     * 20.3's twenty-entry run on the API 26 image is this check.
     */
    @Test
    fun theRotatedListRestartsNothing() = migrateBatchCheck("theRotatedListRestartsNothing") { run ->
        val entries = run.fixture.onA("b").drop(30).take(ROTATION_BATCH)
        runBlocking(Dispatchers.IO) { run.seed(entries) }
        run.configure(targets = listOf(MigrateFixture.TARGET_B))
        val searchesBefore = run.fixture.answered("search", MigrateFixture.TARGET_B)
        val chaptersBefore = run.fixture.answered("chapters", MigrateFixture.TARGET_B)
        withMain(MainDestination.LIBRARY) { scenario ->
            selectAllInLibrary(entries.map { it.title }, entries.size)
            migrateTheSelection()
            continueToTheList {}
            awaitProgress { finished, _ -> finished >= ROTATE_AFTER }
            rotate(scenario, landscape = true)
            batchScreen("1-rotated-searching")
            awaitListFinished(entries.size * PER_ENTRY_MILLIS)
            rotate(scenario, landscape = false)
            val searched = run.fixture.answered("search", MigrateFixture.TARGET_B) - searchesBefore
            assertEquals("searches on B for ${entries.size} entries", entries.size, searched)
            migrateAll(copy = false, checkKeepScreenOn = true)
            compose.waitUntil(MIGRATE_RUN_TIMEOUT_MILLIS) {
                runBlocking(Dispatchers.IO) { entries.count { run.migratedTo(it) == MigrateFixture.TARGET_B } >= ROTATE_AFTER }
            }
            rotate(scenario, landscape = true)
            batchScreen("2-rotated-writing")
            awaitNoNode(listTitle(), entries.size * PER_ENTRY_MILLIS)
            rotate(scenario, landscape = false, onTheList = false)
        }
        assertTrue("the screen is still kept on after the list closed", !keepScreenOnShown())
        runBlocking(Dispatchers.IO) {
            entries.forEach { assertEquals("${it.entry} migrated", MigrateFixture.TARGET_B, run.migratedTo(it)) }
        }
        val chapters = run.fixture.answered("chapters", MigrateFixture.TARGET_B) - chaptersBefore
        assertEquals("chapter lists fetched from B: one by the search and one by the write per entry", 2 * entries.size, chapters)
    }

    /**
     * Section 20.2 step 13: the 300-entry run in the default mode, from the library's Select all
     * over A and the second old source, with B and C as targets: the time from the list's start to
     * its last result, Migrate all's time, and the process's memory before, midway and after, logged
     * for the report. The server's request counts per kind are read from its log on the host; the
     * fixture answers in 0.4 to 0.8 s, so the figures are the fixture's upper bound.
     */
    @Test
    fun theThreeHundredEntryRunIsTimed() = migrateBatchCheck("theThreeHundredEntryRunIsTimed") { run ->
        val entries = run.fixture.entries.getValue(MigrateFixture.OLD_A).values.sortedBy { it.entry } +
            run.fixture.entries.getValue(MigrateFixture.OLD_OVERFLOW).values.filter { it.role == "b" }.sortedBy { it.entry }
        assertEquals(LARGE_BATCH, entries.size)
        val seeded = runBlocking(Dispatchers.IO) { withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) { run.seed(entries) } }
        run.configure(targets = listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C))
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(entries.map { it.title }, entries.size)
            migrateTheSelection()
            logMemory("before")
            val start = SystemClock.elapsedRealtime()
            continueToTheList {}
            awaitProgress(LARGE_RUN_TIMEOUT_MILLIS) { finished, _ -> finished >= entries.size / 2 }
            logMemory("midway")
            awaitListFinished(LARGE_RUN_TIMEOUT_MILLIS)
            val searched = SystemClock.elapsedRealtime() - start
            batchScreen("1-searched")
            val writeStart = SystemClock.elapsedRealtime()
            migrateAll(copy = false)
            awaitNoNode(listTitle(), LARGE_RUN_TIMEOUT_MILLIS)
            val written = SystemClock.elapsedRealtime() - writeStart
            logMemory("after")
            val migrated = runBlocking(Dispatchers.IO) { seeded.count { !run.isFavourite(it) } }
            logcat(LOG_TAG, LogPriority.INFO) {
                "300-entry run: ${entries.size} entries, search ${searched} ms, Migrate all ${written} ms, $migrated migrated"
            }
            val expected = entries.count { it.role in listOf("b", "c", "both") }
            assertEquals("entries migrated in the default mode", expected, migrated)
        }
    }

    /**
     * R101 carry-over 4 (phase 4 device coverage unit DC2, item 3): the library fingerprint's time
     * and Java heap on a library far larger than `sync_3000_no_oom`'s own, and how long a concurrent
     * write waits while it reads (R101 item 3's own carry-over). Skips without `saikou.fingerprint.10k`
     * or the three sign-in arguments [com.shinsaikou.sync.SyncPairTest] already names, `saikou.sync.pair.url`,
     * `.user` and `.password`, for a WebDAV fixture this run alone started: the row stays hidden
     * while no backend is configured (R101 item 1), which is what the sign-in is for, never a real
     * sync of anyone's data.
     */
    @Test
    fun theLibraryFingerprintTimesATenThousandEntryLibrary() {
        val method = "theLibraryFingerprintTimesATenThousandEntryLibrary"
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(FINGERPRINT_ARGUMENT).isNullOrBlank()) return skip(method, "no $FINGERPRINT_ARGUMENT argument")
        val url = arguments.getString("saikou.sync.pair.url")
        val user = arguments.getString("saikou.sync.pair.user")
        val password = arguments.getString("saikou.sync.pair.password")
        if (url.isNullOrBlank() || user.isNullOrBlank() || password.isNullOrBlank()) {
            return skip(method, "no saikou.sync.pair.url, .user or .password argument")
        }
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()

        val library = runBlocking(Dispatchers.IO) { SyncLibrarySeeder.seed(graph, FINGERPRINT_ENTRIES) }
        logcat(LOG_TAG, LogPriority.INFO) {
            "fingerprint: seeded ${library.mangaIds.size} entries, ${library.chaptersInserted} chapters"
        }
        fingerprintMemory("after-seed")

        runBlocking(Dispatchers.IO) {
            val status = graph.webDavAccount.signIn(url, user, password)
            assertTrue("the fixture refused the sign-in: $status", status.reachable && status.authenticated)
            val backend = checkNotNull(graph.webDavAccount.backend()) { "the stored sign-in builds no backend" }
            graph.syncSession.signedIn(backend)
        }
        awaitNoSyncWork()
        fingerprintMemory("after-sign-in-sync")

        withMain(MainDestination.LIBRARY) {
            clickFirst(hasText(context.getString(R.string.tab_more)) and isSelectable())
            val settingsRow = hasText(context.getString(R.string.label_settings)) and hasClickAction()
            compose.onNode(hasScrollAction()).performScrollToNode(settingsRow)
            compose.onNode(settingsRow).performClick()
            val syncRow = hasText(context.getString(SettingsSection.SYNC.labelRes)) and hasClickAction() and isSelectable().not()
            clickFirst(syncRow)
            val fingerprintRow = hasText(context.getString(R.string.sync_fingerprint)) and hasClickAction()
            awaitNode(fingerprintRow, MIGRATE_UI_TIMEOUT_MILLIS)

            val computing = hasText(context.getString(R.string.sync_fingerprint_computing))
            val tapAt = AtomicLong(-1L)
            val stop = AtomicReference(false)
            val samples = AtomicInteger(0)
            val sampler = Thread {
                while (stop.get() != true) {
                    fingerprintMemory("during t=${SystemClock.elapsedRealtime() - tapAt.get()}ms")
                    samples.incrementAndGet()
                    Thread.sleep(FINGERPRINT_SAMPLE_MILLIS)
                }
            }

            val writeId = library.mangaIds.first()
            val writeWaitedMillis = AtomicLong(-1L)
            val writeStartedAfterMillis = AtomicLong(-1L)
            val writeOk = AtomicReference(false)
            val writer = Thread {
                writeStartedAfterMillis.set(SystemClock.elapsedRealtime() - tapAt.get())
                val start = SystemClock.elapsedRealtime()
                val ok = runBlocking(Dispatchers.IO) {
                    graph.mangaRepository.update(MangaUpdate(id = writeId, dateAdded = System.currentTimeMillis()))
                }
                writeWaitedMillis.set(SystemClock.elapsedRealtime() - start)
                writeOk.set(ok)
            }

            tapAt.set(SystemClock.elapsedRealtime())
            compose.onAllNodes(fingerprintRow).onFirst().performClick()
            sampler.start()
            writer.start()

            // In try/finally: an uncaught OutOfMemoryError in computeFingerprint's own coroutine
            // (it catches Exception, not Error, phase 4 device coverage unit DC2's own finding)
            // leaves state stuck at Computing forever, so awaitNoNode's timeout, not a clean
            // return, is the path this takes when that happens; the sampler must stop regardless,
            // or it samples every FINGERPRINT_SAMPLE_MILLIS for as long as the process survives.
            var computeMillis = -1L
            try {
                awaitNoNode(computing, FINGERPRINT_COMPUTE_TIMEOUT_MILLIS)
                computeMillis = SystemClock.elapsedRealtime() - tapAt.get()
            } finally {
                stop.set(true)
                sampler.join(FINGERPRINT_SAMPLE_MILLIS * 4)
                writer.join(FINGERPRINT_COMPUTE_TIMEOUT_MILLIS)
            }

            fingerprintMemory("after-compute")
            logcat(LOG_TAG, LogPriority.INFO) {
                "fingerprint: ${library.mangaIds.size} entries computed in $computeMillis ms, " +
                    "${samples.get()} memory samples during, concurrent write started " +
                    "${writeStartedAfterMillis.get()} ms after the tap and waited ${writeWaitedMillis.get()} ms, ok=${writeOk.get()}"
            }
            assertTrue("the concurrent write failed", writeOk.get())
        }
    }

    /** Polls until [SyncScheduler.WORK_NAME_ONCE] holds nothing enqueued, blocked or running. */
    private fun awaitNoSyncWork() {
        val pending = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
        val idle = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(FINGERPRINT_SYNC_TIMEOUT_MILLIS) {
                while (graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get().any { it.state in pending }) {
                    delay(FINGERPRINT_POLL_MILLIS)
                }
                true
            }
        }
        assertTrue("the signed-in sync did not finish within $FINGERPRINT_SYNC_TIMEOUT_MILLIS ms", idle == true)
    }

    /** [label]'s TOTAL PSS and Java Heap lines from `dumpsys meminfo`, for the fingerprint check alone. */
    private fun fingerprintMemory(label: String): String {
        val output = shell("dumpsys meminfo ${context.packageName}")
        val total = output.lineSequence().firstOrNull { it.trimStart().startsWith("TOTAL") }?.trim()
        val javaHeap = output.lineSequence().firstOrNull { it.trim().startsWith("Java Heap:") }?.trim()
        val line = "fingerprint memory $label: $total; $javaHeap"
        logcat(LOG_TAG, LogPriority.INFO) { line }
        Log.i(LOG_TAG, line)
        return line
    }

    /**
     * Section 20.2 steps 10 and 11, the part inside the app's process: seeds the library a host
     * driver then migrates through the screens, killing the process between, and writes what it
     * seeded to MIGRATE_BATCH_SEED_FILE in the app's files, which the driver reads with `run-as`.
     * `saikou.migrate.seed` names the library: `kill`, thirty entries of A found on B, each with a
     * category, three read chapters, a track and chapter 1 downloaded; `route`, 500 entries on two
     * fixture sources that list nothing, for the 500-id route.
     * Nothing is cleaned up here: [theProcessDeathOutcomeHolds] reads it back and clears it.
     */
    @Test
    fun theProcessDeathLibraryIsSeeded() {
        val method = "theProcessDeathLibraryIsSeeded"
        val layout = InstrumentationRegistry.getArguments().getString(MIGRATE_SEED_ARGUMENT)
            ?: return skip(method, "no $MIGRATE_SEED_ARGUMENT argument")
        val apk = InstrumentationRegistry.getArguments().getString(FIXTURE_APK_ARGUMENT)
            ?: return skip(method, "no $FIXTURE_APK_ARGUMENT argument")
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()
        val fixture = MigrateFixture(FIXTURE_SERVER)
        fixture.control("reset=1")
        val seeded = runBlocking(Dispatchers.IO) {
            withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) {
                val sources = installTheFixtureExtension(apk, ROUTE_SOURCES)
                val run = MigrateRun(fixture, sources.withIndex().associate { (i, s) -> i + 1 to s })
                clearTheMigrationFixture(sources, categoryId = -1L)
                run.configure(targets = listOf(MigrateFixture.TARGET_B))
                when (layout) {
                    "kill" -> {
                        val categoryName = "$MIGRATE_CATEGORY_PREFIX${System.nanoTime()}"
                        assertEquals(CreateCategoryWithName.Result.Success, graph.createCategoryWithName.await(categoryName))
                        val categoryId = graph.categoryRepository.getAll().single { it.name == categoryName }.id
                        fixture.onA("b").drop(40).take(KILL_BATCH).map { entry ->
                            val detail = run.seedDetail(entry, categoryId)
                            JSONObject()
                                .put("old", detail.old.id)
                                .put("title", entry.title)
                                .put("targetUrl", "/manga/${entry.onB}")
                                .put("targetSource", run.id(MigrateFixture.TARGET_B))
                                .put("category", categoryId)
                                .put("remoteId", detail.remoteId)
                                .put("chapter", detail.downloadedChapterId)
                        }
                    }
                    "route" -> (1..ROUTE_BATCH).map { n ->
                        val source = ROUTE_FIRST_SOURCE + (n - 1) / ROUTE_PER_SOURCE
                        val entry = MigrateFixture.Entry(source, (n - 1) % ROUTE_PER_SOURCE + 1, "Route check %03d".format(n), "route", 1, null, null)
                        val manga = run.seed(listOf(entry)).single()
                        JSONObject().put("old", manga.id).put("title", entry.title)
                    }
                    else -> throw AssertionError("$MIGRATE_SEED_ARGUMENT names no layout: $layout")
                }
            }
        }
        File(context.filesDir, MIGRATE_BATCH_SEED_FILE)
            .writeText(JSONObject().put("layout", layout).put("entries", JSONArray(seeded)).toString())
        logcat(LOG_TAG, LogPriority.INFO) { "seeded $layout: ${seeded.size} entries" }
        // The app the driver opens next reads the battery answer, the extension's trust and the
        // migration targets set here, and the process ends as this returns (phase 5 unit TG4).
        StagePreferences.commit()
    }

    /**
     * The other half of [theProcessDeathLibraryIsSeeded], run after the host driver's kills: for the
     * `kill` library each entry is either fully migrated (the target in the library with the
     * category, the track and the read chapters; the old entry out; its download gone) or untouched
     * (the old entry in the library with its download; the target with no category and no track),
     * and the count of untouched entries is logged for the driver to compare with the restored
     * list. Then everything the seed left is removed.
     */
    @Test
    fun theProcessDeathOutcomeHolds() {
        val method = "theProcessDeathOutcomeHolds"
        val layout = InstrumentationRegistry.getArguments().getString(MIGRATE_SEED_ARGUMENT)
            ?: return skip(method, "no $MIGRATE_SEED_ARGUMENT argument")
        val seedFile = File(context.filesDir, MIGRATE_BATCH_SEED_FILE)
        val seed = JSONObject(seedFile.readText())
        assertEquals(layout, seed.getString("layout"))
        val entries = seed.getJSONArray("entries").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
        runBlocking(Dispatchers.IO) {
            if (layout == "kill") {
                var migrated = 0
                var untouched = 0
                for (entry in entries) {
                    val old = graph.mangaRepository.getMangaById(entry.getLong("old"))
                    val target = graph.mangaRepository.getMangaByUrlAndSourceId(entry.getString("targetUrl"), entry.getLong("targetSource"))
                    val downloaded = graph.downloadManifest.isDownloaded(entry.getLong("chapter"))
                    val targetCategories = target?.let { graph.categoryRepository.getCategoriesByMangaId(it.id).map { c -> c.id } }.orEmpty()
                    val targetTracks = target?.let { graph.trackRepository.getTracksByMangaId(it.id) }.orEmpty()
                    if (!old.favorite) {
                        migrated++
                        assertTrue("${old.id}: migrated, but its target is not in the library", target?.favorite == true)
                        assertEquals("${old.id}: the target's categories", listOf(entry.getLong("category")), targetCategories)
                        assertEquals("${old.id}: the target's track", listOf(entry.getLong("remoteId")), targetTracks.map { it.remoteId })
                        val read = graph.chapterRepository.getChapterByMangaId(target!!.id).filter { it.read }.map { it.chapterNumber }.sorted()
                        assertEquals("${old.id}: the target's read chapters", listOf(1.0, 2.0, 3.0), read)
                        assertTrue("${old.id}: migrated, but its download is still there", !downloaded)
                    } else {
                        untouched++
                        assertTrue("${old.id}: untouched, but its download is gone", downloaded)
                        assertTrue("${old.id}: untouched, but its target is in the library", target?.favorite != true)
                        assertEquals("${old.id}: untouched, but its target has categories", emptyList<Long>(), targetCategories)
                        assertEquals("${old.id}: untouched, but its target has a track", 0, targetTracks.size)
                    }
                }
                logcat(LOG_TAG, LogPriority.INFO) { "process death outcome: $migrated migrated, $untouched untouched" }
                Log.i(LOG_TAG, "outcome: migrated=$migrated untouched=$untouched")
            }
            val sources = graph.extensionManager.installedExtensions.value
                .flatMap { it.sources }
                .filterIsInstance<CatalogueSource>()
                .filter { it.name.startsWith(FIXTURE_SOURCE_PREFIX) }
            val categories = entries.mapNotNull { it.optLong("category", -1L).takeIf { id -> id > 0 } }.distinct()
            clearTheMigrationFixture(sources, categoryId = -1L)
            categories.forEach { graph.categoryRepository.delete(it) }
            sources.firstOrNull()?.let { extensionOf(it) }?.let { removeTheFixtureExtension(it) }
        }
        seedFile.delete()
    }

    /** The migrate fixture's sources by the catalogue's number (1 is A, 3 is B), and its catalogue. */
    private class MigrateRun(val fixture: MigrateFixture, val sources: Map<Int, CatalogueSource>) {
        fun id(number: Int): Long = sources.getValue(number).id

        fun name(number: Int): String = sources.getValue(number).name
    }

    /** What [seedDetail] left for one entry, in the shape MA's [assertTheTargetHoldsTheOldEntry] reads. */
    private class DetailSeed(val old: Manga, val remoteId: Long, val downloadedChapterId: Long, val downloadDir: File)

    /** Where the current check's screens go. */
    private var batchScreens = MIGRATE_BATCH_SCREENS_DIR

    /**
     * Runs one of unit MB's checks: skips without `saikou.migrate.batch` or the fixture extension,
     * installs the extension with the migrate fixture's four sources, runs [body], and whatever
     * happened puts the six migration keys back, clears the server's failures, takes the fixture's
     * entries out of the library and removes the extension.
     */
    private fun migrateBatchCheck(method: String, body: (MigrateRun) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(MIGRATE_BATCH_ARGUMENT).isNullOrBlank()) return skip(method, "no $MIGRATE_BATCH_ARGUMENT argument")
        val apk = arguments.getString(FIXTURE_APK_ARGUMENT)
        if (apk.isNullOrBlank()) return skip(method, "no $FIXTURE_APK_ARGUMENT argument")
        val preferences = graph.sourcePreferences
        val restore = listOf(
            snapshot(preferences.migrationSources),
            snapshot(preferences.migrationFlags),
            snapshot(preferences.migrationDeepSearchMode),
            snapshot(preferences.migrationPrioritizeByChapters),
            snapshot(preferences.migrationHideUnmatched),
            snapshot(preferences.migrationHideWithoutUpdates),
        )
        batchScreens = "$MIGRATE_BATCH_SCREENS_DIR/$method"
        shell("rm -rf $batchScreens")
        shell("mkdir -p $batchScreens")
        graph.basePreferences.batteryExemptionRequested.set(true)
        graph.crashLog.markExported()
        val fixture = MigrateFixture(FIXTURE_SERVER)
        fixture.control("reset=1")
        var sources: List<CatalogueSource> = emptyList()
        try {
            sources = runBlocking(Dispatchers.IO) {
                withTimeout(MIGRATE_SEED_TIMEOUT_MILLIS) {
                    installTheFixtureExtension(apk, MigrateFixture.SOURCES).also { clearTheMigrationFixture(it, categoryId = -1L) }
                }
            }
            body(MigrateRun(fixture, sources.withIndex().associate { (index, source) -> index + 1 to source }))
            logcat(LOG_TAG, LogPriority.INFO) { "Passed: $method" }
        } finally {
            restore.forEach { it() }
            runCatching { fixture.control("reset=1") }
            runBlocking(Dispatchers.IO) {
                clearTheMigrationFixture(sources, categoryId = -1L)
                sources.firstOrNull()?.let { extensionOf(it) }?.let { removeTheFixtureExtension(it) }
            }
        }
    }

    private fun <T> snapshot(preference: Preference<T>): () -> Unit {
        val value = preference.get()
        return { preference.set(value) }
    }

    /** The migration keys a check runs under: the targets in order, the modes, and every flag. */
    private fun MigrateRun.configure(
        targets: List<Int>,
        deep: Boolean = false,
        byChapters: Boolean = false,
        hideUnmatched: Boolean = false,
        hideWithoutUpdates: Boolean = false,
    ) {
        val preferences = graph.sourcePreferences
        preferences.migrationSources.set(targets.map(::id))
        preferences.migrationFlags.set(MigrationFlag.entries.toSet())
        preferences.migrationDeepSearchMode.set(deep)
        preferences.migrationPrioritizeByChapters.set(byChapters)
        preferences.migrationHideUnmatched.set(hideUnmatched)
        preferences.migrationHideWithoutUpdates.set(hideWithoutUpdates)
    }

    /**
     * Each entry inserted as the browse screen inserts one, put in the library, with its chapters
     * 1 to its count written locally, as a library update leaves them: nothing is fetched.
     */
    private suspend fun MigrateRun.seed(entries: List<MigrateFixture.Entry>): List<Manga> {
        val now = System.currentTimeMillis()
        return entries.mapIndexed { index, entry ->
            val sourceId = id(entry.source)
            val inserted = graph.mangaRepository.insertNetworkManga(
                listOf(MangaInfo(url = entry.url, title = entry.title).toDomainManga(sourceId)),
            ).single()
            graph.mangaRepository.update(MangaUpdate(id = inserted.id, favorite = true, dateAdded = now - index))
            graph.chapterRepository.addAll(
                (1..entry.chapters).map { number ->
                    ChapterInfo(url = "${entry.url}/chapter/$number", name = "Chapter $number", chapterNumber = number.toFloat())
                        .toDomainChapter(inserted.id)
                        .copy(sourceOrder = (entry.chapters - number).toLong())
                },
            )
            graph.mangaRepository.getMangaById(inserted.id)
        }
    }

    /**
     * [entry] in the library as MA's check seeds its old entry: [categoryId], a note, a custom
     * cover, a track, chapters 1 to 3 read, chapter 2 bookmarked, and chapter 1 downloaded.
     */
    private suspend fun MigrateRun.seedDetail(entry: MigrateFixture.Entry, categoryId: Long): DetailSeed {
        val manga = seed(listOf(entry)).single()
        graph.mangaRepository.update(MangaUpdate(id = manga.id, notes = "$MIGRATE_NOTE_PREFIX${entry.entry}"))
        val byNumber = graph.chapterRepository.getChapterByMangaId(manga.id).associateBy { it.chapterNumber }
        graph.chapterRepository.updateAll(
            (1..3).map { number -> ChapterUpdate(id = byNumber.getValue(number.toDouble()).id, read = true, bookmark = number == 2) },
        )
        graph.mangaRepository.setMangaCategories(manga.id, listOf(categoryId))
        val remoteId = MIGRATE_BATCH_REMOTE_ID + entry.entry
        graph.trackRepository.insert(
            Track(
                id = 0,
                mangaId = manga.id,
                trackerId = TrackerIds.MYANIMELIST,
                remoteId = remoteId,
                libraryId = null,
                title = entry.title,
                lastChapterRead = 3.0,
                totalChapters = 0,
                status = 1,
                score = 0.0,
                remoteUrl = "",
                startDate = 0,
                finishDate = 0,
                private = false,
            ),
        )
        graph.coverCache.getCustomCoverFile(manga.id).also { it.parentFile?.mkdirs() }.let { writePage(it) }
        val (chapterId, dir) = download(graph.mangaRepository.getMangaById(manga.id))
        return DetailSeed(graph.mangaRepository.getMangaById(manga.id), remoteId, chapterId, dir)
    }

    /** Downloads [manga]'s chapter 1 from the fixture server and returns it with its folder. */
    private suspend fun download(manga: Manga): Pair<Long, File> {
        val first = graph.chapterRepository.getChapterByMangaId(manga.id).single { it.chapterNumber == 1.0 }
        graph.downloadQueue.enqueue(manga, listOf(first), autoStart = true)
        awaitResumed(listOf(first.id))
        val dir = graph.downloadManifest.getChapterDir(first.id)
        assertNotNull("chapter ${first.id} is downloaded with no folder", dir)
        return first.id to dir!!
    }

    private suspend fun MigrateRun.target(entry: MigrateFixture.Entry, on: Int): Manga? =
        entry.on(on)?.let { graph.mangaRepository.getMangaByUrlAndSourceId("/manga/$it", id(on)) }

    /** The target source whose entry of [entry]'s title is in the library, or null. */
    private suspend fun MigrateRun.migratedTo(entry: MigrateFixture.Entry): Int? =
        listOf(MigrateFixture.TARGET_B, MigrateFixture.TARGET_C).firstOrNull { target(entry, it)?.favorite == true }

    private suspend fun MigrateRun.isFavourite(entry: MigrateFixture.Entry): Boolean =
        graph.mangaRepository.getMangaByUrlAndSourceId(entry.url, id(entry.source))?.favorite == true

    private suspend fun MigrateRun.isFavourite(manga: Manga): Boolean = graph.mangaRepository.getMangaById(manga.id).favorite

    /** Takes every fixture entry other than [entries] out of the library, earlier runs' targets too. */
    private suspend fun MigrateRun.onlyInLibrary(entries: List<MigrateFixture.Entry>) {
        val keep = entries.map { id(it.source) to it.url }.toSet()
        val ids = sources.values.map { it.id }.toSet()
        graph.mangaRepository.updateAll(
            graph.mangaRepository.getFavorites()
                .filter { it.source in ids && (it.source to it.url) !in keep }
                .map { MangaUpdate(id = it.id, favorite = false) },
        )
    }

    /**
     * One batch from the library through the config screen and the sheet to the list, whose results
     * are awaited, then Migrate all. Only [entries] are in the library while it runs. With
     * [expectShown] the list must end holding exactly those rows; with [checkKeepScreenOn] the
     * window must keep the screen on while the list searches and while it writes.
     */
    private fun runBatch(
        run: MigrateRun,
        entries: List<MigrateFixture.Entry>,
        name: String,
        checkKeepScreenOn: Boolean = false,
        expectShown: List<MigrateFixture.Entry>? = null,
    ) {
        runBlocking(Dispatchers.IO) { run.onlyInLibrary(entries) }
        withMain(MainDestination.LIBRARY) {
            selectAllInLibrary(entries.map { it.title }, entries.size)
            migrateTheSelection()
            continueToTheList {}
            if (checkKeepScreenOn) compose.waitUntil(MIGRATE_UI_TIMEOUT_MILLIS) { keepScreenOnShown() }
            awaitListFinished(entries.size * PER_ENTRY_MILLIS + MIGRATE_UI_TIMEOUT_MILLIS)
            if (expectShown != null) {
                awaitNode(listTitle(expectShown.size, expectShown.size))
                (entries - expectShown.toSet()).forEach { entry ->
                    assertTrue(
                        "${entry.entry} is still listed",
                        compose.onAllNodes(hasText(entry.title)).fetchSemanticsNodes().isEmpty(),
                    )
                }
            }
            batchScreen("$name-list")
            migrateAll(copy = false, checkKeepScreenOn = checkKeepScreenOn)
            awaitNoNode(listTitle(), entries.size * PER_ENTRY_MILLIS + MIGRATE_UI_TIMEOUT_MILLIS)
            batchScreen("$name-closed")
        }
    }

    /**
     * Opens the app at [destination] as MA's check opens it, once the library is up, runs [body]
     * with the scenario, and closes it.
     */
    private fun withMain(destination: MainDestination, body: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var launched: Intent? = null
        try {
            awaitText(R.string.library_title)
            if (destination != MainDestination.LIBRARY) {
                scenario.onActivity {
                    launched = it.intent
                    it.startActivity(MainIntents.newIntent(it, destination))
                }
            }
            body(scenario)
        } finally {
            launched?.let { intent -> scenario.onActivity { it.intent = intent } }
            scenario.close()
        }
    }

    /** The screen of the entry [mangaId], opened as MA's check opens it. */
    private fun withEntry(mangaId: Long, body: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var launched: Intent? = null
        try {
            awaitText(R.string.library_title)
            scenario.onActivity {
                launched = it.intent
                it.startActivity(MainIntents.newIntent(it, MainDestination.ENTRY, mangaId))
            }
            awaitNode(hasContentDescription(context.getString(R.string.action_menu_overflow_description)))
            body(scenario)
        } finally {
            launched?.let { intent -> scenario.onActivity { it.intent = intent } }
            scenario.close()
        }
    }

    /** Waits for [matcher]; on a timeout the screen's semantics go to the log, where the failure is read. */
    private fun awaitNode(matcher: SemanticsMatcher, timeoutMillis: Long = MIGRATE_UI_TIMEOUT_MILLIS, unmerged: Boolean = false) {
        try {
            compose.waitUntil(timeoutMillis) { compose.onAllNodes(matcher, unmerged).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            logTheScreen("waiting for ${matcher.description}")
            throw e
        }
    }

    private fun logTheScreen(why: String) {
        Log.i(LOG_TAG, "screen semantics, $why:")
        runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog(LOG_TAG, maxDepth = Int.MAX_VALUE) }
        runCatching { batchScreen("failed-${System.currentTimeMillis()}") }
    }

    private fun awaitNoNode(matcher: SemanticsMatcher, timeoutMillis: Long = MIGRATE_UI_TIMEOUT_MILLIS) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * With [unmerged], the node is looked up in the unmerged tree: an extended floating action
     * button clears its label's semantics, so "Continue" is not on the button's merged node.
     */
    private fun clickFirst(matcher: SemanticsMatcher, unmerged: Boolean = false) {
        awaitNode(matcher, unmerged = unmerged)
        compose.onAllNodes(matcher, unmerged).onFirst().performClick()
    }

    private fun hasAnyText(texts: Set<String>): SemanticsMatcher = SemanticsMatcher("text in ${texts.size} titles") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text in texts } == true
    }

    /** Long-presses a seeded entry, then Select all, and waits for the bar to count [count]. */
    private fun selectAllInLibrary(titles: Collection<String>, count: Int) {
        val seeded = hasAnyText(titles.toSet())
        awaitNode(seeded, MIGRATE_RUN_TIMEOUT_MILLIS)
        compose.onAllNodes(seeded).onFirst().performTouchInput { longClick() }
        awaitNode(hasText(context.getString(R.string.selection_count_format, 1)))
        clickFirst(hasContentDescription(context.getString(R.string.action_menu_overflow_description)))
        clickFirst(hasText(context.getString(R.string.action_select_all)) and hasClickAction())
        awaitNode(hasText(context.getString(R.string.selection_count_format, count)))
    }

    /**
     * The selection bar's Migrate, waiting for the config screen by its Select none action: with no
     * source installed the screen lists nothing, not even its Selected header.
     */
    private fun migrateTheSelection() {
        clickFirst(hasContentDescription(context.getString(R.string.action_menu_overflow_description)))
        clickFirst(hasText(context.getString(R.string.migrate)) and hasClickAction())
        awaitNode(hasContentDescription(context.getString(R.string.migrationConfigScreen_selectNoneLabel)))
    }

    /**
     * The config screen's Continue, then [sheet] on the sheet two or more entries open, then the
     * sheet's own Continue, waiting for the list.
     */
    private fun continueToTheList(sheet: () -> Unit) {
        val continueButton = hasText(context.getString(R.string.migrationConfigScreen_continueButtonText))
        clickFirst(continueButton, unmerged = true)
        awaitNode(hasText(context.getString(R.string.migrationConfigScreen_dataToMigrateHeader)))
        sheet()
        val last = compose.onAllNodes(continueButton, useUnmergedTree = true).onLast()
        runCatching { last.performScrollTo() }
        last.performClick()
        awaitNode(listTitle())
    }

    /** The list's bar title, with any progress, or exactly [finished] of [size]. */
    private fun listTitle(finished: Int? = null, size: Int? = null): SemanticsMatcher {
        if (finished != null && size != null) {
            return hasText(context.getString(R.string.migrationListScreenTitleWithProgress, finished, size))
        }
        val plain = context.getString(R.string.migrationListScreenTitle)
        return SemanticsMatcher("the migration list's title") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == plain || LIST_PROGRESS.matches(it.text) } == true
        }
    }

    /** The list's progress, finished and size, or null while it shows none. */
    private fun listProgress(): Pair<Int, Int>? = compose.onAllNodes(listTitle()).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .firstNotNullOfOrNull { LIST_PROGRESS.matchEntire(it.text) }
        ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    private fun awaitProgress(timeoutMillis: Long = MIGRATE_RUN_TIMEOUT_MILLIS, done: (Int, Int) -> Boolean) {
        compose.waitUntil(timeoutMillis) { listProgress()?.let { (finished, size) -> done(finished, size) } == true }
    }

    private fun awaitListFinished(timeoutMillis: Long) = awaitProgress(timeoutMillis) { finished, size -> finished == size }

    /**
     * The list's Migrate all or Copy all, confirmed in its dialog. With [checkKeepScreenOn] the
     * window must keep the screen on while the writes run.
     */
    private fun migrateAll(copy: Boolean, checkKeepScreenOn: Boolean = false) {
        val label = if (copy) R.string.copy else R.string.migrate
        clickFirst(hasContentDescription(context.getString(label)))
        awaitNode(inDialog(label))
        compose.onNode(inDialog(label)).performClick()
        if (checkKeepScreenOn) {
            compose.waitUntil(MIGRATE_UI_TIMEOUT_MILLIS) { keepScreenOnShown() }
        }
    }

    /** A row's menu, the one level with [title]'s row, then Search manually, waiting for the search. */
    private fun openManualSearch(title: String) {
        awaitNode(hasText(title))
        val titleCentre = compose.onAllNodes(hasText(title) and hasSetTextAction().not()).onFirst()
            .fetchSemanticsNode().boundsInRoot.center.y
        val menus = compose.onAllNodes(hasContentDescription(context.getString(R.string.action_menu)))
        val index = menus.fetchSemanticsNodes().withIndex()
            .minBy { (_, node) -> kotlin.math.abs(node.boundsInRoot.center.y - titleCentre) }.index
        menus[index].performClick()
        clickFirst(hasText(context.getString(R.string.migrationListScreen_searchManuallyActionLabel)) and hasClickAction())
        awaitNode(hasText(context.getString(R.string.global_search_title)))
    }

    /** Replaces manual search's query with [query] and submits it. */
    private fun searchFor(query: String) {
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performTextClearance()
        field.performTextInput(query)
        clickFirst(hasText(context.getString(R.string.action_search)) and hasClickAction())
    }

    /** Taps the first result titled [title], never the search field that may hold the same text. */
    private fun clickResult(title: String) {
        clickFirst(hasText(title) and hasSetTextAction().not())
    }

    /** The Migrate page's row for [name] shows [count] in its badge. */
    private fun assertRowCount(name: String, count: Int) {
        val row = compose.onAllNodes(hasText(name)).onFirst().fetchSemanticsNode().boundsInRoot
        val badges = compose.onAllNodes(hasText(count.toString())).fetchSemanticsNodes()
        assertTrue(
            "the row for $name shows no count $count",
            badges.any { it.boundsInRoot.center.y in row.top - row.height..row.bottom + row.height },
        )
    }

    /**
     * Drags the config screen's row [name] above [above] by its handle, the trailing icon, in small
     * steps as a finger moves.
     */
    private fun dragAbove(name: String, above: String) {
        val from = compose.onAllNodes(hasText(name)).onFirst().fetchSemanticsNode().boundsInRoot
        val to = compose.onAllNodes(hasText(above)).onFirst().fetchSemanticsNode().boundsInRoot
        val root = compose.onAllNodes(isRoot()).onFirst()
        val width = root.fetchSemanticsNode().boundsInRoot.width
        val handleX = width - with(compose.density) { DRAG_HANDLE_INSET.toPx() }
        val distance = to.center.y - from.center.y - from.height / 2
        root.performTouchInput {
            down(Offset(handleX, from.center.y))
            repeat(DRAG_STEPS) { moveBy(Offset(0f, distance / DRAG_STEPS), delayMillis = DRAG_STEP_MILLIS) }
            up()
        }
        compose.waitForIdle()
    }

    /** Rotates the activity and, while [onTheList], waits for the list to be back. */
    private fun rotate(scenario: ActivityScenario<MainActivity>, landscape: Boolean, onTheList: Boolean = true) {
        scenario.onActivity {
            it.requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        compose.waitForIdle()
        if (onTheList) awaitNode(listTitle())
    }

    private fun pressBack(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Whether the window manager shows the app's window keeping the screen on, as `dumpsys window` reads. */
    private fun keepScreenOnShown(): Boolean = shell("dumpsys window windows")
        .split("\n  Window #")
        .filter { "${context.packageName}/" in it }
        .any { window ->
            // Newer images name the flags; API 26 prints them in hex as `fl=#...`.
            val flags = WINDOW_FLAGS.find(window)?.groupValues?.get(1)?.toLong(16) ?: 0L
            "KEEP_SCREEN_ON" in window || (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.toLong()) != 0L
        }

    private fun logMemory(label: String) {
        val total = shell("dumpsys meminfo ${context.packageName}").lineSequence()
            .firstOrNull { it.trimStart().startsWith("TOTAL") }
            ?.trim()
        logcat(LOG_TAG, LogPriority.INFO) { "memory $label: $total" }
        Log.i(LOG_TAG, "memory $label: $total")
    }

    private fun batchScreen(name: String) {
        compose.waitForIdle()
        shell("screencap -p $batchScreens/$name.png")
        Log.i(LOG_TAG, "screen: $batchScreens/$name.png")
    }

    private companion object {
        const val LOG_TAG = "SaikouGateRows"

        /** See [GateFragments.DEVICE_ARGUMENT]. */
        const val DEVICE_ARGUMENT = GateFragments.DEVICE_ARGUMENT

        /** What `rows.json` calls a row that is not bound to one gate device. */
        const val ANY_DEVICE = "any"

        /** Reserved for documentation and examples, so it can never resolve to anything real. */
        const val SYNTHETIC_HOST = "gate.invalid"

        const val SOLVER_COUNT = 4
        const val SOLVE_HOLD_MILLIS = 5_000L
        const val SAMPLE_DELAY_MILLIS = 750L
        const val THREAD_TIMEOUT_MILLIS = 30_000L
        const val CLOUDFLARE_METHOD =
            "GateRowsTest: $SOLVER_COUNT concurrent callers, one host, peak concurrent solves"

        /** Far longer than the row needs; the test releases the solve itself once it has ticked. */
        const val BANNER_HOLD_MILLIS = 25_000L
        const val BANNER_TIMEOUT_MILLIS = 10_000L

        /** The banner ticks once a second, so three seconds without a change is a banner that does not. */
        const val TICK_TIMEOUT_MILLIS = 3_000L
        const val BANNER_METHOD =
            "GateRowsTest: a solve held on the app's own coordinator, banner shown, ticking and gone in MainActivity"

        const val RESTORE_ENTRIES = 3_000
        const val RESTORE_BUDGET_MILLIS = 120_000L
        const val RESTORE_FILE_NAME = "gate-restore.tachibk"
        const val RESTORE_URL_PREFIX = "/saikou-gate/restore/"
        const val RESTORE_TITLE_PREFIX = "Gate restore entry "
        const val RESTORE_METHOD =
            "GateRowsTest: $RESTORE_ENTRIES synthetic entries through BackupImporter, cold"

        /** No provider serves these ids, so every row under one belongs to this test alone. */
        const val RESTORE_SOURCE_ID = 9_000_000_000_000_000_001L
        const val BROWSE_SOURCE_ID = 9_000_000_000_000_000_002L

        const val BROWSE_ENTRIES = 200
        const val BROWSE_CHAPTERS_PER_ENTRY = 100
        const val BROWSE_HISTORY_ROWS = BROWSE_ENTRIES * BROWSE_CHAPTERS_PER_ENTRY
        const val BROWSE_URL_PREFIX = "/saikou-gate/browse/"
        const val BROWSE_TITLE_PREFIX = "Gate browse entry "
        const val BROWSE_CHAPTER_PREFIX = "Gate chapter "
        const val BROWSE_BUDGET_MILLIS = 500L
        const val BROWSE_METHOD =
            "GateRowsTest: tab tap to idle in MainActivity with $BROWSE_HISTORY_ROWS history rows"

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        const val CANCEL_ROW = "library_update_cancel_ms"

        /** `rows.json`'s phase for [CANCEL_ROW], ruling R47. */
        const val CANCEL_ROW_PHASE = 2

        /** `rows.json`'s threshold for [CANCEL_ROW]: under 2 seconds, absolute. */
        const val CANCEL_BUDGET_MILLIS = 2_000L

        /** How long a cancel is given before the FIFO is released; five times the budget. */
        const val CANCEL_WAIT_MILLIS = 10_000L

        /** The pass building its one-entry queue and the Local source reaching the chapter folder. */
        const val CANCEL_OPEN_TIMEOUT_MILLIS = 30_000L

        const val CANCEL_FIXTURE_PREFIX = "saikou-gate-cancel-"
        const val CANCEL_CATEGORY_PREFIX = "Gate cancel "
        const val CANCEL_CHAPTER_NAME = "Chapter 1"
        const val CANCEL_PAGE_NAME = "001.png"
        const val COMIC_INFO_FILE = "ComicInfo.xml"

        /** What a fetch still waiting on the FIFO reads once the row is measured, so it ends cleanly. */
        const val RELEASED_COMIC_INFO =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo><Title>Gate cancel chapter</Title></ComicInfo>\n"
        const val CANCEL_METHOD =
            "GateRowsTest: cancel to the return of FlatLibraryUpdate.run, its one fetch held in " +
                "a Local source ComicInfo.xml FIFO read"

        /** The Local source's root setting, `LocalSourcePreferences.localSourceDirectory`. */
        const val LOCAL_ROOT_SETTING = "storage_local_dir"

        /** Under the app's internal files, where a FIFO can be made. */
        const val CANCEL_ROOT = "saikou-gate-local"
        const val PAGE_SIZE = 32
        const val PAGE_COLOR = 0xFF404040.toInt()
        const val POLL_MILLIS = 50L

        /** `rows.json`'s phase for both download rows. */
        const val DOWNLOAD_ROWS_PHASE = 2

        const val ADD_ROW = "download_queue_add_ms_at_1000"

        /** `rows.json`'s threshold for [ADD_ROW]: under 1 second, absolute. */
        const val ADD_BUDGET_MILLIS = 1_000L

        /** The downloaded chapters the row is named for. */
        const val ADD_DOWNLOADED = 1_000

        /** The chapters of the timed call that are not downloaded yet, and are queued. */
        const val ADD_BATCH = 10
        const val ADD_PAGES_PER_CHAPTER = 20L
        const val ADD_SOURCE_ID = 9_000_000_000_000_000_003L

        /** The source folder the seeded downloads sit in; no source is named this. */
        const val ADD_SOURCE_FOLDER = "Gate download source"
        const val ADD_URL_PREFIX = "/saikou-gate/download-add/"
        const val ADD_TITLE_PREFIX = "Gate download entry "
        const val ADD_CHAPTER_PREFIX = "Gate chapter "
        const val NANOS_PER_MILLI = 1_000_000L
        const val ADD_METHOD =
            "GateRowsTest: DownloadQueue.enqueue(autoStart = true) of an entry's ${ADD_DOWNLOADED + ADD_BATCH} " +
                "chapters, $ADD_DOWNLOADED of them downloaded, to the other $ADD_BATCH readable in the queue"

        const val FORCE_STOP_ROW = "download_queue_survives_force_stop"

        /** The device path of the fixture extension, which the gate pushes. */
        const val FIXTURE_APK_ARGUMENT = "saikou.download.fixture.apk"
        const val FIXTURE_APK_COPY = "saikou-download-fixture.apk"

        /** `download_hold_server.py` on the host, on the port the fixture extension is built with. */
        const val FIXTURE_SERVER = "http://10.0.2.2:18794"
        const val HOLD_STATUS_PATH = "/hold"
        const val HOLD_RELEASE_PATH = "/hold/release"
        const val FIXTURE_SERVER_TIMEOUT_MILLIS = 10_000
        const val DOWNLOAD_POLL_MILLIS = 200L
        const val FORCE_STOP_SOURCES = 3
        const val FORCE_STOP_CHAPTERS_PER_ENTRY = 2

        /** The install, three listings and three chapter lists at the fixture's 400 to 800 ms each. */
        const val FORCE_STOP_SEED_TIMEOUT_MILLIS = 120_000L

        /** The first chapter's page list and first page; seconds, at the fixture's latency. */
        const val MID_FLIGHT_TIMEOUT_MILLIS = 60_000L

        /** Six chapters of three pages after WorkManager starts the worker again. */
        const val RESUME_TIMEOUT_MILLIS = 180_000L
        /** Unit MA's device check: where its screens go, and what it names its rows. */
        const val MIGRATE_SCREENS_DIR = "/data/local/tmp/saikou-migrate-one"
        const val MIGRATE_CATEGORY_PREFIX = "Migration check "
        const val MIGRATE_NOTE_PREFIX = "Migration check note "
        const val MIGRATE_REMOTE_ID = 9_100_000L

        /** The install, four listings, two chapter lists and two downloads at the fixture's latency. */
        const val MIGRATE_SEED_TIMEOUT_MILLIS = 300_000L
        const val MIGRATE_UI_TIMEOUT_MILLIS = 30_000L

        /** The target's chapter list at the fixture's latency, and the writes after it. */
        const val MIGRATE_RUN_TIMEOUT_MILLIS = 60_000L

        /**
         * Unit DC2's device check (item 3, R101 carry-over 4): its argument, the library size, the
         * memory sample cadence during a compute far shorter than a migration run's, and the
         * timeouts for the one real sign-in sync and for the fingerprint compute itself.
         */
        const val FINGERPRINT_ARGUMENT = "saikou.fingerprint.10k"
        const val FINGERPRINT_ENTRIES = 10_000
        const val FINGERPRINT_SAMPLE_MILLIS = 300L
        const val FINGERPRINT_POLL_MILLIS = 200L
        const val FINGERPRINT_SYNC_TIMEOUT_MILLIS = 300_000L
        const val FINGERPRINT_COMPUTE_TIMEOUT_MILLIS = 120_000L

        /** Unit MB's device checks: their argument, where their screens and the seed go. */
        const val MIGRATE_BATCH_ARGUMENT = "saikou.migrate.batch"
        const val MIGRATE_SEED_ARGUMENT = "saikou.migrate.seed"
        const val MIGRATE_BATCH_SCREENS_DIR = "/data/local/tmp/saikou-migrate-batch"
        const val MIGRATE_BATCH_SEED_FILE = "migrate-batch-seed.json"
        const val MIGRATE_BATCH_REMOTE_ID = 9_200_000L
        const val FIXTURE_SOURCE_PREFIX = "Update Fixture "
        const val SHEET_KEYWORDS = "Extra"
        const val ENTRY_POINT_A_ENTRIES = 6
        const val ENTRY_POINT_OVERFLOW_ENTRIES = 3
        const val FAILURE_BATCH = 60
        val FAILING_ENTRIES = 40..45
        const val UNAVAILABLE_BATCH = 10
        const val UNAVAILABLE_WINDOW = 5
        const val CANCEL_BATCH = 30
        const val CANCEL_AFTER = 3
        const val ROTATION_BATCH = 20
        const val ROTATE_AFTER = 5
        const val LARGE_BATCH = 300
        const val KILL_BATCH = 30
        const val ROUTE_BATCH = 500
        const val ROUTE_SOURCES = 6
        const val ROUTE_FIRST_SOURCE = 5
        const val ROUTE_PER_SOURCE = 250

        /**
         * One entry's search, match and chapter fetch in the default mode at the fixture's 0.4 to
         * 0.8 s: about 2 s, and twice that as the wait's allowance. One search is the mean 0.6 s.
         */
        const val PER_ENTRY_MILLIS = 5_000L
        const val PER_SEARCH_MILLIS = 1_500L
        const val LARGE_RUN_TIMEOUT_MILLIS = 45 * 60_000L

        /** The config screen's drag handle from the right edge: the card's and the row's padding and half the icon. */
        val DRAG_HANDLE_INSET = 44.dp
        const val DRAG_STEPS = 20
        const val DRAG_STEP_MILLIS = 20L

        /** A window's flags as `dumpsys window` prints them in hex on older images. */
        val WINDOW_FLAGS = Regex("""\bfl=#([0-9a-f]+)""")

        /** `migrationListScreenTitleWithProgress` in English; the device checks run in English. */
        val LIST_PROGRESS = Regex("""Migration \((\d+)/(\d+)\)""")

        const val FORCE_STOP_METHOD =
            "GateRowsTest: $FORCE_STOP_SOURCES fixture sources x $FORCE_STOP_CHAPTERS_PER_ENTRY chapters queued " +
                "mid-flight, am force-stop between two runs, the same queue read back and every chapter " +
                "downloaded by the resumed worker"

        /**
         * Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission and asking the
         * shell to grant it throws.
         */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}
