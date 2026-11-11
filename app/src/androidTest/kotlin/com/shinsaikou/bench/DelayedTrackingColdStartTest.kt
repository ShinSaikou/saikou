package com.shinsaikou.bench

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.WorkManagerDelayedTrackingScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/**
 * The tracker retry worker started cold by WorkManager (phase 3 unit T1 stage 2), in two stages the
 * host runs with the device's network off in between, so the worker's network constraint holds the
 * run until the instrumentation process is gone:
 *
 * - `arm`: through the app's graph, one generated entry out of the library, one track row on it for
 *   Kitsu's id, which no tracker in this graph has (MyAnimeList's did until phase 3 unit T2
 *   contributed MyAnimeList, and AniList's until R162 item 8 contributed AniList), a push queued
 *   for that entry and tracker in the retry store (keyed by the pair since phase 3 unit TQ), and the worker enqueued by the app's own scheduler. It asserts the device has no network
 *   and the run waits ENQUEUED.
 * - `check`, after the host saw the process die, turned the network on, and saw WorkManager start
 *   the process and run the worker: the unique name's run SUCCEEDED, the push is gone from the store
 *   (dropped because no tracker has the row's id), and the row and the entry are deleted again.
 *
 * Both return at once with a `Skipped:` line unless [STAGE_ARGUMENT] names their stage, so a suite
 * that runs every class runs nothing here. In `com.shinsaikou.bench`, which steps 5b and 5c leave out,
 * like R1's staged in-place upgrade check.
 */
@RunWith(AndroidJUnit4::class)
class DelayedTrackingColdStartTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    @Test
    fun armsAQueuedPushNoTrackerOwns() {
        if (stage() != STAGE_ARM) return skip("armsAQueuedPushNoTrackerOwns", STAGE_ARM)
        runBlocking(Dispatchers.IO) {
            withTimeout(TIMEOUT_MILLIS) {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                assertNull("the host turns the network off before arming", connectivity.activeNetwork)
                assertEquals("a fresh install has no queued push", emptyList<Any>(), graph.delayedTrackingStore.getItems())
                assertTrue("no tracker in this graph has the row's id", graph.trackerManager.get(TRACKER_ID) == null)

                val manga = graph.mangaRepository.insertNetworkManga(
                    listOf(Manga.create().copy(source = SOURCE_ID, url = URL, title = TITLE, initialized = true)),
                ).single()
                graph.trackRepository.insert(track(manga.id))
                val track = graph.trackRepository.getTracksByMangaId(manga.id).single()

                graph.delayedTrackingStore.add(manga.id, TRACKER_ID, track.remoteId, CHAPTER)
                graph.delayedTrackingScheduler.schedule()

                val runs = graph.workManager.getWorkInfosForUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME).get()
                assertEquals(listOf(WorkInfo.State.ENQUEUED), runs.map { it.state })
                val queued = graph.delayedTrackingStore.getItems().single { it.mangaId == manga.id && it.trackerId == TRACKER_ID }
                assertEquals(CHAPTER, queued.chapterNumber, 0.0)
                Log.i(LOG_TAG, "armed: entry ${manga.id} on tracker $TRACKER_ID (row ${track.id}) queued at $CHAPTER, run ${runs.single().id} enqueued")
            }
        }
    }

    @Test
    fun checksTheColdRunDroppedThePush() {
        if (stage() != STAGE_CHECK) return skip("checksTheColdRunDroppedThePush", STAGE_CHECK)
        runBlocking(Dispatchers.IO) {
            withTimeout(TIMEOUT_MILLIS) {
                val manga = graph.mangaRepository.getMangaByUrlAndSourceId(URL, SOURCE_ID)
                val track = graph.trackRepository.getTracksByMangaId(checkNotNull(manga) { "arm did not run" }.id).single()

                val runs = graph.workManager.getWorkInfosForUniqueWork(WorkManagerDelayedTrackingScheduler.WORK_NAME).get()
                Log.i(LOG_TAG, "runs: ${runs.map { "${it.id} ${it.state} attempts=${it.runAttemptCount}" }}")
                assertEquals(listOf(WorkInfo.State.SUCCEEDED), runs.map { it.state })
                assertTrue(
                    "the push of entry ${manga.id} on tracker $TRACKER_ID is still queued",
                    graph.delayedTrackingStore.getItems().none { it.mangaId == manga.id && it.trackerId == TRACKER_ID },
                )
                // The row is left as it was: a push the worker drops is never sent, so nothing moved.
                assertEquals(0.0, track.lastChapterRead, 0.0)

                graph.trackRepository.deleteIfBoundTo(manga.id, TRACKER_ID, track.remoteId)
                graph.mangaRepository.deleteNonLibraryManga(listOf(SOURCE_ID), keepReadManga = false)
                Log.i(LOG_TAG, "checked: the push of entry ${manga.id} on tracker $TRACKER_ID dropped, run SUCCEEDED; the entry and its row are deleted")
            }
        }
    }

    private fun track(mangaId: Long) = Track(
        id = 0L,
        mangaId = mangaId,
        trackerId = TRACKER_ID,
        remoteId = 1L,
        libraryId = null,
        title = TITLE,
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 1L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun stage(): String? = InstrumentationRegistry.getArguments().getString(STAGE_ARGUMENT)?.takeIf { it.isNotBlank() }

    /** The line a run reads back, as `InPlaceUpgrade.skip` writes it: an early return says why. */
    private fun skip(method: String, wanted: String) {
        Log.i(LOG_TAG, "Skipped: $method: runs only when $STAGE_ARGUMENT is $wanted")
    }

    private companion object {
        const val STAGE_ARGUMENT = "saikou.tracking.coldstart"
        const val STAGE_ARM = "arm"
        const val STAGE_CHECK = "check"
        const val LOG_TAG = "DelayedTrackingColdStart"

        /** A source id no extension has, one of the device suite's own (see `SyncLatchDeviceTest`'s). */
        const val SOURCE_ID = 9_000_000_000_000_000_007L
        const val URL = "/saikou-tracking-cold-start/entry"
        const val TITLE = "Tracking cold start"
        const val CHAPTER = 5.0

        /** A known id no tracker in the graph has: the owner's scope ships MyAnimeList, AniList and MangaBaka. */
        const val TRACKER_ID = TrackerIds.KITSU
        const val TIMEOUT_MILLIS = 60_000L
    }
}
