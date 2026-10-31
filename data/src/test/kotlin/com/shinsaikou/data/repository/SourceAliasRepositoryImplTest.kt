package com.shinsaikou.data.repository

import com.shinsaikou.data.source.SourceAliasRepositoryImpl
import com.shinsaikou.domain.source.model.SourceAlias
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SourceAliasRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { SourceAliasRepositoryImpl(handler) }

    @Test
    fun `upsert inserts then updates the target of an existing alias`() = runTest {
        repository.upsert(SourceAlias(oldSourceId = 1, newSourceId = 2, dateAdded = 100))
        repository.upsert(SourceAlias(oldSourceId = 1, newSourceId = 3, dateAdded = 200))

        repository.getAll() shouldContainExactlyInAnyOrder listOf(
            SourceAlias(oldSourceId = 1, newSourceId = 3, dateAdded = 100),
        )
    }

    @Test
    fun `resolve returns the input when no alias exists`() = runTest {
        repository.resolve(7) shouldBe 7
    }

    @Test
    fun `resolve walks the chain to its end`() = runTest {
        repository.upsert(SourceAlias(oldSourceId = 1, newSourceId = 2, dateAdded = 0))
        repository.upsert(SourceAlias(oldSourceId = 2, newSourceId = 3, dateAdded = 0))
        repository.upsert(SourceAlias(oldSourceId = 3, newSourceId = 4, dateAdded = 0))

        repository.resolve(1) shouldBe 4
    }

    @Test
    fun `resolve gives up after eight hops instead of looping forever`() = runTest {
        // A cycle a restored backup could introduce: 1 to 2 and 2 back to 1.
        repository.upsert(SourceAlias(oldSourceId = 1, newSourceId = 2, dateAdded = 0))
        repository.upsert(SourceAlias(oldSourceId = 2, newSourceId = 1, dateAdded = 0))

        repository.resolve(1) shouldBe 1
    }

    @Test
    fun `remove deletes the row`() = runTest {
        repository.upsert(SourceAlias(oldSourceId = 1, newSourceId = 2, dateAdded = 0))

        repository.remove(1)

        repository.getAll() shouldBe emptyList()
        repository.resolve(1) shouldBe 1
    }
}
