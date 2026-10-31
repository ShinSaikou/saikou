// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupExtensionStore.kt (modified: yes)
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Modified: `backupExtensionStoreMapper` is deleted. The class, including field 5 declared before
 * field 4, is byte for byte upstream's.
 */
@Serializable
class BackupExtensionStore(
    @ProtoNumber(1) var indexUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var badgeLabel: String?,
    @ProtoNumber(5) var signingKey: String,
    @ProtoNumber(4) var contactWebsite: String,
    @ProtoNumber(6) var contactDiscord: String?,
    @ProtoNumber(7) var isLegacy: Boolean?,
    @ProtoNumber(8) var extensionListUrl: String?,
)
