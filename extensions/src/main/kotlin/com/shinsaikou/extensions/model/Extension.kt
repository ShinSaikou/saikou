package com.shinsaikou.extensions.model

import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.source.StubSource
import tachiyomi.domain.extension.model.ExtensionStore
import java.io.File

/**
 * An extension in one of the three states the host can see it in: available in a configured
 * index, installed and loaded, or installed on disk but not trusted.
 */
sealed class Extension {
    /** Android package name of the extension APK. Unique across all three states. */
    abstract val packageName: String

    /** Display name, from `tachiyomix.name` or the index entry. */
    abstract val name: String

    abstract val versionName: String

    abstract val versionCode: Long

    /** Extension ABI major.minor, 1.4 or 1.6 for anything this host loads. */
    abstract val libVersion: Double

    /** Language of the extension's sources; "all" when they disagree, "" when it has none. */
    abstract val lang: String

    abstract val isNsfw: Boolean

    /** Loaded and trusted: its sources are live and published through the source manager. */
    data class Installed(
        override val packageName: String,
        override val name: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        /** The adapters wrapping this extension's ABI sources. */
        val sources: List<com.shinsaikou.source.Source>,
        /** Set by the manager when an index carries a newer version code or lib version. */
        val hasUpdate: Boolean = false,
        /** Set by the manager when no configured index lists this package any more. */
        val isObsolete: Boolean = false,
        /** The index whose signing key trusted it, when the manager could tell. */
        val store: ExtensionStore? = null,
        /** The `.ext` file this was loaded from. */
        val extFile: File,
    ) : Extension()

    /** Listed by a configured index and installable. */
    data class Available(
        override val packageName: String,
        override val name: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val sources: List<AvailableSource>,
        val apkUrl: String,
        val iconUrl: String,
        /** Surfaced for the compat job and the future jar path; the installer ignores it. */
        val jarUrl: String?,
        val store: ExtensionStore,
    ) : Extension()

    /** On disk, but no index key and no manual override vouch for its signer. */
    data class Untrusted(
        override val packageName: String,
        override val name: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        /** The signer fingerprint the user would be trusting; shown before they confirm. */
        val signatureHash: String,
    ) : Extension()
}

/** One source of an [Extension.Available], as the index describes it. */
data class AvailableSource(val id: Long, val name: String, val lang: String, val homeUrl: String) {
    /** The placeholder the library shows for this source until the extension is installed. */
    fun toStub(): StubSource = StubSource(id, name, lang)
}

/** Thrown by the installer flow so a collector sees why the install stopped. */
class InstallFailedException(val reason: InstallRejection) : Exception("Install rejected: $reason")
