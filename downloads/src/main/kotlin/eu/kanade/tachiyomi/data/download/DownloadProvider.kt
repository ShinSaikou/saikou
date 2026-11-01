// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt (modified: yes)
// Modifications (contract 06 section 4.2, decision F12): the class becomes an object of pure
// functions. Everything that touched the file system (getMangaDir, findSourceDir, findMangaDir,
// findChapterDir, findChapterDirs) is gone, together with the Context, the StorageManager and
// UniFile. Every naming function takes disallowNonAscii explicitly instead of reading
// LibraryPreferences through Injekt, and getLegacyChapterDirNames and sanitizeChapterName become
// public because the Phase 2 engine and its tests call them directly. The bodies of the naming
// functions are byte for byte upstream's: these strings are the adoption contract for folders a
// upstream install already wrote. getLegacyChapterDirNames also lists the names upstream v0.21.0
// and v0.21.1 wrote, as upstream v0.21.3 fb2b22403 does, and the names v0.21.2 and later write with
// their hash setting off, so their folders are adopted too.
package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.chapter.model.Chapter

/**
 * The download path scheme: `/<root>/<source name>/<manga>/<chapter>`.
 */
object DownloadProvider {

    fun getSourceDirName(sourceName: String, disallowNonAscii: Boolean): String {
        return DiskUtil.buildValidFilename(sourceName, disallowNonAscii = disallowNonAscii)
    }

    fun getMangaDirName(mangaTitle: String, disallowNonAscii: Boolean): String {
        return DiskUtil.buildValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii)
    }

    /**
     * `<scanlator>_<name>` or `<name>`, truncated to leave room for the suffix, then
     * `_` and the first six characters of the md5 of the chapter url.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAscii: Boolean,
    ): String {
        var dirName = sanitizeChapterName(chapterName)
        if (!chapterScanlator.isNullOrBlank()) {
            dirName = chapterScanlator + "_" + dirName
        }
        // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
        dirName = DiskUtil.buildValidFilename(dirName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii)
        dirName += "_" + md5(chapterUrl).take(6)
        return dirName
    }

    /**
     * Names a chapter folder may already carry: the pre-hash name, and the name the other value of
     * [disallowNonAscii] would produce, so a folder written before the setting changed is found.
     * Then the names only upstream v0.21.0 and later give: both names without the hash, which
     * v0.21.2 and later write with their hash setting off, and [getSplitChapterDirNames].
     */
    fun getLegacyChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAscii: Boolean,
    ): List<String> {
        val sanitizedChapterName = sanitizeChapterName(chapterName)
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !chapterScanlator.isNullOrBlank() -> "${chapterScanlator}_$sanitizedChapterName"
                else -> sanitizedChapterName
            },
        )

        val otherChapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl, !disallowNonAscii)
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl, disallowNonAscii)

        // The current names without the hash, as upstream v0.21.2 and later write them with the hash
        // setting off: the scanlator and the name made valid as one string under the hash's byte
        // limit, which differs from the pre-hash name for a long name, and under the non-ASCII
        // setting for a non-ASCII scanlator. Upstream looks for them with the setting off first,
        // whatever its value, so they come in that order here too.
        val unhashedChapterDirNames = listOf(false, true)
            .map { getChapterDirName(chapterName, chapterScanlator, chapterUrl, it).dropLast(HASH_SUFFIX_LENGTH) }

        return buildList {
            // Chapter name without hash (unable to handle duplicate chapter names)
            add(chapterNameV1)
            add(otherChapterDirName)
            // Last, so a folder under an older name wins a tie, and only where they differ. The names
            // without the hash come before the split ones, in the order upstream looks for them.
            (unhashedChapterDirNames + getSplitChapterDirNames(chapterName, chapterScanlator, chapterUrl)).forEach {
                if (it != chapterDirName && it !in this) add(it)
            }
        }
    }

    /**
     * The names upstream v0.21.0 and v0.21.1 gave a chapter. They made the scanlator and the
     * chapter name valid apart, so the name differs for a name starting with a space or dot, a long
     * name, or a non-ASCII scanlator. Under both ASCII settings, with the hash and without it, which
     * those versions could leave off. With no scanlator the name stands alone, as upstream has it;
     * without the hash it still differs from the pre-hash name for a long or non-ASCII name.
     */
    private fun getSplitChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
    ): List<String> {
        val prefix = if (chapterScanlator.isNullOrBlank()) "" else DiskUtil.buildValidFilename(chapterScanlator) + "_"
        val hash = "_" + md5(chapterUrl).take(6)
        return listOf(false, true).flatMap { disallowNonAscii ->
            // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
            val validName = DiskUtil.buildValidFilename(
                sanitizeChapterName(chapterName),
                DiskUtil.MAX_FILE_NAME_BYTES - 11,
                disallowNonAscii,
            )
            listOf(prefix + validName + hash, prefix + validName)
        }
    }

    /** The folder, the folder as a `.cbz`, then every legacy name and its `.cbz`, in that order. */
    fun getValidChapterDirNames(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAscii: Boolean,
    ): List<String> {
        val chapterDirName = getChapterDirName(chapterName, chapterScanlator, chapterUrl, disallowNonAscii)
        val legacyChapterDirNames =
            getLegacyChapterDirNames(chapterName, chapterScanlator, chapterUrl, disallowNonAscii)

        return buildList {
            // Folder of images
            add(chapterDirName)
            // Archived chapters
            add("$chapterDirName.cbz")

            // any legacy names
            legacyChapterDirNames.forEach {
                add(it)
                add("$it.cbz")
            }
        }
    }

    /**
     * Whether a rename or a re-upload moved the chapter's folder. Both sides are named under the
     * same [disallowNonAscii], which contract 06 decision F12 makes an explicit parameter.
     */
    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter, disallowNonAscii: Boolean): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url, disallowNonAscii) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url, disallowNonAscii)
    }

    /** A blank chapter name would produce an invalid folder name. */
    fun sanitizeChapterName(chapterName: String): String {
        return chapterName.ifBlank {
            "Chapter"
        }
    }

    const val TMP_DIR_SUFFIX = "_tmp"

    /** The `_` and six hash characters [getChapterDirName] appends. */
    private const val HASH_SUFFIX_LENGTH = 7
}
