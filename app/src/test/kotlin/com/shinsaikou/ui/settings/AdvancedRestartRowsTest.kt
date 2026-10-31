package com.shinsaikou.ui.settings

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Which Advanced rows' "Requires app restart to take effect" carries the Restart action (F13).
 *
 * Read off the source text: the section is a composable, and this module's JVM tests have no
 * Compose runtime to tap a row in, so the rows are matched to their snackbar calls by text, as
 * `ViewModelBindingTest` reads the view models. The device suite's `TwoPaneLayoutTest` taps Revoke
 * trusted unknown extensions and waits for the line; the action itself is [com.shinsaikou.ui.main.AppRestartTest].
 *
 * The seven places that change something read once per process: Verbose logging and DNS over HTTPS
 * (its row and its Custom dialog) build the network client, the user agent (its dialog and its
 * reset) is built into each source's headers when the source first asks, trust is read at the next
 * extension load, and so is the extension list override. The other rows' lines (rebuild counts,
 * clear WebView data, refresh covers, reset viewer flags) apply at once and carry no action.
 */
class AdvancedRestartRowsTest {

    /** The unit test process runs in the `app/` module directory, as `ViewModelBindingTest` relies on. */
    private val source: String = File("src/main/kotlin/com/shinsaikou/ui/settings/SettingsSections.kt").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be app/." }
        file.readText()
    }

    private val advanced: String = source
        .substringAfter("private fun AdvancedSection(")
        .substringBefore("private fun LibVersionOverrideDialog(")
        .also { check(it.length < source.length) { "AdvancedSection or LibVersionOverrideDialog was not found" } }

    @Test
    fun `the restart line carries the Restart action that restarts the app on Settings, Advanced`() {
        val offer = advanced.substringAfter("val offerRestart: () -> Unit = {").substringBefore("\n    }\n")

        offer shouldContain "showMessage("
        offer shouldContain "restartNeeded,"
        offer shouldContain "SnackbarAction(restartLabel) { AppRestart.restart(context, MainDestination.ADVANCED_SETTINGS) }"
        // The declaration and the one use in offerRestart: no row shows the line without the action.
        Regex("""\brestartNeeded\b""").findAll(advanced).count() shouldBe 2
    }

    @Test
    fun `each row read once per process offers the restart, and only those rows do`() {
        val offering = mapOf(
            "Verbose logging" to rowAfter("R.string.pref_verbose_logging,"),
            "DNS over HTTPS" to rowAfter("R.string.pref_dns_over_https,"),
            "the custom DNS over HTTPS dialog" to advanced.between("CustomDohDialog(", "\n    if (editingUserAgent)"),
            "Reset default user agent string" to rowAfter("R.string.pref_reset_user_agent_string)"),
            "Revoke trusted unknown extensions" to rowAfter("R.string.ext_revoke_trust)"),
            "the user agent dialog" to advanced.between("UserAgentDialog(", "\n    if (editingLibVersions)"),
            "the extension list override dialog" to advanced.substringAfter("LibVersionOverrideDialog("),
        )

        offering.forEach { (row, text) ->
            withClue(row) {
                text shouldContain "offerRestart()"
                text shouldNotContain "showMessage("
            }
        }
        Regex("""offerRestart\(\)""").findAll(advanced).count() shouldBe offering.size
    }

    @Test
    fun `a changed extension list override offers the restart, an unchanged one does not`() {
        advanced.substringAfter("LibVersionOverrideDialog(") shouldContain
            "if (value != libVersionOverride) offerRestart()"
    }

    @Test
    fun `the other rows' lines carry no action`() {
        val calls = Regex("""showMessage\((.*)\)\s*}""").findAll(advanced).map { it.groupValues[1] }.toList()

        calls shouldContainExactly listOf(
            "if (success) rebuildDone else rebuildFailed, null",
            "if (context.clearWebViewData()) webViewCleared else webViewFailed, null",
            "if (started) updateStarted else updateRunning, null",
            "if (success) resetDone else resetFailed, null",
        )
    }

    /** From [anchor], a row's label, to the start of the next row or block in the section. */
    private fun rowAfter(anchor: String): String {
        check(anchor in advanced) { "$anchor is not in AdvancedSection" }
        val rest = advanced.substringAfter(anchor)
        val end = ROW_STARTS.mapNotNull { start -> rest.indexOf(start).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private fun String.between(start: String, end: String): String {
        check(start in this) { "$start is not in AdvancedSection" }
        return substringAfter(start).substringBefore(end)
    }

    private companion object {
        val ROW_STARTS = listOf(
            "\n        ListRow(",
            "\n        CheckboxRow(",
            "\n        ChoiceRow(",
            "\n        Text(",
            "\n        InlineNotice(",
            "\n    if (editing",
        )
    }
}
