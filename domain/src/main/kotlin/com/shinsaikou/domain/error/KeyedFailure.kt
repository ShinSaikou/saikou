package com.shinsaikou.domain.error

/**
 * A failure that names its own [ErrorKey]. Sync's failures live in `:sync` and `:app`, which depend on
 * `:domain`, so [ErrorKey.of] cannot name their classes the way it names the others; each declares
 * its key here instead, and the resolver reads it before any other rule (ruling R67, phase 3 unit G).
 *
 * [errorDetail] is what the key's text shows beside it and what a stored outcome keeps with the key:
 * a status code or the host a redirect points to, never a message.
 */
interface KeyedFailure {
    val errorKey: ErrorKey

    val errorDetail: String?
        get() = null
}
