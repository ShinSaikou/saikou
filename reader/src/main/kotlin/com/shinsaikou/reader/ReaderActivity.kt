package com.shinsaikou.reader

import android.app.assist.AssistContent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.di.ReaderGraph
import com.shinsaikou.reader.di.ReaderGraphOwner
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.ui.ReaderScreen
import com.shinsaikou.reader.ui.ReaderTheme
import com.shinsaikou.reader.viewer.ViewerHolder

/**
 * Window plumbing and event forwarding, and no reader logic of its own. Contract 07 part 2 section
 * 5.1, under ruling R11: `:reader` owns this activity and declares it in its own manifest.
 */
class ReaderActivity : AppCompatActivity() {

    private val readerGraph: ReaderGraph by lazy {
        (application as ReaderGraphOwner).readerGraphFactory.createReaderGraph()
    }

    private val viewModel: ReaderViewModel by viewModels {
        viewModelFactory {
            initializer {
                val savedState = createSavedStateHandle()
                ReaderIntents.readArgs(savedState, intent)
                readerGraph.readerViewModelFactory.create(savedState)
            }
        }
    }

    private val viewerHolder = ViewerHolder()
    private var assistUrl: String? = null

    /** The orientation last seen, so a configuration change that does not turn the screen sends nothing. */
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    override fun onCreate(savedInstanceState: Bundle?) {
        // Both lines run before super.onCreate. Together with the opaque black windowBackground in
        // the reader theme they are the fix for the grey band the platform's automatic nav-bar scrim
        // puts over a pure black page.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        orientation = resources.configuration.orientation
        ReaderBenchHooks.beginOpen(intent.getStringExtra(ReaderIntents.EXTRA_BENCH_TAG))
        setContent {
            ReaderTheme(readerGraph.uiPreferences) {
                ReaderScreen(
                    viewModel = viewModel,
                    viewerHolder = viewerHolder,
                    webActions = readerGraph.chapterWebActions,
                    pageImageActions = readerGraph.pageImageActions,
                    uiPreferences = readerGraph.uiPreferences,
                    solveCoordinator = readerGraph.cloudflareSolveCoordinator,
                    onAssistUrlChanged = { assistUrl = it },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.send(ReaderEvent.Resumed)
    }

    override fun onPause() {
        viewModel.send(ReaderEvent.Paused)
        super.onPause()
    }

    /**
     * The manifest's `configChanges` keep this activity through a rotation, so nothing rebuilds the
     * viewer that would show the reading mode again, as upstream's recreated activity does; the reducer
     * shows it on [ReaderEvent.OrientationChanged] instead (phase 5 unit FX, R126 carry-over 1).
     * Split screen, dark mode and a keyboard change reach here too, and send nothing unless the
     * orientation turned.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != orientation) {
            orientation = newConfig.orientation
            viewModel.send(ReaderEvent.OrientationChanged)
        }
    }

    // There is deliberately no onTrimMemory override. ReaderViewModel registers ReaderMemoryGuard
    // on the application context (contract 07 part 1b section 4.10), and the framework delivers a
    // trim to the activity and to every registered callback, so sending the event from here as well
    // would run the trim twice on the main thread.

    /**
     * Sent before `super.finish()` so the reducer's row can emit `FlushProgress` and `CommitHistory`
     * while the view model is still alive; both effects run `NonCancellable`, which is what keeps
     * the progress write alive past the activity.
     */
    override fun finish() {
        viewModel.send(ReaderEvent.Finishing)
        super.finish()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        viewerHolder.viewer?.handleKeyEvent(event) == true || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        viewerHolder.viewer?.handleGenericMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_N -> {
            viewModel.send(ReaderEvent.NextChapter)
            true
        }
        KeyEvent.KEYCODE_P -> {
            viewModel.send(ReaderEvent.PreviousChapter)
            true
        }
        else -> super.onKeyUp(keyCode, event)
    }
}
