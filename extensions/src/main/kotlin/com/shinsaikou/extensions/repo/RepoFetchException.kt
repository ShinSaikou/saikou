package com.shinsaikou.extensions.repo

import java.io.IOException

/**
 * Every failure of [ExtensionRepoClient] except cancellation. [kind] is what the repository screen
 * turns into a sentence; [url] is never shown to the user by this module.
 */
class RepoFetchException(
    val url: String,
    val kind: Kind,
    cause: Throwable? = null,
) : IOException("$kind while fetching a repository index", cause) {
    enum class Kind {
        /** The request never completed: DNS, TLS, timeout. */
        NETWORK,

        /** The server answered with a non-2xx status. */
        HTTP,

        /** The body is neither a readable protobuf index, a repo descriptor nor a legacy list. */
        DECODE,

        /** A legacy `index.min.json` URL was configured directly instead of its `repo.json`. */
        LEGACY_URL_MISMATCH,
    }
}
