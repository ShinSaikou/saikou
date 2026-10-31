package com.shinsaikou.ui.browse

import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.loader.ExtensionIcons
import com.shinsaikou.extensions.model.Extension
import java.io.File
import java.io.FileNotFoundException

/**
 * Coil data for an installed extension's own launcher icon, which the extension list, the
 * extension's detail screen and every list of sources draw (upstream's `ExtensionIcon` and
 * `SourceIcon`). A screen names the extension the way it knows it, by package or by one of its
 * sources; both are resolved against the extensions installed when the image is asked for, so a
 * row whose extension is not installed gets no fetcher and [ExtensionIconImage] draws the generic
 * mark instead.
 */
sealed interface ExtensionIcon {
    data class OfPackage(val packageName: String) : ExtensionIcon

    data class OfSource(val sourceId: Long) : ExtensionIcon
}

internal fun ExtensionIcon.resolve(installed: List<Extension.Installed>): Extension.Installed? = when (this) {
    is ExtensionIcon.OfPackage -> installed.firstOrNull { it.packageName == packageName }
    is ExtensionIcon.OfSource -> installed.firstOrNull { ext -> ext.sources.any { it.id == sourceId } }
}

/**
 * Reads the icon out of the extension's `.ext` file on Coil's fetcher context (the IO pool), so no
 * package manager read is on the path to a list's first frame. The icon goes to Coil as a bitmap:
 * Coil's memory cache keeps only shareable images, and a drawable that is not a bitmap is not one.
 */
class ExtensionIconFetcher(
    private val file: File,
    private val icons: ExtensionIcons,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val icon = icons.load(file) ?: throw FileNotFoundException("No icon could be read from ${file.name}")
        return ImageFetchResult(icon.toBitmap().asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    /** Lazy suppliers, as the cover fetchers take theirs, so building the loader constructs neither. */
    class Factory(
        private val extensions: () -> ExtensionManager,
        private val icons: () -> ExtensionIcons,
    ) : Fetcher.Factory<ExtensionIcon> {
        override fun create(data: ExtensionIcon, options: Options, imageLoader: ImageLoader): Fetcher? {
            val extension = data.resolve(extensions().installedExtensions.value) ?: return null
            return ExtensionIconFetcher(extension.extFile, icons())
        }
    }
}

/**
 * Keys the memory cache on the package and its version code, so an update that brings a new icon
 * is read again instead of served from memory. An extension that is not installed has no key, and
 * Coil skips the memory cache for it.
 */
class ExtensionIconKeyer(private val extensions: () -> ExtensionManager) : Keyer<ExtensionIcon> {
    override fun key(data: ExtensionIcon, options: Options): String? = data
        .resolve(extensions().installedExtensions.value)
        ?.let { "extension-icon:${it.packageName}:${it.versionCode}" }
}
