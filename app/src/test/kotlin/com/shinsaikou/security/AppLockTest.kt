package com.shinsaikou.security

import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.core.common.preferences.SecurityPreferences.SecureScreenMode
import com.shinsaikou.security.AppLock.Companion.appClosedStamp
import com.shinsaikou.security.AppLock.Companion.secureScreenOn
import com.shinsaikou.security.AppLock.Companion.unlockRequiredAtStart
import com.shinsaikou.security.AppLock.Companion.windowSecure
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Upstream's app lock decisions (phase 4 unit AL), as `SecureActivityDelegate` makes them: at the
 * process's start, whether the next activity asks for the unlock, and at its stop, whether the
 * close time is stored. `AppLock` runs these two on the process lifecycle; what the device does with
 * the answer is the device checks'. Then Saikou's own: when the content is hidden ([AppLock.locked])
 * and when a window is secure ([windowSecure]).
 */
class AppLockTest {

    private val closedAt = 1_000_000L

    private fun atStart(
        lockAppAfter: Int,
        now: Long = closedAt,
        useAuthenticator: Boolean = true,
        requireUnlock: Boolean = false,
        isAuthenticating: Boolean = false,
    ) = unlockRequiredAtStart(
        useAuthenticator = useAuthenticator,
        lockAppAfter = lockAppAfter,
        lastAppClosed = closedAt,
        now = now,
        requireUnlock = requireUnlock,
        isAuthenticating = isAuthenticating,
    )

    @Test
    fun `lock when idle Never never asks at a start`() {
        atStart(lockAppAfter = -1, now = closedAt + 60 * 60_000) shouldBe false
    }

    @Test
    fun `lock when idle Always asks at every start, however short the stop`() {
        atStart(lockAppAfter = 0, now = closedAt) shouldBe true
    }

    @Test
    fun `a delay asks once the stop has lasted that many minutes, and not a millisecond before`() {
        atStart(lockAppAfter = 2, now = closedAt + 2 * 60_000 - 1) shouldBe false
        atStart(lockAppAfter = 2, now = closedAt + 2 * 60_000) shouldBe true
        atStart(lockAppAfter = 10, now = closedAt + 5 * 60_000) shouldBe false
    }

    @Test
    fun `a lock already owed stays owed at a start, whatever the delay says`() {
        // Process start, or an app closed while locked: upstream leaves the flag as it is.
        atStart(lockAppAfter = -1, requireUnlock = true) shouldBe true
        atStart(lockAppAfter = 10, requireUnlock = true) shouldBe true
    }

    @Test
    fun `a start while the prompt is up leaves the flag as it is`() {
        atStart(lockAppAfter = 0, isAuthenticating = true) shouldBe false
        atStart(lockAppAfter = -1, requireUnlock = true, isAuthenticating = true) shouldBe true
    }

    @Test
    fun `with the lock off a start changes nothing`() {
        atStart(lockAppAfter = 0, useAuthenticator = false) shouldBe false
        atStart(lockAppAfter = -1, useAuthenticator = false, requireUnlock = true) shouldBe true
    }

    @Test
    fun `a stop stores the close time only while a delay is set`() {
        appClosedStamp(useAuthenticator = true, lockAppAfter = 5, now = closedAt, requireUnlock = false, isAuthenticating = false) shouldBe closedAt
        appClosedStamp(useAuthenticator = true, lockAppAfter = 0, now = closedAt, requireUnlock = false, isAuthenticating = false).shouldBeNull()
        appClosedStamp(useAuthenticator = true, lockAppAfter = -1, now = closedAt, requireUnlock = false, isAuthenticating = false).shouldBeNull()
    }

    @Test
    fun `an app closed while locked stores no close time, so the lock it owes is not timed away`() {
        appClosedStamp(useAuthenticator = true, lockAppAfter = 5, now = closedAt, requireUnlock = true, isAuthenticating = false).shouldBeNull()
    }

    @Test
    fun `a stop while the prompt is up, or with the lock off, stores nothing`() {
        appClosedStamp(useAuthenticator = true, lockAppAfter = 5, now = closedAt, requireUnlock = false, isAuthenticating = true).shouldBeNull()
        appClosedStamp(useAuthenticator = false, lockAppAfter = 5, now = closedAt, requireUnlock = false, isAuthenticating = false).shouldBeNull()
    }

    @Test
    fun `secure screen is on always under Always, only in incognito under Incognito, and never under Never`() {
        secureScreenOn(SecureScreenMode.ALWAYS, incognitoMode = false) shouldBe true
        secureScreenOn(SecureScreenMode.ALWAYS, incognitoMode = true) shouldBe true
        secureScreenOn(SecureScreenMode.INCOGNITO, incognitoMode = false) shouldBe false
        secureScreenOn(SecureScreenMode.INCOGNITO, incognitoMode = true) shouldBe true
        secureScreenOn(SecureScreenMode.NEVER, incognitoMode = false) shouldBe false
        secureScreenOn(SecureScreenMode.NEVER, incognitoMode = true) shouldBe false
    }

    @Test
    fun `an unlocked window in front is secure exactly as Secure screen says, lock on or off`() {
        listOf(26, 32, 33, 36).forEach { sdk ->
            listOf(true, false).forEach { lockOn ->
                SecureScreenMode.entries.forEach { mode ->
                    listOf(true, false).forEach { incognito ->
                        windowSecure(mode, incognito, locked = false, lockOn = lockOn, inFront = true, sdkInt = sdk) shouldBe
                            secureScreenOn(mode, incognito)
                    }
                }
            }
        }
    }

    @Test
    fun `a window is secure while a lock is owed, even under Never`() {
        windowSecure(SecureScreenMode.NEVER, false, locked = true, lockOn = true, inFront = true, sdkInt = 26) shouldBe true
        windowSecure(SecureScreenMode.NEVER, false, locked = true, lockOn = true, inFront = true, sdkInt = 36) shouldBe true
    }

    @Test
    fun `below Android 13 a window out of front is secure while the lock is on, for the recents thumbnail`() {
        windowSecure(SecureScreenMode.NEVER, false, locked = false, lockOn = true, inFront = false, sdkInt = 26) shouldBe true
        windowSecure(SecureScreenMode.NEVER, false, locked = false, lockOn = true, inFront = false, sdkInt = 32) shouldBe true
        // Android 13 and later leave the thumbnail out instead, and the lock off changes nothing.
        windowSecure(SecureScreenMode.NEVER, false, locked = false, lockOn = true, inFront = false, sdkInt = 33) shouldBe false
        windowSecure(SecureScreenMode.NEVER, false, locked = false, lockOn = false, inFront = false, sdkInt = 26) shouldBe false
    }

    @Test
    fun `a start that owes the lock hides the content until the prompt is passed`() {
        val lock = appLock(lockOn = true, lockAppAfter = 0)
        lock.locked.value shouldBe false

        lock.onApplicationStart()
        lock.locked.value shouldBe true

        lock.unlock()
        lock.locked.value shouldBe false

        // Lock when idle Always: the next start owes it again.
        lock.onApplicationStopped()
        lock.onApplicationStart()
        lock.locked.value shouldBe true
    }

    @Test
    fun `a start inside the idle delay hides nothing`() {
        val lock = appLock(lockOn = true, lockAppAfter = 5)
        lock.onApplicationStart()
        lock.unlock()

        lock.onApplicationStopped()
        lock.onApplicationStart()

        lock.locked.value shouldBe false
    }

    @Test
    fun `with the lock off a start hides nothing, and turning it on hides nothing until the next start`() {
        val security = SecurityPreferences(InMemoryPreferenceStore())
        val lock = AppLock(security, BasePreferences(InMemoryPreferenceStore()))

        // The process still owes upstream's unlock from its start, which the lock being off ignores.
        lock.onApplicationStart()
        lock.requireUnlock shouldBe true
        lock.locked.value shouldBe false

        security.useAuthenticator.set(true)
        lock.locked.value shouldBe false
        lock.onApplicationStart()
        lock.locked.value shouldBe true
    }

    private fun appLock(lockOn: Boolean, lockAppAfter: Int): AppLock {
        val security = SecurityPreferences(InMemoryPreferenceStore()).apply {
            useAuthenticator.set(lockOn)
            this.lockAppAfter.set(lockAppAfter)
        }
        return AppLock(security, BasePreferences(InMemoryPreferenceStore()))
    }
}
