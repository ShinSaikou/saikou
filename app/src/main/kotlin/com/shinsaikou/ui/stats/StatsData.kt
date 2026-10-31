// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/stats/data/StatsData.kt (modified: yes) StatsScreenState.kt folded in; package com.shinsaikou.ui.stats
package com.shinsaikou.ui.stats

import androidx.compose.runtime.Immutable

sealed interface StatsScreenState {
    @Immutable
    data object Loading : StatsScreenState

    @Immutable
    data class Success(
        val overview: StatsData.Overview,
        val titles: StatsData.Titles,
        val chapters: StatsData.Chapters,
        val trackers: StatsData.Trackers,
    ) : StatsScreenState
}

sealed interface StatsData {

    data class Overview(
        val libraryMangaCount: Int,
        val completedMangaCount: Int,
        val totalReadDuration: Long,
    ) : StatsData

    data class Titles(
        val globalUpdateItemCount: Int,
        val startedMangaCount: Int,
        val localMangaCount: Int,
    ) : StatsData

    data class Chapters(
        val totalChapterCount: Int,
        val readChapterCount: Int,
        val downloadCount: Int,
    ) : StatsData

    data class Trackers(
        val trackedTitleCount: Int,
        val meanScore: Double,
        val trackerCount: Int,
    ) : StatsData
}
