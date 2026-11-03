package com.shinsaikou.domain.extension

/**
 * Installs, from the configured repositories, the extensions that provide a list of sources. The
 * backup restore asks for the sources a backup named that no installed extension serves, once it
 * has put the repositories back. `:extensions` implements it: `:backup` may not depend on the
 * extension host (contract 01, section 1.2).
 *
 * Only a repository the owner had before the restore is installed from unattended. A backup can
 * carry repositories and their signing keys, and a file passed around by others could add one whose
 * extensions would then install with no prompt and load as trusted. What only such a repository
 * offers waits, named with it, for the owner's own tap.
 */
interface SourceExtensionInstaller {

    /**
     * Fetches every configured index, matches [sources] to the extensions that list their ids, and
     * installs those not already on this device one after another, calling [onProgress] before each.
     * An extension is installed from a repository of [storesBefore] when one lists it; one that only
     * the other repositories list is not installed, and is in [Outcome.fromAddedStores] instead.
     * Throws on cancellation, and when the configured repositories cannot be read; an index or an
     * install that fails is in the [Outcome].
     */
    suspend fun install(
        sources: List<WantedSource>,
        storesBefore: List<KnownStore>,
        onProgress: (Progress) -> Unit,
    ): Outcome

    /** A source as the backup names it: the name is what a person would search for. */
    data class WantedSource(val id: Long, val name: String)

    /**
     * A repository as it was configured before the restore wrote any. A row now counts as one of
     * them when it is the same repository ([ExtensionStoreDuplicates.key]) with the same signing
     * key, so a restore that merges two addresses of one repository keeps it, and one that gave a
     * keyless row a key does not.
     */
    data class KnownStore(val indexUrl: String, val signingKey: String)

    /**
     * [done] of [total] extensions are through, installed or failed, and [extensionName] is the one
     * starting now, the ([done] + 1)th.
     */
    data class Progress(val done: Int, val total: Int, val extensionName: String)

    /**
     * Extensions by name, except [notFound], which is the sources no configured index lists.
     * [alreadyInstalled] are the matched extensions that were on this device already, loaded or not.
     * [unreadStores] names the repositories whose index could not be fetched, so a source in
     * [notFound] may be in one of them.
     */
    data class Outcome(
        val installed: List<String> = emptyList(),
        val failed: List<Failure> = emptyList(),
        val notFound: List<WantedSource> = emptyList(),
        val alreadyInstalled: List<String> = emptyList(),
        val unreadStores: List<String> = emptyList(),
        /** Matched extensions that only a repository outside `storesBefore` lists; not installed. */
        val fromAddedStores: List<FromAddedStore> = emptyList(),
    )

    /** An extension that did not install, with the reason in English, for the error log. */
    data class Failure(val extensionName: String, val message: String)

    /**
     * An extension left for the owner to install: its name and package, and the repository that
     * lists it, by the name and the address the owner would be trusting.
     */
    data class FromAddedStore(
        val extensionName: String,
        val packageName: String,
        val storeName: String,
        val storeUrl: String,
    )
}
