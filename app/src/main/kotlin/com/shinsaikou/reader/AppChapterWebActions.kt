package com.shinsaikou.reader

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.source.toChapterInfo
import com.shinsaikou.reader.state.ChapterWebActions
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The reader's web actions, answered through the source contract rather than through `HttpSource`:
 * `Source.getChapterUrl` already returns null for the Local source, for a stub, and for any
 * extension source with no web presence, which is exactly the "not an HTTP source" signal the
 * reader's chrome reads.
 *
 * [webViewIntent] returns null because no in-app WebView screen exists yet; the chrome falls back
 * to the browser, which the interface documents as the supported behaviour.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppChapterWebActions(
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : ChapterWebActions {

    override suspend fun chapterUrl(manga: Manga, chapter: Chapter): String? = withContext(dispatchers.io) {
        val source = sourceManager.get(manga.source) ?: return@withContext null
        // A source builds this string itself, so a throwing extension must not take the chrome down.
        runCatching { source.getChapterUrl(chapter.toChapterInfo()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Source ${manga.source} failed to build a chapter url" } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    override fun webViewIntent(context: Context, manga: Manga, url: String): Intent? = null

    override fun browserIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun shareIntent(context: Context, url: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
