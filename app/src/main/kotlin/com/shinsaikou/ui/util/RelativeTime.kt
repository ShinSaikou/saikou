package com.shinsaikou.ui.util

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Upstream's `relativeTimeSpanString` (`presentation/util/TimeUtils.kt`): Never for no time, Just now
 * inside a minute, and the platform's relative span in minutes after that. Settings > Data shows the
 * last automatic backup with it, and Updates and Settings > Library the last library update.
 */
@Composable
internal fun relativeTimeSpanString(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        epochMillis <= 0L -> stringResource(R.string.relative_time_span_never)
        now - epochMillis < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.updates_last_update_info_just_now)
        else -> DateUtils.getRelativeTimeSpanString(epochMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
