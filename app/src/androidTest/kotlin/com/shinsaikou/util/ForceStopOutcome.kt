package com.shinsaikou.util

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The outcome file that carries a force-stop row across the force-stop (phase 2 decision Q8 (a)(i)).
 *
 * An instrumentation cannot outlive the process it runs in, so a row about what survives
 * `am force-stop` is two runs: the first leaves its state behind and writes what it left to
 * `/data/local/tmp/saikou-force-stop/<row>.json`; `tools/ci/gate.sh` pulls the file, reads it with
 * `tools/ci/force_stop_outcome.py`, force-stops the package and starts the second run, which reads
 * the file back here and compares. The file is outside the application's own storage on purpose:
 * nothing the application does to its data can change what the second run compares against.
 *
 * Written the way [GateFragments.publish] writes a fragment, and for the same reason: the
 * application cannot create a file under `/data/local/tmp`, so the file is staged in its external
 * files directory and copied across by a shell command, then read back before [write] returns.
 *
 * Infrastructure rather than a test, like [GateFragments].
 */
object ForceStopOutcome {

    /** Where the gate step pulls the outcome from, and removes it from before the first run. */
    const val DEVICE_DIRECTORY = "/data/local/tmp/saikou-force-stop"

    /** What `force_stop_outcome.py` reads. A change here is a change there. */
    const val SCHEMA = 1

    private const val STAGING_DIRECTORY = "saikou-force-stop"

    fun path(row: String): String = "$DEVICE_DIRECTORY/$row.json"

    /** Writes [json], an object carrying [SCHEMA], for [row], and proves it landed. */
    fun write(row: String, json: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = File(
            checkNotNull(context.getExternalFilesDir(null)) { "no external files directory" },
            "$STAGING_DIRECTORY/$row.json",
        )
        staged.parentFile?.mkdirs()
        staged.writeText(json)

        GateFragments.shell("mkdir -p $DEVICE_DIRECTORY")
        GateFragments.shell("cp ${staged.path} ${path(row)}")
        GateFragments.shell("chmod 644 ${path(row)}")
        staged.delete()

        val readBack = read(row)
        if (readBack != json) {
            throw AssertionError("the $row outcome did not land at ${path(row)} (read back: $readBack)")
        }
    }

    /**
     * The outcome part one wrote, or null when there is none. A failed `cat` says so only on
     * `stderr`, which [GateFragments.shell] never returns, so a missing file reads as empty.
     */
    fun read(row: String): String? = GateFragments.shell("cat ${path(row)}").takeIf { it.isNotBlank() }

    /** Removes [row]'s outcome, so a second run can never read one twice. Proven by listing. */
    fun remove(row: String) {
        GateFragments.shell("rm -f ${path(row)}")
        if (GateFragments.shell("ls $DEVICE_DIRECTORY").contains("$row.json")) {
            throw AssertionError("the $row outcome at ${path(row)} could not be removed")
        }
    }
}
