package com.shinsaikou.core.metadata.comicinfo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.newGenericReader
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.xmlStreaming
import tachiyomi.core.metadata.comicinfo.ComicInfo
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the `ComicInfo.xml` a chapter folder, a CBZ or a local manga folder carries.
 *
 * The [xml] instance is configured exactly as upstream configures its shared one, unknown children
 * included: a ComicInfo.xml written by another tool carries elements this model does not declare
 * and must still parse.
 */
@Inject
@SingleIn(AppScope::class)
class ComicInfoXml {

    val xml: XML = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
        setIndent(2)
    }

    /**
     * Parses [stream], which the caller closes. The generic (pure Kotlin) reader is used rather
     * than the platform pull parser so the same code path runs on the device and under unit tests.
     */
    fun decode(stream: InputStream): ComicInfo =
        xmlStreaming.newGenericReader(stream, Charsets.UTF_8.name())
            .use { xml.decodeFromReader(ComicInfo.serializer(), it) }

    /** Writes [info] to [stream] as UTF-8; the caller closes the stream. */
    fun encode(info: ComicInfo, stream: OutputStream) {
        stream.write(xml.encodeToString(ComicInfo.serializer(), info).toByteArray())
    }
}
