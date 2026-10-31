package com.shinsaikou.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import com.shinsaikou.domain.preferences.StoragePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Upstream's storage location: one folder the owner picks, holding automatic backups in [AUTOBACKUP],
 * the Local source in [LOCAL] and an export of the downloads in [DOWNLOADS] (upstream's folder names).
 * The downloads themselves stay in app-private storage.
 *
 * Every reader asks [folder] each time and falls back to its app-private folder on null, so a
 * location never set, a folder deleted and a grant revoked all read as "use the app folder" rather
 * than as a crash or a backup written nowhere. A grant can go between two calls, so a folder found
 * before is handed out again only after it answers as a folder once more (one provider query instead
 * of the four a fresh lookup takes; the Local source asks for every page it opens).
 */
@Inject
@SingleIn(AppScope::class)
class StorageLocation(
    private val preferences: StoragePreferences,
    private val trees: StorageTrees,
) {

    enum class Status { NOT_SET, REACHABLE, UNREACHABLE }

    private val found = ConcurrentHashMap<String, UniFile>()

    fun status(): Status = when {
        preferences.storageLocation.get().isBlank() -> Status.NOT_SET
        root() != null -> Status.REACHABLE
        else -> Status.UNREACHABLE
    }

    /** The picked tree, when one is set and is still a folder this app can write to. */
    fun root(): UniFile? = writableTree(preferences.storageLocation.get())

    /**
     * The storage location before the last change, while the move out of it has not finished
     * ([StoragePreferences.previousStorageLocation]), when it is still a folder this app can write to.
     * Its grant is kept until then, so [openTree] still opens it.
     */
    fun previousRoot(): UniFile? = writableTree(preferences.previousStorageLocation.get())

    /** The previous location's name, read from its URI, as [displayName] reads the current one's. */
    fun previousDisplayName(): String? = preferences.previousStorageLocation.get().takeIf { it.isNotBlank() }?.let(::nameOf)

    /** `<tree>/<name>`, made when missing. Null when no location is set, it cannot be reached, or the folder cannot be made. */
    fun folder(name: String): UniFile? {
        val uri = preferences.storageLocation.get().takeIf { it.isNotBlank() } ?: return null
        val key = "$uri\n$name"
        found[key]?.let { known ->
            if (runCatching { known.isDirectory }.getOrDefault(false)) return known
            found.remove(key)
        }
        return root()?.let { folderIn(it, name) }?.also { found[key] = it }
    }

    /** Makes the three folders, as choosing a location does. False when any of them could not be made. */
    fun createFolders(): Boolean {
        val root = root() ?: return false
        return FOLDERS.all { folderIn(root, it) != null }
    }

    /**
     * Opens a tree URI and keeps it only if it is a folder. For the storage location and for the two
     * older per-folder keys, which nothing sets any more but which stay readable.
     */
    fun openTree(uri: String): UniFile? = runCatching { trees.open(uri)?.takeIf { it.isDirectory } }.getOrNull()

    private fun writableTree(uri: String): UniFile? = uri
        .takeIf { it.isNotBlank() }
        ?.let(::openTree)
        ?.takeIf { runCatching { it.canWrite() }.getOrDefault(false) }

    /** The picked folder's name, read from its URI, so it can be named even when it cannot be reached. */
    fun displayName(): String? = preferences.storageLocation.get().takeIf { it.isNotBlank() }?.let(::nameOf)

    companion object {
        /** Upstream's `StorageManager` folder names. */
        const val AUTOBACKUP = "autobackup"
        const val LOCAL = "local"
        const val DOWNLOADS = "downloads"

        val FOLDERS = listOf(AUTOBACKUP, LOCAL, DOWNLOADS)

        /** The child folder [name] of [root]; a file of that name is not taken for it, and nothing is made beside it. */
        fun folderIn(root: UniFile, name: String): UniFile? = runCatching {
            val existing = root.findFile(name)
            if (existing != null) existing.takeIf { it.isDirectory } else root.createDirectory(name)?.takeIf { it.isDirectory }
        }.getOrNull()

        /**
         * The last segment of the folder a tree URI names: `content://<authority>/tree/primary%3ASaikou`
         * is "Saikou", `.../tree/primary%3ABooks%2FSaikou` is "Saikou", and a plain file URI is its last
         * path segment. Pure string work, so it runs where `android.net.Uri` is a stub.
         */
        fun nameOf(uri: String): String {
            val documentId = uri.substringAfter("/tree/", missingDelimiterValue = "")
                .substringBefore('/')
                .takeIf { it.isNotEmpty() }
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
            val path = documentId?.substringAfter(':') ?: uri.trimEnd('/').substringAfterLast('/')
            return path.trimEnd('/').substringAfterLast('/').ifEmpty { documentId ?: uri }
        }

        /**
         * Whether two picked trees are on the same storage of the system's external storage provider,
         * whose document ids are `<volume>:<path>` (`primary:Saikou`, `1A2B-3C4D:Books`), so a move
         * between them is a rename that needs no free space. False for any other provider or a plain
         * file URI, where nothing says so. Pure string work, as [nameOf] is.
         */
        fun sameVolume(first: String, second: String): Boolean {
            val a = volumeOf(first) ?: return false
            return a == volumeOf(second)
        }

        /**
         * Whether one tree is, or is inside, one of the other's three folders (a location picked inside
         * the old one's `local`), so a move between them would copy a folder into itself. Read from the
         * paths the external storage provider's ids and `file:` URIs give; false where nothing says.
         */
        fun overlaps(first: String, second: String): Boolean {
            val a = pathOf(first) ?: return false
            val b = pathOf(second) ?: return false
            fun inside(inner: String, outer: String) = FOLDERS.any { folder ->
                val root = if (outer.endsWith(':') || outer.endsWith('/')) "$outer$folder" else "$outer/$folder"
                inner == root || inner.startsWith("$root/")
            }
            return inside(a, b) || inside(b, a)
        }

        /** `primary:Books/Saikou` for an external storage tree, the path for a `file:` URI. */
        private fun pathOf(uri: String): String? {
            if (uri.startsWith("file:")) return uri.removePrefix("file:").trimEnd('/').ifEmpty { null }
            val prefix = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/"
            if (!uri.startsWith(prefix)) return null
            return URLDecoder.decode(uri.removePrefix(prefix).substringBefore('/'), Charsets.UTF_8.name()).trimEnd('/')
        }

        private fun volumeOf(uri: String): String? {
            val prefix = "content://$EXTERNAL_STORAGE_AUTHORITY/tree/"
            if (!uri.startsWith(prefix)) return null
            val treeId = URLDecoder.decode(uri.removePrefix(prefix).substringBefore('/'), Charsets.UTF_8.name())
            return treeId.substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        }

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}

/** Opens the tree URI the storage location names. Bound by [ContentStorageTrees]; the JVM tests open plain folders. */
fun interface StorageTrees {
    fun open(uri: String): UniFile?

    /**
     * Moves [child] of [from] into [to] whole, in one call to the provider: a rename when both are on
     * the same storage. False when it cannot (another provider, another storage, a name already in
     * [to]); the caller then copies file by file. A test's tree answers false unless it says otherwise.
     */
    fun move(child: UniFile, from: UniFile, to: UniFile): Boolean = false

    /**
     * The children of [folder] with their kind and size, for a count before a move. A tree answers
     * them in one query per folder, where UniFile asks the provider again for each child's kind and
     * size; a plain folder is listed through UniFile.
     */
    fun list(folder: UniFile): List<Entry> = folder.listFiles().orEmpty().mapNotNull { child ->
        val name = child.name ?: return@mapNotNull null
        val isDirectory = child.isDirectory
        Entry(child, name, isDirectory, if (isDirectory) 0L else child.length().coerceAtLeast(0))
    }

    /** One child [list] found: [length] is 0 for a folder. */
    class Entry(val file: UniFile, val name: String, val isDirectory: Boolean, val length: Long)
}

/** A tree document for a picked folder, or a raw file for a `file://` URI, as UniFile answers either. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ContentStorageTrees(private val context: Context) : StorageTrees {
    override fun open(uri: String): UniFile? = UniFile.fromUri(context, Uri.parse(uri))

    /** `DocumentsContract.moveDocument`, tried only when all three are documents of one provider. */
    override fun move(child: UniFile, from: UniFile, to: UniFile): Boolean {
        val uris = listOf(child.uri, from.uri, to.uri)
        if (uris.any { !DocumentsContract.isDocumentUri(context, it) }) return false
        if (uris.map { it.authority }.distinct().size != 1) return false
        return DocumentsContract.moveDocument(context.contentResolver, child.uri, from.uri, to.uri) != null
    }

    override fun list(folder: UniFile): List<StorageTrees.Entry> {
        val uri = folder.uri
        if (!DocumentsContract.isDocumentUri(context, uri) || !DocumentsContract.isTreeUri(uri)) {
            return super.list(folder)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = context.contentResolver.query(children, columns, null, null, null) ?: return super.list(folder)
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    val isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val length = if (isDirectory || it.isNull(3)) 0L else it.getLong(3).coerceAtLeast(0)
                    val file = UniFile.fromUri(context, DocumentsContract.buildDocumentUriUsingTree(uri, it.getString(0))) ?: continue
                    add(StorageTrees.Entry(file, name, isDirectory, length))
                }
            }
        }
    }
}
