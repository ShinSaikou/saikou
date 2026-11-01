package com.shinsaikou.libraryupdate

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.error.KeyedFailure
import com.shinsaikou.source.SourceNotInstalledException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

class LibraryUpdateErrorStoreTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var store: LibraryUpdateErrorStore

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        store = LibraryUpdateErrorStore(context, Json)
    }

    @Test
    fun `the stored list round trips through the file`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException()), failure(2L, IllegalStateException("nope"))))

        store.errors.value.map { it.mangaId } shouldBe listOf(1L, 2L)
        store.file.exists() shouldBe true

        val reloaded = LibraryUpdateErrorStore(store.context(), Json)
        reloaded.load()

        reloaded.errors.value.map { it.mangaId } shouldBe listOf(1L, 2L)
        reloaded.errors.value.map { it.title } shouldBe listOf("Fixture 1", "Fixture 2")
        reloaded.errors.value.map { it.key } shouldBe listOf(ErrorKey.NO_CHAPTERS, ErrorKey.GENERIC)
        reloaded.errors.value.map { it.detail } shouldBe listOf(null, "nope")
        reloaded.errors.value.map { it.sourceId } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `replace rewrites the file rather than appending to it`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException()), failure(2L, NoChaptersException())))
        store.replace(listOf(failure(3L, NoChaptersException())))

        val reloaded = LibraryUpdateErrorStore(store.context(), Json)
        reloaded.load()

        reloaded.errors.value.map { it.mangaId } shouldBe listOf(3L)
    }

    /**
     * A run replaces only the rows of the entries it checked: a category run or a cancelled one used
     * to replace the whole list, which dropped the failures of every entry it never reached.
     */
    @Test
    fun `merge replaces the rows of checked entries and keeps the others`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException()), failure(2L, NoChaptersException())))

        store.merge(listOf(failure(2L, IllegalStateException("again"))), checked = setOf(2L)) { true }

        store.errors.value.map { it.mangaId to it.key } shouldBe
            listOf(1L to ErrorKey.NO_CHAPTERS, 2L to ErrorKey.GENERIC)
        LibraryUpdateErrorStore(store.context(), Json).also { it.load() }.errors.value.map { it.mangaId } shouldBe
            listOf(1L, 2L)
    }

    @Test
    fun `merge drops the row of an entry that updated fine`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException()), failure(2L, NoChaptersException())))

        store.merge(emptyList(), checked = setOf(1L)) { true }

        store.errors.value.map { it.mangaId } shouldBe listOf(2L)
    }

    @Test
    fun `merge drops a row whose entry left the library`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException()), failure(2L, NoChaptersException())))

        store.merge(emptyList(), checked = emptySet()) { it != 2L }

        store.errors.value.map { it.mangaId } shouldBe listOf(1L)
    }

    /** The worker's process may never have loaded the list, so the rows come from the file. */
    @Test
    fun `merge keeps rows a fresh process has not loaded`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException())))
        val fresh = LibraryUpdateErrorStore(store.context(), Json)

        fresh.merge(listOf(failure(2L, NoChaptersException())), checked = setOf(2L)) { true }

        fresh.errors.value.map { it.mangaId } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `clear empties both the file and the flow`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException())))

        store.clear()

        store.errors.value.shouldBeEmpty()
        store.file.exists() shouldBe false
    }

    @Test
    fun `loading a missing file yields an empty list`() = runTest {
        store.load()

        store.errors.value.shouldBeEmpty()
    }

    @Test
    fun `a truncated file is treated as no errors`() = runTest {
        store.replace(listOf(failure(1L, NoChaptersException())))
        store.file.writeText("[{\"mangaId\":")

        store.load()

        store.errors.value.shouldBeEmpty()
    }

    @Test
    fun `each failure is stored as the key the shared resolver gives it`() = runTest {
        val timeout = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()
        timeout as TimeoutCancellationException

        store.replace(
            listOf(
                failure(1L, NoChaptersException()),
                failure(2L, SourceNotInstalledException(2L)),
                failure(3L, timeout),
                failure(4L, SocketTimeoutException("timeout")),
                failure(5L, IllegalStateException()),
            ),
        )

        store.errors.value.map { it.key } shouldBe listOf(
            ErrorKey.NO_CHAPTERS,
            ErrorKey.SOURCE_NOT_INSTALLED,
            ErrorKey.TIMEOUT,
            ErrorKey.TIMEOUT,
            ErrorKey.GENERIC,
        )
        store.errors.value.map { it.detail } shouldBe listOf(null, null, null, null, "IllegalStateException")
    }

    @Test
    fun `every key and the http status code round trip through the file`() = runTest {
        val budget = CloudflareBudgetExceededException(host = "example.invalid", budget = 45.seconds)
        store.replace(
            listOf(
                failure(1L, IOException("interceptor text", budget)),
                failure(2L, IOException("interceptor text", CloudflareBypassException())),
                failure(3L, UnknownHostException("example.invalid")),
                failure(4L, HttpException(503)),
                failure(5L, IOException("wrapper", HttpException(429))),
                failure(6L, SocketTimeoutException("Read timed out")),
                failure(7L, NoChaptersException()),
                failure(8L, SourceNotInstalledException(8L)),
                failure(9L, PageListEmptyException(9L)),
                failure(10L, IllegalStateException("raw text from a source")),
                failure(11L, SiteStoppedException("example.invalid")),
                failure(12L, IOException("interceptor text", ConnectException("Connection refused"))),
            ),
        )

        val reloaded = LibraryUpdateErrorStore(store.context(), Json)
        reloaded.load()

        reloaded.errors.value.map { it.key } shouldBe listOf(
            ErrorKey.CLOUDFLARE_BUDGET,
            ErrorKey.CLOUDFLARE,
            ErrorKey.NO_NETWORK,
            ErrorKey.HTTP,
            ErrorKey.HTTP,
            ErrorKey.TIMEOUT,
            ErrorKey.NO_CHAPTERS,
            ErrorKey.SOURCE_NOT_INSTALLED,
            ErrorKey.PAGE_LIST_EMPTY,
            ErrorKey.GENERIC,
            ErrorKey.SITE_STOPPED,
            ErrorKey.CONNECTION_REFUSED,
        )
        // Every key an update can meet. Sync's keys (phase 3 unit G), the browser sign-in's (unit O),
        // Drive's (unit H), a tracker's (unit T1), the download queue's folder in use (R104 item 3)
        // and the reader's entry that left (phase 4 unit BF) come from failures the update pass
        // never raises; the sync run record's tests, ErrorTextTest, ErrorKeyTest and
        // DownloadEngineTest cover them.
        reloaded.errors.value.map { it.key }.toSet() shouldBe
            ErrorKey.entries.filterNot { it.name.substringBefore('_') in NOT_UPDATE_KEY_PREFIXES }.toSet()
        reloaded.errors.value.map { it.detail } shouldBe listOf(
            null, null, null, "503", "429", null, null, null, null, "raw text from a source", "example.invalid", null,
        )
        // A named failure is its key: the message of the exception that carried it is not kept.
        store.file.readText() shouldNotContain "interceptor text"
    }

    /**
     * R162 item 8: an unknown failure keeps what the extension said about it, which is often the only
     * thing that says why (a sign-in it needs, a rate limit). It used to keep only the class name.
     */
    @Test
    fun `an unknown failure keeps its message's first line, trimmed`() = runTest {
        store.replace(
            listOf(failure(1L, IllegalStateException("\n  Log in through the browser view first  \nat line 2"))),
        )

        val reloaded = LibraryUpdateErrorStore(store.context(), Json)
        reloaded.load()

        reloaded.errors.value.single().key shouldBe ErrorKey.GENERIC
        reloaded.errors.value.single().detail shouldBe "Log in through the browser view first"
        store.file.readText() shouldNotContain "at line 2"
    }

    @Test
    fun `an unknown failure's long message is cut to the cap`() = runTest {
        store.replace(listOf(failure(1L, IOException("<html>" + "x".repeat(5_000)))))

        val detail = store.errors.value.single().detail!!
        detail.length shouldBe ErrorKey.MESSAGE_MAX_LENGTH
        detail.startsWith("<html>xxx") shouldBe true
        store.file.readText().length shouldBeLessThan 1_000
    }

    @Test
    fun `an unknown failure with no message keeps its class name`() = runTest {
        store.replace(listOf(failure(1L, IllegalStateException()), failure(2L, IllegalArgumentException(" \n "))))

        store.errors.value.map { it.key } shouldBe listOf(ErrorKey.GENERIC, ErrorKey.GENERIC)
        store.errors.value.map { it.detail } shouldBe listOf("IllegalStateException", "IllegalArgumentException")
    }

    /** The rows every build before R162 item 8 wrote for an unknown failure: its class name. */
    @Test
    fun `a generic row stored with a class name still loads with it`() = runTest {
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """[{"mangaId":1,"title":"Fixture 1","sourceId":1,"error":"generic",""" +
                """"detail":"IllegalStateException","at":5}]""",
        )

        store.load()

        store.errors.value.single().key shouldBe ErrorKey.GENERIC
        store.errors.value.single().detail shouldBe "IllegalStateException"
        store.errors.value.single().at shouldBe 5L
    }

    @Test
    fun `a failure that names its own key keeps its own detail`() = runTest {
        store.replace(listOf(failure(1L, Keyed(ErrorKey.WEBDAV_HTTP, "507"))))

        val reloaded = LibraryUpdateErrorStore(store.context(), Json)
        reloaded.load()

        reloaded.errors.value.single().key shouldBe ErrorKey.WEBDAV_HTTP
        reloaded.errors.value.single().detail shouldBe "507"
        store.file.readText() shouldNotContain "keyed text"
    }

    /**
     * The form every build before the key was stored wrote: a `message` holding text resolved at
     * write time, which could be a raw exception message. It still loads, and keeps its text.
     */
    @Test
    fun `a row stored before keys were stored loads with its text`() = runTest {
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """[{"mangaId":1,"title":"Fixture 1","sourceId":1,"message":"No chapters found","at":5},""" +
                """{"mangaId":2,"title":"Fixture 2","sourceId":2,"message":"Read timed out","at":5}]""",
        )

        store.load()

        store.errors.value.map { it.mangaId } shouldBe listOf(1L, 2L)
        store.errors.value.map { it.key } shouldBe listOf(null, null)
        store.errors.value.map { it.detail } shouldBe listOf("No chapters found", "Read timed out")
        store.errors.value.map { it.at } shouldBe listOf(5L, 5L)
    }

    @Test
    fun `a key this build does not know loads as a generic failure`() = runTest {
        store.file.parentFile!!.mkdirs()
        store.file.writeText(
            """[{"mangaId":1,"title":"Fixture 1","sourceId":1,"error":"not_a_key","detail":"X","at":5}]""",
        )

        store.load()

        store.errors.value.single().key shouldBe ErrorKey.GENERIC
        store.errors.value.single().detail shouldBe "X"
    }

    private fun failure(id: Long, error: Throwable) = FlatLibraryUpdate.Failure(
        manga = Manga.create().copy(id = id, source = id, title = "Fixture $id"),
        error = error,
    )

    // Matched only by simple name in ErrorKey, so a plain local subclass stands in for the private one.
    private class CloudflareBypassException : Exception()

    /** A failure that declares its own key and detail, the way sync's and a tracker's do. */
    private class Keyed(
        override val errorKey: ErrorKey,
        override val errorDetail: String?,
    ) : IOException("keyed text"), KeyedFailure

    /** A second store over the same directory, to prove the file and not the flow carries the data. */
    private fun LibraryUpdateErrorStore.context(): Context = mockk<Context>().also {
        every { it.filesDir } returns filesDir
    }

    private companion object {
        /** The key families no library update raises: sync's, WebDAV's, the browser sign-in's and Drive's. */
        val NOT_UPDATE_KEY_PREFIXES = setOf("SYNC", "WEBDAV", "OAUTH", "GOOGLE", "DRIVE", "TRACKER", "DOWNLOAD", "MANGA")
    }
}
