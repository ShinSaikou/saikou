package com.shinsaikou.reader

import android.database.sqlite.SQLiteConstraintException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * [isConstraintFailure] over the shapes each driver gives a refused write (phase 5 unit FX). The
 * JVM driver's `org.sqlite.SQLiteException` is a [SQLException] with the primary result code as its
 * error code, built here as that (sqlite-jdbc is on this module's test runtime only; the real one is
 * thrown in `ReaderViewModelNoteNotSavedTest`). The device's message is the one androidx.sqlite's
 * bundled driver writes; the platform stub's own constructor keeps no message on the JVM, so the
 * test's exception carries it as an override.
 */
class ConstraintFailureTest {

    @Test
    fun `the JVM driver's constraint failures are constraint failures, its other failures are not`() {
        isConstraintFailure(jvm(SQLITE_CONSTRAINT, "FOREIGN KEY constraint failed")) shouldBe true
        isConstraintFailure(jvm(SQLITE_CONSTRAINT, "UNIQUE constraint failed: chapters._id")) shouldBe true
        isConstraintFailure(jvm(SQLITE_BUSY, "database is locked")) shouldBe false
        isConstraintFailure(jvm(SQLITE_IOERR, "disk I/O error")) shouldBe false
    }

    @Test
    fun `the bundled driver's failure is read by the result code in its message`() {
        isConstraintFailure(device("Error code: 787, message: FOREIGN KEY constraint failed")) shouldBe true
        isConstraintFailure(device("Error code: 19, message: FOREIGN KEY constraint failed")) shouldBe true
        isConstraintFailure(device("Error code: 5, message: database is locked")) shouldBe false
        isConstraintFailure(device("Error code: 275, message: CHECK constraint failed")) shouldBe true
        isConstraintFailure(device("FOREIGN KEY constraint failed")) shouldBe false
        isConstraintFailure(device(null)) shouldBe false
    }

    @Test
    fun `the platform driver's constraint exception is a constraint failure`() {
        isConstraintFailure(SQLiteConstraintException()) shouldBe true
    }

    @Test
    fun `a constraint failure is found as a cause, and anything else is not one`() {
        val refused = jvm(SQLITE_CONSTRAINT, "FOREIGN KEY constraint failed")
        isConstraintFailure(IllegalStateException("wrapped", refused)) shouldBe true
        isConstraintFailure(IllegalStateException("manga 7 is not in the database")) shouldBe false
        isConstraintFailure(java.io.IOException("disk full")) shouldBe false
    }

    /** As sqlite-jdbc builds it: the message, no SQL state, the primary result code. */
    private fun jvm(code: Int, message: String) = SQLException(message, null, code)

    private fun device(text: String?) = object : android.database.SQLException() {
        override val message: String? = text
    }

    private companion object {
        const val SQLITE_BUSY = 5
        const val SQLITE_IOERR = 10
        const val SQLITE_CONSTRAINT = 19
    }
}
