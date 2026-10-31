package com.shinsaikou.sync

import android.content.Context
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tachiyomi.domain.category.interactor.DeleteCategory
import java.io.File

/**
 * Phase 5 unit CT, item 8: the sub-category scenarios of the two-device suite, over the pair
 * library's own categories ([SyncPairLibrary.CATEGORIES]).
 *
 * [nest] puts one category under another through [SetCategoryParent], as the Categories screen's
 * "Move under" does; [delete] deletes one through [DeleteCategory], as the screen's delete does;
 * [restore] restores a backup of the pair library's categories, flat, as every backup file carries
 * them, through the app's own importer; [check] reports the tree the screens show, from
 * [CategoryTree], and how many live categories store a parent.
 *
 * Names go into the result line with their spaces as `_`, since the line is space-separated.
 */
object SyncPairTree {

    private fun wire(name: String): String = name.replace(' ', '_')

    suspend fun nest(graph: AppGraph, child: String, parent: String): Map<String, Any?> {
        val byName = graph.categoryRepository.getAll().associateBy { it.name }
        val childId = checkNotNull(byName[child]) { "no $child" }.id
        val parentId = checkNotNull(byName[parent]) { "no $parent" }.id
        assertEquals("$child under $parent", SetCategoryParent.Result.Success, graph.setCategoryParent.await(childId, parentId))
        return linkedMapOf("nested" to "${wire(parent)}>${wire(child)}")
    }

    suspend fun delete(graph: AppGraph, name: String): Map<String, Any?> {
        val id = checkNotNull(graph.categoryRepository.getAll().firstOrNull { it.name == name }) { "no $name" }.id
        assertEquals("deleting $name", DeleteCategory.Result.Success, graph.deleteCategory.await(id))
        return linkedMapOf("deleted" to wire(name))
    }

    /**
     * A backup holding [SyncPairLibrary.CATEGORIES] in the seed's order and nothing else, restored
     * with library entries and categories as a restore onto a new phone would be. The file is the
     * test's own, written to the app's cache and removed afterwards.
     */
    suspend fun restore(graph: AppGraph, context: Context): Map<String, Any?> {
        val backup = Backup(
            backupManga = emptyList(),
            backupCategories = SyncPairLibrary.CATEGORIES.mapIndexed { index, name ->
                BackupCategory(name = name, order = index.toLong(), id = index + 1L, flags = 0)
            },
        )
        val file = File(context.cacheDir, RESTORE_FILE)
        file.writeBytes(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
        try {
            val options = RestoreOptions(appSettings = false, extensionStores = false, sourceSettings = false)
            val report = graph.backupImporter.import(file, options) {}
            assertTrue("the restore reported errors: ${report.errors}", report.errors.isEmpty())
            return linkedMapOf("created" to report.categoriesCreated)
        } finally {
            file.delete()
        }
    }

    /**
     * The tree as `name` and `parent>name` in the screens' order, joined by `|`, without the system
     * category; and how many live categories store a parent, which a merged cycle stores on both.
     */
    suspend fun check(graph: AppGraph): Map<String, Any?> {
        val live = graph.categoryRepository.getAll()
        val tree = CategoryTree.of(live).filterNot { it.category.isSystemCategory }.joinToString("|") { node ->
            wire(node.parent?.let { "${it.name}>${node.category.name}" } ?: node.category.name)
        }
        return linkedMapOf(
            "tree" to tree,
            "nested" to live.count { it.parentId != null },
        )
    }

    private const val RESTORE_FILE = "sync-pair-tree.tachibk"
}
