package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.FilterList
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import rx.Observable
import rx.Subscriber
import rx.subscriptions.Subscriptions
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A lib-1.4 extension overrides only the deprecated Observable members. Nothing in this module
 * calls them: the vendored `CatalogueSource` and `HttpSource` defaults route the suspend call to
 * `fetchXxx(...).awaitSingle()`, and that is the path this exercises end to end.
 */
class SourceAdapterRxPathTest {

    private class Lib14Source(
        private val popular: () -> Observable<MangasPage>,
    ) : HttpSource() {
        override val baseUrl: String = "https://example.invalid"
        override val name: String = "Fixture Source A"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        @Deprecated("Use the suspend API instead", ReplaceWith("getPopularManga"))
        override fun fetchPopularManga(page: Int): Observable<MangasPage> = popular()
    }

    private fun adapterFor(source: HttpSource) = SourceAdapters.wrap(source, "com.shinsaikou.fixture", 1.4)

    @Test
    fun `an Observable from a lib-1_4 extension is served through the suspend member`() = runTest {
        val page = MangasPage(
            mangas = listOf(SManga.create().apply { url = "/series/1"; title = "Fixture Series A" }),
            hasNextPage = true,
        )
        val adapter = adapterFor(Lib14Source { Observable.just(page) })

        val result = adapter.getPopularManga(1)

        result.hasNextPage shouldBe true
        result.mangas.single().title shouldBe "Fixture Series A"
    }

    @Test
    fun `an Observable error surfaces as the same exception`() = runTest {
        val boom = IllegalStateException("fixture failure")
        val adapter = adapterFor(Lib14Source { Observable.error(boom) })

        val thrown = shouldThrow<IllegalStateException> { adapter.getPopularManga(1) }

        thrown shouldBe boom
    }

    @Test
    fun `cancelling the caller unsubscribes the Observable`() = runTest {
        val subscribed = CountDownLatch(1)
        val unsubscribed = CountDownLatch(1)
        val never = Observable.unsafeCreate<MangasPage> { subscriber: Subscriber<in MangasPage> ->
            subscriber.add(Subscriptions.create { unsubscribed.countDown() })
            subscribed.countDown()
        }
        val adapter = adapterFor(Lib14Source { never })

        // A real dispatcher, because the assertions below block the test thread.
        val job = async(Dispatchers.Default) { adapter.getPopularManga(1) }
        subscribed.await(5, TimeUnit.SECONDS) shouldBe true
        job.cancelAndJoin()

        // The unsubscribe runs on the IO thread the call was made from, not on the test scheduler.
        unsubscribed.await(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a source with no filter tree reports an empty list`() {
        val adapter = adapterFor(Lib14Source { Observable.empty() })

        adapter.getFilterList() shouldBe FilterList.EMPTY
        AbiFilterList().size shouldBe 0
    }
}
