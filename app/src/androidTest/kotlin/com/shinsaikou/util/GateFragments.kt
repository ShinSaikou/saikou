package com.shinsaikou.util

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Where a device test publishes a gate row, and how it reads the device back. Contract 01 section
 * 7.3.
 *
 * A fragment is one `rows[]` entry of `tools/gate/rows.json`'s shape, written to
 * `/data/local/tmp/saikou-gate/<id>.json`, which is what `collect.py --instrumentation` pulls. It
 * started as `GateRowsTest`'s private helper and moved here when a second class began publishing,
 * so that both write the same shape through the same channel and read it back the same way.
 *
 * Infrastructure rather than a test, like [Args] and [Fixtures].
 */
object GateFragments {

    /**
     * The instrumentation argument form of the bench scripts' `SAIKOU_DEVICE_ID`: the gate's name for
     * the device the run is on, as `rows.json` and `baseline.json` spell it. It is not in
     * `app/build.gradle.kts`'s argument list, because it describes the run rather than the owner, so
     * a harness passes it on the command line:
     * `-Pandroid.testInstrumentationRunnerArguments.saikou.device.id=<id>`, or `-e saikou.device.id
     * <id>` to `am instrument`.
     */
    const val DEVICE_ARGUMENT = "saikou.device.id"

    /** Where `collect.py` pulls instrumentation fragments from. */
    const val DEVICE_GATE_DIRECTORY = "/data/local/tmp/saikou-gate"

    /** Under the app's external files, which the shell user can read and the app can write. */
    private const val STAGING_DIRECTORY = "saikou-gate"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * Writes one `rows[]` entry where `collect.py --instrumentation` pulls from.
     *
     * The application cannot create a file under `/data/local/tmp` itself, so the fragment is
     * staged in the app's own external directory and copied across by a shell command, which runs
     * as the shell user and leaves the copy owned by it, which is what `adb pull` needs. Redirection
     * is deliberately not used: `UiAutomation.executeShellCommand` splits its argument on
     * whitespace and runs the result directly, so a `>` would arrive as an argument rather than as
     * a redirect and the fragment would land nowhere.
     *
     * The fragment is read back through the same channel before this returns. A write that reports
     * success and leaves nothing behind is the failure this project has now paid for three times,
     * and the read is one shell call.
     *
     * [value] is already a JSON literal, a number or a boolean, and is the one field written
     * unquoted. Every other field is quoted and escaped, because a method note may carry measured
     * numbers and punctuation. A non-empty [split], numbers that say where the value's time went
     * (phase 5 unit SC3), is written as the object `split`, which `collect.py` carries through.
     */
    fun publish(
        id: String,
        device: String,
        value: String,
        unit: String,
        method: String,
        split: Map<String, Long> = emptyMap(),
    ) {
        val recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val splitField = if (split.isEmpty()) {
            ""
        } else {
            split.entries.joinToString(",", ",\"split\":{", "}") { (key, number) -> "${quoted(key)}:$number" }
        }
        val fragment = "{\"id\":${quoted(id)},\"device\":${quoted(device)},\"value\":$value," +
            "\"unit\":${quoted(unit)},\"method\":${quoted(method)},\"recordedAt\":${quoted(recordedAt)}$splitField}"

        val context = instrumentation.targetContext
        val staged = File(
            checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" },
            "$STAGING_DIRECTORY/$id.json",
        )
        staged.parentFile?.mkdirs()
        staged.writeText(fragment)

        shell("mkdir -p $DEVICE_GATE_DIRECTORY")
        shell("cp ${staged.path} $DEVICE_GATE_DIRECTORY/$id.json")
        shell("chmod 644 $DEVICE_GATE_DIRECTORY/$id.json")

        val readBack = shell("cat $DEVICE_GATE_DIRECTORY/$id.json")
        if (!readBack.contains("\"id\":\"$id\"")) {
            throw AssertionError("the $id fragment did not land under $DEVICE_GATE_DIRECTORY (read back: $readBack)")
        }
    }

    /**
     * Removes [id]'s fragment, so a run that dies before it publishes leaves nothing behind rather
     * than an earlier run's result. Proven by listing the directory afterwards, for the reason
     * [shell] gives: a failed `rm` reports on `stderr`, which never comes back.
     */
    fun remove(id: String) {
        shell("rm -f $DEVICE_GATE_DIRECTORY/$id.json")
        val listing = shell("ls $DEVICE_GATE_DIRECTORY")
        if (listing.contains("$id.json")) {
            throw AssertionError("the $id fragment under $DEVICE_GATE_DIRECTORY could not be removed (listing: $listing)")
        }
    }

    /**
     * One command through `UiAutomation.executeShellCommand`, as the shell user, and its `stdout`.
     *
     * No shell sits behind it: the argument is split on whitespace and run directly, so `>`, `&&`,
     * `||` and quoting all arrive as plain arguments. And `stderr` is not carried by the returned
     * descriptor, so a command that fails reads back as an empty string. Check a result against
     * `stdout` that only success produces.
     */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString() }

    private fun quoted(text: String): String = buildString {
        append('"')
        for (character in text) {
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }
}
