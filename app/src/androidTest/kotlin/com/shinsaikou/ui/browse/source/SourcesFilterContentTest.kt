package com.shinsaikou.ui.browse.source

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
import com.shinsaikou.domain.source.interactor.languageDisplayName
import com.shinsaikou.domain.source.interactor.languagesWithSources
import com.shinsaikou.i18n.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.model.Source

/**
 * The source filter screen's content composed on a made-up state (phase 5 unit BF), so the gate
 * checks the screen with no extension installed: [SourcesFilterDeviceTest][com.shinsaikou.ui.browse.SourcesFilterDeviceTest]
 * needs the fixture extension for its toggles, which no gate step passes.
 *
 * The state is built by the real [languagesWithSources] from three languages of fixture sources, so
 * the rows come in the order the interactor gives: the enabled languages before the switched-off one,
 * and inside a language the enabled sources before the disabled one. The error state is checked
 * here too: upstream's `internal_error` on the screen's own snackbar, then back within the cap.
 */
@RunWith(AndroidJUnit4::class)
class SourcesFilterContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val clickedLanguages = mutableListOf<String>()
    private val clickedSources = mutableListOf<Source>()
    private var backs = 0

    @Test
    fun theRowsComeInTheInteractorsOrderAndATapReachesItsCallback() {
        setContent(successState())

        // English and Japanese enabled, French not: French's header is last and lists no source.
        // Inside English, Bravo is disabled, so it follows Alpha and Charlie.
        val english = languageName(ENGLISH)
        val japanese = languageName(JAPANESE)
        val french = languageName(FRENCH)
        val enabledHalf = listOf(english to listOf("Alpha", "Charlie", "Bravo"), japanese to listOf("Delta"))
            .sortedBy { it.first }
            .flatMap { (language, sources) -> listOf(language) + sources }
        assertEquals(enabledHalf + french, rowTexts())

        // Each row is one toggle with its state: a switch per language, a checkbox per source.
        composeRule.onNode(hasText(english) and isToggleable()).assertIsOn().assert(hasRole(Role.Switch))
        composeRule.onNode(hasText(french) and isToggleable()).assertIsOff().assert(hasRole(Role.Switch))
        composeRule.onNode(hasText("Alpha") and isToggleable()).assertIsOn().assert(hasRole(Role.Checkbox))
        composeRule.onNode(hasText("Bravo") and isToggleable()).assertIsOff().assert(hasRole(Role.Checkbox))

        composeRule.onNode(hasText(english) and isToggleable()).performClick()
        composeRule.onNode(hasText("Bravo") and isToggleable()).performClick()
        composeRule.onNode(hasText(french) and isToggleable()).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ENGLISH, FRENCH), clickedLanguages)
        assertEquals(listOf(BRAVO_ID), clickedSources.map { it.id })
        assertEquals(0, backs)
    }

    @Test
    fun withNoSourceTheScreenSaysNoSourceIsInstalled() {
        setContent(SourcesFilterViewModel.State.Success(emptyList(), emptySet(), emptySet()))

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { hasNodeWithText(R.string.source_filter_empty_screen) }
        assertEquals(0, backs)
    }

    /**
     * The screen's clock is held, so "at most 1,500 ms" is read off the clock rather than off how fast
     * the device is: the message is up shortly after the error, and back has been called once the
     * cap has passed, well before a short snackbar's own four seconds would end.
     */
    @Test
    fun anErrorSaysInternalErrorOnTheScreensSnackbarThenGoesBackWithinTheCap() {
        composeRule.mainClock.autoAdvance = false
        setContent(SourcesFilterViewModel.State.Error(IllegalStateException("fixture failure")))

        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
        composeRule.waitForIdle()
        assertTrue("internal_error is on the snackbar", hasNodeWithText(R.string.internal_error))
        assertEquals("back is not called before the message has been shown", 0, backs)

        composeRule.mainClock.advanceTimeBy(ERROR_MESSAGE_MILLIS)
        composeRule.waitForIdle()
        assertEquals("back is called once the cap has passed", 1, backs)
    }

    private fun setContent(state: SourcesFilterViewModel.State) {
        composeRule.setContent {
            SourcesFilterContent(
                state = state,
                onBack = { backs++ },
                onClickLanguage = { clickedLanguages += it },
                onClickSource = { clickedSources += it },
            )
        }
        composeRule.waitForIdle()
    }

    /** The screen's rows, top to bottom: every toggle (the back arrow is not one). */
    private fun rowTexts(): List<String> = composeRule.onAllNodes(isToggleable())
        .fetchSemanticsNodes()
        .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }

    private fun hasNodeWithText(id: Int): Boolean =
        composeRule.onAllNodesWithText(composeRule.activity.getString(id)).fetchSemanticsNodes().isNotEmpty()

    /** The header's words, as `enabledLanguageLabel` words a code other than "all". */
    private fun languageName(code: String): String = languageDisplayName(code)

    private fun successState(): SourcesFilterViewModel.State.Success {
        val enabled = setOf(ENGLISH, JAPANESE)
        val disabled = setOf(BRAVO_ID.toString())
        val sources = listOf(
            source(1L, "Charlie", ENGLISH),
            source(BRAVO_ID, "Bravo", ENGLISH),
            source(3L, "Alpha", ENGLISH),
            source(4L, "Delta", JAPANESE),
            source(5L, "Echo", FRENCH),
        )
        val items = languagesWithSources(sources, enabled, disabled).map { (language, list) -> language to list }
        return SourcesFilterViewModel.State.Success(items, enabled, disabled)
    }

    private fun source(id: Long, name: String, lang: String) =
        Source(id = id, lang = lang, name = name, supportsLatest = false, isStub = false)

    private companion object {
        const val ENGLISH = "en"
        const val JAPANESE = "ja"
        const val FRENCH = "fr"
        const val BRAVO_ID = 2L
        const val TIMEOUT_MILLIS = 5_000L
        const val FRAME_MILLIS = 100L

        fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    }
}
