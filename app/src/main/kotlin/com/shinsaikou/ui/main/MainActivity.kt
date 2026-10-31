package com.shinsaikou.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.SaikouApp
import com.shinsaikou.ui.crash.CrashLogExport
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import java.io.File

/** Owns Android intents and hands all in-app navigation to the Compose host. */
class MainActivity : AppCompatActivity() {

    /**
     * What the intent that started or resumed this activity asked for, held as one value so a
     * destination and the payload it needs can never be consumed a frame apart.
     */
    private var pendingTarget by mutableStateOf<MainIntents.Target?>(null)

    /**
     * The result is deliberately ignored. A denial is a legitimate answer, the two notifiers
     * already check [android.app.NotificationManager.areNotificationsEnabled] before every post,
     * and the workers show their progress through `ForegroundInfo`, which needs no permission.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, as in the reader. Android 15 and later draw every app edge to edge
        // whatever it asks, and every screen is laid out for that; without this call Android 8 to 14
        // kept the window's own bars, Material's purple status bar and a black navigation bar.
        // SaikouTheme's pure black bars build on it.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as SaikouApp).graph
        if (savedInstanceState == null) {
            graph.basePreferences.incognitoMode.set(false)
            // Upstream's launch-time clear, from the same block of its own `MainActivity` (ruling
            // R56). A fresh launch is the one moment no reader session can be writing to the
            // cache, and it runs whatever ended the previous process. `clear()` moves itself to IO.
            if (graph.libraryPreferences.autoClearChapterCache.get()) {
                graph.appScope.launch { graph.chapterCache.clear() }
            }
            askForNotificationsOnce()
            // Inside the guard with them: a re-creation (rotation, day or night, font scale,
            // locale) hands back the intent this activity was launched with, and acting on it a
            // second time navigates away from wherever the owner had got to, stacks a duplicate
            // entry, and on a restore intent starts the import again. An intent that genuinely
            // arrives later still reaches the host through onNewIntent.
            handleIntent(intent)
        }
        setContent {
            // Provided once, above the NavHost, so every screen resolves its view model against
            // its own back stack entry with `metroViewModel()` and no screen names a factory.
            CompositionLocalProvider(LocalMetroViewModelFactory provides graph.viewModelFactory) {
                val mainViewModel: MainViewModel = metroViewModel()
                val state by mainViewModel.state.collectAsStateWithLifecycle()
                SaikouAppNav(
                    mainState = state,
                    onToggleIncognito = mainViewModel::toggleIncognito,
                    onToggleDownloadedOnly = mainViewModel::toggleDownloadedOnly,
                    pendingTarget = pendingTarget,
                    onTargetConsumed = { pendingTarget = null },
                    onBatteryAllow = { mainViewModel.requestBatteryExemption(this) },
                    onBatterySkip = mainViewModel::skipBatteryExemption,
                    // The banner goes whether or not a chooser appeared: a device with nothing to
                    // send a text file with is not a reason to keep a banner nobody can dismiss,
                    // and the log is still on the crash log screen.
                    onCrashExport = { path ->
                        CrashLogExport.share(this, File(path))
                        mainViewModel.dismissCrashBanner()
                    },
                    onCrashDismissed = mainViewModel::dismissCrashBanner,
                )
            }
        }
    }

    /**
     * `setIntent` is what makes `getIntent()` return the new one. Without it the activity keeps
     * answering with the intent it was launched with, so anything reading it later (a restart, a
     * re-created activity) would replay the stale destination.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The resolver goes with the intent because a backup picked in the system file browser arrives
     * as a document URI that carries no file name, and the provider is the only one that can say
     * what the file is called.
     */
    private fun handleIntent(intent: Intent) {
        pendingTarget = MainIntents.targetOf(intent, contentResolver)
    }

    /**
     * `POST_NOTIFICATIONS` is a runtime permission from API 33 and starts denied, so without this
     * the library update and backup notifications never appear on a fresh install. Guarded by
     * `savedInstanceState == null` so a rotation does not re-ask; the platform itself stops showing
     * the dialog once the owner has refused, so no "already asked" flag of our own is needed.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
