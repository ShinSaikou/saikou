package com.shinsaikou.ui.about

import com.shinsaikou.appupdate.ApkAssets
import com.shinsaikou.appupdate.ApkKind
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.AppVersion
import com.shinsaikou.appupdate.GitHubRelease

/** One APK of the latest release, as the About screen's Builds list shows it. */
data class BuildAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val kind: ApkKind,
    /** The release's version, `0.1.2`, or its tag as written when that is not a version. */
    val version: String,
    val action: Action,
) {
    enum class Action {
        /** This build's own APK at the version installed: nothing to install. */
        INSTALLED,

        /** This build's own APK, newer: the in-app updater installs it over this app. */
        UPDATE,

        /** Any other APK: the browser downloads it, and Android installs it next to this app. */
        DOWNLOAD,
    }
}

object AboutBuilds {

    /**
     * The `.apk` assets of [release], the release build first, each with what a tap does on this
     * [build]. Only the file the updater would pick for this build ([ApkAssets.choose]) is ever
     * installed in place, since the updater refuses any other package; the rest open in the
     * browser, the other build's APK included, which Android installs as an app of its own.
     */
    fun of(release: GitHubRelease, build: AppUpdateBuild): List<BuildAsset> {
        val offered = AppVersion.fromTag(release.tagName)
        val installed = AppVersion.fromVersionName(build.versionName)
        val own = ApkAssets.choose(release.assets, build.primaryAbi, build.apkKind)
        val ownAction = when {
            offered == null || installed == null -> BuildAsset.Action.DOWNLOAD
            offered == installed -> BuildAsset.Action.INSTALLED
            offered > installed -> BuildAsset.Action.UPDATE
            else -> BuildAsset.Action.DOWNLOAD
        }
        val version = offered?.toString() ?: release.tagName
        return release.assets
            .mapNotNull { asset ->
                val kind = ApkAssets.kindOf(asset) ?: return@mapNotNull null
                BuildAsset(
                    name = asset.name,
                    url = asset.downloadUrl,
                    sizeBytes = asset.size,
                    kind = kind,
                    version = version,
                    action = if (asset == own) ownAction else BuildAsset.Action.DOWNLOAD,
                )
            }
            .sortedBy { it.kind.ordinal }
    }
}
