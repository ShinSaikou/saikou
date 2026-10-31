// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/stats/StatsScreenContent.kt (modified: yes) components/StatsItem.kt and TimeUtils.kt's toDurationString folded in, private; Android string resources
package com.shinsaikou.ui.stats

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item {
            OverviewSection(state.overview)
        }
        item {
            TitlesStats(state.titles)
        }
        item {
            ChapterStats(state.chapters)
        }
        item {
            TrackerStats(state.trackers)
        }
    }
}

@Composable
private fun LazyItemScope.OverviewSection(
    data: StatsData.Overview,
) {
    val none = stringResource(R.string.none)
    val context = LocalContext.current
    val readDurationString = remember(data.totalReadDuration) {
        data.totalReadDuration
            .toDuration(DurationUnit.MILLISECONDS)
            .toDurationString(context, fallback = none)
    }
    SectionCard(R.string.label_overview_section) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            StatsOverviewItem(
                title = data.libraryMangaCount.toString(),
                subtitle = stringResource(R.string.in_library),
                icon = Icons.Outlined.CollectionsBookmark,
            )
            StatsOverviewItem(
                title = readDurationString,
                subtitle = stringResource(R.string.label_read_duration),
                icon = Icons.Outlined.Schedule,
            )
            StatsOverviewItem(
                title = data.completedMangaCount.toString(),
                subtitle = stringResource(R.string.label_completed_titles),
                icon = Icons.Outlined.LocalLibrary,
            )
        }
    }
}

@Composable
private fun LazyItemScope.TitlesStats(
    data: StatsData.Titles,
) {
    SectionCard(R.string.label_titles_section) {
        Row {
            StatsItem(
                data.globalUpdateItemCount.toString(),
                stringResource(R.string.label_titles_in_global_update),
            )
            StatsItem(
                data.startedMangaCount.toString(),
                stringResource(R.string.label_started),
            )
            StatsItem(
                data.localMangaCount.toString(),
                stringResource(R.string.label_local),
            )
        }
    }
}

@Composable
private fun LazyItemScope.ChapterStats(
    data: StatsData.Chapters,
) {
    SectionCard(R.string.chapters) {
        Row {
            StatsItem(
                data.totalChapterCount.toString(),
                stringResource(R.string.label_total_chapters),
            )
            StatsItem(
                data.readChapterCount.toString(),
                stringResource(R.string.label_read_chapters),
            )
            StatsItem(
                data.downloadCount.toString(),
                stringResource(R.string.label_downloaded),
            )
        }
    }
}

@Composable
private fun LazyItemScope.TrackerStats(
    data: StatsData.Trackers,
) {
    val notApplicable = stringResource(R.string.not_applicable)
    val meanScoreStr = remember(data.trackedTitleCount, data.meanScore) {
        if (data.trackedTitleCount > 0 && !data.meanScore.isNaN()) {
            // All other numbers are localized in English
            "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        } else {
            notApplicable
        }
    }
    SectionCard(R.string.label_tracker_section) {
        Row {
            StatsItem(
                data.trackedTitleCount.toString(),
                stringResource(R.string.label_tracked_titles),
            )
            StatsItem(
                meanScoreStr,
                stringResource(R.string.label_mean_score),
            )
            StatsItem(
                data.trackerCount.toString(),
                stringResource(R.string.label_used),
            )
        }
    }
}

// From upstream's presentation/util/TimeUtils.kt.
private fun Duration.toDurationString(context: Context, fallback: String): String {
    return toComponents { days, hours, minutes, seconds, _ ->
        buildList(4) {
            if (days != 0L) add(context.getString(R.string.day_short, days))
            if (hours != 0) add(context.getString(R.string.hour_short, hours))
            if (minutes != 0 && (days == 0L || hours == 0)) {
                add(
                    context.getString(R.string.minute_short, minutes),
                )
            }
            if (seconds != 0 && days == 0L && hours == 0) add(context.getString(R.string.seconds_short, seconds))
        }.joinToString(" ").ifBlank { fallback }
    }
}

// From upstream's presentation/more/stats/components/StatsItem.kt.
@Composable
private fun RowScope.StatsOverviewItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    BaseStatsItem(
        title = title,
        titleStyle = MaterialTheme.typography.titleLarge,
        subtitle = subtitle,
        subtitleStyle = MaterialTheme.typography.bodyMedium,
        icon = icon,
    )
}

@Composable
private fun RowScope.StatsItem(
    title: String,
    subtitle: String,
) {
    BaseStatsItem(
        title = title,
        titleStyle = MaterialTheme.typography.bodyMedium,
        subtitle = subtitle,
        subtitleStyle = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun RowScope.BaseStatsItem(
    title: String,
    titleStyle: TextStyle,
    subtitle: String,
    subtitleStyle: TextStyle,
    icon: ImageVector? = null,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = titleStyle
                .copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = subtitle,
            style = subtitleStyle
                .copy(
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = SECONDARY_ALPHA),
                ),
            textAlign = TextAlign.Center,
        )
        if (icon != null) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
