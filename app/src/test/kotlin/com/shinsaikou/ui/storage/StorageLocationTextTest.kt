package com.shinsaikou.ui.storage

import android.content.res.Resources
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.i18n.R
import com.shinsaikou.storage.StorageGrants
import com.shinsaikou.storage.StorageMove
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The Storage location row's summary in each state, the confirm dialog's sentence, and which
 * persisted grant a stored folder falls under (unit storage-location); the sentence for three counts
 * and for a move from the previous location, and the free-space line (unit
 * storage-move-between-folders).
 */
class StorageLocationTextTest {

    private val resources = mockk<Resources> {
        every { getQuantityString(R.plurals.storage_move_backups, any(), any()) } answers { "${thirdArg<Array<Any>>()[0]} backups" }
        every { getQuantityString(R.plurals.storage_move_local_series, any(), any()) } answers { "${thirdArg<Array<Any>>()[0]} Local series" }
        every { getQuantityString(R.plurals.storage_move_exported_chapters, any(), any()) } answers {
            "${thirdArg<Array<Any>>()[0]} exported chapters"
        }
        every { getString(R.string.storage_move_join_pair_format, any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} and ${args[1]}"
        }
        every { getString(R.string.storage_move_join_comma_format, any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]}, ${args[1]}"
        }
        every { getString(R.string.storage_move_from_format, any(), any(), any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} will be moved from ${args[1]} to ${args[2]} (${args[3]})"
        }
        every { getString(R.string.storage_move_no_space_format, any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} does not have ${args[1]} free"
        }
        every { getString(R.string.storage_move_one_format, any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} will be moved to ${args[1]}"
        }
        every { getString(R.string.pref_storage_location_not_set) } returns "kept inside the app"
        every { getString(R.string.pref_storage_location_unreachable_format, any()) } answers {
            "${secondArg<Array<Any>>()[0]} cannot be reached"
        }
    }

    @Test
    fun `the dialog names both counts, or the one there is, and the folder`() {
        moveSentence(resources, StorageMove.Pending(4, 12), "Saikou") shouldBe "4 backups and 12 Local series will be moved to Saikou"
        moveSentence(resources, StorageMove.Pending(4, 0), "Saikou") shouldBe "4 backups will be moved to Saikou"
        moveSentence(resources, StorageMove.Pending(0, 1), "Saikou") shouldBe "1 Local series will be moved to Saikou"
    }

    @Test
    fun `three counts are joined with a comma and and, and a move from the previous location names both folders and the size`() {
        val pending = StorageMove.Pending(4, 12, exportedChapters = 3100, bytes = 13_314_398_618, fromOldLocation = true)

        moveSentence(resources, pending, "SD card") shouldBe
            "4 backups, 12 Local series and 3100 exported chapters will be moved to SD card"
        moveSentence(resources, pending, "SD card", from = "Saikou", size = ::gigabytes) shouldBe
            "4 backups, 12 Local series and 3100 exported chapters will be moved from Saikou to SD card (12.4 GB)"
        moveSentence(resources, StorageMove.Pending(0, 0, exportedChapters = 1, bytes = 5L shl 30), "SD card", "Saikou", ::gigabytes) shouldBe
            "1 exported chapters will be moved from Saikou to SD card (5.0 GB)"
    }

    @Test
    fun `the dialog turns Move off and says so when the new folder lacks the room the copy needs`() {
        val pending = StorageMove.Pending(0, 0, exportedChapters = 10, bytes = 4L shl 30, fromOldLocation = true)
        fun choice(free: Long?, needed: Long = 4L shl 30) =
            StorageLocationViewModel.Choice("file:/card", "SD card", pending, from = "Saikou", needed = needed, free = free)

        val short = moveDialog(resources, choice(free = 1L shl 30), ::gigabytes)
        short.canMove shouldBe false
        short.text shouldBe "10 exported chapters will be moved from Saikou to SD card (4.0 GB)\n\nSD card does not have 4.0 GB free"

        moveDialog(resources, choice(free = 4L shl 30), ::gigabytes).canMove shouldBe true
        // Free space that cannot be read skips the check, and a move on one storage needs none.
        moveDialog(resources, choice(free = null), ::gigabytes).canMove shouldBe true
        moveDialog(resources, choice(free = 0, needed = 0), ::gigabytes).canMove shouldBe true
    }

    private fun gigabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())

    @Test
    fun `the row names the folder, says when it cannot be reached, and says what is at stake when none is set`() {
        storageLocationSummary(resources, StorageLocation.Status.REACHABLE, "Saikou") shouldBe "Saikou"
        storageLocationSummary(resources, StorageLocation.Status.UNREACHABLE, "Saikou") shouldBe "Saikou cannot be reached"
        storageLocationSummary(resources, StorageLocation.Status.NOT_SET, null) shouldBe "kept inside the app"
        storageLocationSummary(resources, null, null) shouldBe "kept inside the app"
    }

    @Test
    fun `a grant on a tree covers its documents and no sibling tree`() {
        val grant = "content://com.android.externalstorage.documents/tree/primary%3ASaikou"
        StorageGrants.grantCovers(grant, "$grant/document/primary%3ASaikou") shouldBe true
        StorageGrants.grantCovers(grant, grant) shouldBe true
        StorageGrants.grantCovers(grant, "${grant}2/document/primary%3ASaikou2") shouldBe false
    }
}
