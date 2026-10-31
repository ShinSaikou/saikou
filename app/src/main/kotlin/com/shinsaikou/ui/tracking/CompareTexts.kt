package com.shinsaikou.ui.tracking

import android.content.res.Resources
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.CantCompareReason
import com.shinsaikou.tracking.CompareChoice
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.CompareWarning
import com.shinsaikou.tracking.ComparedEntry
import com.shinsaikou.tracking.RemoteAnswer
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.Row
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.RowResult
import com.shinsaikou.ui.tracking.TrackerCompareViewModel.UpdateEnding
import com.shinsaikou.ui.util.ErrorText

/*
 * The texts of a row of "Compare with MyAnimeList" (phase 4 unit TC), the design's section 7.3, from
 * the row's numbers: its choices' labels and sentences, its warnings and notes, and what Apply did
 * with it. [name] is the tracker's name in every text that names it.
 */

/** "In the app: chapter 45", or "no chapters read" when none is. */
internal fun inAppText(resources: Resources, entry: ComparedEntry): String = if (entry.maxRead < 0) {
    resources.getString(R.string.compare_in_app_none)
} else {
    resources.getString(R.string.compare_in_app_format, entry.app.toString())
}

/** [row]'s MyAnimeList number, 0 when the entry is not on the list. */
private val Row.remote: Int get() = entry.listed?.chaptersRead ?: 0

internal fun choiceLabel(resources: Resources, row: Row, choice: CompareChoice, name: String): String = when (choice) {
    CompareChoice.DONT_CHANGE -> resources.getString(R.string.compare_choice_dont_change)
    CompareChoice.MARK_READ -> resources.getString(R.string.compare_choice_mark_read)
    CompareChoice.SET_REMOTE_BACK -> resources.getString(R.string.compare_choice_set_back_format, name, row.entry.app.toString())
    CompareChoice.UPDATE_REMOTE -> resources.getString(R.string.compare_choice_update_format, name, row.entry.target.toString())
    CompareChoice.KEEP_REMOTE -> resources.getString(R.string.compare_choice_keep_format, name, row.remote.toString())
    CompareChoice.ADD_BACK -> resources.getString(R.string.compare_choice_add_back)
    CompareChoice.REMOVE_TRACKING -> resources.getString(R.string.compare_choice_remove)
}

/** What [choice] would change for [row], in the words the owner reads before choosing it. */
internal fun sentence(resources: Resources, row: Row, choice: CompareChoice, name: String): String {
    val entry = row.entry
    val waiting = entry.waitingUpdate
    return when (choice) {
        CompareChoice.DONT_CHANGE -> when {
            entry.remote == RemoteAnswer.Gone -> resources.getString(R.string.compare_gone_dont_change)
            entry.section == CompareSection.NOT_ON_LIST && waiting != null ->
                resources.getString(R.string.compare_not_on_list_waiting_format, name)
            entry.section == CompareSection.NOT_ON_LIST -> resources.getString(R.string.compare_not_on_list_dont_change_format, name)
            waiting != null -> resources.getString(R.string.compare_dont_change_waiting_format, chapterText(waiting.chapterNumber), name)
            else -> resources.getString(R.string.compare_dont_change)
        }
        CompareChoice.MARK_READ -> markReadSentence(resources, row, name)
        CompareChoice.SET_REMOTE_BACK -> buildString {
            append(resources.getString(R.string.compare_set_back_format, name, entry.app.toString(), row.remote.toString()))
            if (row.remoteCompleted) append(' ').append(resources.getString(R.string.compare_set_back_completed_format, name))
        }
        CompareChoice.UPDATE_REMOTE -> buildString {
            val format = if (entry.target < entry.app) R.string.compare_update_capped_format else R.string.compare_update_format
            append(resources.getString(format, name, entry.target.toString()))
            when (row.ending) {
                UpdateEnding.NONE -> Unit
                UpdateEnding.READING -> append(' ').append(resources.getString(R.string.compare_update_reading))
                UpdateEnding.COMPLETED -> append(' ').append(resources.getString(R.string.compare_update_completed))
                UpdateEnding.ENDS_REREAD -> append(' ').append(resources.getString(R.string.compare_update_ends_reread_format, name))
            }
        }
        CompareChoice.KEEP_REMOTE -> resources.getString(
            R.string.compare_keep_format,
            chapterText(waiting?.chapterNumber ?: 0.0),
            name,
            row.remote.toString(),
        )
        CompareChoice.ADD_BACK -> resources.getString(R.string.compare_add_back_format, name, entry.app.toString())
        CompareChoice.REMOVE_TRACKING -> resources.getString(R.string.compare_remove_format, name)
    }
}

/**
 * "Mark chapters 46 to 50 as read in the app (5 chapters) ...": the range the chapters to mark
 * cover, their rows counted (each scanlator's copy, said so when copies outnumber the numbers), and
 * the chapters the app does not have yet up to MyAnimeList's number.
 */
private fun markReadSentence(resources: Resources, row: Row, name: String): String {
    val numbers = row.entry.toMark.map { it.number }
    val low = numbers.minOrNull() ?: return ""
    val high = numbers.max()
    val count = numbers.size
    val countText = if (count > numbers.distinct().size) {
        resources.getQuantityString(R.plurals.compare_count_chapter_copies, count, count)
    } else {
        resources.getQuantityString(R.plurals.compare_count_chapters, count, count)
    }
    val remote = row.remote.toString()
    return buildString {
        if (low == high) {
            append(resources.getString(R.string.compare_mark_read_one_format, chapterText(low), countText, name, remote))
        } else {
            append(resources.getString(R.string.compare_mark_read_range_format, chapterText(low), chapterText(high), countText, name, remote))
        }
        if (high < row.remote) {
            append(' ').append(resources.getString(R.string.compare_mark_read_not_in_app_format, chapterText(high), remote))
        }
    }
}

/**
 * The row's lines besides its numbers: its warnings, its waiting update, its reason, and the note
 * of a row with nothing to mark; [inDialog] adds the note under the options of an app-ahead row.
 */
internal fun notes(resources: Resources, row: Row, name: String, inDialog: Boolean): List<String> = buildList {
    val entry = row.entry
    entry.warnings.forEach { warning ->
        add(
            when (warning) {
                CompareWarning.NUMBERING -> resources.getQuantityString(
                    R.plurals.compare_warning_numbering_format,
                    entry.count.toInt(),
                    name,
                    entry.count.toInt(),
                    chapterText(entry.last),
                )
                CompareWarning.REREAD -> resources.getString(R.string.compare_warning_reread_format, name, row.remote.toString())
                CompareWarning.NOT_OPENED -> resources.getString(R.string.compare_warning_not_opened_format, chapterText(entry.maxRead))
            },
        )
    }
    entry.waitingUpdate?.let { add(resources.getString(R.string.compare_waiting_format, chapterText(it.chapterNumber), name)) }
    when (entry.cantCompare) {
        CantCompareReason.NO_CHAPTERS -> add(resources.getString(R.string.compare_cant_no_chapters))
        CantCompareReason.NO_NUMBERS -> add(resources.getString(R.string.compare_cant_no_numbers_format, name))
        CantCompareReason.COULD_NOT_CHECK -> add(
            resources.getString(R.string.compare_cant_check_format, name, ErrorText.of(resources, (entry.remote as? RemoteAnswer.Failed)?.error)),
        )
        null -> Unit
    }
    if (entry.remote == RemoteAnswer.Gone) add(resources.getString(R.string.compare_gone_format, name))
    if (entry.section == CompareSection.REMOTE_AHEAD && entry.toMark.isEmpty()) {
        val from = if (entry.maxRead < 0) 0 else entry.app + 1
        add(resources.getString(R.string.compare_nothing_to_mark_format, from, row.remote))
    }
    if (inDialog && entry.section == CompareSection.APP_AHEAD) {
        add(
            resources.getString(
                R.string.compare_app_ahead_note_format,
                row.remote.toString(),
                (row.remote + 1).toString(),
                entry.app.toString(),
            ),
        )
    }
}

/** What Apply did with a row that stays, instead of its sentence. */
internal fun resultText(resources: Resources, result: RowResult, name: String): String = when (result) {
    RowResult.ChangedSince -> resources.getString(R.string.compare_result_changed)
    is RowResult.Kept -> resources.getString(R.string.compare_result_kept_format, name, result.remoteChapters.toString())
    is RowResult.PartlyMarked ->
        resources.getQuantityString(R.plurals.compare_result_partly_marked_format, result.listed, result.marked, result.listed)
    is RowResult.Failed -> ErrorText.of(resources, result.problem.key, result.problem.detail)
    RowResult.Unanswered -> resources.getString(R.string.compare_result_unanswered_format, name)
}

/** A chapter number as a whole number when it is one ("46", not "46.0"), else as it is ("45.5"). */
internal fun chapterText(number: Double): String =
    if (number.isFinite() && number == Math.floor(number)) number.toLong().toString() else number.toString()
