package com.shinsaikou.presentation.core.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator.InFlightSolve
import com.shinsaikou.i18n.R
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.i18n.pluralStringResource

/**
 * What the banner says about the solves in flight, with no Android or Compose type in it, so the
 * wording rules test on the JVM.
 *
 * @property host the oldest solve's host, the one the banner names.
 * @property elapsedSeconds how long that solve has run. A second solve on the same host publishes a
 *   new start time, so this restarts from zero when a waiter re-solves after a failed attempt.
 * @property budgetSeconds that solve's own budget, read off the entry and never assumed.
 * @property waiters requests queued behind that solve, which proceed when it finishes.
 * @property moreHosts solves running for other hosts at the same time.
 */
data class SolveBannerText(
    val host: String,
    val elapsedSeconds: Long,
    val budgetSeconds: Long,
    val waiters: Int,
    val moreHosts: Int,
)

/**
 * Null when nothing is in flight. Several hosts collapse to the oldest, because that is the one the
 * person has waited on longest; the rest are a count. Ties on the start time go to the host name so
 * the banner cannot flip between two hosts from one tick to the next.
 *
 * The elapsed figure stops at the budget. The solver's wait is the budget exactly, and the moment
 * between its latch timing out and the entry leaving the map would otherwise read as "31 of 30".
 */
fun solveBannerText(inFlight: Map<String, InFlightSolve>, nowElapsedMs: Long): SolveBannerText? {
    val oldest = inFlight.values.minWithOrNull(compareBy({ it.startedAtElapsedMs }, { it.host }))
        ?: return null
    val budgetSeconds = oldest.budgetMs / MILLIS_PER_SECOND
    val elapsedSeconds = (nowElapsedMs - oldest.startedAtElapsedMs).coerceAtLeast(0) / MILLIS_PER_SECOND
    return SolveBannerText(
        host = oldest.host,
        elapsedSeconds = elapsedSeconds.coerceAtMost(budgetSeconds),
        budgetSeconds = budgetSeconds,
        waiters = oldest.waiters,
        moreHosts = inFlight.size - 1,
    )
}

/**
 * The live latency budget (PLAN.md section 4, contract 03): which host is being checked in a
 * browser, for how long, and against what budget, while it happens rather than after it failed.
 *
 * Takes the coordinator's `inFlight` map and composes nothing at all while it is empty, so a screen
 * that places it pays nothing on the paths nobody is waiting on. The once-a-second tick lives only
 * inside the non-empty branch, and stops when the last solve leaves the map.
 *
 * @param action an optional trailing control, handed the oldest host (the one the text names).
 */
@Composable
fun SolveBanner(
    inFlight: Map<String, InFlightSolve>,
    modifier: Modifier = Modifier,
    action: @Composable ((host: String) -> Unit)? = null,
) {
    if (inFlight.isEmpty()) return

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(TICK_MILLIS)
        }
    }
    val text = solveBannerText(inFlight, now) ?: return

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pluralStringResource(
                        R.plurals.solve_banner_waiting_format,
                        text.budgetSeconds.toInt(),
                        text.host,
                        text.elapsedSeconds,
                        text.budgetSeconds,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (text.waiters > 0) {
                    Text(
                        pluralStringResource(R.plurals.solve_banner_waiters_format, text.waiters, text.waiters),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (text.moreHosts > 0) {
                    Text(
                        pluralStringResource(R.plurals.solve_banner_more_hosts_format, text.moreHosts, text.moreHosts),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            action?.invoke(text.host)
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val TICK_MILLIS = 1_000L
