package com.shinsaikou.reader.state

import android.content.Context
import android.content.Intent
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The chapter's web presence. Contract 07 part 2 section 7.1.
 *
 * `:reader` cannot see `HttpSource`, so it cannot ask "is this an HTTP source"; a null URL is the
 * answer, and the chrome hides the three link actions (decision D27). `:app` binds it with
 * `@ContributesBinding(AppScope::class)`.
 */
interface ChapterWebActions {

    /** Null for a local chapter, a stub source, or any source with no web URL. Called on IO. */
    suspend fun chapterUrl(manga: Manga, chapter: Chapter): String?

    /** Null when no in-app WebView is available; the chrome then falls back to the browser. */
    fun webViewIntent(context: Context, manga: Manga, url: String): Intent?

    fun browserIntent(url: String): Intent

    fun shareIntent(context: Context, url: String): Intent
}
