package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.manga.MangaDetailViewModel.RankingChange
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Which scanlator groups the chapter list shows, and which of them the owner prefers.
 *
 * A ticked box means shown, which is the direction every other filter row in the app reads, so
 * what the owner hands back is inverted into the excluded set on confirm. The working copy is
 * local: the dialog never reaches the database, and nothing is written until the owner confirms.
 * It lives as long as the dialog is open, and a change to the title's groups, exclusions or ranking
 * while it is open (a library update, a sync pull) is laid under the owner's edits
 * ([ScanlatorDraft.rebase]) instead of discarding them.
 *
 * Each row's move-up and move-down arrows order the groups (phase 4 unit SC). Where two groups have
 * the same chapter number, the higher group's copy is the one both resume buttons open and, with
 * "Skip duplicate chapters" on, the one the reader and download ahead keep. The one OK writes the
 * exclusions and, for the ticked groups in order, the ranking ([ScanlatorDraft.rankingChange]).
 * "Reset order" is offered while there is an order to reset.
 */
@Composable
internal fun ScanlatorFilterDialog(
    available: Set<String>,
    excluded: Set<String>,
    ranking: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>, RankingChange) -> Unit,
) {
    var working by remember { mutableStateOf(ScanlatorDraft.of(available, excluded, ranking)) }
    val draft = remember(working, available, excluded, ranking) { working.rebase(available, excluded, ranking) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.scanlator_filter_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = MAX_CONTENT_HEIGHT).verticalScroll(rememberScrollState())) {
                draft.order.forEachIndexed { index, scanlator ->
                    ScanlatorRow(
                        scanlator = scanlator,
                        shown = scanlator !in draft.excluded,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.order.lastIndex,
                        onToggle = { working = draft.toggle(scanlator) },
                        onMoveUp = { working = draft.move(index, index - 1) },
                        onMoveDown = { working = draft.move(index, index + 1) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.excluded, draft.rankingChange(ranking)) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (draft.ranked) {
                    TextButton(onClick = { working = draft.resetOrder() }) {
                        Text(stringResource(R.string.scanlator_reset_order))
                    }
                }
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * The checkbox row with the two arrows beside it. The arrows are disabled at the ends of the list
 * instead of hidden, so they stay in the same place on every row, as on the categories screen.
 */
@Composable
private fun ScanlatorRow(
    scanlator: String,
    shown: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            CheckboxItem(label = scanlator, checked = shown, onClick = onToggle)
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.scanlator_move_up))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.scanlator_move_down))
        }
    }
}

/**
 * The dialog's working copy, apart from the composable so its rules run on the JVM.
 *
 * [order] is every available group: the ranked ones first in rank order, then the rest by name.
 * [opened] is the ticked groups in that order as the dialog opened, what an OK that changed nothing
 * hands back. [hasRanking] is true while the title has a ranking among these groups that "Reset
 * order" has not cleared; [cleared] once "Reset order" was pressed. [edited] is the groups whose box
 * the owner clicked and [moved] is true once a row was moved since the dialog opened or "Reset
 * order" was pressed: the owner's edits, which [rebase] keeps.
 */
@Immutable
internal data class ScanlatorDraft(
    val order: List<String>,
    val excluded: Set<String>,
    val opened: List<String>,
    val hasRanking: Boolean,
    val cleared: Boolean = false,
    val edited: Set<String> = emptySet(),
    val moved: Boolean = false,
) {
    /**
     * True while the rows show a ranking: the title's own, or rows moved out of name order. A row
     * moved and moved back is name order again, so "Reset order" hides.
     */
    val ranked: Boolean get() = hasRanking || order != order.sorted()

    fun toggle(scanlator: String): ScanlatorDraft =
        copy(
            excluded = if (scanlator in excluded) excluded - scanlator else excluded + scanlator,
            edited = edited + scanlator,
        )

    fun move(from: Int, to: Int): ScanlatorDraft {
        if (from !in order.indices || to !in order.indices) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) }, moved = true)
    }

    /** The rows back in name order, the order of an unranked title; OK then clears the ranking. */
    fun resetOrder(): ScanlatorDraft =
        copy(order = order.sorted(), hasRanking = false, cleared = true, moved = false)

    /**
     * This draft laid over the title's groups, exclusions and ranking as they are now, which can
     * change while the dialog is open: a library update or a sync pull brings a new group's chapters
     * or another device's exclusions or ranking (review 3). A group whose box the owner clicked keeps
     * the owner's tick and every other group takes the current one. Once a row was moved, the rows
     * keep the owner's order, a group gone from the title dropping out and a new one joining at the
     * end by name; otherwise they are the current order, by name after "Reset order". [opened] and
     * [hasRanking] follow the current ranking, so an OK that changed nothing still writes nothing.
     * With nothing changed underneath, the result is this draft again.
     */
    fun rebase(available: Set<String>, excluded: Set<String>, ranking: List<String>): ScanlatorDraft {
        val current = of(available, excluded, ranking)
        val order = when {
            moved -> order.filter { it in available }.let { kept -> kept + (available - kept.toSet()).sorted() }
            cleared -> available.sorted()
            else -> current.order
        }
        return copy(
            order = order,
            excluded = (excluded - edited) + this.excluded.intersect(edited),
            opened = current.opened,
            hasRanking = current.hasRanking && !cleared,
        )
    }

    /**
     * What OK does to [existing], the title's ranking when the dialog opened, top first. A ranking
     * is written only when one already exists or the ticked groups are out of name order, so a
     * title the owner only filters, or whose rows were moved and moved back, stays unranked; it is
     * the ticked groups in the dialog's order, and nothing is written when that is the ranking the
     * title already has or the ticked groups as the dialog opened (review 2). The second matters
     * because [existing] need not match the rows: it can hold a group ranked on another device with
     * no chapters here or a group excluded here, and it lacks a group with chapters but no rank. An
     * OK that changed nothing must not rewrite the ranking from the rows.
     */
    fun rankingChange(existing: List<String>): RankingChange {
        val ticked = order.filterNot { it in excluded }
        if (!hasRanking && ticked == ticked.sorted()) {
            return if (cleared && existing.isNotEmpty()) RankingChange.Clear else RankingChange.Keep
        }
        return when {
            ticked == existing || ticked == opened -> RankingChange.Keep
            ticked.isEmpty() -> RankingChange.Clear
            else -> RankingChange.Write(ticked)
        }
    }

    companion object {
        fun of(available: Set<String>, excluded: Set<String>, ranking: List<String>): ScanlatorDraft {
            val rankedFirst = ranking.filter { it in available }
            val order = rankedFirst + (available - rankedFirst.toSet()).sorted()
            return ScanlatorDraft(
                order = order,
                excluded = excluded,
                opened = order.filterNot { it in excluded },
                hasRanking = rankedFirst.isNotEmpty(),
            )
        }
    }
}

private val MAX_CONTENT_HEIGHT = 400.dp
