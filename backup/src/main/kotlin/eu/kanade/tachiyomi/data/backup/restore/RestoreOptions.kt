// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/RestoreOptions.kt (modified: yes)
package eu.kanade.tachiyomi.data.backup.restore

/**
 * Modified: the `Entry` list carrying moko-resources labels and Compose lambdas is deleted. The
 * five flags and their order in [asBooleanArray] are unchanged: the array is the worker input and
 * the checkpoint's identity of "the same run". Saikou's [installExtensions] follows them as the
 * sixth; an array without it, queued by a build before it, reads as off, which is what that run
 * was asked to do.
 */
data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    /** After the rest, install the extensions the backup's sources come from. Restores nothing itself. */
    val installExtensions: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        sourceSettings,
        installExtensions,
    )

    fun canRestore() = libraryEntries || categories || appSettings || extensionStores || sourceSettings

    companion object {
        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appSettings = array[2],
            extensionStores = array[3],
            sourceSettings = array[4],
            installExtensions = array.getOrElse(5) { false },
        )
    }
}
