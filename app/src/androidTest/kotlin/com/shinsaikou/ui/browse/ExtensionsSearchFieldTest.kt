package com.shinsaikou.ui.browse

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.extensions.loader.LoadError
import com.shinsaikou.extensions.loader.LoadResult
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
 * The page is composed on its own, with the real [ExtensionsViewModel] built through the app's view
 * model map and its state collected as [BrowseScreen] collects it, so every key still goes to the
 * view model and the query still comes back the way it does in the app. A test device has no
 * extension and no store, and the field is hidden when there is nothing to search and nothing
 * typed, so the state handed to the page carries one fixture row, a load error, in place of the
 * view model's empty groups. Only the rows are replaced; the query is the view model's own.
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

        composeRule.typeThenDelete(WHERE)
        assertEquals("the view model's query", TYPED_QUERY.dropLast(DELETED_KEYS), viewModel.state.value.searchQuery)
    }

    /**
     * The page leaves the composition whenever Browse shows another of its pages, while the view
     * model, which belongs to Browse, keeps the query. Coming back shows that query in the field,
     * as it did when the field read the view model's copy directly.
     */
    @Test
    fun aQueryTypedBeforeThePageLeftIsInTheFieldWhenItComesBack() {
        if (skippedForAForeignStore("aQueryTypedBeforeThePageLeftIsInTheFieldWhenItComesBack")) return
        var shown by mutableStateOf(true)
        setPage(shown = { shown })
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
                val state by viewModel.state.collectAsState()
                if (shown()) {
                    ExtensionsTab(
                        viewModel = viewModel,
                        state = state.copy(isInitialized = true, groups = FIXTURE_GROUPS),
                        padding = PaddingValues(),
                    )
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "ExtensionsSearchFieldTest"
        const val WHERE = "Extensions"

        /** A row the page can draw without an icon or a network call; it never takes a tap here. */
        val FIXTURE_GROUPS: List<Pair<ExtensionGroup, List<ExtensionUi>>> = listOf(
            ExtensionGroup.Errors to listOf(
                ExtensionUi.LoadError(LoadResult.Error("com.shinsaikou.fixture.searchfield", LoadError.NOT_AN_EXTENSION)),
            ),
        )
    }
}
