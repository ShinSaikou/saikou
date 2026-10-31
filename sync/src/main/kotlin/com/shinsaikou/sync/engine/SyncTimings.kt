package com.shinsaikou.sync.engine

import java.util.concurrent.atomic.AtomicLongArray
import kotlin.time.TimeSource

/**
 * Where one run's time went (phase 5 unit SC3): the time spent in each [Step], which the engine logs
 * as one line when the run ends, so a slow sync in the log says which step was slow, and the
 * 40,000-entry bench (`ScaleRowsTest`) reads the split from it. Steps that run beside each other (the
 * uploads of the parts in flight, the pull's decode beside its transactions) each add their own time,
 * so their sum can pass the run's. Safe to add to from several threads.
 */
internal class SyncTimings {

    enum class Step(val key: String) {
        /** The engine's whole run, under its lock. */
        RUN("run"),

        /** The whole pull: the listing, every `done.json`, and each file read and applied. */
        PULL("pull"),

        /** Reading and decoding the pulled files' rows, the network's wait included. */
        PULL_DECODE("pullDecode"),

        /** The pull's transactions. */
        PULL_APPLY("pullApply"),

        /** The whole push: the full export when one is due, then the segments. */
        PUSH("push"),

        /** The full export, from its start to its progress deleted. */
        EXPORT("export"),

        /** The export's database reads and the planning of each part. */
        EXPORT_PLAN("exportPlan"),

        /** Encoding each planned part. */
        EXPORT_ENCODE("exportEncode"),

        /**
         * Each file's `create` (the export's parts, its `done.json`, the segments), the backend's own
         * read-back included.
         */
        CREATE("create"),

        /** The engine's own read and compare of each file it wrote. */
        READ_BACK("readBack"),

        /** Writing the export's `done.json`, its create and read-back included. */
        EXPORT_DONE("exportDone"),

        /** The segments pushed after the export, or as the whole push. */
        SEGMENTS("segments"),

        /** Deletion and compaction after the push. */
        COMPACTION("compaction"),
    }

    private val nanos = AtomicLongArray(Step.entries.size)

    /** The parts the run's export wrote, 0 when it made none. */
    @Volatile
    var exportParts: Int = 0

    /** [block], its time added to [step], whether it returns or throws. */
    inline fun <T> time(step: Step, block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            add(step, mark.elapsedNow().inWholeNanoseconds)
        }
    }

    fun add(step: Step, elapsedNanos: Long) {
        nanos.addAndGet(step.ordinal, elapsedNanos)
    }

    /** Each step as `key=<ms>`, then `exportParts=<n>`, separated by spaces. */
    fun line(): String = Step.entries.joinToString(" ", postfix = " exportParts=$exportParts") { step ->
        "${step.key}=${nanos.get(step.ordinal) / NANOS_PER_MS}"
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
