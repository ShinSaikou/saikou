// Vendored from upstream v0.21.3 (524fa53): app/src/main/java/eu/kanade/tachiyomi/data/track/comick/dto/ComickLibraryEntry.kt (modified: yes)
package com.shinsaikou.tracking.comick.dto

import kotlinx.serialization.Serializable

/**
 * Modified: the package; every field but [ComickLibraryEntryItem.status] has a default, so an answer
 * that leaves one out still reads; and the entry is laid over the stored row by `ComickApi`, where
 * upstream built a fresh track bean here (`toTrack`).
 */
@Serializable
data class ComickLibraryEntry(
    val data: ComickLibraryEntryItem,
)

@Serializable
data class ComickLibraryEntryItem(
    val hid: String = "",
    val title: String = "",
    val status: Long,
    val progress: ComickLibraryEntryProgress? = null,
    val rating: Int? = null,
)

@Serializable
data class ComickLibraryEntryProgress(
    /** The chapter as text, such as `"12.5"`; null while none is read. */
    val number: String? = null,
)
