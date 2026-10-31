package com.shinsaikou.appupdate

/** What the install check reads off an APK, or off the installed app. */
data class PackageFacts(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 of each signing certificate, lowercase hex; empty when unsigned. */
    val signers: Set<String>,
)

/** Why a downloaded update is refused rather than installed. Every refusal deletes the file. */
enum class UpdateRefusal {
    /** The file does not hash to the digest the release published. */
    DIGEST_MISMATCH,

    /** Android cannot read the file as an APK. */
    UNREADABLE,

    /** The APK is another app. */
    OTHER_PACKAGE,

    /** The APK is this app signed with another key, or not signed at all. */
    OTHER_SIGNER,

    /** The APK's version code is not above the installed one, so it is not the update it claims. */
    NOT_NEWER,
}

object ArchiveRules {

    /**
     * Null when [archive] may be installed over [installed]: the same package, the same set of
     * signing certificates, and a higher version code. [archive] is null when Android could not
     * read the file.
     */
    fun judge(archive: PackageFacts?, installed: PackageFacts): UpdateRefusal? = when {
        archive == null -> UpdateRefusal.UNREADABLE
        archive.packageName != installed.packageName -> UpdateRefusal.OTHER_PACKAGE
        archive.signers.isEmpty() || archive.signers != installed.signers -> UpdateRefusal.OTHER_SIGNER
        archive.versionCode <= installed.versionCode -> UpdateRefusal.NOT_NEWER
        else -> null
    }
}
