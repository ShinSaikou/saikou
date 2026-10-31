package com.shinsaikou.messages

import android.content.Context
import androidx.annotation.StringRes
import com.shinsaikou.extensions.ExtensionMessages
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.loader.LoadError
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * One line of text per failure the extension loader can report. Both `when` blocks are exhaustive,
 * so a new enum constant is a compile error here rather than a blank row on the Extensions screen.
 *
 * Both halves are a `messageRes` below, because the extension list renders the same text through
 * `stringResource` and must not carry a second copy of either mapping.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppExtensionMessages(private val context: Context) : ExtensionMessages {

    override fun installRejected(reason: InstallRejection): String = context.getString(reason.messageRes)

    override fun loadError(reason: LoadError): String = context.getString(reason.messageRes)
}

/** Why an install was refused, as the extension list shows it beside the row's Retry. */
@get:StringRes
internal val InstallRejection.messageRes: Int
    get() = when (this) {
        InstallRejection.NOT_AN_EXTENSION -> R.string.ext_install_rejected_not_an_extension
        InstallRejection.DOWNGRADE -> R.string.ext_install_rejected_downgrade
        InstallRejection.UNSIGNED -> R.string.ext_install_rejected_unsigned
        InstallRejection.SIGNER_MISMATCH -> R.string.ext_install_rejected_signer_mismatch
        InstallRejection.COPY_FAILED -> R.string.ext_install_rejected_copy_failed
        InstallRejection.UNSUPPORTED_LIB_VERSION -> R.string.ext_install_rejected_unsupported_lib
    }

/**
 * The same mapping the extension list needs, which reaches it through `stringResource` rather than
 * through a `Context`. It lives here rather than beside the screen so there is one `when` over
 * [LoadError] in the app: a new constant is a compile error in one place, not a blank row in two.
 */
@get:StringRes
internal val LoadError.messageRes: Int
    get() = when (this) {
        LoadError.NOT_AN_EXTENSION -> R.string.ext_load_error_not_an_extension
        LoadError.MISSING_SOURCE_CLASS -> R.string.ext_load_error_missing_source_class
        LoadError.UNSUPPORTED_LIB_VERSION -> R.string.ext_load_error_unsupported_lib_version
        LoadError.UNSIGNED -> R.string.ext_load_error_unsigned
        LoadError.CLASS_LOAD_FAILED -> R.string.ext_load_error_class_load_failed
        LoadError.INSTANTIATION_FAILED -> R.string.ext_load_error_instantiation_failed
        LoadError.UNKNOWN_SOURCE_TYPE -> R.string.ext_load_error_unknown_source_type
        LoadError.INJEKT_NOT_READY -> R.string.ext_load_error_injekt_not_ready
    }
