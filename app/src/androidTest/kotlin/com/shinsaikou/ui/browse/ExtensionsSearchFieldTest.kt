package com.shinsaikou.ui.browse

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.SaikouNavigator
import com.shinsaikou.ui.DELETED_KEYS
import com.shinsaikou.ui.TYPED_QUERY
import com.shinsaikou.ui.assertFieldSettlesOn
import com.shinsaikou.ui.typeThenDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Extensions page's search field, typed into one key at a time (see `SearchFieldTyping.kt`):
 * on 2026-10-02 the owner's phone turned "Asura" into "suraA" here, because the field's text was
 * the view model's copy, which comes back through a flow on the IO dispatcher a frame or more late.
 *
 * The field is in Browse's bar while the Extensions page is shown, so the bar is composed on its own
 * with the search held as [BrowseScreen] holds it ([rememberExtensionsSearch]), over the real
 * [ExtensionsViewModel] built through the app's view model map: every key still goes to the view
 * model and the query still comes back the way it does in the app. The search icon opens the field.
 *
 * The view model fetches every configured extension index when it is built and nothing is
 * available, so while a store other than the browse fixture's is configured (whose fetch reaches
 * only the host, see `FixtureStore`) it is not built, and each method logs a `Skipped:` line and
 * returns (phase 5 unit TG2, the guard `ExtensionFilterDeviceTest` has).
 */
@RunWith(AndroidJUnit4::class)
class ExtensionsSearchFieldTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph = (instrumentation.targetContext.applicationContext as SaikouApp).graph
    private val store = ViewModelStore()
    private lateinit var viewModel: ExtensionsViewModel
    private var foreignStores: List<String> = emptyList()

    @Before
    fun buildViewModel() {
        foreignStores = runBlocking(Dispatchers.IO) { FixtureStore.foreignStores(graph) }
        if (foreignStores.isNotEmpty()) return
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, graph.viewModelFactory)[ExtensionsViewModel::class.java]
        }
    }

    @After
    fun clearViewModel() {
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test
    fun keysTypedOneAtATimeReachTheFieldAndTheViewModelInOrder() {
        if (skippedForAForeignStore("keysTypedOneAtATimeReachTheFieldAndTheViewModelInOrder")) return
        setPage(shown = { true })
        openSearch()

        composeRule.typeThenDelete(WHERE)
        assertEquals("the view model's query", TYPED_QUERY.dropLast(DELETED_KEYS), viewModel.state.value.searchQuery)
    }

    /**
     * Browse can leave the composition, a trip to another tab, while the view model, which belongs to
     * Browse's entry, keeps the query. Coming back shows that query in the field, already open, as it
     * did when the field read the view model's copy directly.
     */
    @Test
    fun aQueryTypedBeforeThePageLeftIsInTheFieldWhenItComesBack() {
        if (skippedForAForeignStore("aQueryTypedBeforeThePageLeftIsInTheFieldWhenItComesBack")) return
        var shown by mutableStateOf(true)
        setPage(shown = { shown })
        openSearch()
        composeRule.typeThenDelete(WHERE)

        composeRule.runOnIdle { shown = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { shown = true }

        composeRule.assertFieldSettlesOn("$WHERE, shown again", TYPED_QUERY.dropLast(DELETED_KEYS), afterTyping = false)
        assertEquals("the view model's query", TYPED_QUERY.dropLast(DELETED_KEYS), viewModel.state.value.searchQuery)
    }

    private fun skippedForAForeignStore(method: String): Boolean {
        if (foreignStores.isEmpty()) return false
        Log.i(
            LOG_TAG,
            "Skipped: $method: an extension store other than the browse fixture store is configured, " +
                "the view model was not built so that nothing is fetched",
        )
        return true
    }

    private fun setPage(shown: () -> Boolean) {
        composeRule.setContent {
            val navController = rememberNavController()
            val navigator = remember(navController) { SaikouNavigator(navController, composeRule.activity) }
            CompositionLocalProvider(LocalNavigator provides navigator) {
                if (shown()) {
                    var search by rememberExtensionsSearch(viewModel)
                    ExtensionsTopBar(
                        title = WHERE,
                        searchQuery = search,
                        onSearchQueryChange = { search = it },
                        storeCount = 0L,
                    )
                }
            }
        }
    }

    private fun openSearch() {
        val label = instrumentation.targetContext.getString(R.string.action_search)
        composeRule.onNode(hasContentDescription(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private companion object {
        const val LOG_TAG = "ExtensionsSearchFieldTest"
        const val WHERE = "Extensions"
    }
}
