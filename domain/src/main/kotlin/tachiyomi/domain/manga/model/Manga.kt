// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt (modified: yes)
package tachiyomi.domain.manga.model

import androidx.compose.runtime.Immutable
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.extensions.EMPTY
import tachiyomi.core.common.preference.TriState
import java.io.ObjectStreamException
import kotlin.time.Instant
import java.io.Serializable as JavaSerializable

@Serializable
@Immutable
data class Manga(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val fetchInterval: Int,
    val dateAdded: Long,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val url: String,
    val title: String,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val updateStrategy: UpdateStrategy,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val version: Long,
    val notes: String,
    val memo: JsonObject,
    /** Manual library order, 0 = unranked. Fresh field, migration 1. */
    val manualPosition: Long,
    /** Epoch millis tombstone, null = live. Fresh field, migration 1. */
    val deletedAt: Long?,
    /**
     * The owner's edit of [title], null while unedited (schema version 3, R138 item 2). [title] stays
     * the source's text, which downloads, duplicate detection, tracker search and the backup use.
     */
    val titleOverride: String?,
    /** The owner's edit of [description], null while unedited; as [titleOverride]. */
    val descriptionOverride: String?,
) : JavaSerializable {

    /** The title every screen and notification shows: the owner's edit over the source's. */
    val displayTitle: String
        get() = titleOverride ?: title

    /** The description the entry screen shows: the owner's edit over the source's. */
    val displayDescription: String?
        get() = descriptionOverride ?: description

    val expectedNextUpdate: Instant?
        get() = nextUpdate
            .takeIf { status != MangaStatus.COMPLETED.code.toLong() }
            ?.let { Instant.fromEpochMilliseconds(it) }

    val sorting: Long
        get() = chapterFlags and CHAPTER_SORTING_MASK

    val displayMode: Long
        get() = chapterFlags and CHAPTER_DISPLAY_MASK

    val unreadFilterRaw: Long
        get() = chapterFlags and CHAPTER_UNREAD_MASK

    val downloadedFilterRaw: Long
        get() = chapterFlags and CHAPTER_DOWNLOADED_MASK

    val bookmarkedFilterRaw: Long
        get() = chapterFlags and CHAPTER_BOOKMARKED_MASK

    /** Saikou's volume headers in the chapter list (phase 4 unit VG); off unless the bit is set. */
    val groupByVolume: Boolean
        get() = chapterFlags and CHAPTER_GROUP_BY_VOLUME_MASK == CHAPTER_GROUP_BY_VOLUME

    val unreadFilter: TriState
        get() = when (unreadFilterRaw) {
            CHAPTER_SHOW_UNREAD -> TriState.ENABLED_IS
            CHAPTER_SHOW_READ -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    val downloadedFilter: TriState
        get() = when (downloadedFilterRaw) {
            CHAPTER_SHOW_DOWNLOADED -> TriState.ENABLED_IS
            CHAPTER_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    val bookmarkedFilter: TriState
        get() = when (bookmarkedFilterRaw) {
            CHAPTER_SHOW_BOOKMARKED -> TriState.ENABLED_IS
            CHAPTER_SHOW_NOT_BOOKMARKED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    fun sortDescending(): Boolean {
        return chapterFlags and CHAPTER_SORT_DIR_MASK == CHAPTER_SORT_DESC
    }

    /** Modified: the one flag-set implementation, shared with [tachiyomi.domain.library.model.Flag]'s algebra. */
    fun setFlag(flag: Long, mask: Long): Long {
        return chapterFlags and mask.inv() or (flag and mask)
    }

    companion object {
        // Generic filter that does not filter anything
        const val SHOW_ALL = 0x00000000L

        const val CHAPTER_SORT_DESC = 0x00000000L
        const val CHAPTER_SORT_ASC = 0x00000001L
        const val CHAPTER_SORT_DIR_MASK = 0x00000001L

        const val CHAPTER_SHOW_UNREAD = 0x00000002L
        const val CHAPTER_SHOW_READ = 0x00000004L
        const val CHAPTER_UNREAD_MASK = 0x00000006L

        const val CHAPTER_SHOW_DOWNLOADED = 0x00000008L
        const val CHAPTER_SHOW_NOT_DOWNLOADED = 0x00000010L
        const val CHAPTER_DOWNLOADED_MASK = 0x00000018L

        const val CHAPTER_SHOW_BOOKMARKED = 0x00000020L
        const val CHAPTER_SHOW_NOT_BOOKMARKED = 0x00000040L
        const val CHAPTER_BOOKMARKED_MASK = 0x00000060L

        const val CHAPTER_SORTING_SOURCE = 0x00000000L
        const val CHAPTER_SORTING_NUMBER = 0x00000100L
        const val CHAPTER_SORTING_UPLOAD_DATE = 0x00000200L
        const val CHAPTER_SORTING_ALPHABET = 0x00000300L
        const val CHAPTER_SORTING_MASK = 0x00000300L

        const val CHAPTER_DISPLAY_NAME = 0x00000000L
        const val CHAPTER_DISPLAY_NUMBER = 0x00100000L
        const val CHAPTER_DISPLAY_MASK = 0x00100000L

        /**
         * Saikou's bit, outside every upstream mask (phase 4 unit VG): the chapter list shows a header
         * per volume. Below `0x80000000`, so it survives an upstream backup's `Int` flag word whole.
         */
        const val CHAPTER_GROUP_BY_VOLUME = 0x00200000L
        const val CHAPTER_GROUP_BY_VOLUME_MASK = 0x00200000L

        /**
         * Upstream's six chapter settings masks together, the bits the chapter defaults own. Every
         * other bit of the word belongs to the entry alone, so applying the defaults keeps it.
         */
        const val CHAPTER_DEFAULTS_MASK = CHAPTER_SORT_DIR_MASK or CHAPTER_UNREAD_MASK or
            CHAPTER_DOWNLOADED_MASK or CHAPTER_BOOKMARKED_MASK or CHAPTER_SORTING_MASK or CHAPTER_DISPLAY_MASK

        fun create() = Manga(
            id = -1L,
            url = "",
            title = "",
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            nextUpdate = 0L,
            fetchInterval = 0,
            dateAdded = 0L,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            artist = null,
            author = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = false,
            lastModifiedAt = 0L,
            favoriteModifiedAt = null,
            version = 0L,
            notes = "",
            memo = JsonObject.EMPTY,
            manualPosition = 0L,
            deletedAt = null,
            titleOverride = null,
            descriptionOverride = null,
        )
    }

    @Throws(ObjectStreamException::class)
    private fun writeReplace(): Any {
        return JavaToKotlinXSerializable(Json.encodeToString<Manga>(this))
    }

    class JavaToKotlinXSerializable(private val data: String) : JavaSerializable {

        @Throws(ObjectStreamException::class)
        private fun readResolve(): Any {
            return Json.decodeFromString<Manga>(data)
        }
    }
}
