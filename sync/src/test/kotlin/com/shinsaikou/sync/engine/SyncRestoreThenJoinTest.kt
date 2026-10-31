package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncDigest
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.category.CategoryRepositoryImpl
import java.io.File

/**
 * Phase 5 unit CT, item 7: restore, then join. A backup file has no parent field (decision F10), so
 * a restore creates its categories at the top level. A device that restores and then signs in must
 * not flatten the tree the other device already holds: the restore's "no parent" must never beat a
 * parent another device set. R92's `sync_fresh_device_categories` shape (A edits its categories and
 * syncs; B, cleared, joins) with a nested pair, and B restoring the backup before it joins. B's
 * restore goes through the real `BackupImporter` over B's own schema.
 *
 * Phase 5 unit TG3, item 2 (CT's two carried cases): a restore over a category of the same name the
 * device holds deleted takes the repository's revive path, which must leave the revived row with no
 * clock as a created one has, so its values lose to any other device's while its restore clock,
 * past the tombstone, keeps it live; and a delete then revive across three devices, the layout
 * suite's three-device form, ends with the three equal.
 */
class SyncRestoreThenJoinTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)
    private val c = SyncTestDevice(backend)

    @TempDir
    lateinit var files: File

    @AfterEach
    fun close() {
        a.close()
        b.close()
        c.close()
    }

    /** The file B restores: both categories, flat, as every `.tachibk` carries them. */
    private val backup = Backup(
        backupManga = emptyList(),
        backupCategories = listOf(
            BackupCategory(name = "Reading", order = 0, id = 1, flags = 0),
            BackupCategory(name = "Weekly", order = 1, id = 2, flags = 0),
        ),
    )

    private fun SyncTestDevice.categoryId(name: String): Long =
        checkNotNull(long("SELECT _id FROM categories WHERE name = '$name'")) { "no category $name" }

    /** Deletes [name] as the Categories screen does, through the repository's soft delete. */
    private fun SyncTestDevice.deleteCategory(name: String) = runBlocking {
        CategoryRepositoryImpl(handler).delete(categoryId(name))
    }

    /** Weekly's values, tombstone and both clocks: flags, parent name, modified_at, deleted_at, restored_at. */
    private fun SyncTestDevice.weekly(): List<String?> = rows(
        """
        SELECT C.flags, P.name, C.modified_at, C.deleted_at, C.restored_at
        FROM categories C LEFT JOIN categories P ON P._id = C.parent_id WHERE C.name = 'Weekly'
        """,
        5,
    ).single()

    private fun SyncTestDevice.tree(): List<String> = runBlocking {
        CategoryTree.of(CategoryRepositoryImpl(handler).getAll()).filterNot { it.category.isSystemCategory }.map { node ->
            node.parent?.let { "${it.name}>${node.category.name}" } ?: node.category.name
        }
    }

    @Test
    fun `a device that restores a backup and then joins keeps the other device's tree on both`() {
        a.addCategory(1, "Reading")
        a.addCategory(2, "Weekly", parentId = 1)
        // A nested Weekly a minute before B restores: the restore is the later write on the wall clock.
        val nestedAt = System.currentTimeMillis() - 60_000L
        a.exec("UPDATE categories SET modified_at = $nestedAt WHERE _id > 0")
        a.sync()

        b.restore(backup.bytes(), files)
        b.tree() shouldContainExactly listOf("Reading", "Weekly")
        b.sync()
        a.sync()

        a.tree() shouldContainExactly listOf("Reading", "Reading>Weekly")
        b.tree() shouldContainExactly listOf("Reading", "Reading>Weekly")
        a.library()["categories"] shouldBe b.library()["categories"]
        runBlocking { SyncDigest.of(a.store).sha256 shouldBe SyncDigest.of(b.store).sha256 }
        // The parent edit's clock stands on both; nothing the restore wrote outranked it.
        a.long("SELECT modified_at FROM categories WHERE name = 'Weekly'") shouldBe nestedAt
        b.long("SELECT modified_at FROM categories WHERE name = 'Weekly'") shouldBe nestedAt
    }

    @Test
    fun `a category the restore creates holds no clock, and is still logged for the next push`() {
        b.restore(backup.bytes(), files)

        b.string("SELECT modified_at FROM categories WHERE name = 'Weekly'").shouldBeNull()
        b.long("SELECT count(*) FROM sync_change_log WHERE entity_type = 'category'") shouldBe 2
        // The next local edit stamps it as any edit does.
        b.exec("UPDATE categories SET flags = 4 WHERE name = 'Weekly'")
        (b.long("SELECT modified_at FROM categories WHERE name = 'Weekly'")!! > 0) shouldBe true
    }

    @Test
    fun `a restore onto a device that already holds the categories leaves their clocks alone`() {
        b.addCategory(1, "Reading")
        val before = b.long("SELECT modified_at FROM categories WHERE name = 'Reading'")

        b.restore(backup.bytes(), files)

        b.long("SELECT modified_at FROM categories WHERE name = 'Reading'") shouldBe before
        b.string("SELECT modified_at FROM categories WHERE name = 'Weekly'").shouldBeNull()
    }

    /**
     * CT's first carried case. A nested Weekly under Reading with its own flags; B deletes it and both
     * hold the tombstone; B then restores a backup that names Weekly. The repository revives the
     * deleted row (`CategoryRepositoryImpl.insert`, a row of the same sync key) with the file's flat
     * values, and the importer forgets its clock, as for a category it creates. So A's values win the
     * merge on both devices, and the restore clock the revive stamped past the tombstone keeps Weekly
     * live on both.
     */
    @Test
    fun `a restore over a category the device holds deleted revives it with no clock, and the other device's values win`() {
        a.addCategory(1, "Reading")
        a.addCategory(2, "Weekly", parentId = 1)
        a.exec("UPDATE categories SET flags = 4 WHERE name = 'Weekly'")
        val editedAt = System.currentTimeMillis() - 60_000L
        a.exec("UPDATE categories SET modified_at = $editedAt WHERE _id > 0")
        listOf(a, b).forEach { it.sync() }

        b.deleteCategory("Weekly")
        val tombstone = checkNotNull(b.long("SELECT deleted_at FROM categories WHERE name = 'Weekly'"))
        listOf(b, a).forEach { it.sync() }
        a.long("SELECT deleted_at FROM categories WHERE name = 'Weekly'") shouldBe tombstone

        b.restore(backup.bytes(), files)
        // The revive reused the deleted row, and its clock is gone as a created row's is.
        b.long("SELECT count(*) FROM categories WHERE name = 'Weekly'") shouldBe 1
        val revived = b.weekly()
        revived.take(4) shouldBe listOf("0", null, null, null)
        (revived[4]!!.toLong() > tombstone) shouldBe true

        b.sync()
        a.sync()

        for (device in listOf(a, b)) {
            device.tree() shouldContainExactly listOf("Reading", "Reading>Weekly")
            device.weekly() shouldBe listOf("4", "Reading", editedAt.toString(), null, revived[4])
        }
        a.library()["categories"] shouldBe b.library()["categories"]
        runBlocking { SyncDigest.of(a.store).sha256 shouldBe SyncDigest.of(b.store).sha256 }
        listOf(a, b).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }

    /**
     * CT's second carried case, on three devices sharing one backend as the layout suite's
     * three-device case does. A deletes Weekly; B pulls the tombstone and revives Weekly by a
     * restore; C, which never saw the delete, changes Weekly's flags meanwhile. Whatever order the
     * three then sync in, they end equal: Weekly live (B's restore clock is past A's tombstone),
     * with C's flags (C's clock is the only one newer than A's), and nothing left to write.
     */
    @Test
    fun `a category deleted on one device, revived by a restore on another and edited on a third ends the same on all three`() {
        a.addCategory(1, "Reading")
        a.addCategory(2, "Weekly", parentId = 1)
        val createdAt = System.currentTimeMillis() - 60_000L
        a.exec("UPDATE categories SET modified_at = $createdAt WHERE _id > 0")
        listOf(a, b, c, a).forEach { it.sync() }
        listOf(a, b, c).forEach { it.weekly() shouldBe listOf("0", "Reading", createdAt.toString(), null, null) }

        a.deleteCategory("Weekly")
        val tombstone = checkNotNull(a.long("SELECT deleted_at FROM categories WHERE name = 'Weekly'"))
        a.sync()
        b.sync()
        b.long("SELECT deleted_at FROM categories WHERE name = 'Weekly'") shouldBe tombstone

        b.restore(backup.bytes(), files)
        val restoredAt = checkNotNull(b.long("SELECT restored_at FROM categories WHERE name = 'Weekly'"))
        (restoredAt > tombstone) shouldBe true
        runBlocking { CategoryRepositoryImpl(c.handler).updateFlags(c.categoryId("Weekly"), 8) }
        val editedAt = checkNotNull(c.long("SELECT modified_at FROM categories WHERE name = 'Weekly'"))
        (editedAt > createdAt) shouldBe true

        repeat(2) { listOf(c, b, a).forEach { it.sync() } }

        for (device in listOf(a, b, c)) {
            device.tree() shouldContainExactly listOf("Reading", "Reading>Weekly")
            // The tombstone the restore clock beat is dropped (MergeEngine's tombstone rule).
            device.weekly() shouldBe listOf("8", "Reading", editedAt.toString(), null, restoredAt.toString())
            device.latchesRaised() shouldBe 0
        }
        b.library() shouldBe a.library()
        c.library() shouldBe a.library()
        runBlocking {
            val digest = SyncDigest.of(a.store).sha256
            SyncDigest.of(b.store).sha256 shouldBe digest
            SyncDigest.of(c.store).sha256 shouldBe digest
        }
        listOf(a, b, c).forEach { it.sync().shouldNotBeNull().rowsWritten shouldBe 0 }
    }
}
