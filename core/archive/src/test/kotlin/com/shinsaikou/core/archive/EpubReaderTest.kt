package com.shinsaikou.core.archive

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import tachiyomi.core.archive.ArchiveReader
import tachiyomi.core.archive.EpubReader
import org.junit.jupiter.api.Test

/**
 * Drives [EpubReader] over a fake archive: the reader is the only thing that touches libarchive,
 * so stubbing its two entry points exercises the whole container-format layer on the JVM.
 */
class EpubReaderTest {

    @Test
    fun `resolves the package document through META-INF container xml`() {
        val reader = epubReader(EPUB_ENTRIES)

        reader.getPackageHref() shouldBe "OEBPS/content.opf"
    }

    @Test
    fun `falls back to the default package document when there is no container`() {
        val reader = epubReader(EPUB_ENTRIES - "META-INF/container.xml")

        reader.getPackageHref() shouldBe "OEBPS/content.opf"
    }

    @Test
    fun `lists the images of every page in spine order, relative paths resolved`() {
        val reader = epubReader(EPUB_ENTRIES)

        reader.getImagesFromPages() shouldBe listOf("OEBPS/images/002.jpg", "OEBPS/images/001.jpg")
    }

    @Test
    fun `reads an entry through the archive reader`() {
        val reader = epubReader(EPUB_ENTRIES)

        reader.getInputStream("OEBPS/images/001.jpg")!!.use { it.readBytes().decodeToString() } shouldBe "first"
        reader.getInputStream("OEBPS/images/404.jpg") shouldBe null
    }

    private fun epubReader(entries: Map<String, String>): EpubReader {
        val archiveReader = mockk<ArchiveReader>()
        every { archiveReader.getInputStream(any()) } answers { entries[firstArg<String>()]?.byteInputStream() }
        return EpubReader(archiveReader)
    }

    private companion object {
        // The spine deliberately lists the second page first: reading order wins over manifest order.
        val EPUB_ENTRIES = mapOf(
            "META-INF/container.xml" to
                """
                <?xml version="1.0"?>
                <container version="1.0">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            "OEBPS/content.opf" to
                """
                <?xml version="1.0"?>
                <package version="3.0">
                  <manifest>
                    <item id="page1" href="text/001.xhtml" media-type="application/xhtml+xml"/>
                    <item id="page2" href="text/002.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="images/001.jpg" media-type="image/jpeg"/>
                  </manifest>
                  <spine>
                    <itemref idref="page2"/>
                    <itemref idref="page1"/>
                  </spine>
                </package>
                """.trimIndent(),
            "OEBPS/text/001.xhtml" to """<html><body><img src="../images/001.jpg"/></body></html>""",
            "OEBPS/text/002.xhtml" to """<html><body><img src="../images/002.jpg"/></body></html>""",
            "OEBPS/images/001.jpg" to "first",
            "OEBPS/images/002.jpg" to "second",
        )
    }
}
