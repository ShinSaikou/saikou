package com.shinsaikou.reader

import android.database.sqlite.SQLiteConstraintException

/**
 * True when [error], or a cause of it, is the database refusing a write on a constraint: a page
 * note for a chapter whose row a second window's Clear database removed fails `bookmark_notes`'
 * foreign key (R128 carry-over 1). Three shapes, one per driver that can run the write:
 *
 * - the app's driver on a device (`DatabaseBindings`: SQLDelight over androidx.sqlite's
 *   `BundledSQLiteDriver`) throws `android.database.SQLException` itself, the class
 *   `androidx.sqlite.SQLiteException` is an alias of on Android, with the result code only in its
 *   message, `Error code: 787, message: FOREIGN KEY constraint failed`;
 * - the platform's own driver throws [SQLiteConstraintException];
 * - the JVM test driver (SQLDelight's `JdbcSqliteDriver` over sqlite-jdbc) throws
 *   `org.sqlite.SQLiteException`, a [java.sql.SQLException] whose error code is the primary result
 *   code.
 *
 * SQLite's primary code for every constraint is [SQLITE_CONSTRAINT]; an extended code (787 for a
 * foreign key) keeps it in its low byte.
 */
internal fun isConstraintFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(MAX_CAUSES).any { cause ->
        when (cause) {
            is SQLiteConstraintException -> true
            is android.database.SQLException -> cause.message?.let(::resultCodeOf)?.isConstraint() == true
            is java.sql.SQLException -> cause.errorCode.isConstraint()
            else -> false
        }
    }

private fun resultCodeOf(message: String): Int? = RESULT_CODE.find(message)?.groupValues?.get(1)?.toIntOrNull()

private fun Int.isConstraint(): Boolean = this and PRIMARY_CODE_MASK == SQLITE_CONSTRAINT

/** androidx.sqlite's native `throwSQLiteException` writes `Error code: %d, message: %s`. */
private val RESULT_CODE = Regex("""^Error code: (\d+)""")

private const val SQLITE_CONSTRAINT = 19
private const val PRIMARY_CODE_MASK = 0xff

/** A cause chain is short; the bound only guards against a chain that loops back on itself. */
private const val MAX_CAUSES = 8
