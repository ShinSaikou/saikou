// Fresh: the vendored engines' tests (phase 4 unit MB) live in their package to reach the protected searches.
package tachiyomi.feature.migration.list.search

import com.shinsaikou.ui.migration.FakeCatalogueSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The matching, pinned (phase 4 unit MB, design g7 final section 19.1): the scores, the 0.4
 * threshold, every result scored, the deep queries and their order, the keywords, the queries sent
 * together, ties and failures. It is upstream's except the queries' dispatcher, the empty filter
 * list, and the three changes the engine's header names: a lone result is scored, the regular search
 * compares lower-cased titles without brackets, and a non-Latin title keeps its letters.
 *
 * A result is a plain title here. Titles hold no capital I, so the deep path's default-locale
 * lower-casing gives the same text on every machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartSearchEngineTest {

    private class TitleEngine(
        dispatcher: CoroutineDispatcher,
        extraSearchParams: String? = null,
        threshold: Double = BaseSmartSearchEngine.MIN_ELIGIBLE_THRESHOLD,
    ) : BaseSmartSearchEngine<String>(extraSearchParams, threshold, dispatcher) {
        override fun getTitle(result: String) = result

        suspend fun regular(title: String, action: SearchAction<String>) = regularSearch(action, title)

        suspend fun deep(title: String, action: SearchAction<String>) = deepSearch(action, title)
    }

    private val dispatcher = StandardTestDispatcher()

    /** A title that scores 0 against any of the pairs below, so the pair's own candidate is scored. */
    private val unrelated = "#"

    /**
     * The candidate is taken just below its score and refused just above it, which pins the score
     * the engine computed to four places through the threshold alone.
     */
    private suspend fun assertScore(deep: Boolean, title: String, candidate: String, expected: Double) {
        listOf(expected - 0.00005 to candidate, expected + 0.00005 to null).forEach { (threshold, wanted) ->
            val engine = TitleEngine(dispatcher, threshold = threshold)
            val action: SearchAction<String> = { listOf(candidate, unrelated) }
            val found = if (deep) engine.deep(title, action) else engine.regular(title, action)
            found shouldBe wanted
        }
    }

    @Test
    fun `Upstream's scores on eight title pairs, pinned to four places`() = runTest(dispatcher) {
        // The regular search compares the titles lower-cased, without brackets or repeated spaces.
        assertScore(deep = false, "Moonlit Harbour", "Moonlit Harbor", 0.9333)
        assertScore(deep = false, "SALT AND EMBER", "Salt and Ember", 1.0)
        assertScore(deep = false, "The Quiet Forest", "Quiet Forest", 0.75)
        assertScore(deep = false, "Paper Crane", "Crane of Paper", 0.2857)
        assertScore(deep = false, "Moonlit  Harbour (Official)", "moonlit harbour", 1.0)
        // The deep search compares the cleaned titles: brackets, punctuation and splitters removed.
        assertScore(deep = true, "Moonlit Harbour (Official)", "Moonlit Harbor", 0.9333)
        assertScore(deep = true, "Crow - Dawn Road", "Crow Dawn Road!", 1.0)
        assertScore(deep = true, "Blue [Colored] Tower", "The Blue Tower", 0.7143)
        // A cleaned title of five characters or fewer keeps its non-ASCII letters.
        assertScore(deep = true, "Café", "Cafe", 0.75)
    }

    @Test
    fun `a result scoring in the 0_4 band wins over lower ones, and a best below 0_4 is no match`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)

        // 0.4286, 0.2857 and 0.
        engine.regular("Salt and Ember") { listOf("Moon Tower", "Salt Harbors", "zzzz") } shouldBe "Salt Harbors"
        // 0.3571 at best.
        engine.regular("Salt and Ember") { listOf("Moon Tower", "Salt Road", "zzzz") } shouldBe null
    }

    @Test
    fun `a lone result is scored like any other, so an unrelated one is no match`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)

        // Upstream took this one at 1.0: one query, one result.
        engine.regular("Salt and Ember") { listOf("zzzz") } shouldBe null
        engine.regular("Salt and Ember") { listOf("SALT AND EMBER") } shouldBe "SALT AND EMBER"
        // "crow dawn road" makes three distinct queries.
        engine.deep("Crow Dawn Road") { listOf("zzzz") } shouldBe null
    }

    @Test
    fun `a wholly non-Latin title is searched by its own letters, where upstream sent an empty query`() =
        runTest(dispatcher) {
            val engine = TitleEngine(dispatcher, extraSearchParams = "extra")
            val queries = mutableListOf<String>()

            val found = engine.deep("月光の港の物語") { query ->
                queries += query
                listOf("anything", "月光の港の物語")
            }

            queries shouldContainExactly listOf("月光の港の物語 extra")
            found shouldBe "月光の港の物語"
        }

    @Test
    fun `the deep queries are the cleaned title and its words, de-duplicated, in upstream's order`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)
        val all = mutableListOf<String>()
        val deduplicated = mutableListOf<String>()

        engine.deep("Crow Dawn Road Tales") { all += it; emptyList() }
        engine.deep("Nova Tales") { deduplicated += it; emptyList() }

        all shouldContainExactly listOf("crow dawn road tales", "tales crow", "tales", "crow dawn", "crow")
        // The first two words are the whole title, which is asked once.
        deduplicated shouldContainExactly listOf("nova tales", "tales nova", "tales", "nova")
    }

    @Test
    fun `the keywords are appended after a space to every query`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher, extraSearchParams = "volume one")
        val queries = mutableListOf<String>()

        engine.deep("Nova Tales") { queries += it; emptyList() }

        queries shouldContainExactly listOf(
            "nova tales volume one",
            "tales nova volume one",
            "tales volume one",
            "nova volume one",
        )
    }

    @Test
    fun `the search asks for page one with the empty filter list`() = runTest(dispatcher) {
        val source = FakeCatalogueSource(id = 7L)
        val engine = SmartSourceSearchEngine(extraSearchParams = null, dispatcher = dispatcher)

        engine.regularSearch(source, "Salt and Ember")

        source.searchCalls.single().page shouldBe 1
        source.searchCalls.single().query shouldBe "Salt and Ember"
        source.searchCalls.single().filters.isEmpty() shouldBe true
    }

    @Test
    fun `the search's winner becomes a row of the searched source`() = runTest(dispatcher) {
        val source = FakeCatalogueSource(id = 7L)
        source.searchResults["Salt and Ember"] = listOf(FakeCatalogueSource.entry("Salt and Ember", url = "/manga/3"))
        val engine = SmartSourceSearchEngine(extraSearchParams = null, dispatcher = dispatcher)

        val found = engine.regularSearch(source, "Salt and Ember")

        found?.source shouldBe 7L
        found?.url shouldBe "/manga/3"
        found?.title shouldBe "Salt and Ember"
    }

    @Test
    fun `the deep queries are in flight together`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)
        val gate = CompletableDeferred<Unit>()
        var started = 0

        val search = async {
            engine.deep("Crow Dawn Road Tales") {
                started++
                gate.await()
                emptyList()
            }
        }
        runCurrent()

        started shouldBe 5
        gate.complete(Unit)
        search.await() shouldBe null
    }

    @Test
    fun `a tie goes to the first result, then to the first query`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)
        // Both score 0.75 against "abcd".
        engine.regular("abcd") { listOf("abce", "abcf") } shouldBe "abce"
        // "nova tales" is asked before "tales nova", and each finds one of two equal titles.
        engine.deep("Nova Tales") { query ->
            when (query) {
                "nova tales" -> listOf("nova tale")
                "tales nova" -> listOf("nova talex")
                else -> emptyList()
            }
        } shouldBe "nova tale"
    }

    @Test
    fun `one failing query fails the whole source search`() = runTest(dispatcher) {
        val engine = TitleEngine(dispatcher)

        shouldThrow<IOException> {
            engine.deep("Nova Tales") { query ->
                if (query == "tales") throw IOException("refused")
                listOf("Nova Tales")
            }
        }
    }
}
