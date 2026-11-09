package com.shinsaikou.ui.backup

import com.shinsaikou.backup.CustomCoverFiles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import java.io.File
import java.io.IOException

/**
 * The custom covers a backup carries (R165 item 3), where the entry screen keeps them: the file
 * [CoverCache] names for each entry id, written whole, as `MangaCoverActions.setCustomCover` writes
 * it. A Local entry's cover lives in its own folder and is not one of these.
 */
@ContributesBinding(AppScope::class)
@Inject
class CoverCacheCustomCoverFiles(private val coverCache: CoverCache) : CustomCoverFiles {

    override fun exists(mangaId: Long): Boolean = coverCache.getCustomCoverFile(mangaId).exists()

    override fun read(mangaId: Long): ByteArray? =
        coverCache.getCustomCoverFile(mangaId).takeIf { it.exists() }?.readBytes()

    /**
     * Written beside the target and renamed over it, so a process killed mid-write leaves no
     * truncated cover that [exists] would then keep a later restore from replacing.
     */
    override fun write(mangaId: Long, bytes: ByteArray) {
        val target = coverCache.getCustomCoverFile(mangaId)
        val partial = File(target.parentFile, "${target.name}.partial")
        try {
            partial.writeBytes(bytes)
            if (!partial.renameTo(target)) throw IOException("Could not move the cover into place")
        } finally {
            partial.delete()
        }
    }
}
