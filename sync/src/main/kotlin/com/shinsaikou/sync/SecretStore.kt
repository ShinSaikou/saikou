package com.shinsaikou.sync

/**
 * Where sync keeps what signs it in: the Drive refresh token and the WebDAV URL, user name and
 * password (phase 3 Q8). Every value stays on this device. Tracker credentials are not kept here,
 * because they travel in the backup's private settings.
 */
interface SecretStore {

    /**
     * The secret stored under [name], or null when there is none. A value that can no longer be
     * decrypted (a bad tag, a key that is missing or invalidated, a Keystore that cannot be loaded
     * or reached) is deleted and read as null, never thrown, so a backend reports signed out rather
     * than a failure it retries.
     */
    fun get(name: String): String?

    /** Stores [value] under [name], on disk before it returns; throws `IOException` when it is not. */
    fun put(name: String, value: String)

    /** Removes [name]'s secret, gone from disk before it returns; throws `IOException` when it is not. */
    fun delete(name: String)
}
