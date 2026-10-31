package com.shinsaikou.reader.state

/**
 * True when nothing about this session may be written: progress, history, tracking.
 * Contract 07 section 1.2.
 *
 * `:reader` cannot see the extension host, so `:app` binds this with
 * `@ContributesBinding(AppScope::class)` as the global incognito preference or an incognito source.
 * Bookmarks are deliberately outside it, as in upstream.
 */
interface IncognitoPolicy {
    fun isIncognito(sourceId: Long): Boolean
}
