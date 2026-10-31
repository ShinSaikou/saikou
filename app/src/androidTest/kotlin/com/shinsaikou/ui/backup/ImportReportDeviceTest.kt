package com.shinsaikou.ui.backup

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.backup.importer.PreferenceImportCheckpointStore
import com.shinsaikou.di.AppGraph
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The restore report on a device (unit restore-report): a report written where the importer writes
 * it, opened the way the completion notification opens it (`MainIntents.ACTION_OPEN_IMPORT_REPORT`),
 * shows its counts and the source the run found missing, with the action that solves it.
 *
 * The report is the test's own: its source id is one no extension carries, so the registry names it
 * by its id and no repository offers it (Open Extensions, not Install). Whatever report the device
 * held before is put back afterwards, byte for byte.
 */
@RunWith(AndroidJUnit4::class)
class ImportReportDeviceTest {

    /** Outermost, as in `MainActivitySmokeTest`: the permission dialog would cover the activity. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(*NOTIFICATION_PERMISSIONS)

    /** The battery exemption dialog and a crash banner answered first, as in `MainActivitySmokeTest`. */
    @get:Rule(order = 1)
    val firstLaunchAnswered = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                graph.basePreferences.batteryExemptionRequested.set(true)
                graph.crashLog.markExported()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** The store the importer writes through, over the same `filesDir` paths. */
    private val store: ImportCheckpointStore by lazy {
        PreferenceImportCheckpointStore(context, graph.backupPreferences, graph.json)
    }

    private var heldBefore: ByteArray? = null

    /** What the rule launched the activity with; see `ChapterRowDeviceTest.handTheLaunchIntentBack`. */
    private lateinit var launchIntent: Intent

    @Before
    fun seedTheReport() {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        launchIntent = composeRule.activity.intent
        val file = store.reportFile
        heldBefore = file.takeIf { it.exists() }?.readBytes()
        file.parentFile?.mkdirs()
        file.writeText(graph.json.encodeToString(ImportReport.serializer(), REPORT))
    }

    @After
    fun putTheReportBack() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
        val file = store.reportFile
        val before = heldBefore
        if (before != null) file.writeBytes(before) else file.delete()
    }

    @Test
    fun theNotificationsIntentOpensTheReportWithItsCountsAndTheMissingSource() {
        context.startActivity(MainIntents.newIntent(context, MainDestination.IMPORT_REPORT))

        awaitNode(hasText(string(R.string.import_report_title)))
        awaitNode(hasText(context.getString(R.string.import_report_counts_format, INSERTED, MERGED, FAILED)))
        awaitNode(hasText(string(R.string.import_report_missing_sources)))
        awaitNode(hasText(SOURCE_ID.toString()))
        awaitNode(hasText(string(R.string.import_report_open_extensions)) and hasClickAction())
        awaitNode(hasText(string(R.string.import_export_error_log)) and hasClickAction())
    }

    private fun awaitNode(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private companion object {
        /** A source id no extension carries, after `EditDetailsDeviceTest`'s. */
        const val SOURCE_ID = 7_310_000_000_000_041L

        const val INSERTED = 7
        const val MERGED = 2
        const val FAILED = 1

        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        const val SCREEN_TIMEOUT_MILLIS = 20_000L

        val REPORT = ImportReport(
            fileSha256 = "00",
            startedAt = 1_760_000_000_000L,
            finishedAt = 1_760_000_060_000L,
            cancelled = false,
            resumed = false,
            mangaTotal = INSERTED + MERGED + FAILED,
            mangaInserted = INSERTED,
            mangaMerged = MERGED,
            mangaFailed = FAILED,
            chaptersWritten = 0,
            historyWritten = 0,
            tracksWritten = 0,
            categoriesCreated = 0,
            preferencesWritten = 0,
            sourcePreferenceFilesWritten = 0,
            extensionStoresUpserted = 0,
            stubSourcesRemembered = listOf(SOURCE_ID),
            unsupportedTrackers = emptyList(),
            unknownTrackers = emptyList(),
            errors = listOf(
                ImportError(
                    kind = ImportError.Kind.MANGA_ROW,
                    subject = "A restore report entry",
                    sourceLabel = SOURCE_ID.toString(),
                    message = "fixture failure",
                    at = 1_760_000_030_000L,
                ),
            ),
            missingSources = listOf(SOURCE_ID),
        )

        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}
