// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt (modified: yes) adds the import-only fields 800 and 804
package eu.kanade.tachiyomi.data.backup.models

import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.core.common.extensions.JsonObjectEmptyBytes
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.manga.model.Manga

/**
 * Modified: the [UpdateStrategy] import is the contract enum in `:source-contract`. Its entry order
 * is upstream's (`ALWAYS_UPDATE, ONLY_FETCH_ONCE`), so the ordinal-encoded field stays wire-identical.
 * [customTitle] and [customDescription] are the edited title and description that TachiyomiSY,
 * TachiyomiJ2K and Komikku write at numbers upstream does not use (R162 item 8). The import reads them
 * into the entry's overrides; the export never fills them, so the file Saikou writes keeps only
 * upstream's fields (decision F10).
 */
@Suppress("DEPRECATION")
@Serializable
class BackupManga(
    // in 1.x some of these values have different names
    @ProtoNumber(1) var source: Long,
    // url is called key in 1.x
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    // thumbnailUrl is called cover in 1.x
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    // @ProtoNumber(10) val customCover: String = "", 1.x value, not used in 0.x
    // @ProtoNumber(11) val lastUpdate: Long = 0, 1.x value, not used in 0.x
    // @ProtoNumber(12) val lastInit: Long = 0, 1.x value, not used in 0.x
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(14) var viewer: Int = 0, // Replaced by viewer_flags
    // @ProtoNumber(15) val flags: Int = 0, 1.x value, not used in 0.x
    @ProtoNumber(16) var chapters: List<BackupChapter> = emptyList(),
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(18) var tracking: List<BackupTracking> = emptyList(),
    // Bump by 100 for values that are not saved/implemented in 1.x but are used in 0.x
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var chapterFlags: Int = 0,
    // @ProtoNumber(102) var brokenHistory, legacy history model with non-compliant proto number
    @ProtoNumber(103) var viewer_flags: Int? = null,
    @ProtoNumber(104) var history: List<BackupHistory> = emptyList(),
    @ProtoNumber(105) var updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    // Upstream values start here
    @ProtoNumber(108) var excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) var version: Long = 0,
    @ProtoNumber(110) var notes: String = "",
    @ProtoNumber(111) var initialized: Boolean = false,
    @ProtoNumber(112) var memo: ByteArray = JsonObjectEmptyBytes,
    // Read on import only, never written.
    @ProtoNumber(800) var customTitle: String? = null,
    @ProtoNumber(804) var customDescription: String? = null,
) {
    fun getMangaImpl(): Manga {
        return Manga.create().copy(
            url = this@BackupManga.url,
            title = this@BackupManga.title,
            artist = this@BackupManga.artist,
            author = this@BackupManga.author,
            description = this@BackupManga.description,
            genre = this@BackupManga.genre,
            status = this@BackupManga.status.toLong(),
            thumbnailUrl = this@BackupManga.thumbnailUrl,
            favorite = this@BackupManga.favorite,
            source = this@BackupManga.source,
            dateAdded = this@BackupManga.dateAdded,
            viewerFlags = (this@BackupManga.viewer_flags ?: this@BackupManga.viewer).toLong(),
            chapterFlags = this@BackupManga.chapterFlags.toLong(),
            updateStrategy = this@BackupManga.updateStrategy,
            lastModifiedAt = this@BackupManga.lastModifiedAt,
            favoriteModifiedAt = this@BackupManga.favoriteModifiedAt,
            version = this@BackupManga.version,
            notes = this@BackupManga.notes,
            initialized = this@BackupManga.initialized,
            memo = MemoColumnAdapter.decode(this@BackupManga.memo),
        )
    }
}
