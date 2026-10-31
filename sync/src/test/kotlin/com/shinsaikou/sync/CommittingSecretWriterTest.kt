package com.shinsaikou.sync

import android.content.Context
import android.content.SharedPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.io.IOException

/**
 * Fix round 1 item 5, from unit R1's spike: a secret is on disk before `put` or `delete` returns.
 * The store runs as the app builds it, over the app's `AndroidPreferenceStore` and a
 * [CommittingSecretWriter] on one `SharedPreferences`, here a fake that records whether each edit
 * was committed or applied; an `apply()` is what a process killed right after can lose.
 */
class CommittingSecretWriterTest {

    private val file = RecordingSharedPreferences()
    private val store = KeystoreSecretStore(
        AndroidPreferenceStore(mockk<Context>(relaxed = true), file),
        SoftwareSecretCipher(),
        CommittingSecretWriter(file),
    )

    @Test
    fun `put and delete each commit before they return, and nothing is applied`() {
        store.put("drive_refresh_token", "a refresh token")

        file.edits shouldBe listOf("commit")
        file.values.keys shouldBe setOf(Preference.appStateKey("sync_secret_drive_refresh_token"))
        store.get("drive_refresh_token") shouldBe "a refresh token"

        store.delete("drive_refresh_token")

        file.edits shouldBe listOf("commit", "commit")
        file.values.keys shouldBe emptySet()
        store.get("drive_refresh_token").shouldBeNull()
    }

    @Test
    fun `a commit that fails fails the put and the delete loudly`() {
        store.put("webdav_password", "hunter2")
        file.commitSucceeds = false

        shouldThrow<IOException> { store.put("webdav_password", "another") }
        shouldThrow<IOException> { store.delete("webdav_password") }
        file.edits shouldBe listOf("commit", "commit", "commit")
    }

    /** `get`'s contract: a value it cannot open reads absent and is never thrown, even when its delete fails. */
    @Test
    fun `a value that cannot be opened reads absent even when its delete does not commit`() {
        file.values[Preference.appStateKey("sync_secret_webdav_url")] = "not an IV and a ciphertext"
        file.commitSucceeds = false

        store.get("webdav_url").shouldBeNull()
        file.edits shouldBe listOf("commit")
    }

    /**
     * An in-memory `SharedPreferences` that records, per edit, whether it ended in `commit()` or
     * `apply()`. A commit returns [commitSucceeds] and changes nothing when it is false, as a
     * failed write to disk leaves the file.
     */
    private class RecordingSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val edits = mutableListOf<String>()
        var commitSucceeds = true
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = RecordingEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= listener
        }

        private inner class RecordingEditor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)

            override fun remove(key: String): SharedPreferences.Editor {
                removes += key
                return this
            }

            override fun clear(): SharedPreferences.Editor = throw UnsupportedOperationException("not used")

            override fun commit(): Boolean {
                edits += "commit"
                if (!commitSucceeds) return false
                write()
                return true
            }

            override fun apply() {
                edits += "apply"
                write()
            }

            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            private fun write() {
                removes.forEach(values::remove)
                values.putAll(puts)
                (removes + puts.keys).forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@RecordingSharedPreferences, key) } }
            }
        }
    }
}
