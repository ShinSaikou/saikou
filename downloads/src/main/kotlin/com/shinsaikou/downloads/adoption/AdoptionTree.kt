package com.shinsaikou.downloads.adoption

import com.hippo.unifile.UniFile
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * One entry of the tree upstream's downloads are adopted from: what [AdoptionWalker] and
 * [AdoptionCopier] see of it, and all they see.
 *
 * The interface reads and nothing else. The tree belongs to another app, and adoption copies out
 * of it and never writes to it, renames in it or deletes from it (phase 2 decision Q6(b)); with no
 * method here that could, neither class can break that rule by mistake.
 */
interface AdoptionNode {

    /** The entry's own name, not its path. Blank when the provider did not report one. */
    val name: String

    val isDirectory: Boolean

    /** The entries of a directory, in no particular order; empty for a file or an unreadable directory. */
    fun children(): List<AdoptionNode>

    /** Size in bytes as the provider reports it, or 0 when it does not know. */
    fun length(): Long

    /** The file's bytes. The caller closes the stream. */
    fun openStream(): InputStream
}

/**
 * An [AdoptionNode] over a [UniFile]: a document of the tree the owner picked through the storage
 * access framework, which [UniFile.fromUri] answers with its tree document class, or a plain file,
 * which it answers with its raw file class. The adoption worker opens the picked tree this way, and
 * the tests open a generated tree of plain files this way, so both run the same class.
 *
 * A tree document asks its provider once per question, so the type is asked once and kept: the
 * walker and the copier both ask it of every entry.
 */
class UniFileAdoptionNode(private val file: UniFile) : AdoptionNode {

    override val name: String = file.name.orEmpty()

    override val isDirectory: Boolean by lazy { file.isDirectory }

    override fun children(): List<AdoptionNode> =
        file.listFiles().orEmpty().map(::UniFileAdoptionNode)

    override fun length(): Long = file.length().coerceAtLeast(0)

    override fun openStream(): InputStream =
        file.openInputStream() ?: throw FileNotFoundException(name)
}
