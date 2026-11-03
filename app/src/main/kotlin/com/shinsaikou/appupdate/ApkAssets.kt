package com.shinsaikou.appupdate

/**
 * The two builds a release carries: the app itself (`app-release.apk`), and the debug APK that
 * installs next to it under its own package (`app-debug.apk`).
 */
enum class ApkKind {
    RELEASE,
    DEBUG,
}

/** Which of a release's files this device installs. */
object ApkAssets {

    /**
     * [DEBUG][ApkKind.DEBUG] for an `.apk` with `debug` as a word of its own in its name,
     * [RELEASE][ApkKind.RELEASE] for any other `.apk`; null for a file that is not an APK or has no
     * `https` address.
     */
    fun kindOf(asset: GitHubAsset): ApkKind? = when {
        !asset.name.endsWith(APK_SUFFIX, ignoreCase = true) || !asset.downloadUrl.startsWith(HTTPS) -> null
        DEBUG_WORD.containsMatchIn(asset.name.dropLast(APK_SUFFIX.length)) -> ApkKind.DEBUG
        else -> ApkKind.RELEASE
    }

    /**
     * Among the `.apk` assets of [kind] (the build that is installed: a release never takes the
     * debug APK, nor the debug APK a release), the one named for [primaryAbi] (the device's first
     * supported ABI), else one named `universal`, else the only one there is; null when none of
     * those exists. Only `https` addresses count. The ABI must stand as a word of its own in the
     * name, so `x86` does not pick an `x86_64` file.
     */
    fun choose(assets: List<GitHubAsset>, primaryAbi: String?, kind: ApkKind): GitHubAsset? {
        val apks = assets.filter { kindOf(it) == kind }
        if (!primaryAbi.isNullOrBlank()) {
            val word = Regex("(?<![A-Za-z0-9_])" + Regex.escape(primaryAbi) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
            apks.firstOrNull { word.containsMatchIn(it.name) }?.let { return it }
        }
        apks.firstOrNull { it.name.contains(UNIVERSAL, ignoreCase = true) }?.let { return it }
        return apks.singleOrNull()
    }

    private const val APK_SUFFIX = ".apk"
    private const val UNIVERSAL = "universal"
    private const val HTTPS = "https://"

    /** `debug` between separators: `app-debug`, `saikou_debug_x86_64`, never `debugger` or `nodebug`. */
    private val DEBUG_WORD = Regex("(?<![A-Za-z0-9])debug(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
}
