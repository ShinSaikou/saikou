package com.shinsaikou.downloads.adoption

import android.content.Context
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.error.ErrorKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AdoptionSummaryStoreTest {

    @TempDir
    lateinit var filesDir: File

    private val summary = AdoptionSummary(
        finishedAt = 5,
        matched = 4,
        copied = 1,
        skipped = 1,
        unmatched = listOf(
            AdoptionUnmatched(
                "downloads/Fixture Unknown (EN)",
                AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED,
                sourceName = "Fixture Unknown",
                sourceLang = "EN",
            ),
            AdoptionUnmatched(
                "downloads/Fixture Source (EN)/Title Elsewhere",
                AdoptionUnmatchedReason.NOT_IN_LIBRARY,
                sourceName = "Fixture Source",
                sourceLang = "EN",
                title = "Title Elsewhere",
            ),
            AdoptionUnmatched(
                "downloads/Fixture Source (EN)/Title/Chapter 9_ffffff",
                AdoptionUnmatchedReason.NO_CHAPTER,
                sourceName = "Fixture Source",
                sourceLang = "EN",
                title = "Title",
                mangaId = 3,
            ),
            AdoptionUnmatched("downloads/Fixture Source (EN)/Title/Chapter_tmp", AdoptionUnmatchedReason.UNFINISHED),
            AdoptionUnmatched("downloads/Fixture Source (EN)/Title/Chapter.cbz", AdoptionUnmatchedReason.DUPLICATE),
            AdoptionUnmatched("downloads/Fixture Bare", AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED, sourceName = "Fixture Bare"),
        ),
        failures = listOf(
            AdoptionFailure("downloads/Fixture Source (EN)/Title/Chapter 1", AdoptionFailureReason.PAGE_COUNT_MISMATCH, detail = "3/2"),
            AdoptionFailure("downloads/Fixture Source (EN)/Title/Chapter 2", AdoptionFailureReason.ERROR, ErrorKey.GENERIC, "IOException"),
        ),
        treeUri = "content://fixture/tree/primary%3AUpstream",
    )

    private fun store(dispatcher: TestDispatcher): AdoptionSummaryStore {
        val context = mockk<Context> { every { filesDir } returns this@AdoptionSummaryStoreTest.filesDir }
        return AdoptionSummaryStore(context, Json, Dispatchers(dispatcher, dispatcher, dispatcher))
    }

    /** Every reason and every field, the tree with them (unit adoption-unmatched-reasons). */
    @Test
    fun `the summary round trips through its file, which lives outside the download root`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        store(dispatcher).replace(summary)

        val reloaded = store(dispatcher)
        reloaded.load()

        reloaded.summary.value shouldBe summary
        reloaded.file shouldBe File(filesDir, "adoption/last-summary.json")
    }

    @Test
    fun `no file and a truncated file both read as no summary`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        store.load()
        store.summary.value.shouldBeNull()

        store.replace(summary)
        store.file.writeText("{\"finishedAt\":")
        val reloaded = store(dispatcher)
        reloaded.load()
        reloaded.summary.value.shouldBeNull()
    }

    @Test
    fun `a reason this build does not know reads as a generic failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """{"finishedAt":1,"matched":1,"copied":0,"skipped":0,"unmatched":[],""" +
                """"failures":[{"path":"a/b","reason":"not_a_reason"}]}""",
        )

        store.load()

        store.summary.value!!.failures.single() shouldBe
            AdoptionFailure("a/b", AdoptionFailureReason.ERROR, ErrorKey.GENERIC, null)
    }

    /** A file from before the reasons were kept lists bare paths and no tree; each path reads as unknown. */
    @Test
    fun `a summary written as a list of paths loads each path with an unknown reason and no tree`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """{"finishedAt":1,"matched":2,"copied":2,"skipped":0,""" +
                """"unmatched":["downloads/Fixture Unknown (EN)","downloads/Fixture Source (EN)/Title"],"failures":[]}""",
        )

        store.load()

        val loaded = store.summary.value!!
        loaded.unmatched shouldBe listOf(
            AdoptionUnmatched("downloads/Fixture Unknown (EN)", AdoptionUnmatchedReason.UNKNOWN),
            AdoptionUnmatched("downloads/Fixture Source (EN)/Title", AdoptionUnmatchedReason.UNKNOWN),
        )
        loaded.treeUri.shouldBeNull()
        loaded.copied shouldBe 2
    }

    @Test
    fun `an unmatched reason this build does not know reads as unknown, its fields kept`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """{"finishedAt":1,"matched":0,"copied":0,"skipped":0,"failures":[],""" +
                """"unmatched":[{"path":"a/b","reason":"not_a_reason","title":"b"}]}""",
        )

        store.load()

        store.summary.value!!.unmatched.single() shouldBe
            AdoptionUnmatched("a/b", AdoptionUnmatchedReason.UNKNOWN, title = "b")
    }
}
