package com.shinsaikou.ui.main

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Where an intent sent to [MainActivity] from outside the app asks it to go. */
enum class MainDestination {
    LIBRARY,
    UPDATES,
    HISTORY,
    BROWSE,
    EXTENSIONS,

    /** The chapter list of the entry in [MainIntents.mangaIdOf]. */
    ENTRY,

    /** The error sheet of the last library update run. */
    UPDATE_ERRORS,

    /** The counts and errors of the last backup import. */
    IMPORT_REPORT,

    /** A backup file was opened from outside the app; [MainIntents.dataUriOf] carries it. */
    RESTORE_BACKUP,

    /** An extension index address arrived as a link; [MainIntents.storeUrlOf] carries it. */
    EXTENSION_STORE,

    /** The download queue, which the download notifications open. */
    DOWNLOAD_QUEUE,

    /** The download settings, which the adoption notifications open for the adoption summary. */
    DOWNLOAD_SETTINGS,

    /** The sync settings, which sync's notices open: Sign in again and Sync now live there. */
    SYNC_SETTINGS,
    /** Settings > Data, which the storage location move's notifications open (unit storage-location). */
    DATA_SETTINGS,

    /** Settings > Tracking, which a tracker's "sign in again" notice opens: Sign in again is there. */
    TRACKING_SETTINGS,

    /** Settings > Advanced, where [AppRestart] brings the app back after a Restart from there. */
    ADVANCED_SETTINGS,

    /**
     * A search another app asked for (Experimental); [MainIntents.Target.search] carries it, and
     * [MainIntents.Target.sourceSearch] the one source it is for and what it searches there,
     * once `SearchRequestResolver` has found one.
     */
    SEARCH,

    /** Settings > Experimental, where [AppRestart] brings the app back after a Restart from there. */
    EXPERIMENTAL_SETTINGS,
}

/**
 * The one mapping between an Android intent and a [MainDestination]. Notifications, launcher
 * shortcuts and the reader all build their intents here, so the action strings exist in exactly one
 * place and the navigation host reads them back through [destinationOf].
 *
 * Contract 08 section 2.6 is the destination list. The routes themselves belong to the navigation
 * unit; this file deliberately knows nothing about them.
 */
object MainIntents {

    const val ACTION_LIBRARY = "com.shinsaikou.SHORTCUT_LIBRARY"
    const val ACTION_UPDATES = "com.shinsaikou.SHORTCUT_UPDATES"
    const val ACTION_HISTORY = "com.shinsaikou.SHORTCUT_HISTORY"
    const val ACTION_BROWSE = "com.shinsaikou.SHORTCUT_BROWSE"
    const val ACTION_EXTENSIONS = "com.shinsaikou.SHORTCUT_EXTENSIONS"
    const val ACTION_OPEN_ENTRY = "com.shinsaikou.OPEN_ENTRY"
    const val ACTION_OPEN_UPDATE_ERRORS = "com.shinsaikou.OPEN_UPDATE_ERRORS"
    const val ACTION_OPEN_IMPORT_REPORT = "com.shinsaikou.OPEN_IMPORT_REPORT"
    const val ACTION_OPEN_DOWNLOAD_QUEUE = "com.shinsaikou.OPEN_DOWNLOAD_QUEUE"
    const val ACTION_OPEN_DOWNLOAD_SETTINGS = "com.shinsaikou.OPEN_DOWNLOAD_SETTINGS"
    const val ACTION_OPEN_SYNC_SETTINGS = "com.shinsaikou.OPEN_SYNC_SETTINGS"
    const val ACTION_OPEN_DATA_SETTINGS = "com.shinsaikou.OPEN_DATA_SETTINGS"
    const val ACTION_OPEN_TRACKING_SETTINGS = "com.shinsaikou.OPEN_TRACKING_SETTINGS"
    const val ACTION_OPEN_ADVANCED_SETTINGS = "com.shinsaikou.OPEN_ADVANCED_SETTINGS"
    const val ACTION_OPEN_EXPERIMENTAL_SETTINGS = "com.shinsaikou.OPEN_EXPERIMENTAL_SETTINGS"

    const val EXTRA_MANGA_ID = "com.shinsaikou.extra.MANGA_ID"

    /**
     * The host of `saikou://extension-store?url=...`, the one link this activity accepts. The other
     * `saikou` link, `saikou://sign-in-return`, goes to `SignInReturnActivity` and never here.
     */
    const val HOST_EXTENSION_STORE = "extension-store"

    /** The scheme of that link. Kept here so the manifest and this mapping cannot drift apart. */
    const val SCHEME = "saikou"

    fun newIntent(context: Context, destination: MainDestination, mangaId: Long? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = actionOf(destination)
            mangaId?.let { putExtra(EXTRA_MANGA_ID, it) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Null for the launcher's own `ACTION_MAIN`, which means "wherever the app starts".
     *
     * [resolver] is only read for an `ACTION_VIEW` whose URI does not carry a file name, and only
     * to ask the provider what the file is called; see [viewDestinationOf]. A caller with no
     * resolver to hand gets the URI's own name and nothing else.
     */
    fun destinationOf(intent: Intent, resolver: ContentResolver? = null): MainDestination? = when (intent.action) {
        ACTION_LIBRARY -> MainDestination.LIBRARY
        ACTION_UPDATES -> MainDestination.UPDATES
        ACTION_HISTORY -> MainDestination.HISTORY
        ACTION_BROWSE -> MainDestination.BROWSE
        ACTION_EXTENSIONS -> MainDestination.EXTENSIONS
        ACTION_OPEN_ENTRY -> MainDestination.ENTRY
        ACTION_OPEN_UPDATE_ERRORS -> MainDestination.UPDATE_ERRORS
        ACTION_OPEN_IMPORT_REPORT -> MainDestination.IMPORT_REPORT
        ACTION_OPEN_DOWNLOAD_QUEUE -> MainDestination.DOWNLOAD_QUEUE
        ACTION_OPEN_DOWNLOAD_SETTINGS -> MainDestination.DOWNLOAD_SETTINGS
        ACTION_OPEN_SYNC_SETTINGS -> MainDestination.SYNC_SETTINGS
        ACTION_OPEN_DATA_SETTINGS -> MainDestination.DATA_SETTINGS
        ACTION_OPEN_TRACKING_SETTINGS -> MainDestination.TRACKING_SETTINGS
        ACTION_OPEN_ADVANCED_SETTINGS -> MainDestination.ADVANCED_SETTINGS
        ACTION_OPEN_EXPERIMENTAL_SETTINGS -> MainDestination.EXPERIMENTAL_SETTINGS
        Intent.ACTION_VIEW -> viewDestinationOf(intent, resolver)
        else -> SearchRequests.of(intent)?.let { MainDestination.SEARCH }
    }

    fun mangaIdOf(intent: Intent): Long? =
        if (intent.hasExtra(EXTRA_MANGA_ID)) intent.getLongExtra(EXTRA_MANGA_ID, 0L) else null

    /**
     * Everything one intent asks for, in one value. The four readers below stay public because
     * each is worth testing on its own, but the activity holds a [Target]: a destination and its
     * payload have to be consumed together, and two separate pieces of state can be half updated
     * by a second intent arriving between them.
     *
     * [sourceSearch] is never read off the intent: it is what `SearchRequestResolver` found for
     * [search], filled in by the activity before the target is handed on.
     */
    data class Target(
        val destination: MainDestination,
        val mangaId: Long? = null,
        val dataUri: String? = null,
        val storeUrl: String? = null,
        val search: SearchRequest? = null,
        val sourceSearch: SourceSearch? = null,
    )

    /** Null for `ACTION_MAIN` and for any intent this app does not answer. */
    fun targetOf(intent: Intent, resolver: ContentResolver? = null): Target? {
        val destination = destinationOf(intent, resolver) ?: return null
        return Target(
            destination = destination,
            mangaId = mangaIdOf(intent),
            dataUri = dataUriOf(intent),
            storeUrl = storeUrlOf(intent),
            search = SearchRequests.of(intent),
        )
    }

    /**
     * The whole `data` of an `ACTION_VIEW`, as a string, for the destinations that need it. It is
     * passed on verbatim: a `content://` URI is only readable through the grant that arrived with
     * this intent, so re-parsing or rebuilding it would lose the permission.
     */
    fun dataUriOf(intent: Intent): String? =
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()

    /** The `url` query parameter of an extension-store link, or null when there is none. */
    fun storeUrlOf(intent: Intent): String? {
        val data = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        if (!data.isExtensionStoreLink()) return null
        return runCatching { data.getQueryParameter("url") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * An `ACTION_VIEW` is either an extension index link or a file to restore, and nothing else.
     * The suffix test is on the file's name rather than on the MIME type because a file manager
     * hands over `application/octet-stream` for both of upstream's backup extensions, so the type
     * alone cannot tell a backup from any other binary the owner happened to tap.
     */
    private fun viewDestinationOf(intent: Intent, resolver: ContentResolver?): MainDestination? {
        val data = intent.data ?: return null
        if (data.isExtensionStoreLink()) return MainDestination.EXTENSION_STORE
        return if (looksLikeBackup(data, resolver)) MainDestination.RESTORE_BACKUP else null
    }

    /**
     * The URI's own text is tried first because it costs nothing and it is the whole answer for a
     * `file://` path and for the storage provider's `primary:Download/name` document ids. Most
     * other providers hand over an opaque id instead ("msf:1000000042"), so the name has to be
     * asked of the provider itself, which is what [displayNameOf] does.
     *
     * A URI that cannot be named is not a backup. With no name there is no evidence beyond a MIME
     * type that every binary shares, and opening the restore screen on a file that turns out to be
     * a photo is worse than ignoring a tap the owner can repeat from inside the app.
     */
    private fun looksLikeBackup(data: Uri, resolver: ContentResolver?): Boolean {
        if ((data.lastPathSegment ?: data.toString()).hasBackupSuffix()) return true
        if (data.scheme != ContentResolver.SCHEME_CONTENT || resolver == null) return false
        return displayNameOf(resolver, data)?.hasBackupSuffix() == true
    }

    /**
     * What the provider calls the file. This is a single column of a single row, read on the main
     * thread at the moment the intent arrives, which is the only moment its read grant is held; a
     * provider that is gone, slow to answer or unwilling to answer leaves the URI unnamed rather
     * than taking the activity down with it.
     */
    private fun displayNameOf(resolver: ContentResolver, data: Uri): String? = runCatching {
        resolver.query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()

    private fun String.hasBackupSuffix(): Boolean =
        lowercase().let { name -> BACKUP_SUFFIXES.any { name.endsWith(it) } }

    private fun Uri.isExtensionStoreLink(): Boolean =
        scheme == SCHEME && host == HOST_EXTENSION_STORE

    /** The two names upstream writes; `.proto.gz` is what an un-renamed export arrives as. */
    private val BACKUP_SUFFIXES = listOf(".tachibk", ".proto.gz")

    /**
     * [MainDestination.RESTORE_BACKUP] and [MainDestination.EXTENSION_STORE] have no action of
     * their own on purpose. They are things the outside world hands to the app through
     * `ACTION_VIEW` and a `data` URI, so an intent the app built for one of them would carry a
     * destination and no payload, which is a screen that opens on nothing. [MainDestination.SEARCH]
     * is the same: other apps send it with their own actions and a query.
     */
    private fun actionOf(destination: MainDestination): String = when (destination) {
        MainDestination.LIBRARY -> ACTION_LIBRARY
        MainDestination.UPDATES -> ACTION_UPDATES
        MainDestination.HISTORY -> ACTION_HISTORY
        MainDestination.BROWSE -> ACTION_BROWSE
        MainDestination.EXTENSIONS -> ACTION_EXTENSIONS
        MainDestination.ENTRY -> ACTION_OPEN_ENTRY
        MainDestination.UPDATE_ERRORS -> ACTION_OPEN_UPDATE_ERRORS
        MainDestination.IMPORT_REPORT -> ACTION_OPEN_IMPORT_REPORT
        MainDestination.DOWNLOAD_QUEUE -> ACTION_OPEN_DOWNLOAD_QUEUE
        MainDestination.DOWNLOAD_SETTINGS -> ACTION_OPEN_DOWNLOAD_SETTINGS
        MainDestination.SYNC_SETTINGS -> ACTION_OPEN_SYNC_SETTINGS
        MainDestination.DATA_SETTINGS -> ACTION_OPEN_DATA_SETTINGS
        MainDestination.TRACKING_SETTINGS -> ACTION_OPEN_TRACKING_SETTINGS
        MainDestination.ADVANCED_SETTINGS -> ACTION_OPEN_ADVANCED_SETTINGS
        MainDestination.EXPERIMENTAL_SETTINGS -> ACTION_OPEN_EXPERIMENTAL_SETTINGS
        MainDestination.RESTORE_BACKUP, MainDestination.EXTENSION_STORE ->
            error("$destination arrives as an ACTION_VIEW and is never built by the app")
        MainDestination.SEARCH -> error("$destination arrives from another app and is never built by the app")
    }
}
