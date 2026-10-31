package com.shinsaikou.extensions

import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.loader.LoadError

/**
 * User-facing text for the two failure enums this module produces. `:extensions` may not depend on
 * `:i18n`, so the strings leave the module through this callback; `:app` implements it against
 * `strings_extensions.xml` and binds it into the graph.
 */
interface ExtensionMessages {
    /** Why an install was refused, for the browse screen's error row. */
    fun installRejected(reason: InstallRejection): String

    /** Why an extension on disk did not load, for the extensions list. */
    fun loadError(reason: LoadError): String
}
