package com.shinsaikou.reader

import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle

/**
 * The only public entry point of `:reader`. Contract 07 part 2 section 5.1.
 *
 * The extra names and the saved-state keys are mapped in one place, [readArgs], so the two name
 * sets cannot drift. Contract 08 section 6.2's `ReaderActivity.intent(context, route)` with a
 * serialized route extra is superseded by ruling R11 and by this object (decision D13).
 *
 * The reading-mode override part 2 section 8.5 reserved for instrumentation is gone, as ruling R39
 * amending sections 5.1 and 8.5. It selected the viewer family without the page area ever drawing
 * (carry-over 12), and its only caller was a device test. A test that wants a viewer family now
 * writes the mode onto its own entry row, which is the path an owner's library takes anyway, so
 * nothing ships an intent surface that exists only for a test.
 */
object ReaderIntents {

    const val EXTRA_MANGA_ID = "com.shinsaikou.reader.extra.MANGA_ID"
    const val EXTRA_CHAPTER_ID = "com.shinsaikou.reader.extra.CHAPTER_ID"

    /** Optional 0-based page to open at; absent means "resume where the chapter was left". */
    const val EXTRA_PAGE = "com.shinsaikou.reader.extra.PAGE"

    /** Bench and instrumentation only (part 2 section 8.5); ignored when absent. */
    const val EXTRA_BENCH_TAG = "com.shinsaikou.reader.extra.BENCH_TAG"

    const val KEY_MANGA_ID = "saikou.mangaId"
    const val KEY_CHAPTER_ID = "saikou.chapterId"
    const val KEY_PAGE = "saikou.page"

    /**
     * The chapter the intent named, kept apart from [KEY_CHAPTER_ID], which follows the reader
     * from chapter to chapter. A reader restored into a chapter that is gone falls back to it.
     */
    const val KEY_START_CHAPTER_ID = "saikou.startChapterId"
    const val KEY_BENCH_TAG = "saikou.benchTag"

    /**
     * The note dialog left open and the text in its field, so a process the system killed while the
     * owner was writing a note reopens the dialog with that text (phase 4 unit BN). Absent while no
     * note dialog is open; never read from the intent.
     */
    const val KEY_NOTE_CHAPTER_ID = "saikou.noteChapterId"
    const val KEY_NOTE_PAGE = "saikou.notePage"
    const val KEY_NOTE_TEXT = "saikou.noteText"

    fun newIntent(context: Context, mangaId: Long, chapterId: Long, page: Int? = null): Intent =
        Intent(context, ReaderActivity::class.java).apply {
            putExtra(EXTRA_MANGA_ID, mangaId)
            putExtra(EXTRA_CHAPTER_ID, chapterId)
            page?.let { putExtra(EXTRA_PAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Copies the intent's extras into the handle under the keys [ReaderViewModel] reads. A value
     * already in the handle wins, because it came back from process death and is newer than the
     * intent that started the activity.
     */
    fun readArgs(savedState: SavedStateHandle, intent: Intent) {
        savedState.putIfAbsent(KEY_MANGA_ID, intent.longExtraOrNull(EXTRA_MANGA_ID))
        savedState.putIfAbsent(KEY_CHAPTER_ID, intent.longExtraOrNull(EXTRA_CHAPTER_ID))
        savedState.putIfAbsent(KEY_START_CHAPTER_ID, intent.longExtraOrNull(EXTRA_CHAPTER_ID))
        savedState.putIfAbsent(KEY_PAGE, intent.intExtraOrNull(EXTRA_PAGE))
        savedState.putIfAbsent(KEY_BENCH_TAG, intent.getStringExtra(EXTRA_BENCH_TAG))
    }

    private fun <T> SavedStateHandle.putIfAbsent(key: String, value: T?) {
        if (value != null && !contains(key)) this[key] = value
    }

    private fun Intent.longExtraOrNull(name: String): Long? =
        if (hasExtra(name)) getLongExtra(name, 0L) else null

    private fun Intent.intExtraOrNull(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, 0) else null
}
