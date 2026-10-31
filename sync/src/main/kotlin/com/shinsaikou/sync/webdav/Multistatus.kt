package com.shinsaikou.sync.webdav

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * One `<response>` of a PROPFIND answer, with the properties of its successful `<propstat>`
 * blocks: [href] as the server wrote it, and [contentLength] and [lastModified] null when the
 * server gave none it could say. [lastModified] is epoch milliseconds from `getlastmodified`,
 * the server's own clock.
 */
internal data class MultistatusEntry(
    val href: String,
    val collection: Boolean,
    val contentLength: Long?,
    val lastModified: Long?,
    val rawLastModified: String?,
)

/**
 * Reads a `207 Multi-Status` body as it streams, with `javax.xml.parsers`: the platform parser on a
 * device, the JDK's on the JVM (`android.util.Xml` and `XmlPullParser` are stubs there). A
 * multistatus never has a document type, so a DTD or an external entity ends the parse instead of
 * being fetched or expanded; each parser refuses them in the way it supports.
 */
internal object Multistatus {

    private const val DAV = "DAV:"

    private val HARDENING = listOf(
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"

    /** Throws [WebDavMalformedResponseException] for a body that is not well-formed XML. */
    fun parse(stream: InputStream): List<MultistatusEntry> {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's expat-backed factory knows none of these and throws for each; it never
            // loads an external entity without a resolver, and the handler below refuses both.
            HARDENING.forEach { (name, value) -> runCatching { setFeature(name, value) } }
        }
        val handler = Handler()
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        runCatching { reader.setProperty(LEXICAL_HANDLER, handler) }
        try {
            reader.parse(InputSource(stream))
        } catch (e: SAXException) {
            throw WebDavMalformedResponseException("The server's PROPFIND answer is not a readable multistatus: ${e.message}", e)
        }
        return handler.entries
    }

    /** `getlastmodified` is an RFC 1123 date; anything else reads as absent. */
    private fun epochMillis(text: String): Long? = try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** A status line such as `HTTP/1.1 200 OK` names a success. A missing one is taken as success. */
    private fun successful(status: String?): Boolean {
        val code = status?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return status.isNullOrBlank()
        return code in 200..299
    }

    private class Handler : DefaultHandler2() {

        val entries = mutableListOf<MultistatusEntry>()

        private val text = StringBuilder()

        // The response being read, and the propstat inside it.
        private var inResponse = false
        private var href: String? = null
        private var responseStatus: String? = null
        private var collection = false
        private var contentLength: String? = null
        private var lastModified: String? = null

        private var inPropstat = false
        private var propstatStatus: String? = null
        private var propCollection = false
        private var propContentLength: String? = null
        private var propLastModified: String? = null

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (uri != DAV) return
            when (localName) {
                "response" -> {
                    inResponse = true
                    href = null
                    responseStatus = null
                    collection = false
                    contentLength = null
                    lastModified = null
                }
                "propstat" -> if (inResponse) {
                    inPropstat = true
                    propstatStatus = null
                    propCollection = false
                    propContentLength = null
                    propLastModified = null
                }
                "collection" -> if (inPropstat) propCollection = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri != DAV) return
            val value = text.toString().trim()
            when (localName) {
                "href" -> if (inResponse && !inPropstat) href = value
                "status" -> if (inPropstat) propstatStatus = value else if (inResponse) responseStatus = value
                "getcontentlength" -> if (inPropstat) propContentLength = value
                "getlastmodified" -> if (inPropstat) propLastModified = value
                "propstat" -> if (inPropstat) {
                    inPropstat = false
                    if (successful(propstatStatus)) {
                        collection = collection || propCollection
                        propContentLength?.let { contentLength = it }
                        propLastModified?.let { lastModified = it }
                    }
                }
                "response" -> if (inResponse) {
                    inResponse = false
                    val listed = href
                    if (listed != null && successful(responseStatus)) {
                        entries += MultistatusEntry(
                            href = listed,
                            collection = collection,
                            contentLength = contentLength?.toLongOrNull(),
                            lastModified = lastModified?.let(::epochMillis),
                            rawLastModified = lastModified,
                        )
                    }
                }
            }
            text.setLength(0)
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("A multistatus has no document type declaration")
        }

        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            throw SAXException("External entity $systemId refused")

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entity $systemId refused")

        override fun fatalError(e: SAXParseException) {
            throw e
        }
    }
}
