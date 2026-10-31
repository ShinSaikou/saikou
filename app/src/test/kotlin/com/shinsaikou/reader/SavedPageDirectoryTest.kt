package com.shinsaikou.reader

import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Where a saved page goes (phase 4 unit SR): upstream's `create_folder_per_manga`, off by default,
 * saves into the pictures root, and on into a folder named for the entry, the title made a valid
 * file name as upstream's `saveImage` makes it. The sub-folder is a name since unit
 * saved-pages-gallery, so the gallery's MediaStore path can carry it; a picked tree or the app's
 * folder makes it a directory.
 */
class SavedPageDirectoryTest {

    private val entryFolder = mockk<UniFile>()
    private val root = mockk<UniFile> {
        every { createDirectory(any()) } returns entryFolder
    }

    @Test
    fun `save pages into separate folders is upstream's key and starts off`() {
        val preference = ReaderPreferences(InMemoryPreferenceStore()).folderPerManga

        preference.key() shouldBe "create_folder_per_manga"
        preference.get() shouldBe false
    }

    @Test
    fun `with separate folders off a saved page goes into the pictures root`() {
        val subFolder = savedPageSubFolder("Fixture Title", folderPerManga = false)

        subFolder shouldBe null
        pictureDirectory(root, subFolder) shouldBeSameInstanceAs root
        verify(exactly = 0) { root.createDirectory(any()) }
    }

    @Test
    fun `with separate folders on a saved page goes into a folder named for the entry`() {
        val subFolder = savedPageSubFolder("Fixture: Title?", folderPerManga = true)

        subFolder shouldBe "Fixture_ Title_"
        pictureDirectory(root, subFolder) shouldBeSameInstanceAs entryFolder
        verify(exactly = 1) { root.createDirectory("Fixture_ Title_") }
    }
}
