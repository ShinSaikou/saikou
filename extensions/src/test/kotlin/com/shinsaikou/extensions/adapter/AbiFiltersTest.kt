package com.shinsaikou.extensions.adapter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import com.shinsaikou.source.model.Filter as ContractFilter
import com.shinsaikou.source.model.FilterList as ContractFilterList
import eu.kanade.tachiyomi.source.model.Filter as AbiFilter
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

/**
 * Extensions subclass the ABI's abstract filters and read their own request parameters off the
 * subclass, so the important property is that applying user state keeps the extension's instances
 * rather than replacing them with ours.
 */
class AbiFiltersTest {

    /** Stands in for an extension's own Select subclass carrying a request parameter. */
    private class QuerySelect(name: String, values: Array<Pair<String, String>>) :
        AbiFilter.Select<Pair<String, String>>(name, values) {
        val parameter: String get() = values[state].second
    }

    private class Keyword(name: String) : AbiFilter.Text(name)
    private class Completed(name: String) : AbiFilter.CheckBox(name)
    private class Genre(name: String) : AbiFilter.TriState(name)
    private class GenreGroup(name: String, genres: List<Genre>) : AbiFilter.Group<Genre>(name, genres)
    private class Order(name: String, values: Array<String>) : AbiFilter.Sort(name, values)

    private fun abiTree() = AbiFilterList(
        AbiFilter.Header("Filters"),
        AbiFilter.Separator(),
        QuerySelect("Type", arrayOf("Any" to "", "Manga" to "m")),
        Keyword("Keyword"),
        Completed("Completed"),
        GenreGroup("Genres", listOf(Genre("Action"), Genre("Drama"))),
        Order("Order", arrayOf("Title", "Date")),
    )

    @Test
    fun `every subtype maps to its contract node`() {
        val contract = abiTree().toContract()

        contract.size shouldBe 7
        contract[0].shouldBeInstanceOf<ContractFilter.Header>()
        contract[1].shouldBeInstanceOf<ContractFilter.Separator>()
        val select = contract[2].shouldBeInstanceOf<ContractFilter.Select>()
        select.values shouldBe listOf("(Any, )", "(Manga, m)")
        contract[3].shouldBeInstanceOf<ContractFilter.Text>()
        contract[4].shouldBeInstanceOf<ContractFilter.CheckBox>()
        val group = contract[5].shouldBeInstanceOf<ContractFilter.Group>()
        group.state.size shouldBe 2
        val sort = contract[6].shouldBeInstanceOf<ContractFilter.Sort>()
        sort.values shouldBe listOf("Title", "Date")
    }

    @Test
    fun `an empty list means source defaults and never touches the delegate`() {
        val cache = AbiFilterCache()
        val delegate = FakeSource { error("getFilterList must not be called") }

        ContractFilterList.EMPTY.toAbi(delegate, cache).size shouldBe 0
    }

    @Test
    fun `state is applied onto the extension's own instances`() {
        val cache = AbiFilterCache().apply { lastAbiFilters = abiTree() }
        val delegate = FakeSource { error("the cached tree should be reused") }
        val edited = cache.lastAbiFilters!!.toContract().also { list ->
            (list[2] as ContractFilter.Select).state = 1
            (list[3] as ContractFilter.Text).state = "fixture"
            (list[4] as ContractFilter.CheckBox).state = true
            ((list[5] as ContractFilter.Group).state[1] as ContractFilter.TriState).state =
                ContractFilter.TriState.STATE_EXCLUDE
            (list[6] as ContractFilter.Sort).state = ContractFilter.Sort.Selection(1, ascending = false)
        }

        val applied = edited.toAbi(delegate, cache)

        applied shouldBeSameInstanceAs cache.lastAbiFilters
        val select = applied[2] as QuerySelect
        select.state shouldBe 1
        select.parameter shouldBe "m"
        (applied[3] as Keyword).state shouldBe "fixture"
        (applied[4] as Completed).state shouldBe true
        ((applied[5] as GenreGroup).state[1]).state shouldBe ContractFilter.TriState.STATE_EXCLUDE
        (applied[6] as Order).state shouldBe AbiFilter.Sort.Selection(1, false)
    }

    @Test
    fun `a shape mismatch falls back to the extension's defaults`() {
        val cache = AbiFilterCache().apply { lastAbiFilters = abiTree() }
        var refreshes = 0
        val fresh = abiTree()
        val delegate = FakeSource {
            refreshes++
            fresh
        }
        val mismatched = ContractFilterList(ContractFilter.Text("Keyword", "fixture"))

        val applied = mismatched.toAbi(delegate, cache)

        refreshes shouldBe 1
        applied shouldBeSameInstanceAs fresh
        (applied[3] as Keyword).state shouldBe ""
        cache.lastAbiFilters shouldBe null
    }
}
