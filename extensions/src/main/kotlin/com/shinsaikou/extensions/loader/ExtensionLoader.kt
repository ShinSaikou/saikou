package com.shinsaikou.extensions.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Trace
import androidx.core.content.pm.PackageInfoCompat
import com.shinsaikou.extensions.adapter.SourceAdapters
import com.shinsaikou.extensions.di.InjektBootstrap
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.extensions.trust.TrustStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Reads, installs and loads private extension APKs.
 *
 * Private installs only: there is no `getInstalledPackages` scan, no shared-package branch and no
 * install broadcast receiver, because nothing here is installed through the system package
 * installer. One file per extension, `filesDir/exts/<packageName>.ext`, an unmodified APK renamed.
 *
 * Every `PackageManager` call, file copy and `Class.forName` runs on [ioDispatcher]; no caller ever
 * has to remember to switch, which is what makes a cold start free of the blocking load upstream does.
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionLoader(
    private val context: Context,
    private val trustStore: TrustStore,
    private val libVersionPolicy: LibVersionPolicy,
    private val injektBootstrap: InjektBootstrap,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** `filesDir/exts`, created on first use. */
    val privateDir: File by lazy {
        File(context.filesDir, ExtensionMetadata.PRIVATE_DIR).also { it.mkdirs() }
    }

    /** The `.ext` file a package would be installed to, whether or not it exists. */
    fun extFileFor(packageName: String): File =
        File(privateDir, "$packageName.${ExtensionMetadata.PRIVATE_EXTENSION_EXTENSION}")

    /** Parses an APK on disk without installing it. Null when the file is not an extension. */
    suspend fun readArchive(file: File): ExtensionArchive? = withContext(ioDispatcher) { readArchiveBlocking(file) }

    /**
     * Installs [file], a complete APK, into the private directory.
     *
     * Refuses a lib version [load] would refuse, a downgrade, an unsigned APK, and a signer set that
     * does not contain every signer of the copy already installed. Trust is not checked here: an
     * untrusted install lands in the untrusted list with a trust action, which is upstream's
     * behaviour. Does not load; the caller calls [loadPackage] next.
     */
    suspend fun installPrivateExtensionFile(file: File): InstallOutcome = withContext(ioDispatcher) {
        val candidate = readArchiveBlocking(file) ?: return@withContext InstallOutcome.Rejected(InstallRejection.NOT_AN_EXTENSION)
        // Before anything is written: a downgrade is refused, so a copy that cannot load would have
        // no way back to the version that could.
        if (!libVersionPolicy.isSupported(candidate.libVersion)) {
            this@ExtensionLoader.logcat(LogPriority.WARN) { "Refused an install built for lib ${candidate.libVersion}" }
            return@withContext InstallOutcome.Rejected(InstallRejection.UNSUPPORTED_LIB_VERSION)
        }
        val target = extFileFor(candidate.packageName)
        val installed = if (target.isFile) readArchiveBlocking(target) else null

        if (installed != null) {
            if (candidate.versionCode < installed.versionCode) {
                this@ExtensionLoader.logcat(LogPriority.ERROR) { "Installed extension version is higher; downgrading is not allowed" }
                return@withContext InstallOutcome.Rejected(InstallRejection.DOWNGRADE)
            }
            if (candidate.signerFingerprints.isEmpty()) {
                return@withContext InstallOutcome.Rejected(InstallRejection.UNSIGNED)
            }
            if (!candidate.signerFingerprints.containsAll(installed.signerFingerprints)) {
                this@ExtensionLoader.logcat(LogPriority.ERROR) { "Signer set does not match the installed copy" }
                return@withContext InstallOutcome.Rejected(InstallRejection.SIGNER_MISMATCH)
            }
        }

        try {
            file.copyAndSetReadOnlyTo(target)
            InstallOutcome.Installed(candidate.packageName, replaced = installed != null)
        } catch (e: Exception) {
            // The copy goes through a temporary file, so the installed copy, if any, is untouched.
            this@ExtensionLoader.logcat(LogPriority.ERROR, e) { "Failed to copy extension file" }
            InstallOutcome.Rejected(InstallRejection.COPY_FAILED)
        }
    }

    /** Deletes `exts/<packageName>.ext`. */
    suspend fun uninstallPrivateExtension(packageName: String) {
        withContext(ioDispatcher) { extFileFor(packageName).delete() }
    }

    /**
     * Deletes a file [loadAll] could not read as a package, which has no package name to uninstall
     * it by. Only ever a file in [privateDir]: the path arrives from a load result, not from a user.
     */
    suspend fun deleteUnreadable(file: File) {
        withContext(ioDispatcher) {
            if (file.canonicalFile.parentFile == privateDir.canonicalFile) file.delete()
        }
    }

    /**
     * Discovers every `.ext` file and loads them in parallel. A failure in one extension never
     * fails the pass; an unreadable file stays on disk and is reported so the user can remove it
     * from the extension list.
     */
    suspend fun loadAll(): List<LoadResult> = withContext(ioDispatcher) {
        val files = privateDir.listFiles()
            ?.filter { it.isFile && it.extension == ExtensionMetadata.PRIVATE_EXTENSION_EXTENSION }
            ?: return@withContext emptyList()
        coroutineScope {
            files.map { file ->
                async {
                    val archive = readArchiveBlocking(file)
                    if (archive == null) {
                        LoadResult.Error(null, LoadError.NOT_AN_EXTENSION, file = file)
                    } else {
                        load(archive)
                    }
                }
            }.awaitAll()
        }
    }

    /** Loads one installed package, or null when no `.ext` file exists for it. */
    suspend fun loadPackage(packageName: String): LoadResult? = withContext(ioDispatcher) {
        val file = extFileFor(packageName)
        if (!file.isFile) return@withContext null
        val archive = readArchiveBlocking(file)
            ?: return@withContext LoadResult.Error(packageName, LoadError.NOT_AN_EXTENSION, file = file)
        load(archive)
    }

    /** Loads from an archive already read off disk; used by install and by the trust flow. */
    suspend fun load(archive: ExtensionArchive): LoadResult = withContext(ioDispatcher) {
        if (!injektBootstrap.isInstalled) {
            return@withContext archive.error(LoadError.INJEKT_NOT_READY)
        }
        val libVersion = archive.libVersion
        if (libVersion == null || !libVersionPolicy.isSupported(libVersion)) {
            this@ExtensionLoader.logcat(LogPriority.WARN) {
                "Lib version $libVersion is outside ${libVersionPolicy.supported().joinToString()}"
            }
            return@withContext archive.error(LoadError.UNSUPPORTED_LIB_VERSION)
        }
        if (archive.signerFingerprints.isEmpty()) {
            return@withContext archive.error(LoadError.UNSIGNED)
        }
        if (!trustStore.isTrusted(archive.packageName, archive.versionCode, archive.signerFingerprints)) {
            return@withContext LoadResult.Untrusted(
                Extension.Untrusted(
                    packageName = archive.packageName,
                    name = archive.name,
                    versionName = archive.versionName,
                    versionCode = archive.versionCode,
                    libVersion = libVersion,
                    lang = "",
                    isNsfw = archive.isNsfw,
                    signatureHash = archive.signerFingerprints.last(),
                ),
            )
        }
        if (archive.sourceClassNames.isEmpty()) {
            return@withContext archive.error(LoadError.MISSING_SOURCE_CLASS)
        }

        val classLoader = try {
            DelegateLastClassLoaderCompat(archive.file.absolutePath, null, context.classLoader)
        } catch (e: Throwable) {
            return@withContext archive.error(LoadError.CLASS_LOAD_FAILED, e)
        }

        val abiSources = mutableListOf<Source>()
        for (className in archive.sourceClassNames) {
            val instance = try {
                Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()
            } catch (e: ClassNotFoundException) {
                return@withContext archive.error(LoadError.CLASS_LOAD_FAILED, e)
            } catch (e: LinkageError) {
                return@withContext archive.error(LoadError.CLASS_LOAD_FAILED, e)
            } catch (e: Throwable) {
                return@withContext archive.error(LoadError.INSTANTIATION_FAILED, e)
            }
            when (instance) {
                is Source -> abiSources += instance
                is SourceFactory -> abiSources += try {
                    // A factory that returns nothing is allowed, as it is in upstream.
                    instance.createSources()
                } catch (e: Throwable) {
                    return@withContext archive.error(LoadError.INSTANTIATION_FAILED, e)
                }
                else -> return@withContext archive.error(LoadError.UNKNOWN_SOURCE_TYPE)
            }
        }

        val sources = abiSources.map { SourceAdapters.wrap(it, archive.packageName, libVersion) }
        val languages = sources.mapTo(mutableSetOf()) { it.lang }
        LoadResult.Success(
            Extension.Installed(
                packageName = archive.packageName,
                name = archive.name,
                versionName = archive.versionName,
                versionCode = archive.versionCode,
                libVersion = libVersion,
                lang = when (languages.size) {
                    0 -> ""
                    1 -> languages.first()
                    else -> "all"
                },
                isNsfw = archive.isNsfw,
                sources = sources,
                extFile = archive.file,
            ),
        )
    }

    private fun readArchiveBlocking(file: File): ExtensionArchive? {
        // Android 14 and up refuse to load a dex from a file that is writable.
        if (file.canWrite()) file.setReadOnly()

        val path = file.absolutePath
        val packageManager = context.packageManager
        val packageInfo = parseArchive(packageManager, path) ?: return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        applicationInfo.fixBasePaths(path)

        val isExtension = packageInfo.reqFeatures.orEmpty().any { it.name == ExtensionMetadata.FEATURE }
        if (!isExtension) return null

        val metaData = applicationInfo.metaData
        val versionName = packageInfo.versionName
        if (versionName.isNullOrEmpty()) return null

        val name = metaData?.getString(ExtensionMetadata.NAME)
            ?: packageManager.getApplicationLabel(applicationInfo).toString().substringAfter("Tachiyomi: ")
        val libVersion = metaData?.getFloat(ExtensionMetadata.EXTENSION_LIB)
            ?.takeUnless { it == 0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        val sourceClassNames = metaData?.getString(ExtensionMetadata.SOURCE_CLASS)
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { if (it.startsWith(".")) packageInfo.packageName + it else it }
            .orEmpty()

        return ExtensionArchive(
            file = file,
            packageInfo = packageInfo,
            packageName = packageInfo.packageName,
            name = name,
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            libVersion = libVersion,
            isNsfw = (metaData?.getInt(ExtensionMetadata.CONTENT_WARNING) ?: 0) > 0 ||
                metaData?.getInt(ExtensionMetadata.NSFW) == 1,
            sourceClassNames = sourceClassNames,
            signerFingerprints = ExtensionSignatures.fingerprints(packageInfo).orEmpty(),
        )
    }
}

/** A load failure of this archive, carrying what it could tell about itself. */
private fun ExtensionArchive.error(reason: LoadError, cause: Throwable? = null) = LoadResult.Error(
    packageName = packageName,
    reason = reason,
    cause = cause,
    name = name,
    versionCode = versionCode,
    libVersion = libVersion,
    file = file,
)

/**
 * The archive parse, inside a trace section. `getPackageArchiveInfo` parses the APK inside this
 * process rather than asking the system, so the binder slices of a system trace do not show it and
 * only the application can say it happened. Upstream's #3703 was this parse running on the main
 * thread for every installed extension at start-up; `tools/bench/perfetto_metrics.py --metric
 * main_thread_pm_okhttp_slices` counts the section when it lands there. Product observability,
 * compiled into every build and one JNI call when nothing is tracing, so it is not a code path a
 * test owns (R39).
 */
private fun parseArchive(packageManager: PackageManager, path: String): PackageInfo? {
    Trace.beginSection(SECTION_ARCHIVE_PARSE)
    try {
        return packageManager.getPackageArchiveInfo(path, ExtensionMetadata.PACKAGE_FLAGS)
    } finally {
        Trace.endSection()
    }
}

/** The slice name the cold start trace query matches with `PackageManager*`. */
private const val SECTION_ARCHIVE_PARSE = "PackageManager.getPackageArchiveInfo"

/**
 * On Android 13 and up the `ApplicationInfo` from `getPackageArchiveInfo` has no `sourceDir`, which
 * breaks asset loading and therefore the extension icon.
 */
private fun ApplicationInfo.fixBasePaths(apkPath: String) {
    if (sourceDir == null) sourceDir = apkPath
    if (publicSourceDir == null) publicSourceDir = apkPath
}
