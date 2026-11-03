package com.shinsaikou.extensions.store

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.repo.ExtensionRepoClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.extension.model.ExtensionStore

/**
 * Re-reads every configured index and writes back the descriptor it resolves to now, so a renamed
 * repository reaches the Extensions screens without the owner removing the row and adding it again.
 * Contract 04 section 3.5 keeps this out of `:domain` because it needs the network and the trust
 * rules.
 *
 * The signing key is pinned when the repository is added: a refresh may fill in a blank key, but a
 * descriptor that names a different key than the row's is not written at all. Trust is "signed by
 * a configured key", so a refresh that swapped the key would let whoever controls the index, or the
 * network path to an `http` one, have their own extensions trusted with no prompt. A repository
 * that really rotated its key is removed and added again, which asks the owner.
 *
 * A row whose index cannot be read is left exactly as it was: a refresh is a convenience, and a
 * transport failure must never drop a repository the owner configured. A row that now redirects
 * somewhere else is also left alone, because the index URL is the primary key and rewriting it
 * here would strand the extensions already installed from it. The one exception is a row with no
 * name, which is what upstream's legacy repository set restores to: its descriptor redirects to
 * the index proper, so it would never get one. It keeps its address and how it is fetched, and
 * takes the name, badge and contact of the index it redirects to, under the same key rule.
 */
@SingleIn(AppScope::class)
@Inject
class UpdateExtensionStores(
    private val repoClient: ExtensionRepoClient,
    private val stores: ExtensionStoreRepository,
) {

    /** Returns the number of rows whose descriptor actually changed. */
    suspend fun await(): Int = supervisorScope {
        stores.getAll()
            .map { row -> async { refresh(row) } }
            .awaitAll()
            .count { it }
    }

    private suspend fun refresh(row: ExtensionStore): Boolean {
        val fetched = repoClient.fetch(row.indexUrl).getOrNull() ?: return false
        val redirected = fetched.store.indexUrl != row.indexUrl
        if (redirected && row.name.isNotBlank()) return false
        val store = if (redirected) {
            row.copy(
                name = fetched.store.name,
                badgeLabel = fetched.store.badgeLabel,
                signingKey = fetched.store.signingKey,
                contact = fetched.store.contact,
            )
        } else {
            fetched.store
        }
        if (store == row) return false
        if (row.signingKey.isNotBlank() && store.signingKey != row.signingKey) {
            logcat(LogPriority.WARN) { "Kept the pinned signing key of a repository whose index now names another" }
            return false
        }
        // Conditional on the row the check read: one removed (or re-keyed) during the fetch stays so.
        return stores.updateIfSigningKey(store, expectedSigningKey = row.signingKey)
    }
}
