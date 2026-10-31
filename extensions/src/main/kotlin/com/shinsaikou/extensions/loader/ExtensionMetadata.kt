package com.shinsaikou.extensions.loader

import android.content.pm.PackageManager
import android.os.Build

/** Manifest keys read from an extension APK. The names are the ABI's; never rename them. */
object ExtensionMetadata {
    /** `<uses-feature>` every extension declares; its absence means the APK is not an extension. */
    const val FEATURE = "tachiyomi.extension"

    /** ';'-separated list of entry-point classes; a leading '.' is relative to the package. */
    const val SOURCE_CLASS = "tachiyomi.extension.class"

    /** Informational only; the loader dispatches on the instantiated object's type instead. */
    const val SOURCE_FACTORY = "tachiyomi.extension.factory"

    /** Legacy NSFW flag, 1 means adult content. */
    const val NSFW = "tachiyomi.extension.nsfw"

    /** Display name; falls back to the application label without upstream's legacy prefix. */
    const val NAME = "tachiyomix.name"

    /** Extension ABI version as a float; 0 means the key is absent. */
    const val EXTENSION_LIB = "tachiyomix.extensionLib"

    /** Current NSFW flag, anything above zero means adult content. */
    const val CONTENT_WARNING = "tachiyomix.contentWarning"

    /** File extension of a privately installed APK. */
    const val PRIVATE_EXTENSION_EXTENSION = "ext"

    /** Directory under `filesDir` holding the private installs. */
    const val PRIVATE_DIR = "exts"

    /** Upstream's flag set. `GET_CONFIGURATIONS` is what populates `reqFeatures`. */
    @Suppress("DEPRECATION")
    val PACKAGE_FLAGS: Int = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
}
