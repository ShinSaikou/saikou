package com.shinsaikou.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.sync.repository.SyncStoreSnapshot
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.SyncOutcome
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.SyncRunRecord
import com.shinsaikou.sync.SyncRunReport
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.sync.SyncSession
import com.shinsaikou.sync.SyncWorkPreferences
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveAvailability
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.sync.webdav.WebDavSignIn
import com.shinsaikou.ui.main.BatteryExemption
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sync settings (phase 3 unit G): the choice of service and its sign-in while no backend is
 * configured, Google Drive's through the browser (unit H) and WebDAV's form; the account, the last
 * run, Sync now, the interval, the safety backup and sign-out while one is; Sign in again while one
 * is but its sign-in is refused or gone ([State.signInAgain]); and the run reports either way. Its
 * own view model, so [SettingsViewModel] takes on none of sync's classes.
 *
 * The password is a secret. It lives only in this view model's in-memory [State], never in saved
 * state, a preference or a log line, and is dropped as soon as a sign-in finishes, whatever the
 * outcome; [Form.toString] leaves it out. Changing the server is a sign-out followed by a sign-in:
 * the form is offered while a backend is configured only in Sign in again, where the stored sign-in
 * no longer works (ruling draft item 1).
 *
 * Google's sign-in holds nothing in [State] at all: the PKCE verifier, the listener's state and the
 * code live in [signInWithGoogle]'s coroutine only, and the tokens only in [DriveAccount] and the
 * secret store. Only the account's email address and when its sign-in expires are shown.
 *
 * While a backend is configured the section also offers the library fingerprint (phase 3 unit SP,
 * R92 item 6): [SyncDigest] over one [snapshot] of the library, computed on a tap ([computeFingerprint]).
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(SyncSettingsViewModel::class)
@Inject
class SyncSettingsViewModel(
    private val syncPreferences: SyncPreferences,
    private val webDavAccount: WebDavAccount,
    private val session: SyncSession,
    private val scheduler: SyncScheduler,
    private val workPreferences: SyncWorkPreferences,
    private val record: SyncRunRecord,
    private val workManager: WorkManager,
    private val dispatchers: Dispatchers,
    private val batteryExemption: BatteryExemption,
    private val driveAccount: DriveAccount,
    private val driveAvailability: DriveAvailability,
    private val browserSignIn: BrowserSignIn,
    private val snapshot: SyncStoreSnapshot,
) : StateViewModel<SyncSettingsViewModel.State>(State()) {

    /** The services the "Sync service" row offers, in its order (Q2: Google Drive before WebDAV). */
    enum class Service {
        NONE,

        /** Offered only while [DriveAvailability.offered] ([State.driveOffered]). */
        GOOGLE_DRIVE,
        WEBDAV,
    }

    data class State(
        /** The configured backend's id, or empty while none is: the form shows only then. */
        val backendId: String = "",
        /**
         * The service the owner picked while none is configured: WebDAV opens the sign-in form,
         * Google Drive the button that opens Google's page. None is the default.
         */
        val chosen: Service = Service.NONE,
        /** Whether this build offers Google Drive now ([DriveAvailability.offered]), read on open. */
        val driveOffered: Boolean = false,
        val form: Form = Form(),
        val signingIn: Boolean = false,
        /** Why the last sign-in did not sign in; cleared by the next attempt. */
        val signInProblem: SignInProblem? = null,
        /** The stored address and user name while signed in, or null when they cannot be read. */
        val account: WebDavSignIn? = null,
        /** The stored Google account's email address while signed in to Drive, or null when unreadable. */
        val googleAccount: String? = null,
        /** When Google said the stored Drive sign-in lapses, in epoch milliseconds, or null when it did not. */
        val googleExpiresAt: Long? = null,
        val lastAttemptAt: Long = 0L,
        val lastSuccessAt: Long = 0L,
        val outcome: SyncOutcome? = null,
        /** Whether a run of either unique name is RUNNING right now, from WorkManager (R39). */
        val running: Boolean = false,
        val intervalHours: Int = SyncWorkPreferences.DEFAULT_INTERVAL_HOURS,
        val wifiOnly: Boolean = false,
        /** The safety backup's file name, or empty when none was written for this backend. */
        val safetyBackupFile: String = "",
        val signingOut: Boolean = false,
        /** The run reports, newest first, as the section lists them. */
        val reports: List<ReportRows> = emptyList(),
        /**
         * Whether Android lets Saikou ignore battery optimisation. True until read, so the row that
         * asks for it never flashes on an exempt device.
         */
        val batteryExempt: Boolean = true,
        /** The library fingerprint row: what its last tap computed, or why it computed nothing. */
        val fingerprint: Fingerprint = Fingerprint.Idle,
    ) {
        val configured: Boolean get() = backendId.isNotEmpty()

        /** The configured backend's service, by its id's prefix, or [Service.NONE] while none is. */
        val configuredService: Service
            get() = when {
                !configured -> Service.NONE
                backendId.startsWith(DriveSyncBackend.ID_PREFIX) -> Service.GOOGLE_DRIVE
                else -> Service.WEBDAV
            }

        /** A newer format stopped the last pull: the section says so above everything else. */
        val newerFormat: Boolean
            get() = (outcome as? SyncOutcome.Failure)?.key == ErrorKey.SYNC_NEWER_FORMAT

        /**
         * Why a configured backend needs the owner to sign in again, or null when it does not: this
         * device no longer has the stored sign-in, or the last run ended signed out.
         */
        val signInAgain: SignInAgain?
            get() = when {
                !configured -> null
                (if (configuredService == Service.GOOGLE_DRIVE) googleAccount else account) == null -> SignInAgain.SIGN_IN_LOST
                (outcome as? SyncOutcome.Failure)?.key == ErrorKey.SYNC_SIGNED_OUT -> SignInAgain.PASSWORD_REFUSED
                else -> null
            }

        /**
         * What a sign-in sends: in Sign in again with the account known, its stored address and user
         * name with the form's password, so the backend id and this device's place stay the same;
         * otherwise the form as typed.
         */
        val signInForm: Form
            get() = account?.takeIf { signInAgain == SignInAgain.PASSWORD_REFUSED }
                ?.let { form.copy(url = it.url, user = it.user) }
                ?: form

        /**
         * Whether "Sign in with Google" may start now: this build offers Drive, no sign-in is in
         * flight, and either no backend is configured and Google Drive is chosen, or Drive is the
         * configured backend and its sign-in is refused or gone, or will lapse ([googleExpiresAt]),
         * which is when the section offers signing in again.
         */
        val canSignInWithGoogle: Boolean
            get() = driveOffered && !signingIn && when (configuredService) {
                Service.NONE -> chosen == Service.GOOGLE_DRIVE
                Service.GOOGLE_DRIVE -> signInAgain != null || googleExpiresAt != null
                Service.WEBDAV -> false
            }
    }

    /**
     * The library fingerprint row (phase 3 unit SP): [SyncDigest] over this device's library, whose
     * value two devices that have both finished syncing share. Never one of a half-applied sync.
     */
    sealed interface Fingerprint {
        /** Not computed yet on this screen. */
        data object Idle : Fingerprint

        /** Computing now; a tap does nothing. */
        data object Computing : Fingerprint

        /**
         * The digest's first 16 hex characters in four groups of four ([fingerprintGroups]), and how
         * many synced rows went into it ([SyncDigest.Result.rows]).
         */
        data class Computed(val groups: String, val rows: Int) : Fingerprint

        /** The tap came while a sync was running, so nothing was computed. */
        data object SyncRunning : Fingerprint

        /** A sync started while it computed, so its result was thrown away. */
        data object SyncRanMeanwhile : Fingerprint

        /** Reading the library threw, or ran out of memory; the row names it through `ErrorText` (R67). */
        data class Failed(val error: Throwable) : Fingerprint
    }

    /** Why the section shows Sign in again. */
    enum class SignInAgain {
        /**
         * The server refused the saved password, or Google the saved sign-in; the address and user
         * name, or the Google account, are still known.
         */
        PASSWORD_REFUSED,

        /** This device no longer has the saved sign-in, so the whole form is asked for. */
        SIGN_IN_LOST,
    }

    /**
     * The sign-in form. The address and user name may be kept; the password never outlives a
     * sign-in. [canSignIn] is exactly what `WebDavAccount.signIn` requires, so its checks never
     * throw from here.
     */
    data class Form(val url: String = "", val user: String = "", val password: String = "") {
        val canSignIn: Boolean get() = canSignIn(url, user, password)

        override fun toString(): String = "Form(url=$url, user=$user, password=${if (password.isEmpty()) "empty" else "set"})"
    }

    sealed interface SignInProblem {
        /** The server was not reached. [detail] is the backend's message, secondary to the text. */
        data class Unreachable(val detail: String?) : SignInProblem

        /** The server answered and refused the user name or password. */
        data class NotAuthenticated(val detail: String?) : SignInProblem

        /** The sign-in threw; the screen names it through `ErrorText` (R67). */
        data class Failed(val error: Throwable) : SignInProblem
    }

    /**
     * One run report as the section shows it: the counts, the routine merges, whether this device
     * exported, and the true conflicts kept, with [more] the ones the report counted but did not keep.
     */
    data class ReportRows(
        val at: Long,
        val before: SyncRunReport.Counts,
        val after: SyncRunReport.Counts,
        val routineMerges: Int,
        val exported: Boolean,
        val conflictCount: Int,
        val conflicts: List<SyncRunReport.Conflict>,
        val more: Int,
    )

    /** What the section shows of the configured backend's stored sign-in, whichever service it is. */
    private class Accounts(val webDav: WebDavSignIn?, val google: String?, val googleExpiresAt: Long?)

    /** The fingerprint computing now, if one is: a sync that starts stops it. */
    private var fingerprintJob: Job? = null

    init {
        mutableState.update { it.copy(driveOffered = driveAvailability.offered) }
        viewModelScope.launch {
            // A run that ends signed out may be one whose secrets were just found unreadable, so the
            // account is read again with each new outcome as well as each new backend.
            combine(syncPreferences.backendId.withCurrent(), record.lastOutcome.withCurrent()) { id, _ -> id }
                .map { id -> id to readAccounts(id) }
                .collect { (id, accounts) -> mutableState.update { it.withAccounts(accounts).copy(backendId = id) } }
        }
        viewModelScope.launch {
            // Any one of the record's keys changing re-reads all of it, so the four never disagree.
            merge(
                record.lastAttemptAt.withCurrent(),
                record.lastSuccessAt.withCurrent(),
                record.lastOutcome.withCurrent(),
                record.lastOutcomeDetail.withCurrent(),
                record.reports.withCurrent(),
            ).collect {
                val rows = record.reports.get().map(::reportRows)
                mutableState.update { state ->
                    state.copy(
                        lastAttemptAt = record.lastAttemptAt.get(),
                        lastSuccessAt = record.lastSuccessAt.get(),
                        outcome = record.outcome(),
                        reports = rows,
                    )
                }
            }
        }
        viewModelScope.launch {
            workPreferences.intervalHours.withCurrent()
                .collect { hours -> mutableState.update { it.copy(intervalHours = hours) } }
        }
        viewModelScope.launch {
            workPreferences.wifiOnly.withCurrent()
                .collect { wifiOnly -> mutableState.update { it.copy(wifiOnly = wifiOnly) } }
        }
        viewModelScope.launch {
            workPreferences.safetyBackupFile.withCurrent()
                .collect { file -> mutableState.update { it.copy(safetyBackupFile = file) } }
        }
        viewModelScope.launch {
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_ONCE),
                workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_PERIODIC),
            ) { once, periodic -> (once + periodic).any { it.state == WorkInfo.State.RUNNING } }
                .distinctUntilChanged()
                .collect { running ->
                    // A fingerprint read across a sync's writes is of no state either device holds.
                    val discard = running && state.value.fingerprint == Fingerprint.Computing
                    if (discard) fingerprintJob?.cancel()
                    mutableState.update {
                        it.copy(running = running, fingerprint = if (discard) Fingerprint.SyncRanMeanwhile else it.fingerprint)
                    }
                }
        }
    }

    /**
     * Reads the exemption again. The section calls it on each resume, since the owner answers the
     * request in a system screen that reports nothing back.
     */
    fun refreshBatteryExemption() {
        viewModelScope.launch {
            val exempt = withContext(dispatchers.io) { batteryExemption.isExempt() }
            mutableState.update { it.copy(batteryExempt = exempt) }
        }
    }

    /**
     * The same system request the first launch makes (`BatteryExemption.request`). Without the
     * exemption a sync the system starts cold cannot run in the foreground on recent Android
     * versions (report section 12 item 3).
     */
    fun requestBatteryExemption(activity: Activity) = batteryExemption.request(activity)

    /**
     * Picks a service while none is configured. WebDAV opens the form, Google Drive its sign-in
     * button (only while this build offers it); leaving WebDAV drops whatever the form held, and any
     * change drops the last attempt's problem.
     */
    fun choose(service: Service) {
        mutableState.update {
            when {
                service == it.chosen -> it
                service == Service.GOOGLE_DRIVE && !it.driveOffered -> it
                service == Service.WEBDAV -> it.copy(chosen = service, signInProblem = null)
                else -> it.copy(chosen = service, form = Form(), signInProblem = null)
            }
        }
    }

    fun setUrl(url: String) = mutableState.update { it.copy(form = it.form.copy(url = url)) }

    fun setUser(user: String) = mutableState.update { it.copy(form = it.form.copy(user = user)) }

    fun setPassword(password: String) = mutableState.update { it.copy(form = it.form.copy(password = password)) }

    /**
     * Signs in with [State.signInForm]. Reachable and authenticated: the backend becomes the
     * configured one through [SyncSession.signedIn], which arms the schedule and queues the first
     * run, and the form clears. Otherwise nothing changes and [State.signInProblem] says why. The
     * password is dropped from the state in every case once the attempt ends.
     *
     * Offered while no backend is configured, and in Sign in again. There a failed attempt deletes
     * nothing: the cleanup below runs only while no backend is configured, and the probe stores
     * nothing it did not accept.
     */
    fun signIn() {
        val current = state.value
        if (current.signingIn || (current.configured && current.signInAgain == null)) return
        if (current.configured && current.configuredService != Service.WEBDAV) return
        val form = current.signInForm
        if (!form.canSignIn) return
        mutableState.update { it.copy(signingIn = true, signInProblem = null) }
        viewModelScope.launch {
            val problem = try {
                val status = webDavAccount.signIn(form.url.trim(), form.user, form.password)
                when {
                    !status.reachable -> SignInProblem.Unreachable(status.message)
                    !status.authenticated -> SignInProblem.NotAuthenticated(status.message)
                    else -> {
                        val backend = checkNotNull(webDavAccount.backend()) { "The sign-in stored secrets that do not read back" }
                        session.signedIn(backend)
                        null
                    }
                }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(signingIn = false, form = it.form.copy(password = "")) }
                throw e
            } catch (e: Exception) {
                // A failure after the secrets were stored (they did not read back, or the session
                // could not take the backend) must not leave them behind with no backend configured.
                // In Sign in again a backend is configured, so this never deletes its secrets.
                if (syncPreferences.backendId.get().isEmpty()) {
                    withContext(dispatchers.io) { runCatching { webDavAccount.signOut() } }
                }
                SignInProblem.Failed(e)
            }
            // The same backend signed in again leaves the backend id as it was, so the collector
            // above may not read the stored account again; read it here, with the id.
            val backendId = syncPreferences.backendId.get()
            val accounts = readAccounts(backendId)
            mutableState.update {
                it.withAccounts(accounts).copy(
                    backendId = backendId,
                    signingIn = false,
                    signInProblem = problem,
                    form = if (problem == null) Form() else it.form.copy(password = ""),
                    chosen = if (problem == null) Service.NONE else Service.WEBDAV,
                )
            }
        }
    }

    /**
     * Signs in to Google Drive (unit H, G's ruling draft items 9 and 12): O's [BrowserSignIn] opens
     * Google's page from the visible activity and waits on the loopback listener, [DriveAccount]
     * exchanges the code and stores the grant, and [SyncSession.signedIn] configures the backend.
     * [returnIntent] is what the sign-in's notifications open to bring the owner back.
     *
     * Offered while [State.canSignInWithGoogle]. A [com.shinsaikou.signin.SignInCancelledException] (a later
     * sign-in started) shows nothing; every other failure is [SignInProblem.Failed] for `ErrorText`.
     * A failure or a cancel after the grant was stored deletes it (revoking it, best effort) while no
     * backend is configured; in Sign in again one is, so the stored grant stays unless this sign-in
     * replaced it. The same Google account gives the same backend id, so the session keeps this
     * device's place; another account is a new backend.
     */
    fun signInWithGoogle(returnIntent: Intent) {
        if (!state.value.canSignInWithGoogle) return
        mutableState.update { it.copy(signingIn = true, signInProblem = null) }
        viewModelScope.launch {
            var stored = false
            val problem = try {
                val pkce = Pkce.generate()
                val result = browserSignIn.signIn(port = 0, returnIntent = returnIntent) { listener ->
                    driveAccount.authorizationUrl(listener.redirectUri, listener.state, pkce)
                }
                driveAccount.signIn(result.callback.code, pkce, result.redirectUri)
                stored = true
                val backend = checkNotNull(driveAccount.backend()) { "The Google sign-in stored secrets that do not read back" }
                session.signedIn(backend)
                null
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (stored) forgetDriveUnlessConfigured()
                    mutableState.update { it.copy(signingIn = false) }
                }
                throw e
            } catch (e: Exception) {
                if (stored) forgetDriveUnlessConfigured()
                SignInProblem.Failed(e)
            }
            val backendId = syncPreferences.backendId.get()
            val accounts = readAccounts(backendId)
            mutableState.update {
                it.withAccounts(accounts).copy(
                    backendId = backendId,
                    signingIn = false,
                    signInProblem = problem,
                    chosen = if (problem == null) Service.NONE else Service.GOOGLE_DRIVE,
                )
            }
        }
    }

    /** What [signIn] does after a failure, for Drive: the stored grant goes while no backend is configured. */
    private suspend fun forgetDriveUnlessConfigured() {
        if (syncPreferences.backendId.get().isEmpty()) {
            withContext(dispatchers.io) { runCatching { driveAccount.signOut() } }
        }
    }

    /** False, enqueuing nothing, when no backend is configured; the section offers it only when one is. */
    fun syncNow() {
        viewModelScope.launch { scheduler.syncNow() }
    }

    /**
     * Writing the interval is only half of it: the periodic run is armed from the stored value, so
     * it is re-armed after the write, in the same coroutine because `setup` reads the value back.
     */
    fun setInterval(hours: Int) {
        viewModelScope.launch(dispatchers.io) {
            workPreferences.intervalHours.set(hours)
            scheduler.setup()
        }
    }

    /**
     * As [setInterval]: the periodic run is re-armed with the network the switch now asks for, and
     * so is an automatic run waiting to start.
     */
    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            workPreferences.wifiOnly.set(enabled)
            scheduler.setup()
            scheduler.rearm()
        }
    }

    /**
     * Computes the library fingerprint: [SyncDigest.of] over one [snapshot] off the main thread, in
     * this view model's scope, so leaving the screen cancels it. It writes nothing. A tap while one
     * computes does nothing, and a tap while a sync runs computes nothing and says so: a run
     * WorkManager reports ([State.running]), or one that holds the session's lock before WorkManager
     * has reported it ([SyncSession.runs] odd).
     *
     * The digest is read in one read transaction ([SyncStoreSnapshot.read]), so a write any other
     * writer commits meanwhile (a restore's chunk, a library update's chapters, a sync's apply) is
     * in none of its pages: the result is of one state the library held (verifier finding 1). The
     * read holds no write lock, so those writers do not wait for it (phase 5 unit SC6).
     *
     * A failure to read, an [OutOfMemoryError] included, is shown as [Fingerprint.Failed].
     *
     * A sync that starts while it computes throws the result away ([Fingerprint.SyncRanMeanwhile]):
     * the running collector cancels it as soon as WorkManager reports the run, and a run that took the
     * session's lock meanwhile moved [SyncSession.runs], which is checked once the digest is in, for a
     * run that started and ended between two of WorkManager's reports.
     */
    fun computeFingerprint() {
        val current = state.value
        if (current.fingerprint == Fingerprint.Computing) return
        val runsBefore = session.runs
        if (current.running || runsBefore % 2 != 0L) {
            mutableState.update { it.copy(fingerprint = Fingerprint.SyncRunning) }
            return
        }
        mutableState.update { it.copy(fingerprint = Fingerprint.Computing) }
        fingerprintJob = viewModelScope.launch {
            val fingerprint = try {
                val digest = withContext(dispatchers.io) { snapshot.read { SyncDigest.of(it) } }
                if (session.runs != runsBefore) {
                    Fingerprint.SyncRanMeanwhile
                } else {
                    Fingerprint.Computed(groups = fingerprintGroups(digest.sha256), rows = digest.rows)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Fingerprint.Failed(e)
            } catch (e: OutOfMemoryError) {
                // Shown as a failure rather than left uncaught, which left the row on Computing until
                // the process was killed (phase 4 unit DC2's finding, phase 5 unit SC1).
                Fingerprint.Failed(e)
            }
            // Only the computation still shown takes its result; a sync may have discarded it.
            mutableState.update { if (it.fingerprint == Fingerprint.Computing) it.copy(fingerprint = fingerprint) else it }
        }
    }

    /**
     * [SyncSession.signOut]: both schedules cancelled, a run in progress waited for, the secrets and
     * the backend's state forgotten. The run record stays, so the last outcome is still shown.
     */
    fun signOut() {
        if (state.value.signingOut) return
        mutableState.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            try {
                session.signOut()
            } finally {
                mutableState.update { it.copy(signingOut = false) }
            }
        }
    }

    private suspend fun readAccounts(backendId: String): Accounts = when {
        backendId.isEmpty() -> Accounts(null, null, null)
        backendId.startsWith(DriveSyncBackend.ID_PREFIX) ->
            Accounts(null, driveAccount.signedInAs(), driveAccount.refreshTokenExpiresAt())
        else -> Accounts(webDavAccount.signedInAs(), null, null)
    }

    private fun State.withAccounts(accounts: Accounts): State =
        copy(account = accounts.webDav, googleAccount = accounts.google, googleExpiresAt = accounts.googleExpiresAt)

    companion object {

        /**
         * What `WebDavAccount.signIn` requires, checked before it is called: an address, a user name
         * that is not blank and has no colon (Basic authentication splits on the first), and a
         * password that is not empty (the secret store reads an empty value as absent).
         */
        fun canSignIn(url: String, user: String, password: String): Boolean =
            url.isNotBlank() && user.isNotBlank() && ':' !in user && password.isNotEmpty()

        /** The first 16 hex characters of [sha256] in four groups of four, as the row shows them. */
        fun fingerprintGroups(sha256: String): String = sha256.take(16).chunked(4).joinToString(" ")

        /** Every conflict the report kept, and how many more it counted than it kept. */
        fun reportRows(report: SyncRunReport): ReportRows = ReportRows(
            at = report.at,
            before = report.before,
            after = report.after,
            routineMerges = report.routineMerges,
            exported = report.exported,
            conflictCount = report.conflictCount,
            conflicts = report.conflicts,
            more = (report.conflictCount - report.conflicts.size).coerceAtLeast(0),
        )
    }
}
