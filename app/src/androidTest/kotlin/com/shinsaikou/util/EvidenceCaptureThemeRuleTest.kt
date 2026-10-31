package com.shinsaikou.util

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.domain.preferences.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * [EvidenceCapture.themeRule] puts the theme mode back in the preference file itself before it
 * returns, not only in the process's copy (LK2's report: after an `am instrument` run with
 * `saikou.evidence.theme` the app came back in the pinned mode). The runner kills the process moments
 * after the last test, so the file is what the next launch reads.
 *
 * Each test flushes the pinned mode to the file inside the rule, as the activity's stop does in an
 * evidence run, and reads the file straight after the rule returns. A restore left to the store's
 * `apply()` is still queued then (up to 100 ms on API 26 and up) and the file still holds the pinned
 * mode, which is what the rule did before it committed.
 */
@RunWith(AndroidJUnit4::class)
class EvidenceCaptureThemeRuleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val themeMode = (context.applicationContext as SaikouApp).graph.uiPreferences.themeMode

    private val file = File(context.dataDir, "shared_prefs/${context.packageName}_preferences.xml")

    private var wasSet = false
    private lateinit var before: ThemeMode

    @Before
    fun rememberTheThemeMode() {
        wasSet = themeMode.isSet()
        before = themeMode.get()
    }

    @After
    fun putTheThemeModeBack() {
        if (wasSet) themeMode.set(before) else themeMode.delete()
        StagePreferences.commit()
    }

    @Test
    fun aThemeModeThatWasNotSetIsGoneFromTheFileWhenTheRuleReturns() {
        themeMode.delete()
        StagePreferences.commit()

        runPinned(ThemeMode.DARK)

        assertFalse("the process still holds a theme mode", themeMode.isSet())
        assertNull("the file still holds a theme mode", storedThemeMode())
    }

    @Test
    fun aThemeModeThatWasSetIsBackInTheFileWhenTheRuleReturns() {
        themeMode.set(ThemeMode.LIGHT)
        StagePreferences.commit()

        runPinned(ThemeMode.DARK)

        assertEquals(ThemeMode.LIGHT, themeMode.get())
        assertEquals(ThemeMode.LIGHT.name, storedThemeMode())
    }

    @Test
    fun theThemeModeIsBackInTheFileWhenTheTestInsideFails() {
        themeMode.set(ThemeMode.LIGHT)
        StagePreferences.commit()
        val failure = AssertionError("the test inside failed")

        val thrown = runCatching { runPinned(ThemeMode.DARK) { throw failure } }.exceptionOrNull()

        assertSame("the rule did not rethrow the test's own failure", failure, thrown)
        assertEquals(ThemeMode.LIGHT.name, storedThemeMode())
    }

    /** The rule around a test that sees [wanted], flushes it to the file as an activity's stop does, then runs [then]. */
    private fun runPinned(wanted: ThemeMode, then: () -> Unit = {}) {
        val test = object : Statement() {
            override fun evaluate() {
                assertEquals(wanted, themeMode.get())
                StagePreferences.commit()
                assertEquals("the pinned mode never reached the file", wanted.name, storedThemeMode())
                then()
            }
        }
        EvidenceCapture.themeRule(wanted).apply(test, Description.EMPTY).evaluate()
    }

    /** The theme mode as the preference file holds it, read from the file rather than the process's copy. */
    private fun storedThemeMode(): String? {
        val parser = Xml.newPullParser()
        file.inputStream().use { input ->
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG &&
                    parser.getAttributeValue(null, "name") == themeMode.key()
                ) {
                    return parser.nextText()
                }
            }
        }
        return null
    }
}
