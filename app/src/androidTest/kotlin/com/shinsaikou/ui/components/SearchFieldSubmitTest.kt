package com.shinsaikou.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shinsaikou.domain.preferences.AppTheme
import eu.kanade.presentation.theme.BaseSaikouTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard half of [SearchField]'s submit. A source's listing searches only on submit, and
 * before this its field kept Material's Done key, which put the keyboard away and searched for
 * nothing; a hardware Enter did the same. The field is composed on its own, so what is read is the
 * field's behaviour and not a source's, and the keyboard is a stand-in that counts its hides.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SearchFieldSubmitTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var submits = 0
    private var hides = 0

    private val keyboard = object : SoftwareKeyboardController {
        override fun show() = Unit
        override fun hide() {
            hides++
        }
    }

    @Test
    fun theKeyboardsSearchKeySubmitsAndPutsTheKeyboardAway() {
        showField { onSubmit -> FieldUnderTest(onSubmit = onSubmit) }
        field().performClick().assertIsFocused()
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))

        field().performImeAction()

        composeRule.runOnIdle {
            assertEquals("submits", 1, submits)
            assertEquals("keyboard hides", 1, hides)
        }
    }

    @Test
    fun aHardwareEnterSubmitsAndPutsTheKeyboardAway() {
        showField { onSubmit -> FieldUnderTest(onSubmit = onSubmit) }
        field().performClick().assertIsFocused()

        field().performKeyInput { pressKey(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals("submits", 1, submits)
            assertEquals("keyboard hides", 1, hides)
        }
    }

    @Test
    fun aFieldWithNoSubmitKeepsTheDefaultKey() {
        showField { FieldUnderTest(onSubmit = null) }
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
    }

    /** Global search passes its own key and action, and they are the ones the field uses. */
    @Test
    fun aCallersOwnKeyboardActionsAreKept() {
        var own = 0
        showField { onSubmit ->
            FieldUnderTest(
                onSubmit = onSubmit,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { own++ }),
            )
        }
        field().performClick()
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Go))

        field().performImeAction()

        composeRule.runOnIdle {
            assertEquals("the caller's own action", 1, own)
            assertEquals("submits", 0, submits)
        }
    }

    private fun showField(content: @Composable (onSubmit: () -> Unit) -> Unit) {
        composeRule.setContent {
            BaseSaikouTheme(appTheme = AppTheme.DEFAULT, isDark = false, isAmoled = false) {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    content { submits++ }
                }
            }
        }
    }

    /** The keyboard arguments are left out unless given, as a screen leaves them out. */
    @Composable
    private fun FieldUnderTest(
        onSubmit: (() -> Unit)?,
        keyboardOptions: KeyboardOptions? = null,
        keyboardActions: KeyboardActions? = null,
    ) {
        var text by remember { mutableStateOf("query") }
        val modifier = Modifier.testTag(FIELD_TAG)
        if (keyboardOptions != null && keyboardActions != null) {
            SearchField(text, { text = it }, "Search", modifier, onSubmit, keyboardOptions, keyboardActions)
        } else {
            SearchField(text, { text = it }, "Search", modifier, onSubmit)
        }
    }

    private fun field() = composeRule.onNodeWithTag(FIELD_TAG)

    private companion object {
        const val FIELD_TAG = "search_field"
    }
}
