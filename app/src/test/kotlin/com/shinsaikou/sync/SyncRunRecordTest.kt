package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.webdav.WebDavHttpException
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

/** The run record Sync settings reads (phase 3 unit G): its JSON, its cap, and its outcomes by key. */
class SyncRunRecordTest {

    private val values = mutableMapOf<String, Any>()
    private val record = SyncRunRecord(MapPreferenceStore(values))

    private fun result(
        conflicts: List<SyncConflict> = listOf(SyncConflict(RowKind.CHAPTER, "Title 1", "Chapter 3", setOf("read", "bookmark"))),
        rowsWritten: Int = 5,
        exported: Boolean = true,
    ) = SyncRunResult(
        segmentsApplied = 2,
        exportsApplied = 1,
        snapshotApplied = false,
        rowsWritten = rowsWritten,
        rowsSkipped = 0,
        stoppedAtGap = false,
        segmentsPushed = 1,
        exported = exported,
        compacted = false,
        filesDeleted = 0,
        conflicts = conflicts,
        before = SyncLibraryCounts(libraryEntries = 10, categories = 2, readChapters = 30, bookmarkedChapters = 4),
        after = SyncLibraryCounts(libraryEntries = 12, categories = 3, readChapters = 41, bookmarkedChapters = 5),
    )

    @Test
    fun `a report round trips through its JSON, read back by a second reader of the same store`() {
        record.attempted(1_000L)
        record.succeeded(2_000L, result())

        val reread = SyncRunRecord(MapPreferenceStore(values))

        reread.lastAttemptAt.get() shouldBe 1_000L
        reread.lastSuccessAt.get() shouldBe 2_000L
        reread.outcome() shouldBe SyncOutcome.Success
        reread.reports.get() shouldBe listOf(
            SyncRunReport(
                at = 2_000L,
                conflicts = listOf(SyncRunReport.Conflict(RowKind.CHAPTER, "Title 1", "Chapter 3", listOf("bookmark", "read"))),
                conflictCount = 1,
                routineMerges = 4,
                before = SyncRunReport.Counts(10, 2, 30, 4),
                after = SyncRunReport.Counts(12, 3, 41, 5),
                exported = true,
            ),
        )
        // Stored as text under an app-state key, which no backup carries.
        val key = Preference.appStateKey("sync_run_reports")
        (values[key] as String) shouldBe
            """[{"at":2000,"conflicts":[{"kind":"chapter","title":"Title 1","chapter":"Chapter 3","fields":["bookmark","read"]}],""" +
            """"conflictCount":1,"routineMerges":4,"before":{"libraryEntries":10,"categories":2,"readChapters":30,""" +
            """"bookmarkedChapters":4},"after":{"libraryEntries":12,"categories":3,"readChapters":41,"bookmarkedChapters":5},""" +
            """"exported":true}]"""
        values.keys.all { Preference.isAppState(it) } shouldBe true
    }

    @Test
    fun `the record keeps the last ten reports, newest first`() {
        (1..12).forEach { record.succeeded(it * 1_000L, result(rowsWritten = it)) }

        val reports = SyncRunRecord(MapPreferenceStore(values)).reports.get()

        reports shouldHaveSize SyncRunRecord.MAX_REPORTS
        reports.map { it.at } shouldBe (12 downTo 3).map { it * 1_000L }
    }

    @Test
    fun `a report keeps the first hundred conflicts and the exact count`() {
        val many = (1..250).map { SyncConflict(RowKind.MANGA, "Title $it", null, setOf("notes")) }

        record.succeeded(1L, result(conflicts = many, rowsWritten = 300))

        val report = SyncRunRecord(MapPreferenceStore(values)).reports.get().single()
        report.conflicts shouldHaveSize SyncRunReport.MAX_CONFLICTS
        report.conflicts.first().title shouldBe "Title 1"
        report.conflictCount shouldBe 250
        report.routineMerges shouldBe 50
    }

    @Test
    fun `a failure is stored as its key and detail, never its message, and a success clears the detail`() {
        record.succeeded(1L, result())
        record.failed(WebDavHttpException(507, "PUT", "saikou-sync/v1/private/path"))

        val reread = SyncRunRecord(MapPreferenceStore(values))
        reread.outcome() shouldBe SyncOutcome.Failure(ErrorKey.WEBDAV_HTTP, "507")
        // A failure does not move the last success, and adds no report.
        reread.lastSuccessAt.get() shouldBe 1L
        reread.reports.get() shouldHaveSize 1
        values.values.joinToString() shouldNotContain "private/path"

        record.failed(NewerFormatException(found = 2))
        SyncRunRecord(MapPreferenceStore(values)).outcome() shouldBe SyncOutcome.Failure(ErrorKey.SYNC_NEWER_FORMAT, null)

        record.succeeded(2L, result())
        SyncRunRecord(MapPreferenceStore(values)).outcome() shouldBe SyncOutcome.Success
        values.containsKey(Preference.appStateKey("sync_last_outcome_detail")) shouldBe false
    }

    @Test
    fun `no run reads as no outcome, an unknown key as generic, and a report list that does not decode as none`() {
        record.outcome() shouldBe null

        values[Preference.appStateKey("sync_last_outcome")] = "a_key_from_a_later_build"
        values[Preference.appStateKey("sync_run_reports")] = "[{not json"

        val reread = SyncRunRecord(MapPreferenceStore(values))
        reread.outcome() shouldBe SyncOutcome.Failure(ErrorKey.GENERIC, null)
        reread.reports.get() shouldBe emptyList()
    }
}
