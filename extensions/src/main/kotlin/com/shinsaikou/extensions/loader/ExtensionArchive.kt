package com.shinsaikou.extensions.loader

import android.content.pm.PackageInfo
import java.io.File

/** Everything read out of an extension APK before a single class of it is loaded. */
data class ExtensionArchive(
    val file: File,
    /** With `applicationInfo.sourceDir` and `publicSourceDir` pointed at [file]. */
    val packageInfo: PackageInfo,
    val packageName: String,
    /** `tachiyomix.name`, else the application label without upstream's legacy prefix. */
    val name: String,
    val versionName: String,
    val versionCode: Long,
    /** Null when neither the metadata float nor the version name parses. */
    val libVersion: Double?,
    val isNsfw: Boolean,
    /** Entry-point class names with a leading '.' already expanded; empty when the key is missing. */
    val sourceClassNames: List<String>,
    /** Empty when the APK is unsigned. */
    val signerFingerprints: List<String>,
)
