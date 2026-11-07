package com.shinsaikou.reader.state

import com.shinsaikou.source.SourceIds
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderStateTest {

    private val first = manga(id = MANGA_ID, source = 1L)
    private val second = manga(id = 20L, source = SourceIds.LOCAL)

    private fun merged(): Triple<ReaderSession, ReaderChapter, ReaderChapter> {
        val a = ReaderChapter(chapter(id = 1, mangaId = first.id))
        val b = ReaderChapter(chapter(id = 2, mangaId = second.id))
        val owners = mapOf(first.id to first, second.id to second)
        return Triple(ReaderSession(first, listOf(a, b), owners), a, b)
    }

    @Test
    fun `currentOwner is the entry of the chapter on screen`() {
        val (session, a, b) = merged()

        readyState(session, a).currentOwner shouldBe first
        readyState(session, b).currentOwner shouldBe second
    }

    @Test
    fun `currentOwner is the manga when no chapter is on screen`() {
        val (session, _, _) = merged()
        val state = ReaderState(manga = first, session = session)

        state.currentChapter shouldBe null
        state.currentOwner shouldBe first
    }

    @Test
    fun `currentOwner is null before the session names a manga`() {
        ReaderState().currentOwner shouldBe null
    }
}
