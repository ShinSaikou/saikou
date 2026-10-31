package com.shinsaikou.extensions.adapter

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.Source as AbiSource

/**
 * Picks the concrete adapter for [delegate]. The ABI's `ConfigurableSource` and `UnmeteredSource`
 * are a single-method and a marker interface, so four concrete classes cover every combination and
 * an `is` check in the host behaves exactly as it would on the extension object itself.
 */
object SourceAdapters {
    fun wrap(delegate: AbiSource, packageName: String, libVersion: Double): ExtensionSourceAdapter {
        val configurable = delegate is ConfigurableSource
        val unmetered = delegate is UnmeteredSource
        return when {
            configurable && unmetered -> ConfigurableUnmeteredExtensionSourceAdapter(delegate, packageName, libVersion)
            configurable -> ConfigurableExtensionSourceAdapter(delegate, packageName, libVersion)
            unmetered -> UnmeteredExtensionSourceAdapter(delegate, packageName, libVersion)
            else -> ExtensionSourceAdapter(delegate, packageName, libVersion)
        }
    }
}
