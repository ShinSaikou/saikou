package com.shinsaikou.extensions.loader

import com.shinsaikou.extensions.model.Extension
import java.io.File

/** Outcome of loading one `.ext` file. */
sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult

    data class Untrusted(val extension: Extension.Untrusted) : LoadResult

    /**
     * [packageName] is null when the file could not even be parsed as a package. [name],
     * [versionCode] and [libVersion] are the archive's, when it could be read: an update is offered
     * against them, which is often what gets the extension loading again. [file] is what an uninstall
     * removes when there is no package name to remove it by.
     */
    data class Error(
        val packageName: String?,
        val reason: LoadError,
        val cause: Throwable? = null,
        val name: String? = null,
        val versionCode: Long? = null,
        val libVersion: Double? = null,
        val file: File? = null,
    ) : LoadResult
}

/** Why a load failed. Rendered through [com.shinsaikou.extensions.ExtensionMessages]. */
enum class LoadError {
    /** The APK carries no `tachiyomi.extension` feature, or could not be parsed at all. */
    NOT_AN_EXTENSION,

    /** No `tachiyomi.extension.class` metadata. Upstream crashes here; this loader reports it. */
    MISSING_SOURCE_CLASS,

    UNSUPPORTED_LIB_VERSION,

    UNSIGNED,

    /** `Class.forName` raised `ClassNotFoundException` or a `LinkageError`. */
    CLASS_LOAD_FAILED,

    /** The entry-point constructor or `createSources()` threw. */
    INSTANTIATION_FAILED,

    /** The entry point is neither a `Source` nor a `SourceFactory`. */
    UNKNOWN_SOURCE_TYPE,

    /** A load was attempted before the Injekt singletons were registered. */
    INJEKT_NOT_READY,

    /**
     * Not loaded at all: the process started without extensions, from the crash screen. Only the
     * package was read, so the list can still offer its Uninstall and its update.
     */
    EXTENSIONS_OFF,
}

/** Outcome of copying an APK into the private extension directory. */
sealed interface InstallOutcome {
    data class Installed(val packageName: String, val replaced: Boolean) : InstallOutcome

    data class Rejected(val reason: InstallRejection) : InstallOutcome
}

/** Why an install was refused. Rendered through [com.shinsaikou.extensions.ExtensionMessages]. */
enum class InstallRejection {
    NOT_AN_EXTENSION,

    /** The candidate's version code is lower than the installed one. */
    DOWNGRADE,

    UNSIGNED,

    /** The candidate is not signed by every signer of the installed copy. */
    SIGNER_MISMATCH,

    /** A download is a different package than the one it was fetched for. Refused before the copy. */
    PACKAGE_MISMATCH,

    COPY_FAILED,

    /**
     * Built for a lib version this host does not load. Refused before the copy, so an update the
     * catalog moved to a newer lib never replaces a copy that works with one that cannot load.
     */
    UNSUPPORTED_LIB_VERSION,
}
