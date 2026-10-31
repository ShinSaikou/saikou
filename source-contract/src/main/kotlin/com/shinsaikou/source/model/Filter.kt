package com.shinsaikou.source.model

/**
 * A node of a source's filter tree. Mirrors eu.kanade.tachiyomi.source.model.Filter subtype for
 * subtype, with the same state types and defaults, so the adapter can copy state in both
 * directions positionally. Differences from the ABI, all deliberate:
 *  - The subtypes are final here. Extensions subclass the ABI's abstract Select/Text/... to attach
 *    their own request parameters; the host never needs that, it only edits [state]. The adapter
 *    maps our node back onto the extension's own instance, which keeps its subclass.
 *  - Select and Sort carry display strings (List<String>) instead of the ABI's Array<V>; the
 *    adapter renders values with toString(), which is exactly what upstream's filter UI does.
 *  - Group holds List<Filter<*>>; upstream's Group<V> is generic but every real group holds filters.
 *
 * Equality mirrors the ABI: two filters are equal when name and state are equal (subtype ignored).
 */
sealed class Filter<T>(
    /** Label shown next to the control. Also half of the equality contract. */
    val name: String,
    /** The edited value. Mutable because the filter UI writes it in place, exactly as upstream's does. */
    var state: T,
) {

    /** A caption row. State is a constant 0 and never changes (ABI: Filter<Any>(name, 0)). */
    class Header(name: String) : Filter<Any>(name, 0)

    /** A divider row. State is a constant 0. */
    class Separator(name: String = "") : Filter<Any>(name, 0)

    /** A single choice among [values]; [state] is the selected index, default 0. */
    class Select(name: String, val values: List<String>, state: Int = 0) : Filter<Int>(name, state) {
        /** The currently selected display value, or "" when [state] is out of range. */
        val selected: String get() = values.getOrElse(state) { "" }
    }

    /** Free text; default "". */
    class Text(name: String, state: String = "") : Filter<String>(name, state)

    /** A boolean; default false. */
    class CheckBox(name: String, state: Boolean = false) : Filter<Boolean>(name, state)

    /** Ignore / include / exclude; default [STATE_IGNORE]. Codes are the ABI's. */
    class TriState(name: String, state: Int = STATE_IGNORE) : Filter<Int>(name, state) {
        /** Whether the filter is off. */
        fun isIgnored(): Boolean = state == STATE_IGNORE

        /** Whether the filter requires the term. */
        fun isIncluded(): Boolean = state == STATE_INCLUDE

        /** Whether the filter forbids the term. */
        fun isExcluded(): Boolean = state == STATE_EXCLUDE

        companion object {
            /** The filter is off. */
            const val STATE_IGNORE = 0

            /** The term must be present. */
            const val STATE_INCLUDE = 1

            /** The term must be absent. */
            const val STATE_EXCLUDE = 2
        }
    }

    /**
     * A named list of child filters; the children hold the state. The list identity is the state
     * (ABI: Filter<List<V>>). The UI edits the children in place; it never replaces the list.
     */
    class Group(name: String, state: List<Filter<*>>) : Filter<List<Filter<*>>>(name, state)

    /**
     * A sort choice: [values] are the sort keys, [state] the selected key and direction, or null
     * when the source applies its own default ordering.
     */
    class Sort(name: String, val values: List<String>, state: Selection? = null) :
        Filter<Sort.Selection?>(name, state) {
        /** The chosen sort key, by index into [values], and its direction. */
        data class Selection(val index: Int, val ascending: Boolean)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Filter<*>) return false
        return name == other.name && state == other.state
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (state?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "${this::class.simpleName}(name=$name, state=$state)"
}
