package com.shinsaikou.reader

import android.content.Context
import android.content.Intent
import com.shinsaikou.reader.state.EntryScreenIntent
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The entry intent the new-chapters notification sends too. Its clear-top flag brings the main
 * activity under the reader forward, and [SaikouNavigator.arrive][com.shinsaikou.navigation.SaikouNavigator.arrive]
 * leaves the entry's screen alone when it is the one already on top, which it is when the reader
 * was opened from it.
 */
@ContributesBinding(AppScope::class)
@Inject
class AppEntryScreenIntent : EntryScreenIntent {
    override fun create(context: Context, mangaId: Long): Intent =
        MainIntents.newIntent(context, MainDestination.ENTRY, mangaId)
}
