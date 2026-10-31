package com.shinsaikou.ui

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import com.shinsaikou.presentation.core.util.isTabletUi
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.tabs
import com.shinsaikou.ui.settings.SETTINGS_TWO_PANE_TAG
import com.shinsaikou.ui.settings.labelRes
import com.shinsaikou.ui.settings.settingsSectionBodyTag
import com.shinsaikou.ui.settings.shownSettingsSections
import com.shinsaikou.util.Args
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Contract 08 section 7's launch smoke test, and it has no JVM equivalent: it starts the real
 * [MainActivity] on the real `SaikouApp` graph, with the real database behind the library flow and
 * the real Compose tree in a real window. `:app` has no Robolectric, its `src/test` suite is
 * JUnit 5, and the window that decides between the one pane and the two pane Settings layout only
 * exists on a device.
 *
 * What a smoke test can honestly claim is small: every tab is reachable and composes, Settings
 * lists its sections and each one opens, and a tablet composes the two pane layout. Nothing here
 * asserts screen content that only data would have produced, and nothing assumes a fresh install
 * either. The device the gate runs on has whatever library the last run left behind, so a test
 * that pinned the empty screen would be reporting on the database rather than on the app.
 *
 * Labels are read from [com.shinsaikou.i18n.R] through the target context rather than typed in, so the
 * test follows a renamed or translated string instead of pinning English.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    /**
     * Outermost, so the grant is in place before the activity launches. [MainActivity] asks for
     * `POST_NOTIFICATIONS` on its first create, and the dialog that raises would sit over the
     * activity for the rest of the run.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /**
     * Also outermost relative to the activity, for the same reason as the notification grant just
     * above: [MainViewModel][com.shinsaikou.ui.main.MainViewModel] shows the battery exemption dialog
     * the first time `batteryExemptionRequested` is unset, which is exactly the state a test
     * device starts in, and that dialog would sit over the activity for the rest of the run,
     * intercepting every tab click below. Seeding the preference before `createAndroidComposeRule`
     * launches the activity leaves it finding a user who has already answered the prompt, which is
     * the state every test here assumes. The crash log is marked exported in the same place, so a
     * crash some earlier run left on the device cannot raise its banner above the screens these
     * tests assert on (ruling R59).
     */
    @get:Rule(order = 1)
    val firstLaunchAnswered = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val graph = (context.applicationContext as SaikouApp).graph
                graph.basePreferences.batteryExemptionRequested.set(true)
                graph.crashLog.markExported()
                base.evaluate()
            }
        }
    }

    /**
     * `createAndroidComposeRule` launches through `ActivityScenario` like the contract asks, and
     * adds the Compose assertions that the tab bar and the two pane tag need.
     */
    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The library flow reaches the composition some frames after `setContent`, and until it does
     * the start destination is a loading spinner. Every test below would otherwise touch the tab
     * bar mid load.
     *
     * The wait is in two parts because neither half is enough alone. The title appears with the
     * scaffold, which says the activity is up but not that the flow has emitted. The spinner is
     * gone before the scaffold exists as well as after the flow emits, so waiting only for its
     * absence would return on the first frame. The contract waits for the empty screen instead;
     * that only holds on a fresh install, and it fails on any device whose library is not empty.
     */
    @Before
    fun awaitLibraryScreen() {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    @Test
    fun libraryIsTheStartDestinationAndLeavesItsLoadingState() {
        // The title in the bar rather than the tab of the same name, so this is the screen and not
        // the thing that selects it. Which body it then draws, the empty screen or a list, is a
        // fact about the database; that it drew either is what @Before waited for, and a library
        // flow that never emitted would have failed there.
        composeRule.onNode(hasText(string(R.string.library_title)) and isSelectable().not())
            .assertIsDisplayed()
        tabItem(R.string.tab_library).assertIsSelected()
    }

    /**
     * Android 15 and later draw every app edge to edge, and every screen is laid out for that, so
     * this one only has teeth on Android 8 to 14 (the API 26 AVD). There, an activity that never
     * asked kept the window's own bars: the content started below a status bar in Material's
     * purple, and the theme's transparent pure black bars had nothing to show through.
     */
    @Test
    fun theContentDrawsEdgeToEdgeUnderATransparentStatusBar() {
        composeRule.runOnIdle {
            val activity = composeRule.activity
            val content = activity.findViewById<View>(android.R.id.content)
            val inWindow = IntArray(2).also(content::getLocationInWindow)
            assertEquals("the content starts below the status bar", 0, inWindow[1])
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                assertEquals("the status bar is painted", Color.TRANSPARENT, activity.window.statusBarColor)
            }
        }
    }

    @Test
    fun everyTabIsReachableAndComposes() {
        // SaikouAppNav's own list, so a tab added to the bar is a tab this test visits. A screen
        // that threw while composing fails the click that follows it rather than this assertion.
        tabs.forEach { tab ->
            tabItem(tab.labelRes).performClick()
            tabItem(tab.labelRes).assertIsSelected()
        }
    }

    /**
     * Which sections the list shows is the graph's decision (phase 3 unit T1): Tracking only while a
     * configured tracker is in it, read here through the same [shownSettingsSections] the screen
     * uses. Every shown row must be displayed and open its own body; every hidden one must be
     * absent. A graph with a configured tracker (unit T2 onwards) opens the Tracking body here too.
     */
    @Test
    fun settingsListsAndOpensEverySection() {
        val trackers = (context.applicationContext as SaikouApp).graph.trackerManager.trackers
        val shown = shownSettingsSections(trackingOffered = trackers.any { it.isConfigured })
        val hidden = SettingsSection.entries - shown.toSet()
        Log.i(LOG_TAG, "settings sections shown: ${shown.joinToString()}; hidden: ${hidden.joinToString()}")
        openSettings()
        shown.forEach { section -> sectionRow(section).assertIsDisplayed() }
        hidden.forEach { section -> sectionRow(section).assertDoesNotExist() }

        // Which layout composed is the app's decision, not the test's, and the two behave
        // differently from here: a tablet swaps its end pane in place and keeps the list, a phone
        // pushes the section body over it and has to be brought back for the next row.
        val twoPane = composeRule.onAllNodesWithTag(SETTINGS_TWO_PANE_TAG).fetchSemanticsNodes().isNotEmpty()

        shown.forEach { section ->
            sectionRow(section).performClick()
            // The body's own tag, not the section's label. On a tablet the list keeps every label
            // on screen beside the pane, so a label assertion would hold even if the rows had
            // stopped selecting anything at all.
            composeRule.onNodeWithTag(settingsSectionBodyTag(section)).assertIsDisplayed()
            if (!twoPane) composeRule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        }
    }

    @Test
    fun settingsSplitsIntoTwoPanesOnATablet() {
        // The gate's tablet AVD is where this one has anything to assert. On the two phone AVDs
        // and on a developer's phone the app is right to compose a single pane, so the test is a
        // no op there. It returns rather than calling org.junit.Assume: the runner reports an
        // assumption failure through testAssumptionFailure and AGP's XML writer records that as a
        // <failure>, which would turn every phone run of this suite red. The decision is read
        // through the production breakpoint rather than off a device name. The return logs a skip
        // line, so a run can tell it apart from a pass, and under saikou.expect.tablet, which the
        // gate's tablet step passes, it fails instead (ruling R51).
        if (!composesTabletUi()) {
            val reason = "settingsSplitsIntoTwoPanesOnATablet: device is not composing tablet UI, " +
                "the two pane settings assertion was not run."
            if (Args.expectTablet) throw AssertionError("saikou.expect.tablet is true, but $reason")
            Log.i(LOG_TAG, "Skipped: $reason")
            return
        }

        openSettings()

        composeRule.onNodeWithTag(SETTINGS_TWO_PANE_TAG).assertIsDisplayed()
    }

    /**
     * R122 rule 5 (coordinator commit aaf882a): a checkbox row whose master switch is off reads
     * disabled, not merely dimmed, and comes back the moment the master is on. "Invert volume
     * keys" is such a row, gated on "Volume keys" in the Reader section; before that commit
     * `CheckboxRow`'s disabled branch left `enabled` at its default `true`, so the row read
     * enabled, checkable and clickable to a screen reader while a tap on it did nothing.
     *
     * The dependent's own checked value is read before either assertion rather than assumed, since
     * a device running this class after another test may have left it either way; the row must
     * report that same value disabled and again once enabled.
     */
    @Test
    fun aDisabledCheckboxRowReadsDisabledAndTheRowEnabledWhenItsMasterTurnsOn() {
        val preferences = (context.applicationContext as SaikouApp).graph.readerPreferences
        val oldMaster = preferences.readWithVolumeKeys.get()
        val oldDependent = preferences.readWithVolumeKeysInverted.get()
        try {
            preferences.readWithVolumeKeys.set(false)
            openSettings()
            sectionRow(SettingsSection.READER).performClick()

            val dependentRow = hasText(string(R.string.pref_read_with_volume_keys_inverted))
            val readerBody = settingsSectionBodyTag(SettingsSection.READER)
            composeRule.onNodeWithTag(readerBody).performScrollToNode(dependentRow)
            composeRule.onNode(dependentRow).apply {
                assertIsNotEnabled()
                if (oldDependent) assertIsOn() else assertIsOff()
            }

            preferences.readWithVolumeKeys.set(true)
            composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
                composeRule.onAllNodes(dependentRow and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag(readerBody).performScrollToNode(dependentRow)
            composeRule.onNode(dependentRow).apply {
                assertIsEnabled()
                if (oldDependent) assertIsOn() else assertIsOff()
                performClick()
            }
            composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
                preferences.readWithVolumeKeysInverted.get() == !oldDependent
            }
        } finally {
            preferences.readWithVolumeKeysInverted.set(oldDependent)
            preferences.readWithVolumeKeys.set(oldMaster)
        }
    }

    private fun openSettings() {
        tabItem(R.string.tab_more).performClick()
        // The More screen is a lazy column, so the row is scrolled to rather than assumed composed.
        val settings = hasText(string(R.string.label_settings)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(settings)
        composeRule.onNode(settings).performClick()
    }

    /**
     * The bar item rather than the screen behind it. `library_title` and `tab_library` are both
     * "Library", and of the two only the bar item is selectable.
     */
    private fun tabItem(labelRes: Int) = composeRule.onNode(hasText(string(labelRes)) and isSelectable())

    /** A row of the Settings section list: clickable, and not one of the tab bar's items. */
    private fun sectionRow(section: SettingsSection) = composeRule.onNode(
        hasText(string(section.labelRes)) and hasClickAction() and isSelectable().not(),
    )

    /**
     * The rule `isTabletUi()` applies inside the composition, evaluated here against the window the
     * activity was given and the mode the app is really holding.
     */
    private fun composesTabletUi(): Boolean {
        val mode = (context.applicationContext as SaikouApp).graph.uiPreferences.tabletUiMode.get()
        val configuration = composeRule.activity.resources.configuration
        return mode.isTabletUi(configuration.smallestScreenWidthDp, configuration.orientation)
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        /**
         * Empty below API 33, where `POST_NOTIFICATIONS` is not a runtime permission and asking
         * the shell to grant it throws.
         */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        /** Tag for the logcat line that records a skipped assertion rather than an invisible one. */
        const val LOG_TAG = "MainActivitySmokeTest"

        /** A cold start opens the graph and the database before the library flow can emit. */
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
    }
}
