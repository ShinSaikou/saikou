// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt (modified: yes) adds Saikou's own fields 5000 and 5001
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Modified: [mergedGroups] and [customCovers] are Saikou's own, in the block 5000 to 5099 that no
 * other reader of the format uses (R165 item 3; the numbers are listed in `BackupSaikou.kt`). Upstream
 * and its forks skip a field they do not know, so they still restore every other field of a file that
 * carries these. The export writes the covers after every other field, one frame each, and a restore
 * reads them one at a time (`BackupDecoder.customCovers`), so no reader holds them all at once.
 */
@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    // Saikou's own block starts here.
    @ProtoNumber(5000) var mergedGroups: List<BackupMergedGroup> = emptyList(),
    @ProtoNumber(5001) var customCovers: List<BackupCustomCover> = emptyList(),
)
