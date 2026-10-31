package com.shinsaikou.reader.bench

import android.os.Build
import android.os.Trace
import logcat.LogPriority
import logcat.logcat

/**
 * The reader's whole contribution to a Perfetto trace and to the gate's log scraping.
 * Contract 07 part 2 section 8.2.
 *
 * The only place `android.os.Trace` is called from `:reader`.
 *
 * Three rules, all load-bearing:
 *
 *  - **The sections are always compiled in and always called.** They are not behind a debug flag.
 *    The `benchmark` build type is not debuggable and `:reader` has no `BuildConfig`, so anything
 *    gated on `BuildConfig.DEBUG` would be absent from exactly the build the gate measures. An
 *    unmatched `beginSection` with tracing off costs one JNI call (decision D29).
 *  - **A section name is static and at most [MAX_SECTION_NAME] characters.** A page index in the
 *    name makes the trace unqueryable by name; per-page identity goes in the async cookie.
 *  - **[mark] writes through `logcat`, never `android.util.Log`,** and never a title, a URL or a
 *    source name. Chapter and manga ids are numbers and are safe under the wording rule.
 */
object ReaderBenchHooks {

    const val LOG_TAG = "SaikouBench"

    /** `Trace.beginAsyncSection` is API 29; below it the async pair is a no-op. */
    const val MIN_ASYNC_API = Build.VERSION_CODES.Q

    /** The platform truncates a longer name, which silently renames the slice. */
    const val MAX_SECTION_NAME = 127

    private var benchTag: String? = null

    inline fun <T> trace(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** For work that ends on another thread or another frame. */
    fun beginAsync(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= MIN_ASYNC_API) {
            Trace.beginAsyncSection(name, cookie)
        }
    }

    fun endAsync(name: String, cookie: Int) {
        if (Build.VERSION.SDK_INT >= MIN_ASYNC_API) {
            Trace.endAsyncSection(name, cookie)
        }
    }

    /**
     * One line per milestone, always emitted. The Perfetto config records `android.log`, so these
     * land in the trace as well as in logcat. [tag] defaults to the tag [beginOpen] was given, so a
     * caller mid-session does not have to carry it.
     */
    fun mark(event: String, tag: String? = benchTag) {
        val suffix = tag?.let { " tag=$it" }.orEmpty()
        logcat(LOG_TAG, LogPriority.INFO) { event + suffix }
    }

    /** Opens the `Reader.open` async section and remembers the run's tag. */
    fun beginOpen(benchTag: String?) {
        this.benchTag = benchTag
        beginAsync(SECTION_OPEN, OPEN_COOKIE)
        mark("reader.open")
    }

    /** Closes `Reader.open`. Safe to call more than once; the second end is what Perfetto drops. */
    fun endOpen() {
        endAsync(SECTION_OPEN, OPEN_COOKIE)
    }

    const val SECTION_OPEN = "Reader.open"
    const val SECTION_BUILD_VIEWER = "Reader.buildViewer"
    const val SECTION_SET_CHAPTERS = "Reader.setChapters"
    const val SECTION_MOVE_TO_PAGE = "Reader.moveToPage"
    const val SECTION_BIND_PAGE = "Reader.bindPage"
    const val SECTION_SET_IMAGE = "Reader.setImage"
    const val SECTION_REDUCE = "Reader.reduce"
    const val SECTION_FETCH_PAGE = "Reader.fetchPage"
    const val SECTION_PROBE = "Reader.probe"
    const val SECTION_SPLIT = "Reader.split"
    const val SECTION_CACHE_WRITE = "Reader.cacheWrite"
    const val SECTION_TRIM = "Reader.trim"
    const val SECTION_COMMIT_PROGRESS = "Reader.commitProgress"

    private const val OPEN_COOKIE = 0
}
