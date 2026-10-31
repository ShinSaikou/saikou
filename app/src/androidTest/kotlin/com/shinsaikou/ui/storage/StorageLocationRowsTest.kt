package com.shinsaikou.ui.storage

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.i18n.R
import com.shinsaikou.util.Args
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Storage location row (unit storage-location) composed with its view model built through the
 * graph's factory, on an install with no location set: the row says what is at stake and that it
 * fixes it, and upstream's info line is under it. Choosing a folder needs the system picker, which no
 * test here can drive, so this stops at what the row shows before one is chosen.
 */
@RunWith(AndroidJUnit4::class)
class StorageLocationRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp
    private val store = ViewModelStore()

    @After
    fun clear() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
    }

    @Test
    fun withNoLocationTheRowSaysBackupsAndLocalSeriesAreKeptInsideTheApp() {
        // A gate AVD starts from a fresh install, where no location is set. On a device where one is,
        // the row has nothing of this test's to show, so the test returns with a Skipped: line rather
        // than calling org.junit.Assume, which AGP's XML writer records as a <failure> (see Args).
        // Under saikou.strict that return would be a pass that asserted nothing, so it fails instead.
        if (app.graph.storageLocation.status() != StorageLocation.Status.NOT_SET) {
            val reason = "withNoLocationTheRowSaysBackupsAndLocalSeriesAreKeptInsideTheApp: a storage " +
                "location is set on this install, the not-set row was not checked."
            if (Args.strict) throw AssertionError("saikou.strict is true, but $reason")
            Log.i(LOG_TAG, "Skipped: $reason")
            return
        }
        lateinit var viewModel: StorageLocationViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = ViewModelProvider(store, app.graph.viewModelFactory)[StorageLocationViewModel::class.java]
        }

        composeRule.setContent { Column { StorageLocationRows(viewModel) } }

        val notSet = app.getString(R.string.pref_storage_location_not_set)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(notSet, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasTestTag(STORAGE_LOCATION_ROW_TAG)).assertIsDisplayed()
        composeRule.onNode(hasText(app.getString(R.string.pref_storage_location_info))).assertIsDisplayed()
    }

    private companion object {
        const val LOG_TAG = "SaikouStorageLocationRows"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
