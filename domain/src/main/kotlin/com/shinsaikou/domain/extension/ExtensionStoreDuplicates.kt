package com.shinsaikou.domain.extension

import tachiyomi.domain.extension.model.ExtensionStore

/**
 * One repository can reach the index table under more than one address. Upstream's legacy
 * `extension_repos` set names a repository by its base address, which the restore stores as
 * `<base>/repo.json`, while the store list of the same backup names its `<base>/index.pb`; adding
 * the same repository by hand with a trailing slash or with `http` does it too. Each address is
 * its own row, since the index URL is the primary key, so the Extension stores screen listed the
 * repository twice, once with no name.
 *
 * [key] is the address rows are compared by, and [merge] folds the rows that share one into the
 * row with the most to show. Rows that name two different signing keys are left apart: trust is
 * "signed by a configured key", and which of the two the owner meant is not this code's to decide.
 */
object ExtensionStoreDuplicates {

    /**
     * [url] without its scheme, the case of its host, trailing slashes or the index file it ends
     * in, so `https://example.invalid/repo`, `http://Example.Invalid/repo/` and
     * `https://example.invalid/repo/index.pb` are one repository. The path keeps its case: a server
     * may serve `/Repo` and `/repo` as two repositories.
     */
    fun key(url: String): String {
        var address = url.trim()
        SCHEMES.firstOrNull { address.startsWith(it, ignoreCase = true) }?.let { address = address.drop(it.length) }
        val pathStart = address.indexOf('/').takeIf { it >= 0 } ?: address.length
        address = address.take(pathStart).lowercase() + address.drop(pathStart)
        address = address.trimEnd('/')
        INDEX_FILES.firstOrNull { address.endsWith(it) }?.let { address = address.removeSuffix(it) }
        return address.trimEnd('/')
    }

    /** What [merge] changes: the rows to write back, filled in, and the index URLs to delete. */
    data class Merge(
        val updated: List<ExtensionStore>,
        val removed: List<String>,
    ) {
        val isEmpty: Boolean get() = updated.isEmpty() && removed.isEmpty()
    }

    /**
     * For each [key] that more than one of [rows] shares, keeps one row: the one with a name, then a
     * signing key, then the one that is not a legacy descriptor, then the first in [rows]. Its blank
     * name, badge, key and contact are filled from the others. How it is fetched (its address, its
     * legacy flag, its extension list URL) stays its own, since those describe the file it names.
     */
    fun merge(rows: List<ExtensionStore>): Merge {
        val updated = mutableListOf<ExtensionStore>()
        val removed = mutableListOf<String>()
        rows.groupBy { key(it.indexUrl) }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val signingKeys = group.map { it.signingKey }.filter { it.isNotBlank() }.distinct()
            if (signingKeys.size > 1) return@forEach

            val keeper = group.sortedWith(
                compareByDescending<ExtensionStore> { it.name.isNotBlank() }
                    .thenByDescending { it.signingKey.isNotBlank() }
                    .thenBy { it.isLegacy },
            ).first()
            val others = group - keeper
            val filled = keeper.copy(
                name = keeper.name.ifBlank { others.firstNotBlank { it.name } },
                badgeLabel = keeper.badgeLabel.ifBlank { others.firstNotBlank { it.badgeLabel } },
                signingKey = keeper.signingKey.ifBlank { signingKeys.firstOrNull().orEmpty() },
                contact = ExtensionStore.Contact(
                    website = keeper.contact.website.ifBlank { others.firstNotBlank { it.contact.website } },
                    discord = keeper.contact.discord ?: others.firstNotNullOfOrNull { it.contact.discord },
                ),
            )
            if (filled != keeper) updated += filled
            removed += others.map { it.indexUrl }
        }
        return Merge(updated, removed)
    }

    private inline fun List<ExtensionStore>.firstNotBlank(field: (ExtensionStore) -> String): String =
        firstNotNullOfOrNull { row -> field(row).takeIf { it.isNotBlank() } }.orEmpty()

    private val SCHEMES = listOf("https://", "http://")

    /** The files a row's address can end in: the descriptor, the legacy list and the two indexes. */
    private val INDEX_FILES = listOf("/repo.json", "/index.min.json", "/index.json", "/index.pb")
}
