// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/RestoreOptions.kt (modified: yes)
package eu.kanade.tachiyomi.data.backup.restore

/**
 * Modified: the `Entry` list carrying moko-resources labels and Compose lambdas is deleted. The
 * five flags and their order in [asBooleanArray] are unchanged: the array is the worker input and
 * the checkpoint's identity of "the same run".
 */
data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        sourceSettings,
    )

    fun canRestore() = libraryEntries || categories || appSettings || extensionStores || sourceSettings

    companion object {
        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appSettings = array[2],
            extensionStores = array[3],
            sourceSettings = array[4],
        )
    }
}
