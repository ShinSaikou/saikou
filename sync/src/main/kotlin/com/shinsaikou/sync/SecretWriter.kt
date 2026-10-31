package com.shinsaikou.sync

import android.content.SharedPreferences
import java.io.IOException

/**
 * How [KeystoreSecretStore] writes: a stored value is on disk before [write] returns (phase 3 unit O
 * fix round 1 item 5, from unit R1's spike). A `Preference`'s own `set` and `delete` are
 * `SharedPreferences.apply()`, which a process that dies right after can lose, and a secret lost that
 * way is a sign-in that silently did not happen. Only the secret store writes this way; every other
 * preference keeps `apply()`.
 *
 * The application binds [CommittingSecretWriter] over the file the app's `PreferenceStore` reads, so
 * reads go on through the store; a JVM test writes into its fake store instead.
 */
fun interface SecretWriter {

    /**
     * Stores [value] under the preference [key], or removes [key] when [value] is null, and returns
     * once it is on disk.
     *
     * @throws IOException when the write did not reach the disk.
     */
    fun write(key: String, value: String?)
}

/** [SecretWriter] over [preferences]: one `commit()` per write, and a commit that fails throws. */
class CommittingSecretWriter(private val preferences: SharedPreferences) : SecretWriter {

    override fun write(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        if (!editor.commit()) throw IOException("The preference file did not take the write of $key")
    }
}
