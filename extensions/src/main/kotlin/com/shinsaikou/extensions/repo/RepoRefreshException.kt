package com.shinsaikou.extensions.repo

import tachiyomi.domain.extension.model.ExtensionStore
import java.io.IOException

/**
 * A refresh of the configured indexes in which [failures] did not load, one per repository, so the
 * screen can name each and say why. The others' entries were published all the same.
 */
class RepoRefreshException(val failures: List<Failure>) :
    IOException("${failures.size} configured index(es) failed to refresh", failures.firstOrNull()?.error) {

    /** [store] as configured, and what its fetch threw (most often a [RepoFetchException]). */
    data class Failure(val store: ExtensionStore, val error: Throwable)
}
