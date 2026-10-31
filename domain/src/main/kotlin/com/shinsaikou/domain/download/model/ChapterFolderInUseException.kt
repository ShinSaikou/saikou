package com.shinsaikou.domain.download.model

/**
 * A chapter's download folder is one another chapter's download already has: another library entry
 * of the same source with the same title, whose folders it shares (ruling draft R-J7). The download
 * engine throws it rather than take the folder over, and [com.shinsaikou.domain.error.ErrorKey] gives it
 * its own key, so the queue row stores the key and the queue screen names the failure in the
 * reader's language (ruling R67 rule 3, R104 item 3). The message is for the log only; no screen
 * shows it.
 */
class ChapterFolderInUseException(chapterId: Long) :
    IllegalStateException("chapter $chapterId's folder is held by another download")
