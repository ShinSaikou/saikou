// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupChapter.kt (modified: yes) adds Saikou's 5000 to 5001
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.core.common.extensions.JsonObjectEmptyBytes
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * Modified: `backupChapterMapper` is deleted, it was bound to upstream's `chapters` column order.
 * [volume] and [pageNotes] are Saikou's own (R165 item 3), in the block listed in `BackupSaikou.kt`;
 * `toChapterImpl` stays upstream's, and the import reads the two itself.
 */
@Serializable
class BackupChapter(
    // in 1.x some of these values have different names
    // url is called key in 1.x
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    // lastPageRead is called progress in 1.x
    @ProtoNumber(6) var lastPageRead: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    // chapterNumber is called number is 1.x
    @ProtoNumber(9) var chapterNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    @ProtoNumber(11) var lastModifiedAt: Long = 0,
    @ProtoNumber(12) var version: Long = 0,
    @ProtoNumber(13) var memo: ByteArray = JsonObjectEmptyBytes,
    // Saikou's own block starts here.
    @ProtoNumber(5000) var volume: Double? = null,
    @ProtoNumber(5001) var pageNotes: List<BackupPageNote> = emptyList(),
) {
    fun toChapterImpl(): Chapter {
        return Chapter.create().copy(
            url = this@BackupChapter.url,
            name = this@BackupChapter.name,
            chapterNumber = this@BackupChapter.chapterNumber.toDouble(),
            scanlator = this@BackupChapter.scanlator,
            read = this@BackupChapter.read,
            bookmark = this@BackupChapter.bookmark,
            lastPageRead = this@BackupChapter.lastPageRead,
            dateFetch = this@BackupChapter.dateFetch,
            dateUpload = this@BackupChapter.dateUpload,
            sourceOrder = this@BackupChapter.sourceOrder,
            lastModifiedAt = this@BackupChapter.lastModifiedAt,
            version = this@BackupChapter.version,
            memo = MemoColumnAdapter.decode(this@BackupChapter.memo),
        )
    }
}
