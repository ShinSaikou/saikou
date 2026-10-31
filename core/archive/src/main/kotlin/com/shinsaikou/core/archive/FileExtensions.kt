package com.shinsaikou.core.archive

import android.os.ParcelFileDescriptor
import tachiyomi.core.archive.ArchiveReader
import tachiyomi.core.archive.EpubReader
import java.io.File

/**
 * [ArchiveReader] over an app-private path, the sibling of `UniFile.archiveReader(context)` for
 * files this app owns outright (cache, download directory) and that need no content resolver.
 */
fun File.archiveReader(): ArchiveReader =
    ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY).use { ArchiveReader(it) }

/** [EpubReader] over an app-private path. See [archiveReader]. */
fun File.epubReader(): EpubReader = EpubReader(archiveReader())
