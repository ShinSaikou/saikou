// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/AppInfo.kt (modified: yes) reads the host PackageInfo through Injekt instead of BuildConfig, inside a PackageManager.getPackageInfo trace section; the mime list is a constant
package eu.kanade.tachiyomi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Trace
import androidx.core.content.pm.PackageInfoCompat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Used by extensions.
 *
 * This module has no `BuildConfig`, and the host's version is not a compile-time constant here
 * anyway, so both version accessors read the host's own [android.content.pm.PackageInfo] through
 * the [Context] the Injekt bootstrap registers. The mime list is the set the reader's page
 * pipeline actually decodes.
 */
@Suppress("UNUSED")
object AppInfo {

    private val packageInfo by lazy {
        val context = Injekt.get<Context>()
        Trace.beginSection("PackageManager.getPackageInfo")
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = packageInfo?.versionName.orEmpty()

    /**
     * A list of supported image MIME types by the reader.
     * e.g. ["image/jpeg", "image/png", ...]
     *
     * @since extension-lib 1.5
     */
    fun getSupportedImageMimeTypes(): List<String> = SUPPORTED_IMAGE_MIME_TYPES

    private val SUPPORTED_IMAGE_MIME_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/gif")
}
