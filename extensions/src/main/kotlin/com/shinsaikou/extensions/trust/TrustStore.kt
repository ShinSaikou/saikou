package com.shinsaikou.extensions.trust

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.preferences.ExtensionsPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.getAndSet

/**
 * Decides whether an extension on disk may run. Two sources of trust, OR'd, as upstream does:
 *
 * 1. Repo-rooted. Every configured index row carries a signing key; an APK whose signer set
 *    intersects the configured keys is trusted with no prompt. Trust is by fingerprint, not by
 *    which index served the APK.
 * 2. Manual. The `trusted_extensions` preference holds `"<pkg>:<versionCode>:<sha256>"` entries the
 *    user confirmed. The key is upstream's, so a restored preference backup carries manual trust
 *    across devices.
 */
@SingleIn(AppScope::class)
@Inject
class TrustStore(
    private val stores: ExtensionStoreRepository,
    private val preferences: ExtensionsPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Reads the index table and the preference. Never trusts an unsigned APK. */
    suspend fun isTrusted(packageName: String, versionCode: Long, fingerprints: List<String>): Boolean {
        if (fingerprints.isEmpty()) return false
        val repoKeys = withContext(ioDispatcher) { stores.getAll().mapTo(HashSet()) { it.signingKey } }
        if (fingerprints.any { it in repoKeys }) return true
        return manualKey(packageName, versionCode, fingerprints.last()) in preferences.trustedExtensions.get()
    }

    /** Manual override for one package and version; supersedes any older entry for the package. */
    fun trust(packageName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { current ->
            current.filterNot { it.startsWith("$packageName:") }
                .toMutableSet()
                .also { it += manualKey(packageName, versionCode, signatureHash) }
        }
    }

    /** Clears every manual override. Repo-rooted trust is unaffected. */
    fun revokeAll() = preferences.trustedExtensions.delete()

    /** Live view for the settings screen: the raw `pkg:versionCode:sha256` entries. */
    fun manualOverrides(): Flow<Set<String>> = preferences.trustedExtensions.changes()

    private fun manualKey(packageName: String, versionCode: Long, signatureHash: String): String =
        "$packageName:$versionCode:$signatureHash"
}
