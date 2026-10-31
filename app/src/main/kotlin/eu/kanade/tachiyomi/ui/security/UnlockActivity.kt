// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/security/UnlockActivity.kt (modified: yes) extends AppCompatActivity, unlocks the graph's AppLock, reads its strings from com.shinsaikou.i18n, and draws edge to edge so its bars match the cover the lock lays over the app
package eu.kanade.tachiyomi.ui.security

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.shinsaikou.SaikouApp
import com.shinsaikou.i18n.R
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.startAuthentication
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Blank activity with a BiometricPrompt.
 */
class UnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // As MainActivity does: below Android 15 the blank window behind the prompt would otherwise
        // keep Material's purple status bar over the cover AppLock lays on the app.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startAuthentication(
            getString(R.string.unlock_app_title, getString(R.string.app_name)),
            confirmationRequired = false,
            callback = object : AuthenticatorUtil.AuthenticationCallback() {
                override fun onAuthenticationError(
                    activity: FragmentActivity?,
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(activity, errorCode, errString)
                    logcat(LogPriority.ERROR) { errString.toString() }
                    finishAffinity()
                }

                override fun onAuthenticationSucceeded(
                    activity: FragmentActivity?,
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(activity, result)
                    (application as SaikouApp).graph.appLock.unlock()
                    finish()
                }
            },
        )
    }
}
