package com.shinsaikou.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Upstream's `Context.copyToClipboard`: Android 13 and later confirm a copy themselves, so the
 * [copied] toast is shown only below it, and a clipboard that refuses the copy is logged.
 */
internal fun Context.copyToClipboard(text: String, copied: String) {
    try {
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(text, text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) toast(copied)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Could not copy to the clipboard" }
    }
}
