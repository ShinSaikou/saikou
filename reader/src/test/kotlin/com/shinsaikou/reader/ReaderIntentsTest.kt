package com.shinsaikou.reader

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The extra names and the saved-state keys are two name sets that must not drift, and `readArgs` is
 * the one place they meet.
 *
 * `Intent` is mocked rather than constructed: a real one needs the framework even with
 * `unitTests.isReturnDefaultValues`, and what is under test is the mapping, not `Intent` itself.
 */
class ReaderIntentsTest {

    @Test
    fun `every extra lands on its saved-state key`() {
        val handle = SavedStateHandle()
        ReaderIntents.readArgs(
            handle,
            intentOf(
                ReaderIntents.EXTRA_MANGA_ID to 7L,
                ReaderIntents.EXTRA_CHAPTER_ID to 42L,
                ReaderIntents.EXTRA_PAGE to 3,
                ReaderIntents.EXTRA_BENCH_TAG to "open",
            ),
        )

        handle.get<Long>(ReaderIntents.KEY_MANGA_ID) shouldBe 7L
        handle.get<Long>(ReaderIntents.KEY_CHAPTER_ID) shouldBe 42L
        handle.get<Long>(ReaderIntents.KEY_START_CHAPTER_ID) shouldBe 42L
        handle.get<Int>(ReaderIntents.KEY_PAGE) shouldBe 3
        handle.get<String>(ReaderIntents.KEY_BENCH_TAG) shouldBe "open"
    }

    @Test
    fun `an absent page extra means resume, not page zero`() {
        val handle = SavedStateHandle()
        ReaderIntents.readArgs(
            handle,
            intentOf(ReaderIntents.EXTRA_MANGA_ID to 1L, ReaderIntents.EXTRA_CHAPTER_ID to 2L),
        )

        handle.contains(ReaderIntents.KEY_PAGE) shouldBe false
        handle.get<Int>(ReaderIntents.KEY_PAGE) shouldBe null
    }

    @Test
    fun `the absent bench extra leaves no key behind`() {
        val handle = SavedStateHandle()
        ReaderIntents.readArgs(
            handle,
            intentOf(ReaderIntents.EXTRA_MANGA_ID to 1L, ReaderIntents.EXTRA_CHAPTER_ID to 2L),
        )

        handle.contains(ReaderIntents.KEY_BENCH_TAG) shouldBe false
    }

    @Test
    fun `a restored value wins over the intent that started the activity`() {
        val handle = SavedStateHandle(mapOf(ReaderIntents.KEY_PAGE to 11))
        ReaderIntents.readArgs(
            handle,
            intentOf(
                ReaderIntents.EXTRA_MANGA_ID to 1L,
                ReaderIntents.EXTRA_CHAPTER_ID to 2L,
                ReaderIntents.EXTRA_PAGE to 0,
            ),
        )

        handle.get<Int>(ReaderIntents.KEY_PAGE) shouldBe 11
    }

    @Test
    fun `a restored handle keeps the chapter the reader moved to and the one it was started on`() {
        val handle = SavedStateHandle(
            mapOf(ReaderIntents.KEY_CHAPTER_ID to 5L, ReaderIntents.KEY_START_CHAPTER_ID to 2L),
        )
        ReaderIntents.readArgs(
            handle,
            intentOf(ReaderIntents.EXTRA_MANGA_ID to 1L, ReaderIntents.EXTRA_CHAPTER_ID to 2L),
        )

        handle.get<Long>(ReaderIntents.KEY_CHAPTER_ID) shouldBe 5L
        handle.get<Long>(ReaderIntents.KEY_START_CHAPTER_ID) shouldBe 2L
    }

    @Test
    fun `the two key sets are distinct and neither is empty`() {
        val extras = listOf(
            ReaderIntents.EXTRA_MANGA_ID,
            ReaderIntents.EXTRA_CHAPTER_ID,
            ReaderIntents.EXTRA_PAGE,
            ReaderIntents.EXTRA_BENCH_TAG,
        )
        val keys = listOf(
            ReaderIntents.KEY_MANGA_ID,
            ReaderIntents.KEY_CHAPTER_ID,
            ReaderIntents.KEY_START_CHAPTER_ID,
            ReaderIntents.KEY_PAGE,
            ReaderIntents.KEY_BENCH_TAG,
        )
        extras.distinct().size shouldBe extras.size
        keys.distinct().size shouldBe keys.size
        extras.intersect(keys.toSet()).isEmpty() shouldBe true
    }

    /** Only the four getters `readArgs` calls are stubbed; anything else is a test bug, not a default. */
    private fun intentOf(vararg extras: Pair<String, Any>): Intent {
        val values = extras.toMap()
        return mockk<Intent> {
            every { hasExtra(any()) } answers { firstArg<String>() in values }
            every { getLongExtra(any(), any()) } answers { values[firstArg<String>()] as? Long ?: secondArg() }
            every { getIntExtra(any(), any()) } answers { values[firstArg<String>()] as? Int ?: secondArg() }
            every { getStringExtra(any()) } answers { values[firstArg<String>()] as? String }
        }
    }
}
