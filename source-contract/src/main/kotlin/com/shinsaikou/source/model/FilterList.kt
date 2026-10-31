package com.shinsaikou.source.model

/**
 * The filter tree of a source. Mirrors the ABI's FilterList including its deliberate equality
 * contract: [equals] always returns false so a UI state holder never mistakes an edited tree for
 * the previous one and skips an update. Compare filters individually if you need equality.
 * (:source-contract has no Compose dependency; the presentation layer wraps a FilterList in its
 * own stable holder rather than annotating this class.)
 */
data class FilterList(
    /** The top-level nodes, in the order the source declared them. */
    val list: List<Filter<*>>,
) : List<Filter<*>> by list {
    /** Builds a list from varargs, the shape extensions use. */
    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    override fun equals(other: Any?): Boolean = false
    override fun hashCode(): Int = list.hashCode()

    /**
     * Structural copy with the same states, used by the UI to keep an "applied" snapshot apart
     * from the tree being edited. Groups are copied recursively.
     */
    fun deepCopy(): FilterList = FilterList(list.map { it.deepCopy() })

    companion object {
        /** No filters: what a source with no filter tree returns and what "source defaults" means. */
        val EMPTY = FilterList()
    }
}

private fun Filter<*>.deepCopy(): Filter<*> = when (this) {
    is Filter.Header -> Filter.Header(name)
    is Filter.Separator -> Filter.Separator(name)
    is Filter.Select -> Filter.Select(name, values, state)
    is Filter.Text -> Filter.Text(name, state)
    is Filter.CheckBox -> Filter.CheckBox(name, state)
    is Filter.TriState -> Filter.TriState(name, state)
    is Filter.Group -> Filter.Group(name, state.map { it.deepCopy() })
    is Filter.Sort -> Filter.Sort(name, values, state)
}
