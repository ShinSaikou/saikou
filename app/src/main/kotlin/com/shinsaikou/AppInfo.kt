package com.shinsaikou

import android.content.Context
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** The one injected class the scaffold shell resolves through the graph. */
@SingleIn(AppScope::class)
@Inject
class AppInfo(context: Context) {
    val name: String = context.getString(R.string.app_name)
    val commitSha: String = BuildConfig.COMMIT_SHA
    val versionName: String = BuildConfig.VERSION_NAME

    /** Shown beside the name in Settings, Advanced, so a report names the exact build (phase 3 unit AD). */
    val versionCode: Int = BuildConfig.VERSION_CODE
}
