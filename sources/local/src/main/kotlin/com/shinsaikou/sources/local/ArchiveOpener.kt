package com.shinsaikou.sources.local

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.archive.ArchiveReader
import tachiyomi.core.archive.EpubReader
import tachiyomi.core.archive.archiveReader
import tachiyomi.core.archive.epubReader

/**
 * The seam between the Local source and native libarchive. [ArchiveReader] mmaps through
 * `android.system.Os`, so it cannot run under a JVM unit test; the tests stub this interface
 * instead and the native path is covered by instrumentation.
 *
 * The reader is closed when [block] returns; nothing that escapes it may hold a stream open.
 */
interface ArchiveOpener {
    fun <T> readArchive(file: UniFile, block: (ArchiveReader) -> T): T

    fun <T> readEpub(file: UniFile, block: (EpubReader) -> T): T
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ContentResolverArchiveOpener(private val context: Context) : ArchiveOpener {

    override fun <T> readArchive(file: UniFile, block: (ArchiveReader) -> T): T =
        file.archiveReader(context).use(block)

    override fun <T> readEpub(file: UniFile, block: (EpubReader) -> T): T =
        file.epubReader(context).use(block)
}
