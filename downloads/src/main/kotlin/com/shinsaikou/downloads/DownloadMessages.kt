package com.shinsaikou.downloads

/**
 * The user-facing text the engine needs. The module graph gives `:downloads` no dependency on
 * `:i18n`, so `:app` implements this against its string resources, the same way it implements
 * `NetworkUserMessages` for `:core:common`.
 */
interface DownloadMessages {

    fun progressChannelName(): String

    fun errorChannelName(): String

    /** Title of the foreground notification while chapters download. */
    fun downloaderTitle(): String

    /** Text of the foreground notification: the chapter downloading and [left] chapters left, that one included. */
    fun chapterAndLeft(chapterName: String, left: Int): String

    /** The same with notification content hidden: [left] chapters left. */
    fun chaptersLeft(left: Int): String

    /** Title of the foreground notification while [count] chapters download at once, from several sources. */
    fun chaptersRunning(count: Int): String

    /** The foreground notification's action that stops the run and keeps the queue. */
    fun pauseAction(): String

    /** The foreground notification's action that empties the queue. */
    fun cancelAllAction(): String

    /** Title of the notification posted when a run ends with [count] chapters in error. */
    fun downloadErrors(count: Int): String

    /** Title of the notification posted when a run stops because too little storage is free. */
    fun storageFull(): String

    /** Its text, which says what to do. */
    fun storageFullDetail(): String

    /** Title of the foreground notification while upstream's downloads are adopted. */
    fun adoptionTitle(): String

    /** Title of the notification posted when an adoption run ends, having copied [copied] chapters. */
    fun adoptionFinished(copied: Int): String

    /** Its text, which says where the whole summary is. */
    fun adoptionFinishedDetail(): String

    /** Title of the foreground notification while the downloads are exported. */
    fun exportTitle(): String

    /** Title of the notification posted when an export ends: [copied] files copied, [skipped] already there, [failed] not copied. */
    fun exportFinished(copied: Int, skipped: Int, failed: Int): String

    /** Its text, which says where the downloads went. */
    fun exportFinishedDetail(): String

    /** Title of the notification posted when the storage location cannot be written to. */
    fun exportUnreachable(): String

    /** Title of the notification posted when an export failed outright. */
    fun exportStopped(): String

    /** The text of both, which says what to do: the download settings, where the row is. */
    fun exportProblemDetail(): String
}
