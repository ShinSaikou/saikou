package com.shinsaikou.ui.browse.extension

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.domain.source.interactor.languageDisplayName
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.browse.extensionLanguages
import com.shinsaikou.ui.browse.source.ERROR_MESSAGE_MILLIS
import eu.kanade.presentation.theme.SaikouTheme
import tachiyomi.domain.extension.model.ExtensionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The extension filter screen's content composed on a made-up state (phase 5 unit BX), so the gate
 * checks the screen with no extension store configured and nothing fetched.
 *
 * The state is built by the real [extensionLanguages] from four made-up index entries in three
 * languages, one of them listing two, so the rows come in the order the interactor gives: the
 * enabled languages before the switched-off one. The error state is checked as the source filter's
 * is: upstream's `internal_error` on the screen's own snackbar, then back within the cap.
 *
 * The content is drawn in the app's theme, following the system's dark mode, so the screen looks as
 * it does in the app. `saikou.extension.filter.hold.millis` (0 when absent) holds the rows on screen
 * before the taps, for a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionFilterContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val clickedLanguages = mutableListOf<String>()
    private var backs = 0

    @Test
    fun theRowsComeInTheInteractorsOrderAndATapReachesItsCallback() {
        setContent(successState())

        // English and Japanese enabled, French not: French is last. Each language once, though two
        // entries are English and one lists both English and Japanese.
        val english = languageDisplayName(ENGLISH)
        val japanese = languageDisplayName(JAPANESE)
        val french = languageDisplayName(FRENCH)
        assertEquals(listOf(english, japanese).sorted() + french, rowTexts())
        holdForScreenshot()

        // Each row is one switch with its state.
        composeRule.onNode(hasText(english) and isToggleable()).assertIsOn().assert(hasRole(Role.Switch))
        composeRule.onNode(hasText(japanese) and isToggleable()).assertIsOn().assert(hasRole(Role.Switch))
        composeRule.onNode(hasText(french) and isToggleable()).assertIsOff().assert(hasRole(Role.Switch))

        composeRule.onNode(hasText(english) and isToggleable()).performClick()
        composeRule.onNode(hasText(french) and isToggleable()).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ENGLISH, FRENCH), clickedLanguages)
        assertEquals(0, backs)
    }

    @Test
    fun withNothingAvailableTheScreenIsUpstreamsEmptyScreen() {
        setContent(ExtensionFilterViewModel.State.Success(emptyList(), setOf(ENGLISH)))

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { hasNodeWithText(R.string.empty_screen) }
        assertTrue("no language row on the empty screen", composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty())
        assertEquals(0, backs)
    }

    /**
     * The screen's clock is held, so "at most 1,500 ms" is read off the clock rather than off how fast
     * the device is, as `SourcesFilterContentTest` reads it.
     */
    @Test
    fun anErrorSaysInternalErrorOnTheScreensSnackbarThenGoesBackWithinTheCap() {
        composeRule.mainClock.autoAdvance = false
        setContent(ExtensionFilterViewModel.State.Error(IllegalStateException("fixture failure")))

        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
        composeRule.waitForIdle()
        assertTrue("internal_error is on the snackbar", hasNodeWithText(R.string.internal_error))
        assertEquals("back is not called before the message has been shown", 0, backs)

        composeRule.mainClock.advanceTimeBy(ERROR_MESSAGE_MILLIS)
        composeRule.waitForIdle()
        assertEquals("back is called once the cap has passed", 1, backs)
    }

    private fun setContent(state: ExtensionFilterViewModel.State) {
        composeRule.setContent {
            SaikouTheme {
                ExtensionFilterContent(
                    state = state,
                    onBack = { backs++ },
                    onClickLanguage = { clickedLanguages += it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun holdForScreenshot() {
        val millis = InstrumentationRegistry.getArguments().getString(HOLD_ARGUMENT)?.toLongOrNull() ?: 0L
        if (millis > 0) Thread.sleep(millis)
    }

    /** The screen's rows, top to bottom: every toggle (the back arrow is not one). */
    private fun rowTexts(): List<String> = composeRule.onAllNodes(isToggleable())
        .fetchSemanticsNodes()
        .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }

    private fun hasNodeWithText(id: Int): Boolean =
        composeRule.onAllNodesWithText(composeRule.activity.getString(id)).fetchSemanticsNodes().isNotEmpty()

    private fun successState(): ExtensionFilterViewModel.State.Success {
        val enabled = setOf(ENGLISH, JAPANESE)
        val available = listOf(
            available("a", listOf(FRENCH)),
            available("b", listOf(ENGLISH)),
            available("c", listOf(ENGLISH, JAPANESE)),
            available("d", listOf(ENGLISH)),
        )
        return ExtensionFilterViewModel.State.Success(extensionLanguages(available, enabled), enabled)
    }

    private fun available(packageSuffix: String, languages: List<String>) = Extension.Available(
        packageName = "dev.example.fixture.$packageSuffix",
        name = "Fixture $packageSuffix",
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.6,
        lang = languages.singleOrNull() ?: "all",
        isNsfw = false,
        sources = languages.mapIndexed { index, lang ->
            AvailableSource(id = 2L + index, name = "Fixture Index Source", lang = lang, homeUrl = "")
        },
        apkUrl = "https://example.invalid/fixture.apk",
        iconUrl = "https://example.invalid/fixture.png",
        jarUrl = null,
        store = ExtensionStore(
            indexUrl = "https://example.invalid/index.json",
            name = "Fixture Index",
            badgeLabel = "FI",
            signingKey = "fixture-signing-key",
            contact = ExtensionStore.Contact("https://example.invalid", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )

    private companion object {
        const val ENGLISH = "en"
        const val JAPANESE = "ja"
        const val FRENCH = "fr"
        const val TIMEOUT_MILLIS = 5_000L
        const val FRAME_MILLIS = 100L
        const val HOLD_ARGUMENT = "saikou.extension.filter.hold.millis"

        fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    }
}
