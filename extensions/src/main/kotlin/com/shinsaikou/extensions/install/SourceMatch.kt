package com.shinsaikou.extensions.install

import com.shinsaikou.domain.extension.SourceExtensionInstaller.WantedSource
import com.shinsaikou.extensions.model.Extension

/**
 * Which extensions provide [SourceMatch.match]'s sources. A backup names a source by id and name
 * only, never by package, and an index lists each extension's sources with their ids, so the id is
 * the whole match: a name can be shared by sources of different languages, an id cannot.
 */
internal data class SourceMatch(
    /** One entry per package, in the order of the first source that asked for it. */
    val toInstall: List<Extension.Available>,
    /** Extensions a source matched whose package is on this device already, loaded or not. */
    val alreadyInstalled: List<Extension.Available>,
    /** Sources no available extension lists. */
    val notFound: List<WantedSource>,
) {
    companion object {
        /**
         * [wanted] against [available], the merged list of every configured index (one entry per
         * package). A package in [onDisk] is never installed again: an untrusted copy needs Trust on
         * the Extensions tab, not a second download, and a loaded one already serves its sources.
         */
        fun match(
            wanted: List<WantedSource>,
            available: List<Extension.Available>,
            onDisk: Set<String>,
        ): SourceMatch {
            val bySourceId = HashMap<Long, Extension.Available>()
            available.forEach { extension ->
                extension.sources.forEach { source -> bySourceId.putIfAbsent(source.id, extension) }
            }
            val toInstall = LinkedHashMap<String, Extension.Available>()
            val alreadyInstalled = LinkedHashMap<String, Extension.Available>()
            val notFound = mutableListOf<WantedSource>()
            wanted.distinctBy { it.id }.forEach { source ->
                val extension = bySourceId[source.id]
                when {
                    extension == null -> notFound += source
                    extension.packageName in onDisk -> alreadyInstalled.putIfAbsent(extension.packageName, extension)
                    else -> toInstall.putIfAbsent(extension.packageName, extension)
                }
            }
            return SourceMatch(toInstall.values.toList(), alreadyInstalled.values.toList(), notFound)
        }
    }
}
