package com.shinsaikou.util

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Instrumentation arguments, read through [InstrumentationRegistry.getArguments].
 *
 * Nothing here is a test. This is the one place that turns a runtime argument into either a value,
 * null or a failure, so that no test class has to decide for itself what a missing argument means.
 *
 * Two modes, and the difference is deliberate:
 *
 * - On the owner's gate run every argument is supplied and `saikou.strict` is `"true"`. A missing
 *   argument there means the harness is misconfigured and the run would silently prove nothing, so
 *   [argument] fails the test rather than letting it pass having asserted nothing.
 * - On a developer machine the arguments are normally absent. [argument] returns null and the test
 *   body returns early, which keeps `connectedDebugAndroidTest` green for work that has nothing to
 *   do with the owner's private inputs.
 *
 * Contract 01 section 8.3 writes the second mode with `org.junit.Assume`. It cannot be used here:
 * `AndroidJUnitRunner` reports an `AssumptionViolatedException` through `testAssumptionFailure` and
 * AGP's XML writer records that as a `<failure>`, so an assumed skip is a red suite on every
 * machine without the owner's fixtures. An early return is invisible in the report, which is why
 * the strict half is not optional: it is the only thing standing between a skipped test and a gate
 * that passes by doing nothing.
 *
 * The argument names are the keys in `app/build.gradle.kts` `defaultConfig`, which copies them from
 * `local.properties` or the matching environment variable and passes only the keys it actually
 * found. Values stay out of the repository by construction: they exist in a gitignored file, in the
 * environment, or on the `gradlew` command line, and only ever reach the device as runner arguments.
 */
object Args {
    private val bundle get() = InstrumentationRegistry.getArguments()
    val strict: Boolean get() = bundle.getString("saikou.strict") == "true"

    /**
     * Whether the run was told it is on a device that composes tablet UI. Only the gate's tablet
     * step passes `saikou.expect.tablet=true`, and under it a test's early return for a one pane
     * device fails instead, because on that step the return would be a pass that asserted
     * nothing (ruling R51). Absent everywhere else, where the return is right on a phone.
     */
    val expectTablet: Boolean get() = bundle.getString("saikou.expect.tablet") == "true"

    /**
     * The gate phase the run is for, from `saikou.gate.phase`, and null when the run names none, which
     * binds as phase 0. A row that `rows.json` puts in a later phase than this returns early with a
     * `Skipped:` line, so an earlier phase's gate keeps the verdict it had before the row could be
     * measured, and the step that measures it passes the phase and fails on that line. Read
     * directly rather than through [argument]: absent is a real answer here, which strict mode must
     * not turn into a failure. A value that is not a number is a harness error and fails.
     */
    val gatePhase: Int?
        get() {
            val value = bundle.getString("saikou.gate.phase")
            if (value.isNullOrBlank()) return null
            return value.toIntOrNull()
                ?: throw AssertionError("instrumentation argument saikou.gate.phase is not a number: $value")
        }

    /**
     * The argument, or null when it was not supplied and the run is not strict. Call it as
     * `val url = Args.repoUrl ?: return` so a developer run is a no op and the gate is not.
     */
    fun argument(name: String): String? {
        val value = bundle.getString(name)
        if (value.isNullOrBlank()) {
            if (strict) throw AssertionError("instrumentation argument $name is missing")
            return null
        }
        return value
    }

    val repoUrl get() = argument("saikou.repo.url")
    val refExt14Package get() = argument("saikou.ref.ext14.pkg")
    val refExt16Package get() = argument("saikou.ref.ext16.pkg")
    val fixturesDir get() = argument("saikou.fixtures.dir")
    val backupFile get() = argument("saikou.backup.file")
}
