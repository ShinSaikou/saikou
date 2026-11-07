package com.shinsaikou.sync

import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.engine.planCategories
import com.shinsaikou.sync.engine.planLibraryManga
import com.shinsaikou.sync.engine.toRecord
import com.shinsaikou.sync.engine.toRow
import com.shinsaikou.sync.format.ChapterRemovedRow
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.ExcludedScanlatorsRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncRow
import java.security.MessageDigest

/**
 * One hash over everything this device syncs (phase 3 unit I, fix round 1): the rows a full export
 * of its library would carry, planned from the database by the export's own mapping
 * ([planLibraryManga], [planCategories], the merged groups' lines) and encoded as the wire encodes
 * them. Every value the wire carries and the merge takes is in it, and a column the wire gains later
 * joins it with no edit here; local ids, latches and anything else the wire leaves out never enter.
 * The one column the wire carries that no merge rule takes, [ChapterRow.sourceOrder], is left out
 * (see [encode]). Two devices that converged give the same [Result.sha256]. [ChapterRemovedRow]
 * never enters: it is a
 * deletion event a segment carries, and an export writes state, so the plan never makes one.
 *
 * The order a device's local ids put rows in does not count (phase 5 unit SC1). Each unordered set a
 * row carries is sorted in its line, and the lines are combined by a sum that no order changes:
 * each line's UTF-8 bytes are hashed with SHA-256, and the 32 bytes are added into a 256-bit
 * accumulator as an unsigned big-endian number, modulo 2^256. Two libraries with the same lines,
 * in any order, reach the same sum, and [Result.sha256] is the SHA-256 of the sum's 32 bytes.
 *
 * Memory is constant in the library's size: the accumulator, one encoded line at a time and the
 * page being read, whatever the count. At 2,000,000 rows (40,000 entries of 50 chapters) the sum is
 * still 32 bytes; the sorted lines this replaced held every line at once, and the joined text once
 * more, which ran out of memory at 10,000 entries on a 384 MB heap (R131 carry-over 1).
 *
 * A sum is safe here because the fingerprint is something the owner compares across their own
 * devices by eye, not a security boundary: nobody chooses the rows to make two libraries collide,
 * and for rows nobody chose, two different sets of lines reach the same sum with probability about
 * 2^-256. No line can cancel another, as with an exclusive or, because the sum counts each line as
 * often as it occurs, and identical lines do not occur anyway: each row carries its natural key.
 *
 * Each page of the library is read in one transaction, as an export reads it, so the device should
 * be idle while it runs. The two-device suite prints it after every step, and M compares the owner's
 * two devices with it after their first real sync.
 */
object SyncDigest {

    /** The SHA-256 of the lines' sum, in lowercase hex, and how many rows went into it. */
    data class Result(val sha256: String, val rows: Int)

    suspend fun of(store: SyncStore): Result = of(store) { it }

    /** [of], with each planned row passed through [rewrite] before it is encoded: SyncDigestTest's seam. */
    internal suspend fun of(store: SyncStore, rewrite: (SyncRow) -> SyncRow): Result {
        val sum = LineSum()
        val categories = mutableMapOf<String, StoredCategory>()
        var afterId = 0L
        while (true) {
            val ids = store.libraryMangaIds(afterId, PAGE)
            val stored = store.readLibrary(ids)
            stored.categories.values.associateByTo(categories) { it.syncKey }
            if (ids.isEmpty()) break
            ids.forEach { id -> planLibraryManga(id, stored)?.forEach { sum.add(encode(rewrite(it))) } }
            afterId = ids.last()
        }
        planCategories(categories.keys, categories.values).forEach { sum.add(encode(rewrite(it))) }
        store.mergedGroups().forEach { sum.add(encode(rewrite(it.toRecord().toRow()))) }
        val bytes = MessageDigest.getInstance("SHA-256").digest(sum.bytes)
        return Result(sha256 = bytes.joinToString("") { "%02x".format(it) }, rows = sum.lines)
    }

    /** The order-free sum of the lines' SHA-256s, modulo 2^256, as 32 unsigned big-endian bytes. */
    private class LineSum {
        private val line = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(32)
        var lines = 0
            private set

        fun add(text: String) {
            val hash = line.digest(text.toByteArray())
            var carry = 0
            for (i in bytes.indices.reversed()) {
                val total = (bytes[i].toInt() and 0xff) + (hash[i].toInt() and 0xff) + carry
                bytes[i] = total.toByte()
                carry = total ushr 8
            }
            // The carry out of the top byte is dropped: the sum is modulo 2^256.
            lines++
        }
    }

    /** [row] as the wire writes it, its unordered sets in order and its unmerged column cleared. */
    private fun encode(row: SyncRow): String {
        val canonical = when (row) {
            is MangaCategoriesRow -> row.copy(categories = row.categories.sorted().toSet())
            is ExcludedScanlatorsRow -> row.copy(scanlators = row.scanlators.sorted().toSet())
            // The sender's place for the chapter in its own last fetch of the list. No merge rule
            // takes it (only an insert does), so two converged devices can hold different values,
            // and it is not a synced value.
            is ChapterRow -> row.copy(sourceOrder = 0)
            else -> row
        }
        return SyncFormat.json.encodeToString(SyncRow.serializer(), canonical)
    }

    /** Manga read per transaction, as the export pages them. */
    private const val PAGE = 20L
}
