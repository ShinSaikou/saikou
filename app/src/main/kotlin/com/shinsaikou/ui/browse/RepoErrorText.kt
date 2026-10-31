package com.shinsaikou.ui.browse

import android.content.res.Resources
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.extensions.repo.RepoFetchException
import com.shinsaikou.extensions.repo.RepoRefreshException
import com.shinsaikou.extensions.store.InvalidStoreAddressException
import com.shinsaikou.extensions.store.StoreAlreadyConfiguredException
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.util.ErrorText

/**
 * A repository failure in words, for the Extensions page's banner and snackbar and the repository
 * screen's. [ErrorText] already names a network or HTTP failure; what it cannot name is a body that
 * is not an index, or a refresh in which some repositories failed and others did not, and those
 * would otherwise read as the client's own English or as nothing in particular.
 */
object RepoErrorText {

    fun of(resources: Resources, error: Throwable): String = when (error) {
        // Each repository that failed, by the name the owner gave it, and why.
        is RepoRefreshException -> error.failures.joinToString(", ") {
            resources.getString(R.string.ext_repo_failed_format, it.store.name, of(resources, it.error))
        }
        is RepoFetchException -> when (error.kind) {
            RepoFetchException.Kind.DECODE -> resources.getString(R.string.ext_repo_error_decode)
            RepoFetchException.Kind.LEGACY_URL_MISMATCH -> resources.getString(R.string.ext_repo_error_legacy_url)
            // A failure ErrorText names (no network, a timeout, an HTTP status) reads as that; any
            // other, a certificate refused say, would read as the client's own English.
            RepoFetchException.Kind.NETWORK, RepoFetchException.Kind.HTTP ->
                if (ErrorKey.of(error) == ErrorKey.GENERIC) {
                    resources.getString(R.string.ext_repo_error_unreachable)
                } else {
                    ErrorText.of(resources, error)
                }
        }
        is InvalidStoreAddressException -> resources.getString(R.string.ext_stores_error_address)
        is StoreAlreadyConfiguredException -> resources.getString(R.string.ext_stores_error_duplicate)
        else -> ErrorText.of(resources, error)
    }
}
