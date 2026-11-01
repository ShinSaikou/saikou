package com.shinsaikou.reader.viewer

import android.content.Context
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.util.ErrorKeyText
import com.shinsaikou.reader.pipeline.UnsupportedImageFormatException
import com.shinsaikou.source.SourceNotInstalledException

/**
 * The text the two page holders put in their error layout.
 *
 * Upstream uses `Throwable.formattedMessage` from `eu/kanade/presentation/util/`, which `:reader` does
 * not vendor: it needs `HttpException` and a `Context.isOnline()` that live in the `app/` module,
 * and three of its five branches are about search results rather than pages. This names the
 * failures a page can have: an unsupported format, a missing source, bytes the decoder could not
 * read, and the network failures [ErrorKey] knows, in the same words every other screen uses for
 * them (ruling R67). Anything else falls back to upstream's own `decode_image_error`.
 *
 * Never renders a URL, a host or a source name, which is why a generic failure's own message is
 * not shown here as it is on other screens: an exception from a source can carry any of them.
 */
fun Context.pageErrorMessage(error: Throwable?): String = when (error) {
    is UnsupportedImageFormatException -> getString(R.string.reader_unsupported_format, error.format.name)
    is SourceNotInstalledException -> getString(R.string.loader_not_implemented_error)
    is PageDecodeException -> getString(R.string.reader_page_decode_error)
    null -> getString(R.string.decode_image_error)
    else -> when (val key = ErrorKey.of(error)) {
        in PAGE_NETWORK_KEYS -> ErrorKeyText.text(resources, key, ErrorKey.httpCode(error))
        else -> getString(R.string.decode_image_error)
    }
}

/**
 * The image view or Coil could not turn a Ready page's bytes into a picture. The holders wrap what
 * the view reports in this, because by then the bytes are on the device and the failure is the
 * decode, whatever exception the decoder chose to say it with.
 */
class PageDecodeException(cause: Throwable?) : RuntimeException("the page could not be decoded", cause)

/** The keys whose text says something true about a page, and carries no host or address. */
private val PAGE_NETWORK_KEYS = setOf(
    ErrorKey.NO_NETWORK,
    ErrorKey.CONNECTION_REFUSED,
    ErrorKey.HTTP,
    ErrorKey.TIMEOUT,
    ErrorKey.CLOUDFLARE,
    ErrorKey.CLOUDFLARE_BUDGET,
)
