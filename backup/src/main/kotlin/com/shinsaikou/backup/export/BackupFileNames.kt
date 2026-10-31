package com.shinsaikou.backup.export

import com.shinsaikou.domain.preferences.BackupPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The suggested name for an exported file and the pattern the automatic rotation matches. The
 * extension is upstream's, so the owner's other tooling still recognises the file; the prefix is
 * Saikou's, so a directory holding both stays readable.
 */
object BackupFileNames {

    const val EXTENSION = "tachibk"
    const val MIME_TYPE = "application/octet-stream"

    /** How many automatic exports are kept in the target directory, unless the owner chose another count. */
    const val MAX_KEPT = BackupPreferences.DEFAULT_KEPT_BACKUPS

    /** How many of sync's safety backups are kept: the newest and the one before it. */
    const val SYNC_KEPT = 2

    private const val PREFIX = "com.shinsaikou_"
    private const val SYNC_PREFIX = "com.shinsaikou_sync_"
    private const val PATTERN = "yyyy-MM-dd_HH-mm"

    /**
     * Matches exactly what [suggest] produces, so rotation never deletes a manual export or a safety
     * backup ([suggestSync]'s names have a word where this expects the date).
     */
    val AUTO_BACKUP_REGEX = Regex("""^com\.shinsaikou_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk$""")

    /** Matches exactly what [suggestSync] produces. */
    val SYNC_BACKUP_REGEX = Regex("""^com\.shinsaikou_sync_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk$""")

    /** Matches exactly what [pending] produces. */
    val PENDING_REGEX = Regex("""^com\.shinsaikou_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk\.tmp$""")

    /** Matches exactly what [replaced] produces; its first group is the name the file was set aside from. */
    val REPLACED_REGEX = Regex("""^(com\.shinsaikou_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk)\.old$""")

    fun suggest(now: Long): String = "$PREFIX${stamp(now)}.$EXTENSION"

    /**
     * The name an automatic file is written under until it is complete. Neither the listing nor the
     * rotation matches it, so a half-written file is never shown, kept or counted.
     */
    fun pending(now: Long): String = "${suggest(now)}.tmp"

    /**
     * The name a file already named for [now]'s minute is set aside under while [pending]'s file
     * takes the name. Neither the listing nor the rotation matches it either.
     */
    fun replaced(now: Long): String = "${suggest(now)}.old"

    /**
     * The name of the backup sync writes before a backend's first pull (phase 3 unit G): the same
     * folder and extension as the automatic export, but its own name, so the automatic rotation
     * never deletes it and a same-minute pair of the two never share a file.
     */
    fun suggestSync(now: Long): String = "$SYNC_PREFIX${stamp(now)}.$EXTENSION"

    /** Which of the two sets of kept files [name] belongs to, or null for a name the app did not give. */
    fun kindOf(name: String): AutomaticBackup.Kind? = when {
        AUTO_BACKUP_REGEX.matches(name) -> AutomaticBackup.Kind.AUTOMATIC
        SYNC_BACKUP_REGEX.matches(name) -> AutomaticBackup.Kind.SYNC_SAFETY
        else -> null
    }

    /**
     * The minute [suggest] or [suggestSync] named [name] for, in this device's time zone as they write
     * it, or null for any other name.
     */
    fun timeOf(name: String): Long? {
        if (kindOf(name) == null) return null
        val stamp = name.removeSuffix(".$EXTENSION").takeLast(PATTERN.length)
        return runCatching { SimpleDateFormat(PATTERN, Locale.ENGLISH).parse(stamp)?.time }.getOrNull()
    }

    private fun stamp(now: Long): String = SimpleDateFormat(PATTERN, Locale.ENGLISH).format(Date(now))
}
