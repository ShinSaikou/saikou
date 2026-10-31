package com.shinsaikou.extensions.loader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies this file to [target] with [File.setReadOnly] applied to the open target before the bytes
 * are written. Ported from upstream's `copyAndSetReadOnlyTo` and kept in that order on purpose:
 * Android 14 and up throw `SecurityException: Writable dex file` when a dex is loaded from a file
 * that was ever writable after it existed.
 *
 * Unlike upstream's, the bytes go to a temporary file beside [target], which is then renamed over
 * it. Upstream deletes [target] first, so a full disk or a kill halfway through an update left no
 * extension at all where a working one had been; a rename either happens whole or not at all.
 */
internal fun File.copyAndSetReadOnlyTo(target: File, bufferSize: Int = DEFAULT_BUFFER_SIZE): File {
    if (!exists()) throw NoSuchFileException(file = this, reason = "The source file doesn't exist.")
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}$PARTIAL_SUFFIX")
    temp.delete()
    try {
        inputStream().use { input ->
            temp.outputStream().use { output ->
                temp.setReadOnly()
                input.copyTo(output, bufferSize)
            }
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.delete()
    }
    return target
}

/** What an unfinished copy is called; the loader only reads names ending in its own extension. */
internal const val PARTIAL_SUFFIX = ".part"
