package com.shinsaikou.domain.category

import eu.kanade.tachiyomi.util.lang.Hash
import java.util.UUID

/**
 * The key two devices match a category by (phase 3 Q7). It is derived from the name the category
 * had when it was created, so the owner's devices, whose categories came from one backup, agree
 * without exchanging anything, and a rename keeps it.
 *
 * Derived keys are 64 lower-case hex characters. [random] gives a row that cannot hold its derived
 * key (a deleted row that shares a name with a live one, or a name whose key a renamed row still
 * holds) a UUID string instead, which can never equal a derived key.
 */
object CategorySyncKey {

    /**
     * Trimmed and lower-cased with Kotlin's Unicode rules, then hashed. The key is never computed
     * in SQL: SQLite's `lower()` folds ASCII only, so a non-ASCII name would get a second key.
     */
    fun of(name: String): String = Hash.sha256(name.trim().lowercase())

    fun random(): String = UUID.randomUUID().toString()
}
