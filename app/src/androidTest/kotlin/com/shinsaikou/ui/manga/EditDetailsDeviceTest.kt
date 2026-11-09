package com.shinsaikou.ui.manga

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.i18n.R
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.ui.main.MainActivity
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import com.shinsaikou.util.EvidenceCapture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.util.Date

/**
 * The owner's detail and cover edits on a device (phase 5 unit ED, R138 item 2; R165 item 4): the
 * Edit details dialog's open, save, empty-title refusal and Reset all with its question; the author,
 * artist, status and genre fields and the header that shows them; the shown title on every screen a
 * device can read (the library grid, the entry screen, Updates and History); and the cover viewer's
 * set and delete of a custom cover.
 *
 * Every entry is the test's own, a favourite on a source id no extension carries, `initialized`
 * so the entry screen starts no fetch, titled with the run's time so nothing the device already
 * holds matches it. Each test soft-deletes what it seeded, whatever the outcome. The source's title
 * and the edit share no word but the run's mark, so a screen that shows one cannot pass for
 * showing the other; every absence asserted here follows a presence on the same screen.
 *
 * The cover's image picker is answered by an [Instrumentation.ActivityMonitor] on the picker's
 * intent, which hands back a PNG this test wrote, as espresso-intents' stubbing does: the system
 * picker never opens.
 */
@RunWith(AndroidJUnit4::class)
class EditDetailsDeviceTest {

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

    /** The theme an evidence run names, before the launch; nothing on any other run. */
    @get:Rule(order = 2)
    val evidenceTheme: TestRule = EvidenceCapture.themeRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /** The run's mark, in both titles and nothing else on the device. */
    private val mark = "ed${System.nanoTime()}"
    private val sourceTitle = "Source named $mark"
    private val editedTitle = "Owner named $mark"
    private val sourceDescription = "The source's summary of $mark"
    private val editedDescription = "The owner's summary of $mark"
    private val sourceAuthor = "Source writer $mark"
    private val editedAuthor = "Owner writer $mark"
    private val sourceArtist = "Source painter $mark"
    private val editedArtist = "Owner painter $mark"
    private val sourceGenre = "Sourcegenre$mark"
    private val editedGenres = listOf("Ownergenre$mark", "Othergenre$mark")

    private var mangaId = -1L

    /** What the rule launched the activity with; see `ChapterRowDeviceTest.handTheLaunchIntentBack`. */
    private lateinit var launchIntent: Intent

    @Before
    fun awaitLibraryScreen() {
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.library_title)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = LAUNCH_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        launchIntent = composeRule.activity.intent
    }

    @After
    fun removeTheEntry() {
        if (::launchIntent.isInitialized) {
            composeRule.runOnUiThread { composeRule.activity.intent = launchIntent }
        }
        if (mangaId > 0) {
            graph.coverCache.deleteCustomCover(mangaId)
            runBlocking { graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis()) }
        }
    }

    /**
     * The overflow's Edit details opens the dialog prefilled with what the screen shows; Save writes
     * the edit and the screen shows it; an emptied title is refused (the message, Save disabled,
     * nothing written); Reset all asks, and once answered clears both and the screen shows the
     * source's text again.
     */
    @Test
    fun theDialogSavesAnEditRefusesAnEmptyTitleAndResets() {
        seedEntry()
        openEntry()
        awaitText(sourceDescription)

        openDialog()
        // Prefilled with what the screen shows; Reset all is off while there is no edit.
        dialogField(EDIT_DETAILS_TITLE_TAG).assertTextContains(sourceTitle)
        dialogField(EDIT_DETAILS_DESCRIPTION_TAG).assertTextContains(sourceDescription)
        dialogButton(R.string.manga_edit_reset).assertIsNotEnabled()
        capture("edit-dialog")
        dialogField(EDIT_DETAILS_TITLE_TAG).performTextClearance()
        dialogField(EDIT_DETAILS_TITLE_TAG).performTextInput(editedTitle)
        dialogField(EDIT_DETAILS_DESCRIPTION_TAG).performTextClearance()
        dialogField(EDIT_DETAILS_DESCRIPTION_TAG).performTextInput(editedDescription)
        dialogButton(R.string.action_save).performClick()

        awaitDialogGone()
        awaitText(editedTitle)
        awaitText(editedDescription)
        assertEquals("the stored title edit", editedTitle, manga().titleOverride)
        assertEquals("the stored description edit", editedDescription, manga().descriptionOverride)
        assertEquals("the source's title under the edit", sourceTitle, manga().title)
        composeRule.onAllNodesWithText(sourceTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(sourceDescription).assertCountEquals(0)
        capture("entry-edited")

        openDialog()
        dialogField(EDIT_DETAILS_TITLE_TAG).assertTextContains(editedTitle)
        dialogField(EDIT_DETAILS_TITLE_TAG).performTextClearance()
        awaitText(string(R.string.manga_edit_title_empty))
        dialogButton(R.string.action_save).assertIsNotEnabled()
        capture("edit-dialog-empty-title")
        dialogButton(R.string.action_cancel).performClick()
        awaitDialogGone()
        assertEquals("a refused title wrote nothing", editedTitle, manga().titleOverride)

        openDialog()
        dialogButton(R.string.manga_edit_reset).assertIsEnabled()
        resetAll()
        awaitDialogGone()
        awaitText(sourceTitle)
        awaitText(sourceDescription)
        assertNull("the reset title edit", manga().titleOverride)
        assertNull("the reset description edit", manga().descriptionOverride)
        composeRule.onAllNodesWithText(editedTitle).assertCountEquals(0)
    }

    /**
     * The author, artist, status and genres (R165 item 4): each field starts from the source's value;
     * Save stores the edits and the header shows the edited author, the artist line, the status and
     * the genre chips; Reset all asks, its Cancel keeps every edit, and once answered every detail is
     * the source's again.
     */
    @Test
    fun theDialogEditsTheAuthorArtistStatusAndGenresAndResetsAll() {
        seedEntry(withDetails = true)
        openEntry()
        awaitText(string(R.string.manga_author_format, sourceAuthor))
        awaitText(string(R.string.manga_artist_format, sourceArtist))
        awaitText(string(R.string.manga_status_ongoing))

        openDialog()
        dialogField(EDIT_DETAILS_AUTHOR_TAG).assertTextContains(sourceAuthor)
        dialogField(EDIT_DETAILS_ARTIST_TAG).assertTextContains(sourceArtist)
        dialogField(EDIT_DETAILS_STATUS_TAG).assertTextContains(string(R.string.manga_status_ongoing))
        dialogField(EDIT_DETAILS_AUTHOR_TAG).performTextClearance()
        dialogField(EDIT_DETAILS_AUTHOR_TAG).performTextInput(editedAuthor)
        dialogField(EDIT_DETAILS_ARTIST_TAG).performTextClearance()
        dialogField(EDIT_DETAILS_ARTIST_TAG).performTextInput(editedArtist)
        dialogField(EDIT_DETAILS_STATUS_TAG).performClick()
        awaitNode(hasText(string(R.string.manga_status_completed)) and hasClickAction())
        composeRule.onNode(hasText(string(R.string.manga_status_completed)) and hasClickAction()).performClick()
        dialogField(EDIT_DETAILS_STATUS_TAG).assertTextContains(string(R.string.manga_status_completed))
        // A tap on the source's genre removes it; the field adds two, split at the comma.
        dialogChip(sourceGenre).performScrollTo().performClick()
        dialogField(EDIT_DETAILS_GENRE_TAG).performTextInput(editedGenres.joinToString(", "))
        composeRule.onNode(
            hasContentDescription(string(R.string.action_add)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        editedGenres.forEach { dialogChip(it).assertExists() }
        composeRule.onAllNodes(hasText(sourceGenre) and hasAnyAncestor(isDialog())).assertCountEquals(0)
        capture("edit-dialog-details")
        dialogButton(R.string.action_save).performClick()

        awaitDialogGone()
        awaitText(string(R.string.manga_author_format, editedAuthor))
        awaitText(string(R.string.manga_artist_format, editedArtist))
        awaitText(string(R.string.manga_status_completed))
        editedGenres.forEach { awaitText(it) }
        composeRule.onAllNodesWithText(sourceGenre).assertCountEquals(0)
        val edited = manga()
        assertEquals("the stored author edit", editedAuthor, edited.authorOverride)
        assertEquals("the stored artist edit", editedArtist, edited.artistOverride)
        assertEquals("the stored status edit", MangaStatus.COMPLETED.code.toLong(), edited.statusOverride)
        assertEquals("the stored genre edit", editedGenres, edited.genreOverride)
        assertEquals("the source's author under the edit", sourceAuthor, edited.author)
        assertNull("the title saved as the source's is no edit", edited.titleOverride)
        capture("entry-details-edited")

        // Reset all asks first; Cancel keeps every edit.
        openDialog()
        dialogButton(R.string.manga_edit_reset).performClick()
        awaitText(string(R.string.manga_edit_reset_title))
        capture("edit-dialog-reset-question")
        composeRule.onNode(
            hasText(string(R.string.action_cancel)) and hasClickAction() and hasAnyAncestor(resetQuestion()),
        ).performClick()
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(string(R.string.manga_edit_reset_title)).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("a cancelled reset wrote nothing", editedAuthor, manga().authorOverride)
        composeRule.onAllNodes(isDialog()).assertCountEquals(1)
        composeRule.onNode(
            hasText(string(R.string.manga_edit_reset)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
        composeRule.onNode(
            hasText(string(R.string.manga_edit_reset)) and hasClickAction() and hasAnyAncestor(resetQuestion()),
        ).performClick()

        awaitDialogGone()
        awaitText(string(R.string.manga_author_format, sourceAuthor))
        awaitText(string(R.string.manga_artist_format, sourceArtist))
        awaitText(string(R.string.manga_status_ongoing))
        awaitText(sourceGenre)
        val reset = manga()
        assertNull("the reset author edit", reset.authorOverride)
        assertNull("the reset artist edit", reset.artistOverride)
        assertNull("the reset status edit", reset.statusOverride)
        assertNull("the reset genre edit", reset.genreOverride)
        capture("entry-details-reset")
    }

    /** The library grid draws the edit, and its search finds the entry by the edit's own words. */
    @Test
    fun theLibraryShowsTheEdit() {
        seedEntry()
        edit()
        composeRule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Owner named $mark")
        val cell = hasText(editedTitle) and hasClickAction() and hasSetTextAction().not()
        awaitNode(cell)
        composeRule.onAllNodes(hasText(sourceTitle) and hasSetTextAction().not()).assertCountEquals(0)
        capture("library-edited")
    }

    /** The entry screen's header, top bar and description show the edit, and the source's text nowhere. */
    @Test
    fun theEntryScreenShowsTheEdit() {
        seedEntry()
        edit()
        openEntry()
        awaitText(editedDescription)
        // The top bar and the header both draw the title.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(editedTitle).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodesWithText(sourceTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(sourceDescription).assertCountEquals(0)
    }

    /** Updates and History name the entry by its edit. */
    @Test
    fun updatesAndHistoryShowTheEdit() {
        val now = System.currentTimeMillis()
        seedEntry(dateAdded = now - UPDATE_AGE_MILLIS)
        runBlocking {
            val chapter = graph.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = "/fixture/$mark/1",
                        name = "Chapter 1",
                        chapterNumber = 1.0,
                        dateFetch = now,
                        dateUpload = now,
                    ),
                ),
            ).single()
            graph.historyRepository.upsertHistory(
                HistoryUpdate(chapter.id, readAt = Date(now), sessionReadDuration = 1),
            )
        }
        edit()

        context.startActivity(MainIntents.newIntent(context, MainDestination.UPDATES))
        awaitText(editedTitle)
        composeRule.onAllNodesWithText(sourceTitle).assertCountEquals(0)
        capture("updates-edited")

        context.startActivity(MainIntents.newIntent(context, MainDestination.HISTORY))
        awaitNode(hasText(editedTitle, substring = true))
        composeRule.onAllNodes(hasText(sourceTitle, substring = true)).assertCountEquals(0)
        capture("history-edited")
    }

    /**
     * A tap on the header's cover opens the viewer. With no custom cover, Edit cover goes straight to
     * the picker, whose image becomes the custom cover file and bumps `coverLastModified`; with one,
     * Edit cover's menu deletes it and bumps the clock again.
     */
    @Test
    fun theCoverViewerSetsAndDeletesACustomCover() {
        seedEntry()
        val picked = pickedImage()
        val monitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("image/*")
            },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(picked))),
            true,
        )
        instrumentation.addMonitor(monitor)
        try {
            openEntry()
            val customCover = graph.coverCache.getCustomCoverFile(mangaId)
            assertFalse("a custom cover before the test set one", customCover.exists())
            val before = manga().coverLastModified

            openCover()
            capture("cover-viewer")
            composeRule.onNode(viewerAction(R.string.manga_cover_edit)).performClick()
            awaitTrue("the picked image did not become the custom cover") { customCover.exists() }
            awaitText(string(R.string.cover_updated))
            assertEquals("the picker was asked once", 1, monitor.hits)
            assertEquals("the custom cover's bytes", picked.length(), customCover.length())
            val afterSet = manga().coverLastModified
            assertTrue("coverLastModified did not move on the set ($before, $afterSet)", afterSet > before)

            openCover()
            composeRule.onNode(viewerAction(R.string.manga_cover_edit)).performClick()
            awaitNode(hasText(string(R.string.manga_cover_delete)) and hasClickAction())
            composeRule.onNode(hasText(string(R.string.manga_cover_choose)) and hasClickAction()).assertExists()
            capture("cover-viewer-edit-menu")
            composeRule.onNode(hasText(string(R.string.manga_cover_delete)) and hasClickAction()).performClick()
            awaitTrue("the custom cover was not deleted") { !customCover.exists() }
            // The clock moves after the file goes, as the set's does after its file is written.
            awaitTrue("coverLastModified did not move on the delete (from $afterSet)") {
                manga().coverLastModified > afterSet
            }
            assertEquals("the picker was asked again", 1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            picked.delete()
        }
    }

    // region Fixture

    /** The entry; [withDetails] gives it a source author, artist, status and genre. */
    private fun seedEntry(dateAdded: Long = System.currentTimeMillis(), withDetails: Boolean = false) {
        val manga = Manga.create().copy(
            source = SOURCE_ID,
            url = "/fixture/$mark",
            title = sourceTitle,
            description = sourceDescription,
            favorite = true,
            initialized = true,
            dateAdded = dateAdded,
        )
        val seeded = if (withDetails) {
            manga.copy(
                author = sourceAuthor,
                artist = sourceArtist,
                status = MangaStatus.ONGOING.code.toLong(),
                genre = listOf(sourceGenre),
            )
        } else {
            manga
        }
        mangaId = runBlocking { graph.mangaRepository.insertNetworkManga(listOf(seeded)).single().id }
    }

    /** The title and description edit as the dialog's Save writes it, the entry having no other detail. */
    private fun edit() = runBlocking {
        val edit = SetMangaDetailsOverride.Edit(
            title = editedTitle,
            description = editedDescription,
            author = "",
            artist = "",
            status = null,
            genres = emptyList(),
        )
        assertTrue(SetMangaDetailsOverride(graph.mangaRepository).await(mangaId, edit))
    }

    private fun manga(): Manga = runBlocking { graph.mangaRepository.getMangaById(mangaId) }

    /** A small PNG in the app's cache, readable by the app as the picker's answer. */
    private fun pickedImage(): File {
        val file = File(context.cacheDir, "saikou-edit-details-$mark.png")
        val bitmap = Bitmap.createBitmap(PICKED_WIDTH, PICKED_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(PICKED_RED, PICKED_GREEN, PICKED_BLUE))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    // endregion

    // region Screen

    private fun openEntry() {
        context.startActivity(MainIntents.newIntent(context, MainDestination.ENTRY, mangaId))
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(MANGA_OVERFLOW_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openDialog() {
        composeRule.onNodeWithTag(MANGA_OVERFLOW_TAG).performClick()
        awaitNode(hasText(string(R.string.manga_edit_details)) and hasClickAction())
        composeRule.onNode(hasText(string(R.string.manga_edit_details)) and hasClickAction()).performClick()
        awaitNode(isDialog())
    }

    /** A field of the dialog by its tag, scrolled into view. */
    private fun dialogField(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo()

    /** A genre chip of the dialog, by its genre. */
    private fun dialogChip(genre: String) =
        composeRule.onNode(hasText(genre) and hasClickAction() and hasAnyAncestor(isDialog()))

    /** The question Reset all asks, a dialog of its own over the edit dialog. */
    private fun resetQuestion(): SemanticsMatcher =
        isDialog() and hasAnyDescendant(hasText(string(R.string.manga_edit_reset_title)))

    /** Reset all, then its question's answer. */
    private fun resetAll() {
        dialogButton(R.string.manga_edit_reset).performClick()
        awaitNode(resetQuestion())
        composeRule.onNode(
            hasText(string(R.string.manga_edit_reset)) and hasClickAction() and hasAnyAncestor(resetQuestion()),
        ).performClick()
    }

    private fun dialogButton(label: Int) =
        composeRule.onNode(hasText(string(label)) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun awaitDialogGone() {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openCover() {
        composeRule.onNodeWithTag(MANGA_COVER_TAG).performClick()
        awaitNode(viewerAction(R.string.action_share))
    }

    /** An action of the cover viewer, an icon button described by its label. */
    private fun viewerAction(label: Int): SemanticsMatcher =
        hasContentDescription(string(label)) and hasClickAction() and hasAnyAncestor(isDialog())

    private fun awaitNode(matcher: SemanticsMatcher) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) = awaitNode(hasText(text))

    /** Polls [condition] off the main thread, failing with [message] once the wait has passed. */
    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ACTION_TIMEOUT_MILLIS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("$message (after $ACTION_TIMEOUT_MILLIS ms)")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        EvidenceCapture.screenshot(EVIDENCE_UNIT, name)
    }

    private fun string(resourceId: Int): String = context.getString(resourceId)

    private fun string(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    // endregion

    private companion object {
        const val EVIDENCE_UNIT = "phase5-edit-details"

        /** A source id no extension carries, after `ChapterRowDeviceTest`'s. */
        const val SOURCE_ID = 7_310_000_000_000_031L

        /** An hour, so the chapter fetched now counts as an update of the entry. */
        const val UPDATE_AGE_MILLIS = 3_600_000L

        const val PICKED_WIDTH = 60
        const val PICKED_HEIGHT = 90
        const val PICKED_RED = 200
        const val PICKED_GREEN = 80
        const val PICKED_BLUE = 40

        const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        const val SCREEN_TIMEOUT_MILLIS = 20_000L
        const val ACTION_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L

        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}
