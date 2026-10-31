package com.shinsaikou.bench

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import coil3.memory.MemoryCache
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.reader.ReaderActivity
import com.shinsaikou.reader.ReaderIntents
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.util.Args
import com.shinsaikou.util.GateFragments
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * `low_ram_repro_pass`, ruling R61: the cover cache growth repro, followed by reading.
 *
 * PLAN.md's row replays the defect where a large library's covers grew the heap until the process
 * died, and fails on growth. So this seeds a library of [LibraryCoverSeeder.ENTRIES] entries with
 * distinct covers, scrolls the library grid end to end [LIBRARY_PASSES] times, then opens the
 * generated webtoon chapter and scrolls it to its end, all in the application's own activities and
 * through its own graph. It passes when all three of R61's checks, as amended on 2026-09-24, hold:
 *
 * 1. the process keeps one id throughout and no `OutOfMemoryError` is seen;
 * 2. the cover cache is bounded: after every library pass, the application's image memory cache
 *    holds at most [CACHE_LIMIT_PERCENT] percent of `ActivityManager.memoryClass` in bytes;
 * 3. nothing leaks per pass: live allocation, the Java heap in use after a collection plus
 *    `Debug.getNativeHeapAllocatedSize()`, is at most [LEAK_LIMIT] higher after the last pass than
 *    after pass 2.
 *
 * Check 2 is what the row exists for, and no comparison between passes could make it. A library
 * pass shows every cover, so a cache with no bound is already full after pass 1, and on the
 * emulator its covers are hardware bitmaps held on the host, which PSS never counts: a cache that
 * held all 1,000 covers (574 MiB) passed the PSS growth check this row used to have, 5 runs in 5.
 * So the cache's own level is read, through the loader the application's graph hands every screen,
 * and held against a fixed share of the memory class rather than the product's own configured
 * maximum, so a bound that someone raises past the share or removes fails. Check 3 catches what
 * check 2 cannot, a per-pass leak anywhere in the process; pass 1 is left out because it carries
 * about 2.4 MB of native allocation the later passes release.
 *
 * PSS after every checkpoint, the memory class, the device's MemTotal and the `reader.trim` levels
 * `ReaderMemoryGuard` wrote go in the fragment's method note. None of them is a pass condition: PSS
 * moves by up to 10 MB between single checkpoints with no trend, a device with room to spare need
 * not trim, and the guard is registered only while a reader session is open, so no trim can appear
 * during the library passes.
 *
 * ### A FAIL is published; only a death is missing
 *
 * `GateRowsTest` asserts before it publishes. This row inverts that on purpose: the fragment carries
 * `false` when a check fails, and the test then fails as well, so the gate reports a FAIL with the
 * numbers rather than the MISSING a run that never happened produces. A run the process does not
 * survive cannot publish anything, so the fragment a previous run left is removed before anything
 * else happens, and the harness tells that death apart from a never-run test by reading the
 * platform's crash and events buffers.
 *
 * A measurement that cannot be taken is not a FAIL (R36). A library that did not show every seeded
 * entry, a grid or a reader that never became scrollable, a pass that ran out of steps, or an image
 * loader with no memory cache to read ends the test with no fragment, because a level or a growth
 * over a library that was not all there, or over a cache that is not there, proves nothing.
 *
 * ### Where it runs
 *
 * Only on `lowram_1_5g`, the one device the row binds. It lives in `com.shinsaikou.bench` so that every
 * step that excludes that package, the API 26 step and the tablet step, never runs it. The 4 GB
 * step runs the whole suite with `saikou.device.id=pixel9_4g`, and this returns early there with a
 * `Skipped:` line; without the argument it returns early too, and under `saikou.strict` [Args] fails
 * instead, as everywhere else.
 */
@RunWith(AndroidJUnit4::class)
class LowRamReproTest {

    /** [MainActivity] asks for `POST_NOTIFICATIONS`, and the dialog would sit over the library. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** Every `reader.trim` and `OutOfMemoryError` line this process wrote, gathered at each checkpoint. */
    private val trimLines = LinkedHashSet<String>()
    private val outOfMemoryLines = LinkedHashSet<String>()

    @Test
    fun theLibraryAndAWebtoonChapterSurviveOnTheLowRamProfile() {
        val device = Args.argument(GateFragments.DEVICE_ARGUMENT) ?: return skip("no ${GateFragments.DEVICE_ARGUMENT} argument")
        if (device != DEVICE) return skip("the row binds $DEVICE and this run is on $device")

        GateFragments.remove(ROW)
        val startedAt = SystemClock.elapsedRealtime()
        val pid = Process.myPid()
        val pidAtStart = pidOfPackage()
        assertEquals("pidof ${context.packageName} does not name the process running this test", pid.toString(), pidAtStart)

        // Generation happens here, before the first measurement, and costs nothing on a device that
        // has been seeded before.
        val library = runBlocking { LibraryCoverSeeder.seed(graph) }
        val strip = runBlocking { BenchFixtureSeeder.seed(context, graph) }.strip
        Log.i(
            LOG_TAG,
            "seeded ${library.mangaIds.size} entries (${library.coversWritten} covers written, " +
                "${library.refavourited} re-favourited), strip manga ${strip.mangaId} chapter ${strip.chapterId}, " +
                "in ${SystemClock.elapsedRealtime() - startedAt} ms",
        )

        // The loader every screen draws its covers through, reached the way SaikouApp hands it to
        // Coil. No cache to read is a measurement that cannot be taken (R36), not a bounded cache.
        val coverCache = checkNotNull(graph.imageLoader.memoryCache) {
            "the application's image loader has no memory cache, so the cover cache's level cannot be read"
        }
        val memoryClass = checkNotNull(context.getSystemService(ActivityManager::class.java)).memoryClass
        check(memoryClass > 0) { "ActivityManager reported a memory class of $memoryClass MB" }
        val cacheLimit = memoryClass * BYTES_PER_MB * CACHE_LIMIT_PERCENT / 100
        val memTotal = memTotalKb()

        val passes = mutableListOf<Reading>()
        var webtoonEnd: Reading? = null
        var caught: OutOfMemoryError? = null
        try {
            graph.basePreferences.batteryExemptionRequested.set(true)
            graph.crashLog.markExported()
            val main = ActivityScenario.launch(MainActivity::class.java)
            try {
                awaitLibrary()
                repeat(LIBRARY_PASSES) { pass ->
                    if (pass > 0) scrollLibraryToTop(pass + 1)
                    val seen = scrollLibraryToEnd(pass + 1)
                    assertEquals(
                        "library pass ${pass + 1} showed ${seen.size} of the ${LibraryCoverSeeder.ENTRIES} " +
                            "seeded entries, so the pass measured another library (a filter, a category or a display " +
                            "mode without titles hides some, or the grid stopped short of its end)",
                        LibraryCoverSeeder.ENTRIES,
                        seen.size,
                    )
                    passes += checkpoint("library pass ${pass + 1}", pid, coverCache)
                }
                readTheStripToItsEnd(strip)
                webtoonEnd = checkpoint("webtoon end", pid, coverCache)
            } finally {
                main.close()
            }
        } catch (error: OutOfMemoryError) {
            caught = error
        }

        val pidAtEnd = pidOfPackage()
        checkpointLogs(pid)
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        // Check 1.
        val failures = mutableListOf<String>()
        if (pidAtStart != pidAtEnd || pidAtEnd != pid.toString()) {
            failures += "the process id changed from $pidAtStart to $pidAtEnd"
        }
        if (caught != null || outOfMemoryLines.isNotEmpty()) {
            failures += "an OutOfMemoryError was seen: ${caught ?: outOfMemoryLines.first()}"
        }
        // Check 2, after every pass. Only an OutOfMemoryError ends the passes early without ending
        // the test, and that is already a failure above, so fewer readings never read as a pass.
        passes.forEachIndexed { index, reading ->
            if (reading.cacheBytes > cacheLimit) {
                failures += "the cover cache held ${reading.cacheBytes} B after library pass ${index + 1}, over its " +
                    "limit of $cacheLimit B ($CACHE_LIMIT_PERCENT% of the $memoryClass MB memory class)"
            }
        }
        // Check 3.
        val second = passes.getOrNull(1)?.liveBytes
        val last = passes.getOrNull(LIBRARY_PASSES - 1)?.liveBytes
        var leak: Double? = null
        if (second != null && last != null) {
            val growth = (last - second).toDouble() / second
            leak = growth
            if (growth > LEAK_LIMIT) {
                failures += "live allocation grew ${percent(growth)} from library pass 2 to pass $LIBRARY_PASSES " +
                    "(${second / 1024} KB to ${last / 1024} KB), over the ${percent(LEAK_LIMIT)} the row allows"
            }
        }
        val passed = failures.isEmpty()

        val note = methodNote(
            pidAtStart, pidAtEnd, passes, webtoonEnd, leak, cacheLimit, memoryClass, memTotal, caught, elapsed, failures,
        )
        Log.i(LOG_TAG, "verdict ${if (passed) "PASS" else "FAIL"}: $note")
        GateFragments.publish(ROW, device, passed.toString(), "bool", note)

        assertTrue(failures.joinToString("; "), passed)
    }

    /**
     * The library is driven through the accessibility tree the application publishes, the one
     * TalkBack reads: the grid is the node that offers to scroll down or up, a step is its own
     * scroll action, which moves it a viewport, and the titles are the text its items publish. That
     * reaches the grid without a single Compose class in this test, which matters because the gate
     * runs it against the minified release build, where R8 has renamed or removed most of the
     * semantics API a Compose test rule would call. The platform types below are not the app's to
     * shrink.
     */
    private val automation get() = instrumentation.uiAutomation

    /** The library drawn with its grid laid out and at least one seeded entry on screen. */
    private fun awaitLibrary() {
        val deadline = SystemClock.uptimeMillis() + LAUNCH_TIMEOUT_MILLIS
        while (true) {
            val root = freshRoot()
            if (root != null && libraryGrid(root) != null && titlesUnder(root).isNotEmpty()) return
            check(SystemClock.uptimeMillis() < deadline) {
                "the library never showed a seeded entry in a vertically scrollable grid (after $LAUNCH_TIMEOUT_MILLIS ms)"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /**
     * One pass: from the top of the grid to its end, a viewport at a time through the grid's own
     * scroll action, so every row between is composed and every cover in it is requested. The dwell
     * after each step covers the scroll and gives the decoders time to finish the covers on screen,
     * which is what puts them in the memory cache; a scroll that outran them would measure requests
     * cancelled before they allocated anything.
     *
     * Returns the seeded titles the pass saw. A pass that runs out of steps, or a grid that refuses
     * a step it offered, ends the test rather than measuring a partial library (R36).
     */
    private fun scrollLibraryToEnd(pass: Int): Set<String> {
        val seen = HashSet<String>()
        var steps = 0
        while (true) {
            val root = checkNotNull(freshRoot()) { "library pass $pass: no active window" }
            seen += titlesUnder(root)
            val grid = checkNotNull(libraryGrid(root)) { "library pass $pass: the grid is gone" }
            if (!grid.offers(AccessibilityAction.ACTION_SCROLL_DOWN)) break
            check(steps < MAX_LIBRARY_STEPS) { "library pass $pass did not reach the end in $MAX_LIBRARY_STEPS steps" }
            check(grid.performAction(AccessibilityAction.ACTION_SCROLL_DOWN.id)) {
                "library pass $pass: the grid refused to scroll down at step $steps"
            }
            Thread.sleep(LIBRARY_DWELL_MILLIS)
            steps++
        }
        Log.i(LOG_TAG, "library pass $pass: $steps steps, ${seen.size} seeded titles seen")
        return seen
    }

    /**
     * Back to the top before the next pass, through the same action in the other direction and with
     * no dwell: the way up is not part of the pass, only the way to its start.
     */
    private fun scrollLibraryToTop(pass: Int) {
        var steps = 0
        while (true) {
            val root = checkNotNull(freshRoot()) { "before library pass $pass: no active window" }
            val grid = checkNotNull(libraryGrid(root)) { "before library pass $pass: the grid is gone" }
            if (!grid.offers(AccessibilityAction.ACTION_SCROLL_UP)) return
            check(steps < MAX_LIBRARY_STEPS) { "the grid did not return to its top in $MAX_LIBRARY_STEPS steps" }
            check(grid.performAction(AccessibilityAction.ACTION_SCROLL_UP.id)) { "the grid refused to scroll up" }
            Thread.sleep(RETURN_DWELL_MILLIS)
            steps++
        }
    }

    /**
     * The active window's tree as the application publishes it now. `UiAutomation` keeps a cache of
     * the nodes it has handed out and updates it only as change events arrive, and the lazy grid
     * reuses its item nodes as it scrolls. Read through that cache, the grid kept offering the same
     * rows for steps after it had moved on, and still offered to scroll down after it had reached
     * its end: a pass over the 1,000 entries saw 94 of them on the low RAM AVD (226 in an earlier
     * run), with the scroll itself working throughout. So the cache is dropped before every read,
     * and the root is refreshed as well.
     *
     * Dropping it also keeps it out of the measurement. The instrumentation runs in the
     * application's process, so the cache is in the heap this test measures, and every grid item
     * the passes compose publishes nodes under ids of its own. On API 28, where the row's device is,
     * nothing prunes the ones a scroll leaves behind: from library pass 2 to pass 8 the cache grew
     * by 15,495 nodes and 7.66 MB, of the 8.56 MB the live Java heap gained, and PSS with it.
     * `clearCache` is API 34; before that, `setServiceInfo` is the public call that clears the same
     * cache.
     */
    private fun freshRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            automation.clearCache()
        } else {
            automation.serviceInfo = automation.serviceInfo
        }
        return automation.rootInActiveWindow?.also { it.refresh() }
    }

    /**
     * The one node that scrolls vertically. The category pager scrolls too, but sideways, so it
     * offers left and right rather than up and down.
     */
    private fun libraryGrid(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val found = mutableListOf<AccessibilityNodeInfo>()
        visit(root) { node ->
            if (node.offers(AccessibilityAction.ACTION_SCROLL_DOWN) || node.offers(AccessibilityAction.ACTION_SCROLL_UP)) {
                found += node
            }
        }
        check(found.size <= 1) { "the library shows ${found.size} vertically scrollable nodes where it should show one" }
        return found.firstOrNull()
    }

    private fun titlesUnder(root: AccessibilityNodeInfo): Set<String> {
        val titles = HashSet<String>()
        visit(root) { node ->
            listOfNotNull(node.text, node.contentDescription).forEach { text ->
                TITLE.findAll(text).forEach { titles += it.value }
            }
        }
        return titles
    }

    private fun visit(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, action) }
    }

    private fun AccessibilityNodeInfo.offers(action: AccessibilityAction): Boolean =
        actionList.any { it.id == action.id }

    /**
     * The seeded webtoon chapter, opened through the reader's own intent at page 1 and scrolled to
     * its last page in the reader's own view. The view is the deepest one in the activity that can
     * scroll vertically, which is the webtoon viewer's recycler; it is found by what it does rather
     * than by class name, because the release build renames classes. Its own `scrollBy` is what a
     * fling ends in, so the viewer binds, probes, splits and decodes every page on the way down.
     *
     * Done when the reader has selected the last page, the viewer has been through it (its route is
     * set, which only the holder's loader does), and the view cannot scroll any further. A page that
     * failed to decode fails here with its own error, and one that failed for want of memory is
     * rethrown as the `OutOfMemoryError` it was.
     */
    private fun readTheStripToItsEnd(strip: BenchFixtureSeeder.Entry) {
        val reader = ActivityScenario.launch<ReaderActivity>(
            ReaderIntents.newIntent(context, strip.mangaId, strip.chapterId, page = 0)
                .putExtra(ReaderIntents.EXTRA_BENCH_TAG, BENCH_TAG),
        )
        try {
            lateinit var activity: ReaderActivity
            reader.onActivity { activity = it }
            val viewModel = viewModelOf(activity)
            val ready = runBlocking {
                try {
                    withTimeout(OPEN_TIMEOUT_MILLIS) {
                        viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError("the webtoon session never became ready (after $OPEN_TIMEOUT_MILLIS ms)", e)
                }
            }
            assertEquals("the strip did not open in the webtoon viewer", BenchFixtureSeeder.STRIP_MODE, ready.readingMode)
            assertEquals("the reader opened another chapter", strip.chapterId, ready.currentChapter?.chapter?.id)
            assertEquals("the chapter is not the seeded strip", strip.pages, ready.totalPages)
            // The chapter's page list grows while it is read: the splitter inserts a strip's parts
            // after it, and every part reports its parent's index. So the end is the last page
            // index, never the list's last position, and the list is read afresh on every step.
            val lastIndex = ready.totalPages - 1
            val last = checkNotNull(ready.currentChapter?.pages).first { it.index == lastIndex }

            val scroller = awaitVerticalScroller(activity)
            val deadline = SystemClock.uptimeMillis() + WEBTOON_TIMEOUT_MILLIS
            var steps = 0
            while (true) {
                val pages = checkNotNull(viewModel.state.value.currentChapter?.pages) { "the strip's chapter unloaded" }
                pages.forEach { page ->
                    val status = page.status
                    if (status is PageState.Error) {
                        (status.error as? OutOfMemoryError)?.let { throw it }
                        throw AssertionError("page ${page.number} of the strip did not decode: ${status.error}")
                    }
                }
                var canScrollDown = true
                instrumentation.runOnMainSync { canScrollDown = scroller.canScrollVertically(1) }
                val atEnd = viewModel.state.value.currentPage == lastIndex
                if (atEnd && last.route != null && !canScrollDown) break
                check(SystemClock.uptimeMillis() < deadline) {
                    "the strip did not reach its end in $WEBTOON_TIMEOUT_MILLIS ms: page " +
                        "${viewModel.state.value.currentPage + 1} of ${ready.totalPages}, $steps steps"
                }
                if (canScrollDown) {
                    instrumentation.runOnMainSync { scroller.scrollBy(0, (scroller.height * WEBTOON_STEP_FRACTION).toInt()) }
                    steps++
                }
                Thread.sleep(WEBTOON_DWELL_MILLIS)
            }
            Log.i(LOG_TAG, "webtoon: ${ready.totalPages} pages to the end in $steps steps, last page in ${last.partCount} part(s)")
        } finally {
            reader.close()
        }
    }

    /**
     * The webtoon viewer's own scrolling view, waited for rather than read once: the viewer is built
     * when the session loads and its first rows are laid out a frame or two later. None at all means
     * the reader built a viewer that pages instead of scrolling, and the strip cannot be read to its
     * end, so the run ends without a measurement (R36).
     */
    private fun awaitVerticalScroller(activity: Activity): View {
        val deadline = SystemClock.uptimeMillis() + SCROLLER_TIMEOUT_MILLIS
        while (true) {
            verticalScroller(activity)?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) {
                "the reader showed no vertically scrolling view for the webtoon strip after $SCROLLER_TIMEOUT_MILLIS ms"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** The deepest view under the window that can scroll vertically, read on the main thread. */
    private fun verticalScroller(activity: Activity): View? {
        var found: View? = null
        instrumentation.runOnMainSync {
            fun visit(view: View, depth: Int, best: IntArray) {
                if (view.isShown && (view.canScrollVertically(1) || view.canScrollVertically(-1)) && depth > best[0]) {
                    best[0] = depth
                    found = view
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1, best)
                }
            }
            visit(activity.window.decorView, 0, intArrayOf(-1))
        }
        return found
    }

    /**
     * The activity's own view model, not a second one; see `BenchContentTest.viewModelOf`.
     */
    @Suppress("DEPRECATION")
    private fun viewModelOf(activity: ReaderActivity): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        instrumentation.runOnMainSync { viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java] }
        return viewModel
    }

    /** What one checkpoint read: PSS in KB, live allocation and the cover cache's level in bytes. */
    private class Reading(val pss: Int, val liveBytes: Long, val cacheBytes: Long)

    /**
     * Read after a collection, so garbage the pass left behind is not counted as growth. What a
     * cache holds survives a collection, which is the point.
     *
     * Live allocation is what check 3 compares: the Java heap in use plus the native heap's
     * allocated bytes, both counted by the allocators themselves, so a page the allocator keeps
     * resident after freeing it is not in it. PSS, the figure `dumpsys meminfo` prints as TOTAL
     * PSS, is recorded beside it. The cache's level is Coil's own count of the bytes its entries
     * hold, which on this emulator is the one place hardware bitmaps show up at all.
     */
    private fun checkpoint(label: String, pid: Int, coverCache: MemoryCache): Reading {
        // A bitmap's pixels are native and freed by a cleaner the collection only queues, so the
        // queue is drained before the second collection.
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(SETTLE_MILLIS)
        Runtime.getRuntime().gc()
        val runtime = Runtime.getRuntime()
        val javaBytes = runtime.totalMemory() - runtime.freeMemory()
        val nativeBytes = Debug.getNativeHeapAllocatedSize()
        check(javaBytes > 0 && nativeBytes > 0) { "no live allocation at $label: Java $javaBytes B, native $nativeBytes B" }
        val cacheBytes = coverCache.size
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val pss = info.totalPss
        check(pss > 0) { "Debug.getMemoryInfo reported no PSS at $label" }
        checkpointLogs(pid)
        // What grows, when something does: the same split `dumpsys meminfo` prints as its App Summary.
        val parts = SUMMARY_PARTS.joinToString(" ") { "${it.removePrefix("summary.")}=${info.getMemoryStat(it)}" }
        Log.i(
            LOG_TAG,
            "$label: PSS $pss KB, live ${(javaBytes + nativeBytes) / 1024} KB (Java ${javaBytes / 1024}, native " +
                "${nativeBytes / 1024}), cover cache $cacheBytes of ${coverCache.maxSize} B ($parts)",
        )
        return Reading(pss, javaBytes + nativeBytes, cacheBytes)
    }

    /** The device's MemTotal in kB, for the method note; null when `/proc/meminfo` has no such line. */
    private fun memTotalKb(): Long? =
        MEM_TOTAL.find(GateFragments.shell("cat /proc/meminfo"))?.groupValues?.get(1)?.toLong()

    /**
     * This process's own log lines, read through the shell because the application cannot read the
     * log itself. Read at every checkpoint rather than once at the end, so a marker from the first
     * pass is not lost when the ring buffer wraps during a long run.
     */
    private fun checkpointLogs(pid: Int) {
        val lines = GateFragments.shell("logcat -d --pid=$pid").lineSequence()
        lines.forEach { line ->
            if (TRIM.containsMatchIn(line)) trimLines += line
            if (line.contains(OUT_OF_MEMORY)) outOfMemoryLines += line
        }
    }

    private fun pidOfPackage(): String = GateFragments.shell("pidof ${context.packageName}").trim()

    /**
     * One line with every number the verdict rests on and the ones beside it, so a FAIL reaches the
     * gate with the figures that failed and a PASS with its margins.
     */
    private fun methodNote(
        pidAtStart: String,
        pidAtEnd: String,
        passes: List<Reading>,
        webtoonEnd: Reading?,
        leak: Double?,
        cacheLimit: Long,
        memoryClass: Int,
        memTotalKb: Long?,
        caught: OutOfMemoryError?,
        elapsedMillis: Long,
        failures: List<String>,
    ): String {
        fun series(value: (Reading) -> Long): String =
            (1..LIBRARY_PASSES).joinToString(" ") { pass -> "pass$pass=${passes.getOrNull(pass - 1)?.let(value) ?: "none"}" }
        val levels = trimLines.mapNotNull { TRIM.find(it)?.groupValues?.get(1)?.toInt() }
        val trims = if (levels.isEmpty()) {
            "none"
        } else {
            levels.groupingBy { it }.eachCount().entries.sortedBy { it.key }.joinToString(",") { "${it.key}x${it.value}" }
        }
        val outOfMemory = (if (caught != null) 1 else 0) + outOfMemoryLines.size
        val verdict = if (failures.isEmpty()) "all three checks hold" else "failed: ${failures.joinToString("; ")}"
        return "LowRamReproTest: ${LibraryCoverSeeder.ENTRIES} entries with distinct covers, $LIBRARY_PASSES library " +
            "passes, then the ${BenchFixtureSeeder.STRIP_PAGES}-strip webtoon chapter to its end; " +
            "1. pid $pidAtStart to $pidAtEnd, OutOfMemoryError $outOfMemory; " +
            "2. cover cache B ${series { it.cacheBytes }} (limit $cacheLimit, $CACHE_LIMIT_PERCENT% of the " +
            "memory class); " +
            "3. live allocation KB ${series { it.liveBytes / 1024 }}, pass2 to pass$LIBRARY_PASSES ${percent(leak)} " +
            "(limit ${percent(LEAK_LIMIT)}); " +
            "not conditions: PSS KB ${series { it.pss.toLong() }} webtoonEnd=${webtoonEnd?.pss ?: "none"}, " +
            "memoryClass $memoryClass MB, MemTotal ${memTotalKb ?: "unread"} kB, reader.trim levels $trims; " +
            "${elapsedMillis / 1000} s; $verdict"
    }

    private fun percent(fraction: Double?): String =
        fraction?.let { String.format(Locale.ROOT, "%+.1f%%", it * 100) } ?: "none"

    /** Writes the line a gate step counts, then returns: an early return must say why. */
    private fun skip(reason: String) {
        Log.i(LOG_TAG, "Skipped: theLibraryAndAWebtoonChapterSurviveOnTheLowRamProfile: $reason")
    }

    private companion object {
        const val LOG_TAG = "LowRamReproTest"

        const val ROW = "low_ram_repro_pass"

        /** The gate's name for `saikou_lowram_1_5g`, as `rows.json` binds the row. */
        const val DEVICE = "lowram_1_5g"

        const val BENCH_TAG = "lowram"

        const val LIBRARY_PASSES = 5

        /**
         * Check 2, R61 as amended: the cover cache at most a quarter of the memory class. The product
         * asks Coil for 20 percent (`ImageBindings.MEMORY_CACHE_PERCENT`), and this is deliberately
         * not read from there, so raising that past the share fails as removing it does.
         */
        const val CACHE_LIMIT_PERCENT = 25
        const val BYTES_PER_MB = 1024L * 1024L

        /** Check 3, R61 as amended: live allocation after the last pass at most 5 percent above pass 2. */
        const val LEAK_LIMIT = 0.05

        /** The scroll a step starts, and the covers it brings on screen decoding. */
        const val LIBRARY_DWELL_MILLIS = 400L
        const val RETURN_DWELL_MILLIS = 100L

        /** About four times what a pass over the seeded library takes on a phone-sized grid. */
        const val MAX_LIBRARY_STEPS = 400

        const val WEBTOON_STEP_FRACTION = 0.5f
        const val WEBTOON_DWELL_MILLIS = 50L

        /** Splitting ten 16 megapixel strips on the smallest AVD, with room to spare. */
        const val WEBTOON_TIMEOUT_MILLIS = 600_000L

        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        const val OPEN_TIMEOUT_MILLIS = 60_000L
        const val SCROLLER_TIMEOUT_MILLIS = 30_000L
        const val SETTLE_MILLIS = 500L
        const val POLL_MILLIS = 100L

        val TITLE = Regex(Regex.escape(LibraryCoverSeeder.TITLE_PREFIX) + "\\d{${LibraryCoverSeeder.TITLE_DIGITS}}")
        val TRIM = Regex("reader\\.trim level=(\\d+)")
        val MEM_TOTAL = Regex("MemTotal:\\s+(\\d+) kB")

        val SUMMARY_PARTS = listOf(
            "summary.java-heap",
            "summary.native-heap",
            "summary.graphics",
            "summary.code",
            "summary.private-other",
            "summary.system",
        )

        /** Assembled, so that no line this test logs can ever match it. */
        val OUT_OF_MEMORY = "OutOfMemory" + "Error"

        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}
