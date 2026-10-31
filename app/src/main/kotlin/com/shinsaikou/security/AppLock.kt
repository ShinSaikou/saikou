package com.shinsaikou.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.content.res.use
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences.SecureScreenMode
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Upstream's app lock and secure screen (phase 4 unit AL), upstream's `SecureActivityDelegate` reaching
 * every activity from the application instead of from a base class.
 *
 * Two halves, as in upstream. The lock timer follows the process: [install] observes
 * `ProcessLifecycleOwner` and runs upstream's `onApplicationStart` and `onApplicationStopped` on its
 * start and stop, where upstream's `App` calls them, so moving between the app's own activities is
 * neither a stop nor a start. The window half follows each activity: activity lifecycle callbacks
 * set the secure flag from upstream's combine of Secure screen and incognito mode when an activity is
 * created, collected in that activity's `lifecycleScope`, and run upstream's unlock check when it
 * resumes. They reach every [ComponentActivity] of the process but [UnlockActivity]: `MainActivity`,
 * `:reader`'s `ReaderActivity`, the debug build's `GoogleRoundTripActivity` and the Compose test
 * manifest's `ComponentActivity`, and any activity added later with no call site of its own.
 *
 * Upstream's lock is a layer on top: the activity under the prompt draws first, and the recents
 * thumbnail shows whatever was on screen when the app left (the 2026-10-30 audit). So while a lock
 * is owed ([locked]) every activity is covered and its window secure, and while the lock is on the
 * recents thumbnail is never of the library: Android 13 and later are asked not to take one, and
 * older versions get the secure flag whenever the activity is not in front, since that is the only
 * means they have. An unlocked app in front stays exactly as Secure screen says.
 *
 * [requireUnlock], [locked] and `AuthenticatorUtil.isAuthenticating` are read and written on the
 * main thread only: the lifecycle callbacks and the prompt's callbacks all run there.
 */
@SingleIn(AppScope::class)
@Inject
class AppLock(
    private val securityPreferences: SecurityPreferences,
    private val basePreferences: BasePreferences,
) {

    /**
     * Set to true if we need the first activity to authenticate.
     *
     * Always require unlock if app is killed.
     */
    var requireUnlock = true
        private set

    private val lockedState = MutableStateFlow(false)

    /**
     * True from the moment the lock is owed, at a start or when the prompt is opened, until it is
     * passed. [requireUnlock] cannot stand in for it: it is also true for the whole of a process
     * that started with the lock off, so turning the lock on would cover the screen it was turned
     * on from.
     */
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    /** Main thread, once per process: `ProcessLifecycleOwner` takes observers there only. */
    fun install(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onApplicationStart()
                override fun onStop(owner: LifecycleOwner) = onApplicationStopped()
            },
        )
        application.registerActivityLifecycleCallbacks(SecureActivityCallbacks())
    }

    /** What `UnlockActivity` calls once the prompt succeeds. */
    fun unlock() {
        requireUnlock = false
        lockedState.value = false
    }

    internal fun onApplicationStopped() {
        appClosedStamp(
            useAuthenticator = securityPreferences.useAuthenticator.get(),
            lockAppAfter = securityPreferences.lockAppAfter.get(),
            now = System.currentTimeMillis(),
            requireUnlock = requireUnlock,
            isAuthenticating = AuthenticatorUtil.isAuthenticating,
        )?.let(securityPreferences.lastAppClosed::set)
    }

    /**
     * Checks if unlock is needed when app comes foreground.
     */
    internal fun onApplicationStart() {
        if (!securityPreferences.useAuthenticator.get()) return

        val lastClosedPref = securityPreferences.lastAppClosed
        requireUnlock = unlockRequiredAtStart(
            useAuthenticator = true,
            lockAppAfter = securityPreferences.lockAppAfter.get(),
            lastAppClosed = lastClosedPref.get(),
            now = System.currentTimeMillis(),
            requireUnlock = requireUnlock,
            isAuthenticating = AuthenticatorUtil.isAuthenticating,
        )
        // Here and not only when the prompt opens: this runs as the first activity starts, before
        // it resumes, so a window coming back from the background is covered before it draws.
        lockedState.value = requireUnlock

        lastClosedPref.delete()
    }

    /**
     * The window side of the lock, collected for as long as [activity] lives: the secure flag from
     * [windowSecure], the cover from [locked], and on Android 13 and later the recents thumbnail
     * from whether the lock is on.
     */
    private fun watchWindow(activity: ComponentActivity) {
        val secureScreenFlow = securityPreferences.secureScreen.changes()
        val incognitoModeFlow = basePreferences.incognitoMode.changes()
        val lockOnFlow = securityPreferences.useAuthenticator.changes()
        val inFrontFlow = activity.lifecycle.currentStateFlow
        combine(secureScreenFlow, incognitoModeFlow, locked, lockOnFlow, inFrontFlow) {
                secureScreen, incognitoMode, locked, lockOn, state ->
            windowSecure(
                secureScreen = secureScreen,
                incognitoMode = incognitoMode,
                locked = locked,
                lockOn = lockOn,
                inFront = state.isAtLeast(Lifecycle.State.RESUMED),
                sdkInt = Build.VERSION.SDK_INT,
            )
        }
            .onEach(activity.window::setSecureScreen)
            .launchIn(activity.lifecycleScope)
        locked.onEach(activity::setCovered).launchIn(activity.lifecycleScope)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lockOnFlow.onEach { activity.setRecentsScreenshotEnabled(!it) }.launchIn(activity.lifecycleScope)
        }
    }

    private fun setAppLock(activity: Activity) {
        if (!securityPreferences.useAuthenticator.get()) {
            lockedState.value = false
            return
        }
        if (activity.isAuthenticationSupported()) {
            if (!requireUnlock) return
            lockedState.value = true
            activity.startActivity(Intent(activity, UnlockActivity::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        } else {
            securityPreferences.useAuthenticator.set(false)
            lockedState.value = false
        }
    }

    /**
     * The two moments [watchWindow]'s collectors could be a frame late for apply directly as well:
     * the resume, after which the window's first frame is drawn, and the pause, after which the
     * recents thumbnail is taken.
     */
    private inner class SecureActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.isWatched()) watchWindow(activity as ComponentActivity)
        }

        override fun onActivityResumed(activity: Activity) {
            if (!activity.isWatched()) return
            setAppLock(activity)
            if (locked.value) {
                activity.window.setSecureScreen(true)
                activity.setCovered(true)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (!activity.isWatched()) return
            val lockOn = securityPreferences.useAuthenticator.get()
            if (lockOn && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity.window.setSecureScreen(true)
        }

        private fun Activity.isWatched() = this is ComponentActivity && this !is UnlockActivity

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {

        /**
         * Upstream's `onApplicationStart` decision: whether the next activity to resume asks for the
         * unlock, given the lock's settings, the stamp [appClosedStamp] left, the time [now] and
         * the current [requireUnlock].
         */
        internal fun unlockRequiredAtStart(
            useAuthenticator: Boolean,
            lockAppAfter: Int,
            lastAppClosed: Long,
            now: Long,
            requireUnlock: Boolean,
            isAuthenticating: Boolean,
        ): Boolean {
            if (!useAuthenticator) return requireUnlock
            // `requireUnlock` can be true on process start or if app was closed in locked state
            if (isAuthenticating || requireUnlock) return requireUnlock
            return when (lockAppAfter) {
                -1 -> false // Never
                0 -> true // Always
                else -> lastAppClosed + lockAppAfter * 60_000 <= now
            }
        }

        /**
         * Upstream's `onApplicationStopped` decision: the close time to store, or null to store
         * nothing.
         */
        internal fun appClosedStamp(
            useAuthenticator: Boolean,
            lockAppAfter: Int,
            now: Long,
            requireUnlock: Boolean,
            isAuthenticating: Boolean,
        ): Long? {
            if (!useAuthenticator) return null
            if (isAuthenticating) return null
            // Return if app is closed in locked state
            if (requireUnlock) return null
            // Save app close time if lock is delayed
            return if (lockAppAfter > 0) now else null
        }

        /** Upstream's secure screen combine: always, or while incognito mode is on. */
        internal fun secureScreenOn(secureScreen: SecureScreenMode, incognitoMode: Boolean): Boolean =
            secureScreen == SecureScreenMode.ALWAYS ||
                (secureScreen == SecureScreenMode.INCOGNITO && incognitoMode)

        /**
         * Whether an activity's window is secure: whenever Secure screen says so, while a lock is
         * owed, and below Android 13 while the lock is on and the activity is not in front, which
         * is when the recents thumbnail is taken. Android 13 and later are asked to take no
         * thumbnail instead, so there a lock that is on and passed changes nothing.
         */
        internal fun windowSecure(
            secureScreen: SecureScreenMode,
            incognitoMode: Boolean,
            locked: Boolean,
            lockOn: Boolean,
            inFront: Boolean,
            sdkInt: Int,
        ): Boolean = secureScreenOn(secureScreen, incognitoMode) ||
            locked ||
            (lockOn && !inFront && sdkInt < Build.VERSION_CODES.TIRAMISU)
    }
}

/** The tag of the view [setCovered] lays over a window; the device test looks for it. */
internal const val APP_LOCK_COVER_TAG = "com.shinsaikou.security.AppLock.cover"

/**
 * Lays an opaque view of the theme's background over the whole window, or takes it away. It goes on
 * the decor view, since `setContentView` empties the content view, after the content, so it is drawn
 * above it (the decor may add the bars' background views later, which are only the bars' colours);
 * it takes every touch, so nothing under it can be pressed. A window not set up yet has nothing to
 * cover: the resume covers it.
 */
private fun Activity.setCovered(covered: Boolean) {
    val decor = window.peekDecorView() as? ViewGroup ?: return
    val cover = decor.findViewWithTag<View>(APP_LOCK_COVER_TAG)
    if (covered && cover == null) {
        val background = obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use {
            it.getColor(0, Color.BLACK)
        }
        val view = View(this).apply {
            tag = APP_LOCK_COVER_TAG
            setBackgroundColor(background)
            isClickable = true
        }
        decor.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    } else if (!covered && cover != null) {
        decor.removeView(cover)
    }
}
