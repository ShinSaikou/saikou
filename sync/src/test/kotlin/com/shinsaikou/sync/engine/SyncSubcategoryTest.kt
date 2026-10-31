package com.shinsaikou.sync.engine

import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncDigest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.category.CategoryRepositoryImpl

/**
 * Phase 5 unit CT, items 2 and 3 through the real engine: what the merge stores when two devices
 * nest categories apart (challenge c2, g3 C), and that every device then stores the same rows and
 * reads the same tree from them. Each device is the JVM schema behind `:data`'s store, so the apply's
 * last pass, its latch and the triggers are the app's own.
 */
class SyncSubcategoryTest {

    private val backend = InMemorySyncBackend()
    private val devices = mutableListOf<SyncTestDevice>()

    private fun device(): SyncTestDevice = SyncTestDevice(backend).also { devices += it }

    @AfterEach
    fun close() {
        devices.forEach { it.close() }
    }

    private fun SyncTestDevice.idOf(name: String): Long = long("SELECT _id FROM categories WHERE name = '$name'")!!

    /** A local nest, as SetCategoryParent writes it: the triggers stamp the clock and log the row. */
    private fun SyncTestDevice.nest(child: String, parent: String?) =
        exec("UPDATE categories SET parent_id = ${parent?.let { idOf(it) } ?: "NULL"} WHERE _id = ${idOf(child)}")

    /** The tree the screens show on this device, as "name" and "parent>name". */
    private fun SyncTestDevice.tree(): List<String> = runBlocking {
        CategoryTree.of(CategoryRepositoryImpl(handler).getAll()).filterNot { it.category.isSystemCategory }.map { node ->
            node.parent?.let { "${it.name}>${node.category.name}" } ?: node.category.name
        }
    }

    /** Each category's stored parent, by name, deleted rows included. */
    private fun SyncTestDevice.parents(): List<List<String?>> = rows(
        "SELECT C.name, P.name FROM categories C LEFT JOIN categories P ON P._id = C.parent_id WHERE C._id > 0 ORDER BY C.name",
        2,
    )

    private fun SyncTestDevice.digest(): String = runBlocking { SyncDigest.of(store).sha256 }

    @Test
    fun `the reparent race stores the merged cycle on both devices and both read one tree`() {
        val a = device()
        val b = device()
        a.addCategory(1, "North")
        a.addCategory(2, "South")
        a.sync()
        b.sync()
        a.sync()

        // Each device nests the other's category before either syncs.
        a.nest("North", parent = "South")
        b.nest("South", parent = "North")
        a.sync()
        b.sync()
        a.sync()

        a.parents() shouldContainExactly listOf(listOf("North", "South"), listOf("South", "North"))
        b.parents() shouldBe a.parents()
        a.library() shouldBe b.library()
        a.digest() shouldBe b.digest()
        val (low, high) = listOf("North", "South").sortedBy { a.string("SELECT sync_key FROM categories WHERE name = '$it'") }
        a.tree() shouldContainExactly listOf(low, "$low>$high")
        b.tree() shouldBe a.tree()
    }

    @Test
    fun `a fresh device that reads a cycle from a full export stores it as merged, not one member flattened`() {
        val a = device()
        a.addCategory(1, "North")
        a.addCategory(2, "South", parentId = 1)
        a.nest("North", parent = "South")
        a.sync()

        val fresh = device()
        fresh.sync()

        fresh.parents() shouldBe a.parents()
        fresh.library()["categories"] shouldBe a.library()["categories"]
        fresh.tree() shouldBe a.tree()
    }

    @Test
    fun `a parent deleted on one device while the other nests under it leaves the child at the top level on both`() {
        val a = device()
        val b = device()
        a.addCategory(1, "Reading")
        a.addCategory(2, "Weekly")
        a.sync()
        b.sync()
        a.sync()

        // B nests Weekly under Reading; A, before it sees that, deletes Reading as the screen does.
        b.nest("Weekly", parent = "Reading")
        runBlocking { CategoryRepositoryImpl(a.handler).delete(a.idOf("Reading")) }
        b.sync()
        a.sync()
        b.sync()

        a.tree() shouldContainExactly listOf("Weekly")
        b.tree() shouldContainExactly listOf("Weekly")
        a.parents() shouldBe b.parents()
        a.library() shouldBe b.library()
        a.digest() shouldBe b.digest()
        // The apply moved no clock of its own: Weekly keeps the clock of B's nest on both.
        a.long("SELECT modified_at FROM categories WHERE name = 'Weekly'") shouldBe
            b.long("SELECT modified_at FROM categories WHERE name = 'Weekly'")
    }

    @Test
    fun `the categories of an export part stay in one transaction, so a cycle across the chunk boundary is stored whole`() {
        val a = device()
        // Enough categories that the cycle's two lines fall either side of a chunk of SyncPull.CHUNK:
        // the planner writes South (whose parent North comes next) as the chunk's last line.
        (1L until SyncPull.CHUNK.toLong()).forEach { a.addCategory(it, "Filler $it") }
        val north = SyncPull.CHUNK.toLong()
        a.addCategory(north, "North")
        a.addCategory(north + 1, "South", parentId = north)
        a.nest("North", parent = "South")
        a.sync()

        val fresh = device()
        fresh.sync()

        fresh.parents().filter { it[1] != null } shouldContainExactly listOf(listOf("North", "South"), listOf("South", "North"))
        fresh.library()["categories"] shouldBe a.library()["categories"]
    }
}
