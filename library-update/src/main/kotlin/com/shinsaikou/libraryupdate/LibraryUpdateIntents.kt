package com.shinsaikou.libraryupdate

import android.app.PendingIntent

/**
 * The parts of the update notifications only the app module can build, because they point at its
 * activities and its receivers. This module may not depend on `:app`, so the intents leave through
 * a callback, the same way user-facing network text leaves `:core:common` through
 * `NetworkUserMessages`. Every method may return null; the notification is then posted without
 * that action.
 */
interface LibraryUpdateIntents {
    /** Opens the Updates screen. Content intent of the new-chapters summary. */
    fun openUpdates(): PendingIntent?

    /** Opens a manga's chapter list. */
    fun openManga(mangaId: Long): PendingIntent?

    /** Opens the reader on one chapter. */
    fun openChapter(mangaId: Long, chapterId: Long): PendingIntent?

    /** Marks [chapterIds] read and dismisses the notification with [notificationId]. */
    fun markChaptersRead(mangaId: Long, chapterIds: List<Long>, notificationId: Int): PendingIntent?

    /** Opens the error sheet backed by [LibraryUpdateErrorStore]. */
    fun openUpdateErrors(): PendingIntent?
}
