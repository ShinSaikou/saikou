package com.shinsaikou.downloads.adoption

import com.shinsaikou.core.archive.archiveReader
import com.shinsaikou.image.ImageUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Opens a copied chapter archive the way the reader will, and counts its pages. The reader's
 * downloaded branch hands a `.cbz` to `ArchivePageLoader`, which reads it through libarchive and
 * takes as a page every file entry `ImageUtil.isImage` accepts; an archive that fails here would
 * fail there.
 *
 * An interface because libarchive is native code the JVM tests do not have; they count a zip's
 * entries instead, and the device test runs this binding.
 */
interface ChapterArchives {

    /** The pages of the archive at [file]. Throws when it does not open as an archive. */
    fun pageCount(file: File): Int
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LibArchiveChapterArchives : ChapterArchives {

    override fun pageCount(file: File): Int = file.archiveReader().use { reader ->
        reader.useEntries { entries ->
            entries.count { entry ->
                entry.isFile && ImageUtil.isImage(entry.name) { checkNotNull(reader.getInputStream(entry.name)) }
            }
        }
    }
}
