package com.shinsaikou.source

/**
 * Persistent name/language memory for source ids, backing [StubSource]. Implemented in :data over
 * the sources table (upstream's schema keeps one row per id with name and lang). The manager calls
 * [remember] for every live source it sees so a later uninstall still shows a readable name.
 */
interface StubSourceNames {
    /** The remembered entry for [id], or null when the id has never been seen. */
    suspend fun find(id: Long): Entry?

    /** Records [name] and [lang] for [id], replacing whatever was remembered before. */
    suspend fun remember(id: Long, name: String, lang: String)

    /** One remembered row: a source id with the last name and language it reported. */
    data class Entry(val id: Long, val name: String, val lang: String)
}
