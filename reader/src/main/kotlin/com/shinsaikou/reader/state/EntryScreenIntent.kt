package com.shinsaikou.reader.state

import android.content.Context
import android.content.Intent

/**
 * The intent that opens an entry's screen. The screen is `:app`'s and `:reader` cannot reach it, so
 * `:app` binds this. The top bar's title opens it, as upstream's `openMangaScreen` does.
 */
fun interface EntryScreenIntent {
    fun create(context: Context, mangaId: Long): Intent
}
