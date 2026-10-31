// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/chapter/interactor/ShouldUpdateDbChapter.kt (modified: yes)
package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter

/** Modified: `volume` joins the comparison, because it is a stored column now. */
class ShouldUpdateDbChapter {

    fun await(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
        return dbChapter.scanlator != sourceChapter.scanlator ||
            dbChapter.name != sourceChapter.name ||
            dbChapter.dateUpload != sourceChapter.dateUpload ||
            dbChapter.chapterNumber != sourceChapter.chapterNumber ||
            dbChapter.sourceOrder != sourceChapter.sourceOrder ||
            dbChapter.volume != sourceChapter.volume ||
            dbChapter.memo != sourceChapter.memo
    }
}
