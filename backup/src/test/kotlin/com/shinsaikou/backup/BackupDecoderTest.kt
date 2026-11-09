package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.BackupDecodeException
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.Test

/**
 * The sniffing half of the decoder, which is what makes a file written by a third-party sync
 * transport (raw protobuf under the same extension) readable at all, and the streaming half
 * (phase 5 unit SC2): [BackupDecoder.header] and [BackupDecoder.entries] read the file frame by
 * frame, whatever order its fields come in, as every protobuf reader does.
 */
class BackupDecoderTest {

    private val decoder = BackupDecoder(mockk(), ProtoBuf)
    private val backup = SyntheticBackupFixture.backup(mangaCount = 3, chaptersPerManga = 2)

    @Test
    fun `gzipped bytes decode`() {
        val decoded = decoder.decode(Buffer().write(SyntheticBackupFixture.tachibk(backup)))

        decoded.backupManga.size shouldBe backup.backupManga.size
        decoded.backupManga.first().url shouldBe backup.backupManga.first().url
    }

    @Test
    fun `raw uncompressed bytes decode`() {
        val decoded = decoder.decode(Buffer().write(SyntheticBackupFixture.tachibk(backup, gzip = false)))

        decoded.backupManga.size shouldBe backup.backupManga.size
    }

    @Test
    fun `a JSON backup from an old release is rejected by reason`() {
        listOf("{}", """{"a":1}""", "{\n  \"a\": 1\n}").forEach { json ->
            val thrown = shouldThrow<BackupDecodeException> {
                decoder.decode(Buffer().write(json.encodeToByteArray()))
            }
            thrown.reason shouldBe BackupDecodeException.Reason.JSON_NOT_SUPPORTED
        }
    }

    @Test
    fun `random bytes are malformed`() {
        val garbage = ByteArray(64) { (it * 31 + 7).toByte() }

        val thrown = shouldThrow<BackupDecodeException> { decoder.decode(Buffer().write(garbage)) }

        thrown.reason shouldBe BackupDecodeException.Reason.MALFORMED
    }

    @Test
    fun `a field a later release added is ignored rather than failing the file`() {
        val extra = BackupWithExtra(
            backupManga = backup.backupManga,
            backupCategories = backup.backupCategories,
            futureField = "written by a release that does not exist yet",
        )
        val bytes = ProtoBuf.encodeToByteArray(BackupWithExtra.serializer(), extra)

        val decoded = decoder.decode(Buffer().write(bytes))

        decoded.backupManga.size shouldBe backup.backupManga.size
    }

    @Test
    fun `the header and the entries read a file whose entries sit before and after the other fields`() {
        listOf(true, false).forEach { gzip ->
            val bytes = interleaved(gzip)

            val header = decoder.header(Buffer().write(bytes))
            header.backup.backupManga.shouldBeEmpty()
            header.mangaCount shouldBe backup.backupManga.size
            // The models are not data classes, so the fields are compared as the bytes they encode to.
            encode(header.backup).toList() shouldBe encode(backup.copy(backupManga = emptyList())).toList()

            val urls = decoder.entries(Buffer().write(bytes)).use { entries -> entries.map { it.url }.toList() }
            urls shouldContainExactly backup.backupManga.map { it.url }

            // The old path reads the same file whole, merging the repeated field across the message.
            val whole = decoder.decode(Buffer().write(bytes))
            whole.backupManga.map { it.url } shouldContainExactly backup.backupManga.map { it.url }
            encode(whole).toList() shouldBe encode(backup).toList()
        }
    }

    @Test
    fun `the header names every tracker id the entries carry, as the whole file does`() {
        val many = SyntheticBackupFixture.backup(mangaCount = 40, chaptersPerManga = 1)

        val header = decoder.header(Buffer().write(SyntheticBackupFixture.tachibk(many)))

        header.trackerIds shouldContainExactly many.backupManga.flatMap { it.tracking }.map { it.syncId.toLong() }.distinct()
        header.trackerIds.contains(SyntheticBackupFixture.UNKNOWN_TRACKER) shouldBe true
    }

    @Test
    fun `entries skip the first frames in file order without decoding them`() {
        val bytes = raw(
            frame(1, ByteArray(0)), // no source and no url: an entry that cannot decode
            encode(Backup(emptyList(), backupCategories = backup.backupCategories)),
            encode(Backup(backupManga = backup.backupManga)),
        )

        val urls = decoder.entries(Buffer().write(bytes), skip = 2).use { entries -> entries.map { it.url }.toList() }

        // Frame 0 was passed over undecoded, and frame 1, the first entry, skipped; the category
        // field between them counts for nothing.
        urls shouldContainExactly backup.backupManga.drop(1).map { it.url }
    }

    /**
     * An entry whose frame is whole but whose bytes do not decode (one missing its required fields,
     * one whose nested chapter is cut short) is listed by the header wherever it sits, and the
     * entries that pass over it read every other entry, in order, with the frame count unchanged.
     */
    @Test
    fun `an entry that does not decode is listed by the header and passed over by the entries`() {
        val missingFields = frame(1, ByteArray(0))
        val brokenChapter = frame(
            1,
            raw(
                ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup.backupManga[0]),
                // One more chapter whose url says five bytes and holds one.
                frame(16, byteArrayOf(0x0a, 0x05, 0x41)),
            ),
        )
        val urls = backup.backupManga.map { it.url }
        listOf(missingFields, brokenChapter).forEach { broken ->
            val first = raw(broken, encode(Backup(backupManga = backup.backupManga)))
            val middle = raw(
                encode(Backup(backupManga = backup.backupManga.take(2))),
                broken,
                encode(Backup(backupManga = backup.backupManga.drop(2), backupCategories = backup.backupCategories)),
            )
            val last = raw(encode(backup), broken)
            listOf(first to 0, middle to 2, last to 3, gzip(middle) to 2).forEach { (bytes, index) ->
                val header = decoder.header(Buffer().write(bytes))
                header.mangaCount shouldBe urls.size + 1
                header.undecodable shouldContainExactly listOf(index)

                decoder.entries(Buffer().write(bytes), passOverUndecodable = true).use { entries ->
                    entries.map { it.url }.toList() shouldContainExactly urls
                }
            }
        }
    }

    @Test
    fun `a file none of whose entries decode is refused by the header`() {
        val bytes = raw(frame(1, ByteArray(0)), encode(Backup(emptyList(), backupCategories = backup.backupCategories)))

        shouldThrow<BackupDecodeException> { decoder.header(Buffer().write(bytes)) }
            .reason shouldBe BackupDecodeException.Reason.MALFORMED
    }

    @Test
    fun `an entry that does not decode throws malformed where the sequence reaches it`() {
        val bytes = raw(encode(Backup(backupManga = backup.backupManga.take(2))), frame(1, ByteArray(0)))
        val read = mutableListOf<String>()

        val thrown = shouldThrow<BackupDecodeException> {
            decoder.entries(Buffer().write(bytes)).use { entries -> entries.forEach { read += it.url } }
        }

        thrown.reason shouldBe BackupDecodeException.Reason.MALFORMED
        read shouldContainExactly backup.backupManga.take(2).map { it.url }
    }

    @Test
    fun `a file cut inside a frame is malformed for the header and for the entries`() {
        val whole = SyntheticBackupFixture.tachibk(backup, gzip = false)
        // Inside the last entry's bytes, after the first two entries.
        val cut = whole.copyOf(encode(Backup(backupManga = backup.backupManga.take(2))).size + 5)
        listOf(cut, gzip(cut)).forEach { bytes ->
            shouldThrow<BackupDecodeException> { decoder.header(Buffer().write(bytes)) }
                .reason shouldBe BackupDecodeException.Reason.MALFORMED

            val read = mutableListOf<String>()
            shouldThrow<BackupDecodeException> {
                decoder.entries(Buffer().write(bytes)).use { entries -> entries.forEach { read += it.url } }
            }.reason shouldBe BackupDecodeException.Reason.MALFORMED
            read shouldContainExactly backup.backupManga.take(2).map { it.url }
        }
    }

    @Test
    fun `a custom cover frame of the wrong wire type is refused by the header`() {
        // Field 5001 as a varint: the header refuses it before a restore writes anything, rather than
        // the covers pass failing after the entries are in.
        val cover = Buffer().also {
            varint(it, (5001L shl 3) or 0L)
            varint(it, 7L)
        }.readByteArray()
        listOf(raw(encode(backup), cover), gzip(raw(encode(backup), cover))).forEach { bytes ->
            shouldThrow<BackupDecodeException> { decoder.header(Buffer().write(bytes)) }
                .reason shouldBe BackupDecodeException.Reason.MALFORMED
        }
    }

    @Test
    fun `random bytes and a JSON file are refused by the header and the entries by reason`() {
        val garbage = ByteArray(64) { (it * 31 + 7).toByte() }
        shouldThrow<BackupDecodeException> { decoder.header(Buffer().write(garbage)) }
            .reason shouldBe BackupDecodeException.Reason.MALFORMED
        shouldThrow<BackupDecodeException> { decoder.entries(Buffer().write(garbage)).use { it.toList() } }
            .reason shouldBe BackupDecodeException.Reason.MALFORMED

        val json = """{"a":1}""".encodeToByteArray()
        shouldThrow<BackupDecodeException> { decoder.header(Buffer().write(json)) }
            .reason shouldBe BackupDecodeException.Reason.JSON_NOT_SUPPORTED
        shouldThrow<BackupDecodeException> { decoder.entries(Buffer().write(json)) }
            .reason shouldBe BackupDecodeException.Reason.JSON_NOT_SUPPORTED
    }

    @Test
    fun `a field a later release added is ignored by the header`() {
        val extra = BackupWithExtra(
            backupManga = backup.backupManga,
            backupCategories = backup.backupCategories,
            futureField = "written by a release that does not exist yet",
        )
        val bytes = ProtoBuf.encodeToByteArray(BackupWithExtra.serializer(), extra)

        val header = decoder.header(Buffer().write(bytes))

        header.mangaCount shouldBe backup.backupManga.size
        encode(header.backup).toList() shouldBe encode(Backup(emptyList(), backupCategories = backup.backupCategories)).toList()
        decoder.entries(Buffer().write(bytes)).use { it.count() } shouldBe backup.backupManga.size
    }

    @Test
    fun `the entries are read once`() {
        decoder.entries(Buffer().write(SyntheticBackupFixture.tachibk(backup))).use { entries ->
            entries.count() shouldBe backup.backupManga.size
            shouldThrow<IllegalStateException> { entries.count() }
        }
    }

    /**
     * Categories, then the first entry, then the sources, then the rest of the entries, then the
     * remaining fields: every repeated field split or moved, as a writer other than upstream's may.
     */
    private fun interleaved(gzip: Boolean): ByteArray {
        val bytes = raw(
            encode(Backup(emptyList(), backupCategories = backup.backupCategories)),
            encode(Backup(backupManga = backup.backupManga.take(1))),
            encode(Backup(emptyList(), backupSources = backup.backupSources)),
            encode(Backup(backupManga = backup.backupManga.drop(1))),
            encode(
                Backup(
                    emptyList(),
                    backupPreferences = backup.backupPreferences,
                    backupSourcePreferences = backup.backupSourcePreferences,
                    backupExtensionStores = backup.backupExtensionStores,
                ),
            ),
        )
        return if (gzip) gzip(bytes) else bytes
    }

    private fun encode(backup: Backup): ByteArray = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

    private fun raw(vararg parts: ByteArray): ByteArray = parts.fold(Buffer()) { buffer, part -> buffer.write(part) }.readByteArray()

    private fun gzip(bytes: ByteArray): ByteArray {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.write(bytes) }
        return compressed.readByteArray()
    }

    /** One length-delimited top-level frame. */
    private fun frame(field: Int, payload: ByteArray): ByteArray {
        val buffer = Buffer()
        varint(buffer, (field.toLong() shl 3) or 2L)
        varint(buffer, payload.size.toLong())
        return buffer.write(payload).readByteArray()
    }

    private fun varint(buffer: Buffer, value: Long) {
        var rest = value
        while (rest >= 0x80) {
            buffer.writeByte(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        buffer.writeByte(rest.toInt())
    }

    /** Shares numbers 1 and 2 with [Backup] and adds one the vendored model has never seen. */
    @Serializable
    private data class BackupWithExtra(
        @ProtoNumber(1) val backupManga: List<BackupManga>,
        @ProtoNumber(2) val backupCategories: List<eu.kanade.tachiyomi.data.backup.models.BackupCategory>,
        @ProtoNumber(200) val futureField: String,
    )
}
