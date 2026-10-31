// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsViewModel.kt (modified: yes)
package com.shinsaikou.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.sources.local.isLocal
import com.shinsaikou.tracking.TrackerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * Modified (phase 4 unit SS): Metro constructor injection instead of Injekt, and three sources
 * differ from upstream's.
 *
 * - The update tile is the library update's own queue, `FlatLibraryUpdate.buildQueue` for an
 *   automatic run, built from the library list this screen has already read rather than a second
 *   read of its own (phase 5 unit SC5), so it applies the fetch-once and release-period rules
 *   Saikou's update applies and upstream's stat leaves out, with the window taken as the detail
 *   refresh takes it.
 * - Every track row is read in one query and grouped by entry, where upstream asks once per entry.
 * - The download count is the manifest's per-entry counts summed, every downloaded chapter as
 *   upstream's `DownloadManager.getDownloadCount` counts them.
 *
 * The read duration is `sum(time_read)` over history. Sync merges a chapter's duration by the
 * larger of the two devices' values (R82), so a chapter read on two devices counts its longer
 * session once, not both.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(StatsViewModel::class)
@Inject
class StatsViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val getTotalReadDuration: GetTotalReadDuration,
    private val trackRepository: TrackRepository,
    private val trackerManager: TrackerManager,
    private val downloadManifest: DownloadManifest,
    private val libraryUpdate: FlatLibraryUpdate,
    private val fetchInterval: FetchInterval,
    private val dispatchers: Dispatchers,
) : StateViewModel<StatsScreenState>(StatsScreenState.Loading) {

    private val loggedInTrackers by lazy { trackerManager.loggedInTrackers() }

    init {
        viewModelScope.launch(dispatchers.io) {
            val startedAt = TimeSource.Monotonic.markNow()

            // The reads that do not need the library start first and run beside its read, on this
            // coroutine's IO dispatcher (phase 5 unit SC5); before, each waited for the one before it.
            val tracks = async { trackRepository.getTracksAsFlow().first() }
            val totalReadDuration = async { getTotalReadDuration.await() }
            val downloadCount = async { downloadManifest.subscribeCountsByManga().first().values.sum().toInt() }

            val libraryManga = getLibraryManga.await()
            val libraryRead = startedAt.elapsedNow()

            // The update tile takes the list in hand; it never reads the library again.
            val updateQueue = async { measureTimedValue { getGlobalUpdateItemCount(libraryManga) } }

            val distinctLibraryManga = libraryManga.distinctBy { it.id }

            val mangaTrackMap = getMangaTrackMap(distinctLibraryManga, tracks.await())
            val scoredMangaTrackerMap = getScoredMangaTrackMap(mangaTrackMap)

            val meanScore = getTrackMeanScore(scoredMangaTrackerMap)

            val overviewStatData = StatsData.Overview(
                libraryMangaCount = distinctLibraryManga.size,
                completedMangaCount = distinctLibraryManga.count {
                    it.manga.status == MangaStatus.COMPLETED.code.toLong() && it.unreadCount == 0L
                },
                totalReadDuration = totalReadDuration.await(),
            )

            val globalUpdateItemCount = updateQueue.await()
            val titlesStatData = StatsData.Titles(
                globalUpdateItemCount = globalUpdateItemCount.value,
                startedMangaCount = distinctLibraryManga.count { it.hasStarted },
                localMangaCount = distinctLibraryManga.count { it.manga.isLocal() },
            )

            val chaptersStatData = StatsData.Chapters(
                totalChapterCount = distinctLibraryManga.sumOf { it.totalChapters }.toInt(),
                readChapterCount = distinctLibraryManga.sumOf { it.readCount }.toInt(),
                downloadCount = downloadCount.await(),
            )

            val trackersStatData = StatsData.Trackers(
                trackedTitleCount = mangaTrackMap.count { it.value.isNotEmpty() },
                meanScore = meanScore,
                trackerCount = loggedInTrackers.size,
            )

            mutableState.update {
                StatsScreenState.Success(
                    overview = overviewStatData,
                    titles = titlesStatData,
                    chapters = chaptersStatData,
                    trackers = trackersStatData,
                )
            }
            logcat(LogPriority.DEBUG) {
                "Statistics: ${libraryManga.size} library rows read in ${libraryRead.inWholeMilliseconds} ms, " +
                    "the update tile's queue built in ${globalUpdateItemCount.duration.inWholeMilliseconds} ms, " +
                    "every tile in ${startedAt.elapsedNow().inWholeMilliseconds} ms"
            }
        }
    }

    /** The entries an automatic library update would refresh now, by the update's own rules. */
    private fun getGlobalUpdateItemCount(libraryManga: List<LibraryManga>): Int {
        val zone = TimeZone.currentSystemDefault()
        val window = fetchInterval.getWindow(Clock.System.now().toLocalDateTime(zone).date, zone)
        return libraryUpdate.buildQueue(libraryManga, categoryId = null, automatic = true, window = window).size
    }

    private fun getMangaTrackMap(libraryManga: List<LibraryManga>, tracks: List<Track>): Map<Long, List<Track>> {
        val loggedInTrackerIds = loggedInTrackers.map { it.id }.toHashSet()
        val tracksByManga = tracks.groupBy { it.mangaId }
        return libraryManga.associate { manga ->
            val tracks = tracksByManga[manga.id].orEmpty()
                .filter { it.trackerId in loggedInTrackerIds }

            manga.id to tracks
        }
    }

    private fun getScoredMangaTrackMap(mangaTrackMap: Map<Long, List<Track>>): Map<Long, List<Track>> {
        return mangaTrackMap.mapNotNull { (mangaId, tracks) ->
            val trackList = tracks.mapNotNull { track ->
                track.takeIf { it.score > 0.0 }
            }
            if (trackList.isEmpty()) return@mapNotNull null
            mangaId to trackList
        }.toMap()
    }

    private fun getTrackMeanScore(scoredMangaTrackMap: Map<Long, List<Track>>): Double {
        return scoredMangaTrackMap
            .map { (_, tracks) ->
                tracks.map(::get10PointScore).average()
            }
            .filter { !it.isNaN() }
            .average()
    }

    private fun get10PointScore(track: Track): Double {
        val service = trackerManager.get(track.trackerId)!!
        return service.get10PointScore(track)
    }
}
