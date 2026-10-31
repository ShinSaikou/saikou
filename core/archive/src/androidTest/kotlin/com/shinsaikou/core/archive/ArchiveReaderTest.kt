package com.shinsaikou.core.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import tachiyomi.core.archive.ArchiveEntry
import tachiyomi.core.archive.ZipWriter
import tachiyomi.core.archive.archiveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Instrumentation, not a unit test: [tachiyomi.core.archive.ArchiveReader] mmaps the file through
 * `android.system.Os` and reads it with the native libarchive binding, neither of which exists on
 * the unit test JVM.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "archive-reader-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun readsEveryEntryOfACbz() {
        val cbz = File(directory, "chapter.cbz")
        ZipOutputStream(cbz.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg"))
            zip.write(PAGE_ONE.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("002.jpg"))
            zip.write(PAGE_TWO.toByteArray())
            zip.closeEntry()
        }

        UniFile.fromFile(cbz)!!.archiveReader(context).use { reader ->
            val names = reader.useEntries { entries ->
                entries.filter(ArchiveEntry::isFile).map(ArchiveEntry::name).toList()
            }
            assertEquals(listOf("001.jpg", "002.jpg"), names)

            val page = reader.getInputStream("002.jpg")!!.use { it.readBytes().decodeToString() }
            assertEquals(PAGE_TWO, page)
            assertNull(reader.getInputStream("003.jpg"))
        }
    }

    @Test
    fun readsBackWhatZipWriterWrote() {
        val page = File(directory, "001.jpg").apply { writeText(PAGE_ONE) }
        val cbz = UniFile.fromFile(File(directory, "written.cbz").apply { createNewFile() })!!

        ZipWriter(context, cbz).use { it.write(UniFile.fromFile(page)!!) }

        cbz.archiveReader(context).use { reader ->
            val names = reader.useEntries { entries -> entries.map(ArchiveEntry::name).toList() }
            assertEquals(listOf("001.jpg"), names)
            assertEquals(PAGE_ONE, reader.getInputStream("001.jpg")!!.use { it.readBytes().decodeToString() })
        }
    }

    private companion object {
        const val PAGE_ONE = "first page bytes"
        const val PAGE_TWO = "second page bytes"
    }
}
