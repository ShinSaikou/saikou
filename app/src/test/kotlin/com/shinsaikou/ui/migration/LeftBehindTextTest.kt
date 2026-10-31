package com.shinsaikou.ui.migration

import android.content.res.Resources
import com.shinsaikou.i18n.R
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The question after a Migrate that left downloaded chapters on this device (units migrate-downloads
 * and migrate-kept-downloads): "could not be moved" when the downloads were carried, "stay on this
 * device" when Downloaded chapters was unticked, for the dialog and for the migration list's snackbar.
 */
class LeftBehindTextTest {

    private val resources = mockk<Resources> {
        every { getQuantityString(R.plurals.migration_downloads_not_moved, any(), any()) } answers {
            "${thirdArg<Array<Any>>()[0]} could not be moved to the new entry"
        }
        every { getQuantityString(R.plurals.migration_downloads_not_moved_batch, any(), any()) } answers {
            "${thirdArg<Array<Any>>()[0]} could not be moved to their new entries"
        }
        every { getQuantityString(R.plurals.migration_downloads_kept, any(), any()) } answers {
            "${thirdArg<Array<Any>>()[0]} of the old entry stay on this device"
        }
        every { getQuantityString(R.plurals.migration_downloads_kept_batch, any(), any()) } answers {
            "${thirdArg<Array<Any>>()[0]} of the old entries stay on this device"
        }
    }

    @Test
    fun `the dialog says the chapters stay when nothing was carried, and could not be moved when they were`() {
        leftBehindText(resources, 3, carried = false, batch = false) shouldBe "3 of the old entry stay on this device"
        leftBehindText(resources, 3, carried = true, batch = false) shouldBe "3 could not be moved to the new entry"
    }

    @Test
    fun `the list's snackbar says the same over several entries`() {
        leftBehindText(resources, 12, carried = false, batch = true) shouldBe "12 of the old entries stay on this device"
        leftBehindText(resources, 12, carried = true, batch = true) shouldBe "12 could not be moved to their new entries"
    }
}
