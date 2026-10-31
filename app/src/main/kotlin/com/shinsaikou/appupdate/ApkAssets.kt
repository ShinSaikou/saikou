package com.shinsaikou.appupdate

/** Which of a release's files this device installs. */
object ApkAssets {

    /**
     * The `.apk` asset named for [primaryAbi] (the device's first supported ABI), else one named
     * `universal`, else the only `.apk` there is; null when none of those exists. Only `https`
     * addresses count. The ABI must stand as a word of its own in the name, so `x86` does not pick
     * an `x86_64` file.
     */
    fun choose(assets: List<GitHubAsset>, primaryAbi: String?): GitHubAsset? {
        val apks = assets.filter { it.name.endsWith(APK_SUFFIX, ignoreCase = true) && it.downloadUrl.startsWith(HTTPS) }
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
}
