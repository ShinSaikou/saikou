package com.shinsaikou.ui.browse

import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds

/**
 * The language rule the Sources page and Global Search share (contract 08 section 5.3, ruling
 * R57). `"all"` is a literal code, the one multi-language sources report, matched exactly as upstream
 * matches it rather than read as a wildcard. The Local source is exempt, as it is in upstream's
 * source list: its code is `"other"`, which no extension offers and the language picker would
 * never list, so the rule would otherwise hide the owner's own files.
 */
internal fun CatalogueSource.isInEnabledLanguage(enabled: Set<String>): Boolean =
    id == SourceIds.LOCAL || lang in enabled

/**
 * The ids of every source an adult extension provides. The flag is not on the source contract, so
 * it is joined in from the extension the source was loaded from.
 */
internal fun matureSourceIds(installed: List<Extension.Installed>): Set<Long> = installed
    .filter { it.isNsfw }
    .flatMapTo(mutableSetOf()) { extension -> extension.sources.map { it.id } }
