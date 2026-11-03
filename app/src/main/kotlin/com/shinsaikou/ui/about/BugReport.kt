package com.shinsaikou.ui.about

/** What the bug report and Copy device info say about the build and the device. */
data class DeviceInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    /** `BuildConfig.BUILD_TYPE`: `release`, `debug`, `publicDebug` or `benchmark`. */
    val buildType: String,
    val debuggable: Boolean,
    val commit: String,
    val packageName: String,
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val abis: List<String>,
) {
    /** Six lines, in English whatever the device's language, since a developer reads them. */
    fun header(): String = buildString {
        appendLine("$appName $versionName ($versionCode)")
        appendLine("Build: ${if (debuggable) "Debug" else "Release"} ($buildType), commit $commit")
        appendLine("Package: $packageName")
        appendLine("Android $androidRelease (API $sdk)")
        appendLine("Device: $manufacturer $model")
        append("ABIs: ${abis.joinToString()}")
    }
}

/** The newest crash file, by name, with its text. */
data class CrashFile(val name: String, val text: String)

/**
 * The text file Share bug report sends: [DeviceInfo.header], the newest crash log when there is
 * one, and the app's own recent log, every part scrubbed of what looks like a credential.
 */
object BugReport {

    /** How much of the app's log the report carries, from its end. */
    const val LOG_LINES = 2_000

    private const val REDACTED = "<redacted>"

    /**
     * [log] is the app's log as `logcat` printed it, or null when it could not be read, which the
     * report says rather than leaving the section out.
     */
    fun text(info: DeviceInfo, writtenAt: String, crash: CrashFile?, log: String?): String = scrub(
        buildString {
            appendLine(info.header())
            appendLine("Written: $writtenAt")
            appendLine()
            if (crash != null) {
                appendLine("== Newest crash log: ${crash.name} ==")
                appendLine(crash.text.trimEnd())
                appendLine()
            }
            appendLine("== Log of this process, last $LOG_LINES lines ==")
            appendLine(log?.let(::lastLines)?.trimEnd() ?: "The log could not be read.")
        },
    )

    /** The last [count] lines of [text]. */
    fun lastLines(text: String, count: Int = LOG_LINES): String = text.lines().takeLast(count).joinToString("\n")

    /**
     * [text] with the values of credential headers (Authorization, Cookie and their kin), bearer
     * tokens, and token, code, secret and password parameters in a query, a form or JSON replaced
     * by `<redacted>`. The names stay, so the report still shows that a request carried one. The
     * network log already redacts the headers it prints; this also covers whatever else reached
     * the log, a crash's message included.
     */
    fun scrub(text: String): String = text
        .replace(HEADER) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        .replace(BEARER) { "${it.groupValues[1]} $REDACTED" }
        .replace(JSON_FIELD) { "${it.groupValues[1]}${it.groupValues[2]}\"$REDACTED\"" }
        .replace(PARAMETER) { "${it.groupValues[1]}=$REDACTED" }

    private val HEADER = Regex(
        """(?<![A-Za-z0-9-])(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|auth)(\s*:\s*)[^\r\n]+""",
        RegexOption.IGNORE_CASE,
    )

    private val BEARER = Regex("""\b(bearer)\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    /** A name ending in `token` (`token`, `access_token`, `csrftoken`), and the secrets. */
    private const val SECRET_NAMES = """[A-Za-z_]*token|client_secret|code_verifier|password|api_key|apikey"""

    /** A parameter's value: up to the next parameter, a space, a quote or punctuation that ends it. */
    private const val PARAMETER_VALUE = """[^&\s"'<>,;)]+"""

    /**
     * The OAuth `code` counts only as a query, fragment or form parameter (after `?`, `#` or `&`, or
     * at the start of the text, a line or after a space when another parameter follows it, as a
     * logged form body starts), or as a JSON field: the runtime logs its own `code=30KB` as plain
     * text.
     */
    private val PARAMETER = Regex(
        """((?<![A-Za-z0-9_])(?:$SECRET_NAMES)|(?<=[?#&])code|(?<!\S)code(?==$PARAMETER_VALUE&))=$PARAMETER_VALUE""",
        RegexOption.IGNORE_CASE,
    )

    private val JSON_FIELD = Regex("""("(?:$SECRET_NAMES|code)")(\s*:\s*)"[^"]*"""", RegexOption.IGNORE_CASE)
}
