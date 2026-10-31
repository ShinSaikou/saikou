package com.shinsaikou.downloads.adoption

import com.shinsaikou.domain.error.ErrorKey

/**
 * What one adoption run did, for the owner to read at the end. Every path is relative to the tree
 * the owner picked, with `/` between names.
 *
 * [matched] counts the chapter folders and archives the walker matched to a chapter row; each of
 * them is then [copied], [skipped] because a download of that chapter is already there (an earlier
 * run adopted it, or the engine downloaded it), or one of [failures]. [unmatched] lists what matched
 * nothing, each with why, and it is left where it is (phase 2 decision Q6(c)).
 *
 * [treeUri] is the tree the run read, which the summary screen's Copy again starts a run over; null
 * in a summary written before it was kept.
 */
data class AdoptionSummary(
    val finishedAt: Long,
    val matched: Int,
    val copied: Int,
    val skipped: Int,
    val unmatched: List<AdoptionUnmatched>,
    val failures: List<AdoptionFailure>,
    val treeUri: String? = null,
)

/**
 * A folder or chapter the walker matched to nothing (unit adoption-unmatched-reasons), with what it
 * knew when it gave up, so the summary can offer the action that would match it next time.
 *
 * [sourceName] and [sourceLang] are the source folder's name read as upstream writes it,
 * `"<name> (<LANG>)"`, with the language as the folder spells it; a folder that does not read that
 * way is [sourceName] whole and no [sourceLang]. [title] is the entry folder's name, for a path at
 * or below one. [mangaId] is the library entry the entry folder matched, for
 * [AdoptionUnmatchedReason.NO_CHAPTER] only.
 */
data class AdoptionUnmatched(
    val path: String,
    val reason: AdoptionUnmatchedReason,
    val sourceName: String? = null,
    val sourceLang: String? = null,
    val title: String? = null,
    val mangaId: Long? = null,
)

/** Why a path matched nothing. [id] is what the summary file stores and never changes. */
enum class AdoptionUnmatchedReason(val id: String) {

    /** A source folder that names no installed source and no source of a library entry. */
    SOURCE_NOT_INSTALLED("source_not_installed"),

    /** An entry folder that names no library entry of its source. */
    NOT_IN_LIBRARY("not_in_library"),

    /** A chapter folder or archive whose name is none of its entry's chapters' (or two chapters'). */
    NO_CHAPTER("no_chapter"),

    /** A `_tmp` folder: a download upstream had not finished. */
    UNFINISHED("unfinished"),

    /** A second folder or archive of a chapter another one in the same entry folder was taken for. */
    DUPLICATE("duplicate"),

    /** Read from a summary written before the reasons were kept, or with a reason this build does not know. */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromId(id: String): AdoptionUnmatchedReason? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One matched chapter that was not adopted, or the run itself when [path] is empty. [key] and
 * [detail] are set for [AdoptionFailureReason.ERROR] only, the way the library update error list
 * stores them (ruling R67): [detail] is the exception's class name for [ErrorKey.GENERIC] and the
 * status code for [ErrorKey.HTTP], never an exception's message. For
 * [AdoptionFailureReason.PAGE_COUNT_MISMATCH], [detail] is "expected/found".
 */
data class AdoptionFailure(
    val path: String,
    val reason: AdoptionFailureReason,
    val key: ErrorKey? = null,
    val detail: String? = null,
)

/** Why a matched chapter was not adopted. [id] is what the summary file stores and never changes. */
enum class AdoptionFailureReason(val id: String) {

    /** The folder holds no image, or the archive no page. */
    NO_PAGES("no_pages"),

    /** The copy holds a different number of images than the folder it came from. */
    PAGE_COUNT_MISMATCH("page_count_mismatch"),

    /** The copied archive's size differs from the size the tree reports for it. */
    SIZE_MISMATCH("size_mismatch"),

    /** The copied `.cbz` does not open as an archive. */
    NOT_AN_ARCHIVE("not_an_archive"),

    /** A download of another chapter already has the folder this one would be copied to. */
    DESTINATION_IN_USE("destination_in_use"),

    /** The chapter is in the download queue; the engine will write it, so adoption leaves it. */
    QUEUED("queued"),

    /** Of the run itself: it stopped with too little storage free, before the chapters still to copy. */
    STORAGE_FULL("storage_full"),

    /** Anything else, named by [AdoptionFailure.key]. */
    ERROR("error"),
    ;

    companion object {
        fun fromId(id: String): AdoptionFailureReason? = entries.firstOrNull { it.id == id }
    }
}
