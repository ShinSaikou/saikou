// Fresh: exercises the vendored ShouldUpdateDbChapter now that the volume parser writes a volume (phase 4 unit VG).
package tachiyomi.domain.chapter.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * `volume` in the comparison (phase 4 unit VG): once the parser writes one, a source refresh that
 * reads a new volume, a changed one or none updates the row, and the same one does not.
 */
class ShouldUpdateDbChapterTest {

    private val shouldUpdate = ShouldUpdateDbChapter()

    private val db = Chapter.create().copy(id = 1L, url = "/1", name = "Vol.1 Ch.1", chapterNumber = 1.0, volume = null)

    @Test
    fun `a volume the stored row does not have is an update`() {
        shouldUpdate.await(db, db.copy(volume = 1.0)) shouldBe true
    }

    @Test
    fun `a changed volume and a lost one are updates`() {
        shouldUpdate.await(db.copy(volume = 1.0), db.copy(volume = 2.0)) shouldBe true
        shouldUpdate.await(db.copy(volume = 1.0), db.copy(volume = null)) shouldBe true
    }

    @Test
    fun `the same volume, volume 0 included, is not an update`() {
        shouldUpdate.await(db.copy(volume = 1.0), db.copy(volume = 1.0)) shouldBe false
        shouldUpdate.await(db.copy(volume = 0.0), db.copy(volume = 0.0)) shouldBe false
        shouldUpdate.await(db, db) shouldBe false
        shouldUpdate.await(db, db.copy(volume = 0.0)) shouldBe true
    }
}
