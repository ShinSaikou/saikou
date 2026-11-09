// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupDecoder.kt (modified: yes) streamed, can pass over an entry that does not decode, lists a file's merged entries, and streams Saikou's custom covers
package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import com.shinsaikou.source.SourceIds
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCustomCover
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.gzip
import okio.source
import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/**
 * Modified: the Injekt default is gone, the `okio.Source` overload is split out so the sniffing
 * algorithm can be decoded on the JVM without a [Context], and the two localized [IOException]
 * messages became [BackupDecodeException] so the screen owns the wording.
 *
 * Phase 5 unit SC2 adds the streaming half, [header] and [entries], so a restore never holds the
 * whole file: a 40,000-entry file is 127 MB decoded, which one array of it alone exhausts a 192 MB
 * heap. Both walk the file's top-level protobuf frames. Every field of [Backup] but `backupManga`
 * (field 1) is small, and each library entry is its own length-delimited field 1 frame, so the
 * walk reads one entry at a time. [decode] stays for the callers that need the whole object, which
 * are tests and the device tests' readers of a file they wrote.
 *
 * An entry whose own bytes do not decode no longer refuses the file. Its frame says where it ends,
 * so the walk goes on past it: [header] lists it, and [entries] can pass over it, so a restore keeps
 * every other entry and reports the one. A file whose frames themselves break (cut short, a tag or a
 * length that cannot be) is still refused, since nothing after the break can be found.
 *
 * [header] keeps a TachiyomiSY file's synthetic merged entries, so a restore knows them whatever
 * their place in the file; there are few, and their chapter lists are dropped on the way.
 *
 * Saikou's custom covers (field 5001, R165 item 3) are images, so they are streamed as the entries are:
 * [header] passes over them, and [customCovers] reads them one at a time.
 */
@Inject
class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf,
) {

    /** Opens the content URI and reads its [header]. */
    fun header(uri: Uri): BackupHeader {
        return context.contentResolver.openInputStream(uri)!!.use { header(it.source()) }
    }

    /**
     * Peeks two bytes: `0x1f8b` is gzip, the three `{` signatures are a 1.x JSON backup, anything
     * else is read raw. Raw is not defensive padding, third-party sync transports write
     * uncompressed protobuf under the same file extension.
     */
    fun decode(source: Source): Backup {
        val bytes = open(source).use { it.readByteArray() }

        return try {
            parser.decodeFromByteArray(Backup.serializer(), bytes)
        } catch (e: SerializationException) {
            throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, e)
        }
    }

    /**
     * Walks the file once and closes [source]. Every top-level field but 1 is decoded as [decode]
     * would decode it, into a [Backup] whose `backupManga` is empty. Each field 1 frame is decoded
     * whole, one at a time, read for the tracker ids its entry names and then let go, so a file whose
     * frames break is refused here, before a restore writes anything. An entry that does not decode
     * is listed in [BackupHeader.undecodable]; a file none of whose entries decode is refused, since
     * it is no backup (or bytes that happen to frame). Fields the vendored model does not know are
     * kept for the parser, which ignores them as it does in [decode]. A synthetic merged entry is
     * kept too, in [BackupHeader.syMerged]. The custom covers are passed over, for [customCovers].
     */
    fun header(source: Source): BackupHeader = open(source).use { stream ->
        malformedOnFailure {
            val rest = Buffer()
            val trackerIds = LinkedHashSet<Long>()
            val undecodable = mutableListOf<Int>()
            val syMerged = mutableListOf<BackupManga>()
            var count = 0
            val frames = FrameReader(stream)
            while (frames.next()) {
                if (frames.field == MANGA_FIELD) {
                    val entry = decodeEntryOrNull(frames.readEntry())
                    if (entry == null) {
                        undecodable += count
                    } else {
                        entry.tracking.mapTo(trackerIds) { it.syncId.toLong() }
                        if (entry.source == SourceIds.SY_MERGED) syMerged += entry.apply { chapters = emptyList() }
                    }
                    count++
                } else if (frames.field == COVERS_FIELD) {
                    // Checked here, as an entry is, so a cover frame of the wrong wire type is refused
                    // before a restore writes anything rather than when [customCovers] reaches it.
                    frames.skipLengthDelimited()
                } else {
                    frames.copyTo(rest)
                }
            }
            if (undecodable.isNotEmpty() && undecodable.size == count) {
                throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, IOException("No entry decodes"))
            }
            BackupHeader(
                backup = parser.decodeFromByteArray(Backup.serializer(), rest.readByteArray()),
                mangaCount = count,
                trackerIds = trackerIds.toList(),
                undecodable = undecodable,
                syMerged = syMerged,
            )
        }
    }

    /**
     * The file's library entries in file order, each decoded when the sequence reaches it, after
     * the first [skip] field 1 frames, which are passed over undecoded. The index of an entry is
     * the number of field 1 frames before it, which is what a restore's checkpoint counts. Read the
     * sequence once, and close it, which closes [source]. A frame that does not decode throws
     * [BackupDecodeException] with [BackupDecodeException.Reason.MALFORMED] where the sequence
     * reaches it, unless [passOverUndecodable] is set and the frame itself is whole: then that entry
     * is left out, as [BackupHeader.undecodable] lists it, and the sequence goes on after it.
     */
    fun entries(source: Source, skip: Int = 0, passOverUndecodable: Boolean = false): BackupEntries {
        require(skip >= 0) { "skip is $skip" }
        return BackupFrames(open(source)) { stream ->
            iterator {
                val frames = FrameReader(stream)
                var index = 0
                while (malformedOnFailure { frames.next() }) {
                    if (frames.field != MANGA_FIELD) {
                        malformedOnFailure { frames.skip() }
                    } else if (index++ < skip) {
                        malformedOnFailure { frames.skipEntry() }
                    } else {
                        val bytes = malformedOnFailure { frames.readEntry() }
                        val entry = if (passOverUndecodable) {
                            decodeEntryOrNull(bytes)
                        } else {
                            malformedOnFailure { parser.decodeFromByteArray(BackupManga.serializer(), bytes) }
                        }
                        if (entry != null) yield(entry)
                    }
                }
            }
        }
    }

    /**
     * The file's custom covers (field 5001) in file order, each decoded when the sequence reaches it,
     * as [entries] reads the entries; one that does not decode is left out. Read the sequence once,
     * and close it, which closes [source].
     */
    fun customCovers(source: Source): BackupCustomCovers = BackupFrames(open(source)) { stream ->
        iterator {
            val frames = FrameReader(stream)
            while (malformedOnFailure { frames.next() }) {
                if (frames.field != COVERS_FIELD) {
                    malformedOnFailure { frames.skip() }
                } else {
                    val bytes = malformedOnFailure { frames.readLengthDelimited() }
                    decodeOrNull(BackupCustomCover.serializer(), bytes)?.let { yield(it) }
                }
            }
        }
    }

    /** One entry's bytes, or null when they do not decode, where [malformedOnFailure] would say malformed. */
    private fun decodeEntryOrNull(bytes: ByteArray): BackupManga? = decodeOrNull(BackupManga.serializer(), bytes)

    private fun <T> decodeOrNull(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T? = try {
        parser.decodeFromByteArray(deserializer, bytes)
    } catch (_: SerializationException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** Sniffs gzip and JSON, as [decode] always has. The caller closes what it returns. */
    private fun open(source: Source): BufferedSource {
        val buffered = source.buffer()
        try {
            val peeked = buffered.peek().apply { require(2) }
            return when (peeked.readShort().toInt()) {
                MAGIC_GZIP -> buffered.gzip().buffer()
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 ->
                    throw BackupDecodeException(BackupDecodeException.Reason.JSON_NOT_SUPPORTED)
                else -> buffered
            }
        } catch (e: Throwable) {
            buffered.close()
            throw e
        }
    }

    /**
     * A file that ends inside a frame, or whose frame the parser refuses, is malformed. The parser
     * refuses a nested message whose length runs past its frame with an [IndexOutOfBoundsException]
     * rather than a [SerializationException], so that is malformed too. Every other [IOException]
     * (a gzip checksum, the storage itself) is what it is.
     */
    private inline fun <T> malformedOnFailure(block: () -> T): T = try {
        block()
    } catch (e: EOFException) {
        throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, e)
    } catch (e: SerializationException) {
        throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, e)
    } catch (e: IndexOutOfBoundsException) {
        throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, e)
    }

    /**
     * Reads one top-level frame at a time: [next] reads its tag, after which exactly one of
     * [readEntry], [skipEntry], [readLengthDelimited], [skipLengthDelimited], [skip] or [copyTo]
     * consumes its value.
     */
    private class FrameReader(private val stream: BufferedSource) {
        private var tag = 0L
        var field = 0
            private set
        private var wireType = 0

        /** False at the end of the file. */
        fun next(): Boolean {
            if (stream.exhausted()) return false
            tag = readVarint()
            field = (tag ushr 3).toInt()
            wireType = (tag and 7).toInt()
            if (tag !in 1..MAX_TAG || field == 0) malformed("tag $tag")
            return true
        }

        fun readEntry(): ByteArray = stream.readByteArray(entryLength())

        fun readLengthDelimited(): ByteArray {
            if (wireType != WIRE_LENGTH_DELIMITED) malformed("wire type $wireType in field $field")
            return stream.readByteArray(length())
        }

        fun skipLengthDelimited() {
            if (wireType != WIRE_LENGTH_DELIMITED) malformed("wire type $wireType in field $field")
            stream.skip(length())
        }

        fun skipEntry() = stream.skip(entryLength())

        fun skip() {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> stream.skip(8)
                WIRE_LENGTH_DELIMITED -> stream.skip(length())
                WIRE_FIXED32 -> stream.skip(4)
                else -> malformed("wire type $wireType in field $field")
            }
        }

        /** Writes the frame, tag and value, into [sink] as the file held it. */
        fun copyTo(sink: Buffer) {
            sink.writeVarint(tag)
            when (wireType) {
                WIRE_VARINT -> sink.writeVarint(readVarint())
                WIRE_FIXED64 -> sink.write(stream, 8)
                WIRE_LENGTH_DELIMITED -> length().let { length ->
                    sink.writeVarint(length)
                    sink.write(stream, length)
                }
                WIRE_FIXED32 -> sink.write(stream, 4)
                else -> malformed("wire type $wireType in field $field")
            }
        }

        private fun entryLength(): Long {
            if (wireType != WIRE_LENGTH_DELIMITED) malformed("wire type $wireType in field $MANGA_FIELD")
            return length()
        }

        private fun length(): Long = readVarint().also { if (it !in 0..Int.MAX_VALUE) malformed("length $it") }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < Long.SIZE_BITS) {
                val byte = stream.readByte().toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            malformed("a varint longer than ten bytes")
        }

        private fun Buffer.writeVarint(value: Long) {
            var rest = value
            while (rest and 0x7fL.inv() != 0L) {
                writeByte(((rest and 0x7f) or 0x80).toInt())
                rest = rest ushr 7
            }
            writeByte(rest.toInt())
        }

        private fun malformed(what: String): Nothing =
            throw BackupDecodeException(BackupDecodeException.Reason.MALFORMED, IOException("Malformed frame: $what"))
    }

    companion object {
        private const val MAGIC_GZIP = 0x1f8b
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`

        /** `Backup.backupManga`'s proto number. */
        const val MANGA_FIELD = 1

        /** `Backup.customCovers`'s proto number, Saikou's own (R165 item 3). */
        const val COVERS_FIELD = 5001

        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5

        /** The largest field number protobuf allows is 2^29 - 1, so a tag fits 32 bits. */
        private const val MAX_TAG = 0xffff_ffffL
    }
}

/** What [BackupDecoder.header] reads from a file. */
class BackupHeader(
    /** Every field of the file but its library entries; `backupManga` is empty. */
    val backup: Backup,
    /** How many library entries (field 1 frames) the file holds. */
    val mangaCount: Int,
    /** Every `BackupTracking.syncId` the entries name, distinct, in file order. */
    val trackerIds: List<Long>,
    /** The index, among the field 1 frames, of each entry that does not decode, lowest first. */
    val undecodable: List<Int> = emptyList(),
    /** The file's TachiyomiSY synthetic merged entries, in file order, less their chapters. */
    val syMerged: List<BackupManga> = emptyList(),
)

/** [BackupDecoder.entries]'s library entries. */
typealias BackupEntries = BackupFrames<BackupManga>

/** [BackupDecoder.customCovers]'s custom covers. */
typealias BackupCustomCovers = BackupFrames<BackupCustomCover>

/**
 * Messages of one top-level field of a file, decoded as they are reached. A sequence that can be read
 * once, over a stream that [close] closes, so the caller decides when the file is let go whether or
 * not it read to the end.
 */
class BackupFrames<T> internal constructor(
    private val stream: BufferedSource,
    private val read: (BufferedSource) -> Iterator<T>,
) : Sequence<T>, Closeable {

    private var iterated = false

    override fun iterator(): Iterator<T> {
        check(!iterated) { "A file's entries are read once" }
        iterated = true
        return read(stream)
    }

    override fun close() = stream.close()
}

/** Why a file could not be read. The screen maps [reason] to its own string. */
class BackupDecodeException(
    val reason: Reason,
    cause: Throwable? = null,
) : IOException(reason.name, cause) {

    enum class Reason { JSON_NOT_SUPPORTED, MALFORMED }
}
