package com.shinsaikou.reader.cache

import com.shinsaikou.reader.pipeline.SplitPlanner
import eu.kanade.tachiyomi.util.storage.DiskUtil
import java.util.Locale

/**
 * Every key handed to `DiskLruCache` is produced here. Pure. Contract 07 part 1b section 4.9.1.
 *
 * Keys are MD5 through `:core:common`'s `DiskUtil.hashKeyForDisk`, not the `Hash.sha256` beside
 * it, because `DiskLruCache` validates keys against `[a-z0-9_-]{1,64}`. MD5 hex is 32 characters
 * and leaves room for the part suffix; SHA-256 hex is 64 and leaves none. A cache key is not a
 * security boundary, so the length is what is being bought.
 */
object DiskKeys {

    /**
     * The chapter's page-list entry. The two fields are separated, which upstream's
     * `"${chapter.mangaId}${chapter.url}"` does not do: without a separator, manga 12 with url
     * `/3` and manga 1 with url `2/3` hash the same.
     */
    fun pageList(mangaId: Long, chapterUrl: String): String = hash("pagelist|$mangaId|$chapterUrl")

    /** A whole page. [imageKey] is the loader's `page.imageUrl ?: page.url + "#" + page.index`. */
    fun image(imageKey: String): String = hash(imageKey)

    /**
     * Part [index] of the page keyed by [imageKey]. Part 0 is the first band, not the parent.
     *
     * Derived from the parent key rather than hashed from a decorated string, so `getPartFile` is
     * stateless and `partsInCache` can walk indices upward. An MD5 hex string contains no
     * underscore, so no whole-page key can ever have the shape `<32 hex>_p<3 digits>`.
     */
    fun part(imageKey: String, index: Int): String {
        require(index in 0..<SplitPlanner.MAX_PARTS) { "part index out of range: $index" }
        return image(imageKey) + PART_INFIX + "%03d".format(Locale.ENGLISH, index)
    }

    private fun hash(value: String): String = DiskUtil.hashKeyForDisk(value)

    const val PART_INFIX = "_p"
}
