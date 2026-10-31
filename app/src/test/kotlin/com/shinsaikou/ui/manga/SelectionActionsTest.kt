package com.shinsaikou.ui.manga

import com.shinsaikou.ui.chapter.ChapterDownload
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * The entry screen's selection bar shows only the actions that apply (plan 2.6 part F, unit ID3),
 * by upstream's `SharedMangaBottomActionMenu` rules. One test per rule, each with a selection that
 * offers the action and one that does not.
 */
class SelectionActionsTest {

    private val downloaded = setOf(3L)
    private val downloadOf: (Long) -> ChapterDownload = { id ->
        if (id in downloaded) ChapterDownload.Downloaded else ChapterDownload.None
    }

    private fun chapter(id: Long, read: Boolean = false, bookmark: Boolean = false, lastPageRead: Long = 0L) =
        Chapter.create().copy(id = id, read = read, bookmark = bookmark, lastPageRead = lastPageRead)

    private fun actions(vararg selected: Chapter, downloadsOffered: Boolean = true) =
        selectionActionsOf(selected.toList(), downloadOf, downloadsOffered)

    @Test
    fun `bookmark is offered while any selected chapter is not bookmarked`() {
        actions(chapter(1), chapter(2, bookmark = true)).bookmark shouldBe true
        actions(chapter(1, bookmark = true), chapter(2, bookmark = true)).bookmark shouldBe false
    }

    /** Upstream's code: `fastAll { it.chapter.bookmark }`, so a mixed selection offers only Bookmark. */
    @Test
    fun `remove bookmark is offered only while every selected chapter is bookmarked`() {
        actions(chapter(1, bookmark = true), chapter(2, bookmark = true)).removeBookmark shouldBe true
        actions(chapter(1, bookmark = true), chapter(2)).removeBookmark shouldBe false
        actions(chapter(1)).removeBookmark shouldBe false
    }

    @Test
    fun `mark as read is offered while any selected chapter is unread`() {
        actions(chapter(1, read = true), chapter(2)).markRead shouldBe true
        actions(chapter(1, read = true), chapter(2, read = true)).markRead shouldBe false
    }

    /** Upstream's code: read, or partly read (`lastPageRead > 0`), since unread also clears the page. */
    @Test
    fun `mark as unread is offered while any selected chapter is read or partly read`() {
        actions(chapter(1), chapter(2, read = true)).markUnread shouldBe true
        actions(chapter(1), chapter(2, lastPageRead = 4)).markUnread shouldBe true
        actions(chapter(1), chapter(2)).markUnread shouldBe false
    }

    @Test
    fun `mark previous as read is offered with exactly one chapter selected`() {
        actions(chapter(1)).markPreviousRead shouldBe true
        actions(chapter(1, read = true)).markPreviousRead shouldBe true
        actions(chapter(1), chapter(2)).markPreviousRead shouldBe false
    }

    @Test
    fun `download is offered while any selected chapter is not downloaded`() {
        actions(chapter(3), chapter(4)).download shouldBe true
        actions(chapter(3)).download shouldBe false
    }

    @Test
    fun `delete is offered while any selected chapter is downloaded`() {
        actions(chapter(3), chapter(4)).delete shouldBe true
        actions(chapter(4)).delete shouldBe false
    }

    /** A Local entry offers neither download action, whatever the chapters' states. */
    @Test
    fun `a Local entry offers neither download nor delete`() {
        val local = actions(chapter(3), chapter(4), downloadsOffered = false)
        local.download shouldBe false
        local.delete shouldBe false
        // The rest still apply there.
        local.bookmark shouldBe true
        local.markRead shouldBe true
    }

    /** The before state of the rules' absences: a selection that offers every action at once. */
    @Test
    fun `a selection can offer every action but remove bookmark and mark previous together`() {
        actions(chapter(3, read = true), chapter(4)) shouldBe SelectionActions(
            bookmark = true,
            removeBookmark = false,
            markRead = true,
            markUnread = true,
            markPreviousRead = false,
            download = true,
            delete = true,
        )
    }
}
