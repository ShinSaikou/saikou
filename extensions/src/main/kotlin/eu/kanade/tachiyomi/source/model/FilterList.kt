// Vendored from upstream v0.20.4: source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/FilterList.kt (modified: yes) @Stable and its Compose import removed
package eu.kanade.tachiyomi.source.model

data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    override fun equals(other: Any?): Boolean {
        return false
    }

    override fun hashCode(): Int {
        return list.hashCode()
    }
}
