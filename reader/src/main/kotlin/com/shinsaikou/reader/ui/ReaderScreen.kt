package com.shinsaikou.reader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.components.SaikouSnackbarHost
import com.shinsaikou.presentation.core.util.ErrorKeyText
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.settings.ReaderBackground
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import com.shinsaikou.reader.state.ChapterWebActions
import com.shinsaikou.reader.state.EntryScreenIntent
import com.shinsaikou.reader.state.ModeResolver
import com.shinsaikou.reader.state.ReaderEffect
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.viewer.PageImageActions
import com.shinsaikou.reader.viewer.ReaderViewerHost
import com.shinsaikou.reader.viewer.ViewerHolder
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.CombinedCircularProgressIndicator
import tachiyomi.presentation.core.util.collectAsState

/**
 * The reader's one composable tree. Contract 07 part 2 section 5.2.
 *
 * The z-order is upstream's: the page indicator sits under the content overlay so the brightness dim
 * covers it, and the app bars sit on top. Two additions: the navigation overlay is directly above
 * the viewer instead of beside it in an XML layout, and the initial spinner is a Compose indicator
 * rather than a View added to a container, because in this tree there is no container `ViewGroup`
 * to add one to (decision D15).
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    viewerHolder: ViewerHolder,
    webActions: ChapterWebActions,
    entryScreenIntent: EntryScreenIntent,
    pageImageActions: PageImageActions,
    uiPreferences: UiPreferences,
    solveCoordinator: CloudflareSolveCoordinator,
    onAssistUrlChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Resources rather than the context: LocalContext is not re-read on a configuration
    // change, so a string pulled through it survives a locale switch stale. The ids here
    // arrive as callback arguments, so stringResource cannot hoist them.
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val prefs: ReaderPreferences = viewModel.readerPreferences
    val snackbarHostState = remember { SnackbarHostState() }
    val messages = remember {
        ReaderMessages(
            snackbar = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
            toast = { text -> context.toast(text) },
            activityFinishing = { activity?.isFinishing == true },
        )
    }

    val host = remember {
        ReaderViewerHost(
            context = context,
            scope = scope,
            readerPreferences = prefs,
            state = { viewModel.state.value },
            send = viewModel::send,
            animatedPolicy = viewModel.animatedImagePolicy,
        )
    }
    // Ruling R21: the vendored AbstractComposeViews open their own composition roots and cannot
    // inherit the theme, so the resolved values travel on the host. Their night mode is the reader
    // background's, as upstream's createReaderThemeContext sets it (black and gray dark, white light,
    // automatic the app's mode); the app theme and pure black stay the app's.
    val themeValues = rememberReaderThemeValues(uiPreferences)
    val readerTheme by prefs.readerTheme.collectAsState()
    val downloadedChapterIds by viewModel.downloadedChapterIds.collectAsStateWithLifecycle()
    SideEffect {
        host.appTheme = themeValues.appTheme
        host.isDark = ReaderBackground.fromPreference(readerTheme).isDark(themeValues.isDark)
        host.isAmoled = themeValues.isAmoled
        host.downloadedChapterIds = downloadedChapterIds
    }

    val displayRefreshHost = remember { DisplayRefreshHost(prefs) }
    val settingsViewModel = remember {
        ReaderSettingsViewModel(
            preferences = prefs,
            readerState = viewModel.state,
            scope = scope,
            onChangeReadingMode = { viewModel.send(ReaderEvent.SetReadingMode(it)) },
            onChangeOrientation = { viewModel.send(ReaderEvent.SetOrientation(it)) },
        )
    }

    // The chapter web URL is shell state, not reader state: one IO call per chapter, and nothing in
    // the reducer depends on it (decision D14).
    val chapterUrl by produceState<String?>(null, state.manga, state.currentChapter) {
        val manga = state.manga
        val chapter = state.currentChapter?.chapter
        value = if (manga == null || chapter == null) null else webActions.chapterUrl(manga, chapter)
    }
    LaunchedEffect(chapterUrl) { onAssistUrlChanged(chapterUrl) }

    val showPageNumber by prefs.showPageNumber.collectAsState()
    val flashOnPageChange by prefs.flashOnPageChange.collectAsState()
    val grayscale by prefs.grayscale.collectAsState()
    val invertedColors by prefs.invertedColors.collectAsState()
    val fullscreen by prefs.fullscreen.collectAsState()
    val drawUnderCutout by prefs.drawUnderCutout.collectAsState()
    val colorFilter by prefs.colorFilter.collectAsState()
    val colorFilterValue by prefs.colorFilterValue.collectAsState()
    val colorFilterMode by prefs.colorFilterMode.collectAsState()

    val background = ReaderBackground.fromPreference(readerTheme).color(context.isNightMode())

    ReaderSystemBars(state = state, preferences = prefs, onEvent = viewModel::send)
    ReaderEffects(viewModel, viewerHolder, displayRefreshHost, messages)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(background)),
    ) {
        // Not before the session: until it loads, readingMode is the state's RIGHT_TO_LEFT default,
        // and sessionLoaded sets the entry's mode without bumping the generation, so a viewer built
        // first stayed a pager on a webtoon entry while the state said WEBTOON. Which one won was a
        // race, seen on the low RAM AVD.
        if (state.session != null) {
            ReaderViewerContainer(
                generation = state.viewerGeneration,
                readingMode = state.readingMode,
                host = host,
                holder = viewerHolder,
                grayscale = grayscale,
                invertedColors = invertedColors,
                fullscreen = fullscreen,
                drawUnderCutout = drawUnderCutout,
                onViewerBuilt = { viewModel.send(ReaderEvent.ViewerBuilt(it)) },
            )
        }

        ReaderNavigationOverlay(request = host.navigationOverlay.value)

        if (!state.menuVisible && showPageNumber) {
            ReaderPageIndicator(
                currentPage = state.currentPage + 1,
                totalPages = state.totalPages,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorFilterValue.takeIf { colorFilter },
            colorBlendMode = ReaderSettingsOptions.colorFilterModeOrNull(colorFilterMode)?.second
                ?: BlendMode.SrcOver,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }

        // Under the app bars, so a menu opened during a long wait keeps its back button.
        ReaderSolveBanner(
            state = state,
            coordinator = solveCoordinator,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        ReaderAppBarsHost(
            state = state,
            preferences = prefs,
            chapterUrl = chapterUrl,
            onEvent = viewModel::send,
            onNavigateUp = { activity?.finish() },
            onOpenEntry = {
                val manga = state.manga ?: return@ReaderAppBarsHost
                context.startActivitySafely(entryScreenIntent.create(context, manga.id), messages)
                // finish() rather than leaving it to the intent's clear-top: only finish() sends
                // Finishing, which deletes the chapters read under "delete after reading".
                activity?.takeIf { !it.isFinishing }?.finish()
            },
            onOpenInWebView = {
                val url = chapterUrl ?: return@ReaderAppBarsHost
                val manga = state.manga ?: return@ReaderAppBarsHost
                val intent = webActions.webViewIntent(context, manga, url) ?: webActions.browserIntent(url)
                context.startActivitySafely(intent, messages)
            },
            onOpenInBrowser = {
                val url = chapterUrl ?: return@ReaderAppBarsHost
                context.startActivitySafely(webActions.browserIntent(url), messages)
            },
            onShareChapter = {
                val url = chapterUrl ?: return@ReaderAppBarsHost
                context.startActivitySafely(webActions.shareIntent(context, url), messages)
            },
        )

        // Over the bars, as a snackbar is over a screen's bottom bar (unit ID3).
        SaikouSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )

        if (state.window == null && state.phase !is ReaderPhase.Failed) {
            CombinedCircularProgressIndicator(
                progress = { 0f },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    ReaderDialogs(
        state = state,
        settingsViewModel = settingsViewModel,
        pageImageActions = pageImageActions,
        messages = messages,
        onEvent = viewModel::send,
    )
}

/**
 * Collects `viewModel.effects` once and performs the shell's share of them. The nine rows of part 2
 * section 5.2 are the whole of it, with upstream's reading mode toast (phase 4 unit MP1) as a tenth;
 * every other effect is the view model's own.
 *
 * The effects are queued in the view model until this collects them, so the ones sent before the
 * first composition (`ApplyOrientation` from the session, `Finish` from a chapter that fails at
 * once) are performed here too, in order, rather than lost.
 *
 * A reading mode toast cancels the one before it and goes with the screen, as upstream's
 * `readingModeToast` is cancelled before each new one and in `onDestroy`. Every other message goes
 * through [ReaderMessages]: the snackbar while the reader is open, a toast from `Finish` on.
 */
@Composable
private fun ReaderEffects(
    viewModel: ReaderViewModel,
    holder: ViewerHolder,
    displayRefreshHost: DisplayRefreshHost,
    messages: ReaderMessages,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    LaunchedEffect(Unit) {
        var readingModeToast: Toast? = null
        try {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ReaderEffect.ApplyOrientation -> {
                        val requested = with(ModeResolver) { effect.orientation.toActivityOrientation() }
                        if (activity != null && activity.requestedOrientation != requested) {
                            activity.requestedOrientation = requested
                        }
                    }
                    // Nothing: the viewer is keyed on state.viewerGeneration, so the state change is
                    // what rebuilds it, and performing this too would build two viewers (decision D16).
                    is ReaderEffect.RebuildViewer -> Unit
                    // Dropped when the viewer is null, because ViewerBuilt re-issues both.
                    is ReaderEffect.SetViewerChapters -> holder.viewer?.setChapters(effect.window)
                    is ReaderEffect.MoveViewerToPage -> holder.viewer?.moveToPage(effect.page)
                    is ReaderEffect.NotifySplitInserted -> holder.viewer?.onSplitInserted(effect.page)
                    ReaderEffect.PageChangedFlash -> displayRefreshHost.flash()
                    ReaderEffect.TrimViewer -> holder.viewer?.trimMemory()
                    is ReaderEffect.ShowMessage -> messages.show(resources.getString(effect.res))
                    is ReaderEffect.ShowReadingMode -> {
                        readingModeToast?.cancel()
                        readingModeToast = context.toast(resources.getString(effect.mode.stringRes))
                    }
                    is ReaderEffect.Finish -> {
                        // From here every message is a toast: a snackbar would go with the activity.
                        messages.closing()
                        // Why the reader closed, in the words every other screen uses for the failure
                        // (ruling R67), unless the reducer named its own reason.
                        effect.error?.let {
                            messages.show(effect.message?.let(resources::getString) ?: ErrorKeyText.of(resources, it))
                        }
                        // Guarded because finish() is what sends ReaderEvent.Finishing: calling it on
                        // an activity that is already finishing is how this became an infinite loop
                        // once, and the guard costs one field read.
                        activity?.takeIf { !it.isFinishing }?.finish()
                    }
                    else -> Unit
                }
            }
        } finally {
            readingModeToast?.cancel()
        }
    }
}

/** A browser or a share chooser may be absent; a missing one is a message, never a crash. */
private fun Context.startActivitySafely(intent: Intent, messages: ReaderMessages) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        messages.show(e.message ?: getString(R.string.unknown_error))
    }
}
