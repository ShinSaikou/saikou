package com.shinsaikou.appupdate

/**
 * A newer release this device can install: what the prompt shows and what the download needs.
 * [version] is the tag without its `v`, [notes] the release's text already trimmed for the prompt.
 */
data class AppUpdate(
    val version: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    /** The asset's `digest` field as GitHub wrote it, null when it has none. */
    val assetDigest: String?,
)

/** What a release means for the installed build. */
sealed interface ReleaseVerdict {

    /** Newer than the installed build, not skipped, with a file for this device. */
    data class Offer(val update: AppUpdate) : ReleaseVerdict

    /**
     * Nothing to offer: not newer, or not a release at all for this purpose (a draft, a prerelease,
     * a tag that is not a version, an installed version name that is not one either).
     */
    data object UpToDate : ReleaseVerdict

    /** Newer, but the owner chose to skip exactly this version or a newer one. */
    data class Skipped(val version: String) : ReleaseVerdict

    /** Newer, but no `.apk` asset of this build's kind fits this device, so there is nothing to install. */
    data class NoApk(val version: String) : ReleaseVerdict
}

object ReleaseRules {

    /** How much of the release text the prompt shows. */
    const val NOTES_MAX_CHARS = 4_000

    /**
     * Judges [release] against the installed [versionName]. [skippedVersion] is the version the
     * owner skipped (empty for none); it holds back that version and any older one, and a newer
     * release is offered again. [honourSkip] is false for a check the owner asked for, which offers
     * a skipped version anyway. [kind] is the build installed, whose own APK alone is offered.
     */
    fun judge(
        release: GitHubRelease,
        versionName: String,
        skippedVersion: String,
        primaryAbi: String?,
        honourSkip: Boolean,
        kind: ApkKind,
    ): ReleaseVerdict {
        // The latest endpoint never answers with either, but a fork's proxy or a future API might.
        if (release.draft || release.prerelease) return ReleaseVerdict.UpToDate
        val offered = AppVersion.fromTag(release.tagName) ?: return ReleaseVerdict.UpToDate
        val installed = AppVersion.fromVersionName(versionName) ?: return ReleaseVerdict.UpToDate
        if (offered <= installed) return ReleaseVerdict.UpToDate
        val skipped = AppVersion.fromTag(skippedVersion)
        if (honourSkip && skipped != null && offered <= skipped) return ReleaseVerdict.Skipped(offered.toString())
        val asset = ApkAssets.choose(release.assets, primaryAbi, kind) ?: return ReleaseVerdict.NoApk(offered.toString())
        return ReleaseVerdict.Offer(
            AppUpdate(
                version = offered.toString(),
                notes = trimNotes(release.body),
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetDigest = asset.digest,
            ),
        )
    }

    /**
     * The release text as the prompt shows it: Windows line ends made plain, outer blank lines
     * dropped, and anything past [max] characters cut, at a line end when one is near, with an
     * ellipsis to say so.
     */
    fun trimNotes(body: String?, max: Int = NOTES_MAX_CHARS): String {
        val text = body.orEmpty().replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it >= max / 2 } ?: max
        return text.substring(0, cut).trimEnd() + "\n…"
    }
}
