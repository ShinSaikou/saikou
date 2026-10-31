// Vendored from upstream v0.20.4: source-local/src/main/kotlin/tachiyomi/source/local/filter/OrderBy.kt (modified: yes)
// Modifications: Filter.Sort is final in the source contract, so the two subclasses become factory
// functions returning a configured com.shinsaikou.source.model.Filter.Sort. The selection indices and
// directions are upstream's: title ascending for popular, date descending for latest.
package tachiyomi.source.local.filter

import android.content.Context
import com.shinsaikou.i18n.R
import com.shinsaikou.source.model.Filter

object OrderBy {

    /** Index into [Filter.Sort.values] that means "by folder name". */
    const val INDEX_TITLE = 0

    /** Index into [Filter.Sort.values] that means "by last modified". */
    const val INDEX_DATE = 1

    fun popular(context: Context): Filter.Sort = sort(context, Filter.Sort.Selection(INDEX_TITLE, ascending = true))

    fun latest(context: Context): Filter.Sort = sort(context, Filter.Sort.Selection(INDEX_DATE, ascending = false))

    private fun sort(context: Context, selection: Filter.Sort.Selection) = Filter.Sort(
        name = context.getString(R.string.local_filter_order_by),
        values = listOf(context.getString(R.string.title), context.getString(R.string.date)),
        state = selection,
    )
}
