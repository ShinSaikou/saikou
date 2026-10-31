package com.shinsaikou.reader

import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.ui.manga.savedCoverFileName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Where [SavedPictures] puts a saved page or cover (unit saved-pages-gallery, F6): a picked export
 * tree first, then the gallery's `Pictures/Saikou` through MediaStore from API 29, as upstream uses
 * its own folder under Pictures, and the app's own pictures folder on API 26 to 28, where the public folder
 * would need `WRITE_EXTERNAL_STORAGE`. The MediaStore writes themselves are checked on a device by
 * `SavedPicturesDeviceTest`.
 */
class SavedPicturesTest {

    @Test
    fun `nothing picks an export tree, so a fresh install saves to the gallery from API 29`() {
        val exportDirectory = StoragePreferences(InMemoryPreferenceStore()).exportDirectory

        exportDirectory.get() shouldBe ""
        savedPictureDestination(exportDirectory.get(), 29) shouldBe SavedPictureDestination.GALLERY
        savedPictureDestination(exportDirectory.get(), 26) shouldBe SavedPictureDestination.APP_FOLDER
    }

    @Test
    fun `a picked export tree wins on every API level`() {
        listOf(26, 28, 29, 30, 36).forEach { sdkInt ->
            savedPictureDestination(EXPORT_TREE, sdkInt) shouldBe SavedPictureDestination.EXPORT_TREE
        }
    }

    @Test
    fun `with no tree picked API 29 and later save to the gallery`() {
        listOf(29, 30, 34, 36).forEach { sdkInt ->
            savedPictureDestination("", sdkInt) shouldBe SavedPictureDestination.GALLERY
        }
    }

    @Test
    fun `with no tree picked API 26 to 28 save to the app's own folder`() {
        listOf(26, 27, 28).forEach { sdkInt ->
            savedPictureDestination("", sdkInt) shouldBe SavedPictureDestination.APP_FOLDER
        }
    }

    @Test
    fun `a blank export tree counts as none picked`() {
        savedPictureDestination("  ", 29) shouldBe SavedPictureDestination.GALLERY
        savedPictureDestination("  ", 28) shouldBe SavedPictureDestination.APP_FOLDER
    }

    @Test
    fun `with separate folders off a page goes to Pictures Saikou itself`() {
        galleryRelativePath(isImage = true, savedPageSubFolder("Fixture Title", folderPerManga = false)) shouldBe
            "Pictures/Saikou"
    }

    @Test
    fun `with separate folders on a page goes to a folder named for the entry under Pictures Saikou`() {
        galleryRelativePath(isImage = true, savedPageSubFolder("Fixture: Title?", folderPerManga = true)) shouldBe
            "Pictures/Saikou/Fixture_ Title_"
    }

    @Test
    fun `a mime the gallery does not know goes under Documents Saikou as upstream's does`() {
        galleryRelativePath(isImage = false, subFolder = null) shouldBe "Documents/Saikou"
        galleryRelativePath(isImage = false, subFolder = "Fixture Title") shouldBe "Documents/Saikou/Fixture Title"
    }

    @Test
    fun `a saved page is named for the entry, the chapter and the page, made valid as a whole`() {
        savedPageFileName("Fixture Title", 1.0, "Chapter 1", 3, "png") shouldBe "Fixture Title - 1 - Chapter 1 - 3.png"
        savedPageFileName("Fixture: Title?", 1.5, "Ch. 1/2", 12, "webp") shouldBe
            "Fixture_ Title_ - 1.5 - Ch. 1_2 - 12.webp"
    }

    @Test
    fun `pages of two chapters that share a name get different names`() {
        val first = savedPageFileName("Fixture Title", 10.0, "Extra", 3, "png")
        val second = savedPageFileName("Fixture Title", 20.0, "Extra", 3, "png")

        first shouldBe "Fixture Title - 10 - Extra - 3.png"
        second shouldBe "Fixture Title - 20 - Extra - 3.png"
    }

    /** A source's number is a `Float` the chapter sync widens, so 1.1 arrives as 1.100000023841858. */
    @Test
    fun `a widened chapter number is named as the chapter list shows it`() {
        savedPageFileName("Fixture Title", 1.1f.toDouble(), "Extra", 3, "png") shouldBe
            "Fixture Title - 1.1 - Extra - 3.png"
    }

    @Test
    fun `a chapter with no recognised number is named for its name alone`() {
        savedPageFileName("Fixture Title", null, "Oneshot", 3, "png") shouldBe "Fixture Title - Oneshot - 3.png"
    }

    @Test
    fun `a saved cover is the entry's title as a JPEG`() {
        savedCoverFileName("Fixture Title") shouldBe "Fixture Title.jpg"
        savedCoverFileName("Fixture: Title?") shouldBe "Fixture_ Title_.jpg"
    }

    private companion object {
        const val EXPORT_TREE = "content://com.android.externalstorage.documents/tree/primary%3APictures"
    }
}
