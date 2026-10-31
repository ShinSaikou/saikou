package com.shinsaikou.ui.browse

/**
 * A test's body and then its cleanup steps, each run whatever the ones before it did. The first
 * failure is the one [finish] throws, the test's own when the body failed, with every later one
 * suppressed under it: a cleanup step that throws neither skips the steps after it nor hides why
 * the test failed (phase 5 unit TG2's fix round).
 *
 * A step's block must not `return` from the enclosing function, which would skip the steps after it.
 * The block is inlined, so a step in a suspend function may suspend (`FixtureStore.restore`), which
 * a `crossinline` block, refusing such a return, could not.
 */
internal class Teardown {

    private var failure: Throwable? = null

    inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (thrown: Throwable) {
            record(thrown)
        }
    }

    fun record(thrown: Throwable) {
        val first = failure
        if (first == null) failure = thrown else if (first !== thrown) first.addSuppressed(thrown)
    }

    /** Throws the first failure, if any step failed. */
    fun finish() {
        failure?.let { throw it }
    }
}
