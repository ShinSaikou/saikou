package com.shinsaikou.reader

import com.shinsaikou.libraryupdate.LibraryUpdateNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@ContributesBinding(AppScope::class)
@Inject
class AppNewChaptersNotification(private val notifier: LibraryUpdateNotifier) : NewChaptersNotification {
    override fun dismiss(mangaId: Long) = notifier.dismissNewChapters(mangaId)
}
