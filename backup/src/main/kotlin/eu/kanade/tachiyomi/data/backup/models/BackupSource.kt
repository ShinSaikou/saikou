// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupSource.kt (modified: no)
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupSource(
    @ProtoNumber(1) var name: String = "",
    @ProtoNumber(2) var sourceId: Long,
)
