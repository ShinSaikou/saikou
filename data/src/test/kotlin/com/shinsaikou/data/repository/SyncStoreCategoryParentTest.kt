package com.shinsaikou.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.domain.sync.model.StoredCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Phase 5 unit CT, item 3: the apply's last pass gives each category it wrote its parent once every
 * category of the apply is stored, and a category the apply deletes leaves nothing under it, through
 * [SyncStoreImpl.apply] over the real schema and triggers. No clock, version or log row moves.
 */
class SyncStoreCategoryParentTest : RepositoryTest() {

    private val store by lazy { SyncStoreImpl(handler, driver) }

    private fun stored(name: String, parent: String? = null, modifiedAt: Long = 1_000L, deletedAt: Long? = null) = StoredCategory(
        id = 0,
        syncKey = CategorySyncKey.of(name),
        name = name,
        order = 0,
        flags = 0,
        parentKey = parent?.let(CategorySyncKey::of),
        modifiedAt = modifiedAt,
        deletedAt = deletedAt,
        restoredAt = null,
    )

    /** Each live or deleted category as `name>parent name` (or `name` alone), with its clock and version. */
    private fun rows(): List<String> = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT C.name, P.name, C.modified_at, C.version, C.is_syncing FROM categories C
            LEFT JOIN categories P ON P._id = C.parent_id WHERE C._id > 0 ORDER BY C.name
        """,
        mapper = { cursor: SqlCursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) {
                val parent = cursor.getString(1)?.let { ">$it" }.orEmpty()
                rows += "${cursor.getString(0)}$parent at=${cursor.getLong(2)} v=${cursor.getLong(3)} latched=${cursor.getLong(4)}"
            }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    private fun logCount(): Long = scalar("SELECT count(*) FROM sync_change_log")!!.toLong()

    @Test
    fun `a child written before its parent gets the parent once the apply's categories are stored`() = runTest {
        store.apply {
            insertCategory(stored("Weekly", parent = "Reading"))
            insertCategory(stored("Reading"))
        }

        rows() shouldContainExactly listOf(
            "Reading at=1000 v=0 latched=0",
            "Weekly>Reading at=1000 v=0 latched=0",
        )
        logCount() shouldBe 0
    }

    @Test
    fun `a cycle of parents is stored as merged, each member under the other`() = runTest {
        store.apply {
            insertCategory(stored("North", parent = "South"))
            insertCategory(stored("South", parent = "North"))
        }

        rows() shouldContainExactly listOf(
            "North>South at=1000 v=0 latched=0",
            "South>North at=1000 v=0 latched=0",
        )
        logCount() shouldBe 0
    }

    @Test
    fun `an update whose parent comes later in the apply gets it too`() = runTest {
        store.apply { insertCategory(stored("Weekly")) }
        val weekly = store.apply { category(CategorySyncKey.of("Weekly"))!! }

        store.apply {
            updateCategory(stored("Weekly", parent = "Reading", modifiedAt = 2_000L).copy(id = weekly.id))
            insertCategory(stored("Reading"))
        }

        rows() shouldContainExactly listOf(
            "Reading at=1000 v=0 latched=0",
            "Weekly>Reading at=2000 v=0 latched=0",
        )
        logCount() shouldBe 0
    }

    @Test
    fun `a parent deleted by the apply leaves its children at the top level, with their clocks`() = runTest {
        store.apply {
            insertCategory(stored("Reading"))
            insertCategory(stored("Weekly", parent = "Reading", modifiedAt = 1_500L))
        }
        val reading = store.apply { category(CategorySyncKey.of("Reading"))!! }

        // Only the parent's line: the child is not in this apply, as when the other device deleted
        // the parent after this one nested the child.
        store.apply { updateCategory(reading.copy(deletedAt = 3_000L)) }

        rows() shouldContainExactly listOf(
            "Reading at=1000 v=0 latched=0",
            "Weekly at=1500 v=0 latched=0",
        )
        logCount() shouldBe 0
    }

    @Test
    fun `a line naming a parent this device holds deleted is stored with none`() = runTest {
        store.apply { insertCategory(stored("Reading", deletedAt = 3_000L)) }

        store.apply { insertCategory(stored("Weekly", parent = "Reading", modifiedAt = 2_000L)) }

        rows() shouldContainExactly listOf(
            "Reading at=1000 v=0 latched=0",
            "Weekly at=2000 v=0 latched=0",
        )
    }

    @Test
    fun `a parent this device never receives is stored as none, as before`() = runTest {
        store.apply { insertCategory(stored("Weekly", parent = "Not here")) }

        rows() shouldContainExactly listOf("Weekly at=1000 v=0 latched=0")
    }
}
