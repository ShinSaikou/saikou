// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupOptions.kt (modified: yes)
package eu.kanade.tachiyomi.data.backup.create

/**
 * Modified: the two `Entry` lists carrying moko-resources labels and Compose lambdas are deleted.
 * The export screen builds its own rows from `com.shinsaikou.i18n.R.string`. The ten flags, their order
 * in [asBooleanArray] and [canCreate] are unchanged, because the array crosses a worker boundary.
 */
data class BackupOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val readEntries: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        chapters,
        tracking,
        history,
        readEntries,
        appSettings,
        extensionStores,
        sourceSettings,
        privateSettings,
    )

    fun canCreate() = libraryEntries || categories || appSettings || extensionStores || sourceSettings

    companion object {
        fun fromBooleanArray(array: BooleanArray) = BackupOptions(
            libraryEntries = array[0],
            categories = array[1],
            chapters = array[2],
            tracking = array[3],
            history = array[4],
            readEntries = array[5],
            appSettings = array[6],
            extensionStores = array[7],
            sourceSettings = array[8],
            privateSettings = array[9],
        )
    }
}
