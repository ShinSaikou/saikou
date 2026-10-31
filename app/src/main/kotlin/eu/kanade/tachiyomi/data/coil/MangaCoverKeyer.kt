// Fresh: contract 08 section 3.4 assigns this package. Upstream's keyer of the same name is Coil 2 and keys on its own Manga type, so these two keyers were written fresh.
package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover

/**
 * Cache keys for the two cover data types (contract 08 section 3.4).
 *
 * `coverLastModified` is part of every key, which is what makes a replaced cover appear: the entry
 * bumps that column and the old memory and disk entries stop being consulted rather than being
 * hunted down and evicted.
 */
class MangaKeyer : Keyer<Manga> {
    override fun key(data: Manga, options: Options): String? = MangaCoverKeyer.key(data.asMangaCover())
}

class MangaCoverKeyer(private val coverCache: CoverCache) : Keyer<MangaCover> {

    override fun key(data: MangaCover, options: Options): String? =
        if (coverCache.getCustomCoverFile(data.mangaId).exists()) {
            "custom;${data.mangaId};${data.lastModified}"
        } else {
            key(data)
        }

    companion object {
        /**
         * The key without the custom-cover branch. `MangaKeyer` uses it because a `Manga` reaches
         * Coil only from list rows, which never render a custom cover of their own.
         */
        fun key(data: MangaCover): String? = data.url?.takeIf { it.isNotBlank() }?.let { "$it;${data.lastModified}" }
    }
}
