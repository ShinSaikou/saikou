// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/network/HttpException.kt (modified: yes) a constructor from the response keeps its pushback headers for the library update
package eu.kanade.tachiyomi.network

import com.shinsaikou.core.common.network.Pushback
import okhttp3.Response

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @see Response.isSuccessful
 * @since tachiyomix 1.6
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code") {

    /**
     * Modified: what the response said about being asked again, when the host built this from the
     * response itself (`awaitSuccess`, `asObservableSuccess`, which every default request of
     * `HttpSource` goes through). Null when it was built from a code alone, as an extension that
     * checks the code itself builds it, and when the response carried neither header.
     */
    var pushback: Pushback? = null
        private set

    /** Modified: the host's own throw sites use this; the extension ABI's `(Int)` constructor stays. */
    constructor(response: Response) : this(response.code) {
        pushback = Pushback.of(response)
    }
}
