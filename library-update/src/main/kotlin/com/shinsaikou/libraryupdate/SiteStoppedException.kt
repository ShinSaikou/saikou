package com.shinsaikou.libraryupdate

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure

/**
 * An entry the library update did not ask its site for, because the site pushed back
 * [AdaptiveSourceLimit.MAX_BACKOFFS] times earlier in the same run. [host] is the site's host name,
 * which the error list shows, or null for a source that names none. The next run asks again.
 */
class SiteStoppedException(val host: String?) :
    Exception("${host ?: "The site"} pushed back ${AdaptiveSourceLimit.MAX_BACKOFFS} times in this update, so it was not asked"),
    KeyedFailure {

    override val errorKey: ErrorKey
        get() = ErrorKey.SITE_STOPPED

    override val errorDetail: String?
        get() = host
}
