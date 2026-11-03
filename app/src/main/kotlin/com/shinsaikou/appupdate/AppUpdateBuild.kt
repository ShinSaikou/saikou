package com.shinsaikou.appupdate

/**
 * What the updater needs to know about this build and this device, read once from `BuildConfig`
 * and `Build` by [AppUpdateBindings] so the checker's tests can hand in their own.
 */
data class AppUpdateBuild(
    /** `owner/name` of the GitHub repository whose latest release is offered (`-Psaikou.updateRepo`). */
    val repository: String,
    /** `BuildConfig.VERSION_NAME`, a build type suffix included. */
    val versionName: String,
    /** Whether coming to the foreground checks by itself: a release build does, a debug or benchmark build never. */
    val automaticChecks: Boolean,
    /** `Build.SUPPORTED_ABIS[0]`, which picks the release's file. */
    val primaryAbi: String?,
    /** Which of the release's APKs this build updates from: the debug APK's own for publicDebug, else the release's. */
    val apkKind: ApkKind = ApkKind.RELEASE,
)
