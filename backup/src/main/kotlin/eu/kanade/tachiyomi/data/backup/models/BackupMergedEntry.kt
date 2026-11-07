// Fresh: a field of BackupManga, and a message's serial name carries its package, so it sits beside it.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * One source of a merged entry as TachiyomiSY writes it, at field 600 of [BackupManga] (R163 item 4).
 * Read on import only: the export never fills it, so a file Saikou writes stays upstream's (decision
 * F10). [isInfoManga] and [chapterSortMode] are what older files carry where [BackupMergeSettings] now
 * stands. Field 6, the merge's own url in those files, is not read.
 */
@Serializable
class BackupMergedEntry(
    @ProtoNumber(1) var isInfoManga: Boolean = false,
    @ProtoNumber(2) var fetchChapterUpdates: Boolean = false,
    @ProtoNumber(3) var chapterSortMode: Int = 0,
    @ProtoNumber(4) var chapterPriority: Int = 0,
    @ProtoNumber(5) var downloadChapters: Boolean = false,
    @ProtoNumber(7) var mangaUrl: String = "",
    @ProtoNumber(8) var mangaSourceId: Long = 0,
)

/** A merged entry's settings in a TachiyomiSY file, field 604 of [BackupManga]. Read on import only. */
@Serializable
class BackupMergeSettings(
    @ProtoNumber(1) var chapterSortMode: Int = 0,
    @ProtoNumber(2) var infoMangaUrl: String = "",
    @ProtoNumber(3) var infoMangaSourceId: Long = 0,
)
