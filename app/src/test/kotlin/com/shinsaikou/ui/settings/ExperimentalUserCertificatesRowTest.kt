package com.shinsaikou.ui.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings > Experimental's "Trust certificates you installed" row, read off the source text for the
 * reason [AdvancedRestartRowsTest] gives: the section is a composable and this module's JVM tests
 * have no Compose runtime. The row is read once per process, so toggling it offers the Advanced
 * rows' restart, which comes back on Settings > Experimental; the other Experimental rows apply at
 * once and offer nothing. The two WebView handlers that act on the switch are pinned the same way,
 * because the platform's `SslErrorHandler` cannot be built here.
 */
class ExperimentalUserCertificatesRowTest {

    /** The unit test process runs in the `app/` module directory, as `AdvancedRestartRowsTest` relies on. */
    private fun read(path: String): String = File("src/main/kotlin/com/shinsaikou/$path").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be app/." }
        file.readText()
    }

    private val experimental: String = read("ui/settings/SettingsSections.kt")
        .substringAfter("private fun ExperimentalSection(")
        .also { check(it.isNotBlank()) { "ExperimentalSection was not found" } }
        .let { rest -> rest.substringBefore("\n}\n") }

    private val certificatesRow: String = experimental
        .substringAfter("labelRes = R.string.settings_experimental_user_certificates,")

    @Test
    fun `the row is bound to the switch NetworkHelper reads`() {
        certificatesRow shouldContain "pref = viewModel.networkPreferences.trustUserCertificates,"
        certificatesRow shouldContain "detailRes = R.string.settings_experimental_user_certificates_summary,"
    }

    @Test
    fun `toggling the row offers the restart that comes back on Settings, Experimental`() {
        val toggled = certificatesRow.substringAfter("onToggled = {")

        toggled shouldContain "showMessage("
        toggled shouldContain "restartNeeded,"
        toggled shouldContain
            "SnackbarAction(restartLabel) { AppRestart.restart(context, MainDestination.EXPERIMENTAL_SETTINGS) }"
        toggled shouldNotContain "MainDestination.ADVANCED_SETTINGS"
    }

    @Test
    fun `the restart line is the Advanced rows' own`() {
        experimental shouldContain "val restartNeeded = stringResource(R.string.requires_app_restart)"
        experimental shouldContain "val restartLabel = stringResource(R.string.action_restart)"
    }

    @Test
    fun `only the certificates row offers a restart`() {
        Regex("""showMessage\(""").findAll(experimental).count() shouldBe 1
        Regex("""AppRestart\.restart\(""").findAll(experimental).count() shouldBe 1
    }

    @Test
    fun `the challenge WebView proceeds only with the switch on and a user CA's certificate, else cancels as before`() {
        val handler = read("../../../../../../core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt")
            .substringAfter("override fun onReceivedSslError(")
            .substringBefore("override fun onRenderProcessGone")

        handler shouldContain "if (trustsUserCertificates && isSignedByUserCertificate(error)) {"
        handler shouldContain "handler.proceed()"
        handler shouldContain "super.onReceivedSslError(view, handler, error)"
        Regex("""handler\.proceed\(\)""").findAll(handler).count() shouldBe 1
    }

    @Test
    fun `Open in WebView proceeds only with the switch on and a user CA's certificate, else cancels as before`() {
        val handler = read("ui/webview/WebViewScreen.kt")
            .substringAfter("override fun onReceivedSslError(")
            .substringBefore("override fun onRenderProcessGone")

        handler shouldContain "if (viewModel.trustsUserCertificates && isSignedByUserCertificate(error)) {"
        handler shouldContain "handler.proceed()"
        handler shouldContain "super.onReceivedSslError(view, handler, error)"
        Regex("""handler\.proceed\(\)""").findAll(handler).count() shouldBe 1
    }
}
