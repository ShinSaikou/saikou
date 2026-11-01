package com.shinsaikou.signin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.shinsaikou.SaikouApp
import com.shinsaikou.ui.main.MainActivity

/**
 * Where the "Return to Saikou" button on the loopback page leads, as `saikou://sign-in-return`: it
 * brings back the activity the latest sign-in started from ([SignInHold.lastReturnIntent]), the one
 * the "Signed in, tap to return" notification opens, or the main screen when no sign-in started in
 * this process. Then it finishes, before it is ever drawn. The main screen is named rather than
 * looked up as the package's launch intent, since a debug build has a second launcher entry, the
 * Google round trip, which that lookup can pick.
 *
 * It is the app's one entry point a web page can reach, so it reads nothing from the intent that
 * started it: no data, no extras, no action. Any page could send the owner here with a tap, and
 * all that does is bring Saikou to the front as it was. The returning activity is started with
 * `FLAG_ACTIVITY_NEW_TASK`, the way the notification's pending intent starts it, so it comes back
 * in its own task and a `singleTop` activity at the top of it gets `onNewIntent` rather than a
 * second copy. This activity has a task of its own, and no place in Recents.
 *
 * A plain [Activity] rather than a `ComponentActivity`, so the app lock never watches it; the
 * activity it brings back is watched as usual.
 */
class SignInReturnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = (application as SaikouApp).graph.signInHold.lastReturnIntent
            // The launcher's own intent, so an existing task is brought back rather than added to.
            ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        startActivity(Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }

    companion object {
        /** The host of the link, under [com.shinsaikou.ui.main.MainIntents.SCHEME]. */
        const val HOST = "sign-in-return"
    }
}
