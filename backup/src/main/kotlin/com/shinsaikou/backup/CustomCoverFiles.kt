package com.shinsaikou.backup

/**
 * The custom cover file the app keeps for an entry, by the entry's id (`CoverCache` in `:app`), as a
 * backup carries it (R165 item 3). `:backup` cannot see `:app`, so the app binds this. Called on
 * `Dispatchers.IO`.
 */
interface CustomCoverFiles {

    fun exists(mangaId: Long): Boolean

    /** The file's bytes as stored, or null when the entry has no custom cover. */
    fun read(mangaId: Long): ByteArray?

    /** Writes [bytes] as the entry's custom cover, in place of any file it has. */
    fun write(mangaId: Long, bytes: ByteArray)
}
