package com.shinsaikou.extensions.loader

import android.content.Context
import android.graphics.drawable.Drawable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Loads an extension's launcher icon out of its `.ext` file, off the load path so a slow icon never
 * delays a source becoming usable. Nothing is cached here: the app's image loader keeps the icons
 * it has drawn, keyed by package and version code, so an update's new icon is read again where a
 * cache keyed on the file path would go on serving the old one (an update reuses the path).
 *
 * The archive is parsed with no flags. An icon needs neither the signatures nor the metadata, and
 * asking for the signatures would verify the whole APK on every icon read.
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionIcons(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(extFile: File): Drawable? = withContext(ioDispatcher) {
        try {
            val path = extFile.absolutePath
            val packageManager = context.packageManager
            val applicationInfo = packageManager
                .getPackageArchiveInfo(path, 0)
                ?.applicationInfo
                ?: return@withContext null
            if (applicationInfo.sourceDir == null) applicationInfo.sourceDir = path
            if (applicationInfo.publicSourceDir == null) applicationInfo.publicSourceDir = path
            applicationInfo.loadIcon(packageManager)
        } catch (e: Throwable) {
            this@ExtensionIcons.logcat(LogPriority.WARN, e) { "Could not load the icon of ${extFile.name}" }
            null
        }
    }
}
