package com.shinsaikou.domain.merged.interactor

import app.cash.turbine.test
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.merged.model.MergedGroupCounts
import com.shinsaikou.domain.merged.model.MergedMember
import com.shinsaikou.domain.merged.repository.MergedGroupRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** While the Experimental switch is off [GetMergedGroups] reads nothing from the repository (R138). */
class GetMergedGroupsGateTest {

    private val repository = mockk<MergedGroupRepository>()
    private val preferences = LibraryPreferences(InMemoryPreferenceStore())
    private val getMergedGroups = GetMergedGroups(repository, preferences)

    private val group = MergedGroup(
        id = 1,
        mainMangaId = 1,
        showAll = false,
        members = listOf(1L, 2L).map { MergedMember(it, source = it, url = "/$it", position = it, checkUpdates = true) },
    )

    init {
        coEvery { repository.getLiveGroups() } returns listOf(group)
        every { repository.subscribeLiveGroups() } returns flowOf(listOf(group))
        every { repository.subscribeGroupCounts() } returns flowOf(mapOf(1L to MergedGroupCounts(1, 4, 2, 0, 0)))
    }

    @Test
    fun `await with the switch off returns nothing and queries nothing`() = runTest {
        getMergedGroups.await().shouldBeEmpty()

        coVerify(exactly = 0) { repository.getLiveGroups() }
    }

    @Test
    fun `await with the switch on returns the live groups`() = runTest {
        preferences.mergedEntries.set(true)

        getMergedGroups.await() shouldBe listOf(group)
    }

    @Test
    fun `subscribe with the switch off emits an empty list and subscribes to no query`() = runTest {
        getMergedGroups.subscribe().test {
            awaitItem().shouldBeEmpty()
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { repository.subscribeLiveGroups() }
    }

    @Test
    fun `subscribe follows the switch when it is turned on and off`() = runTest {
        getMergedGroups.subscribe().test {
            awaitItem().shouldBeEmpty()
            preferences.mergedEntries.set(true)
            awaitItem() shouldBe listOf(group)
            preferences.mergedEntries.set(false)
            awaitItem().shouldBeEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `subscribe for one entry finds the group of any member and null for a stranger`() = runTest {
        preferences.mergedEntries.set(true)

        getMergedGroups.subscribe(2).test {
            awaitItem() shouldBe group
            cancelAndIgnoreRemainingEvents()
        }
        getMergedGroups.subscribe(9).test {
            awaitItem().shouldBeNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `subscribeTitles with the switch off reads no counts`() = runTest {
        getMergedGroups.subscribeTitles().test {
            awaitItem().shouldBeEmpty()
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { repository.subscribeGroupCounts() }
    }

    @Test
    fun `subscribeTitles with the switch on pairs each group with its counts`() = runTest {
        preferences.mergedEntries.set(true)

        getMergedGroups.subscribeTitles().test {
            val title = awaitItem().single()
            title.group shouldBe group
            title.counts?.total shouldBe 4
            cancelAndIgnoreRemainingEvents()
        }
    }
}
