package com.shinsaikou.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shinsaikou.R
import com.shinsaikou.SaikouApp
import com.shinsaikou.signin.SignInBrowser
import com.shinsaikou.ui.util.ErrorText
import eu.kanade.presentation.theme.SaikouTheme

/**
 * The debug build's Google round trip screen (phase 3 unit O, owner item 2), its own launcher entry.
 *
 * The test base field, shown above Run, is how the spike reaches the sync fixture: typed on the
 * device (`http://10.0.2.2:18797/`), Run sends every Google call to it with the fixture's test
 * client; left blank, the endpoints are Google's. The screen reads no base from its intent (fix round
 * 1 item 1): it is exported, and any app could start it naming its own listener. The screen shows yes
 * or no per step, never a value.
 */
class GoogleRoundTripActivity : AppCompatActivity() {

    private val graph by lazy { (application as SaikouApp).graph }

    private val viewModel: GoogleRoundTripViewModel by viewModels {
        viewModelFactory { initializer { GoogleRoundTripViewModel(graph, SignInBrowser(applicationContext)) } }
    }

    /** Ignored: a denial only means the "Signed in, tap to return" notification is not shown. */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) askForNotifications()
        setContent {
            SaikouTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    RoundTripScreen(
                        state = state,
                        onBaseChanged = viewModel::setBaseInput,
                        onHoldChanged = viewModel::setHoldForeground,
                        onRun = { viewModel.run(applicationContext, returnIntent()) },
                    )
                }
            }
        }
    }

    private fun returnIntent(): Intent = Intent(this, GoogleRoundTripActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun RoundTripScreen(
    state: GoogleRoundTripViewModel.State,
    onBaseChanged: (String) -> Unit,
    onHoldChanged: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val resources = LocalResources.current
    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.debug_google_round_trip_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(
                if (state.client.configured) R.string.debug_google_configured else R.string.debug_google_not_configured,
            ),
        )
        val base = state.client.testBase
        Text(
            if (base == null) {
                stringResource(R.string.debug_google_endpoints_google)
            } else {
                stringResource(R.string.debug_google_endpoints_test_format, base)
            },
        )
        OutlinedTextField(
            value = state.baseInput,
            onValueChange = onBaseChanged,
            label = { Text(stringResource(R.string.debug_google_base)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.holdForeground, onCheckedChange = onHoldChanged, enabled = !state.running)
            Text(stringResource(R.string.debug_google_hold))
        }
        Button(onClick = onRun, enabled = (state.client.configured || state.baseInput.isNotBlank()) && !state.running) {
            Text(stringResource(R.string.debug_google_run))
        }
        if (state.running) Text(stringResource(R.string.debug_google_running))
        Text(stringResource(R.string.debug_google_foreground_format, answer(state.heldForeground)))
        val seconds = state.codeAfterSeconds
        if (seconds == null) {
            Text(stringResource(R.string.debug_google_code_format, answer(null)))
        } else {
            Text(stringResource(R.string.debug_google_code_after_format, seconds.toInt()))
        }
        Text(stringResource(R.string.debug_google_refresh_token_format, answer(state.refreshTokenIssued)))
        Text(stringResource(R.string.debug_google_expires_in_format, answer(state.expiresInPresent)))
        Text(stringResource(R.string.debug_google_refreshed_format, answer(state.refreshed)))
        Text(stringResource(R.string.debug_google_revoked_format, answer(state.revoked)))
        state.failure?.let { Text(stringResource(R.string.debug_google_failed_format, ErrorText.of(resources, it))) }
    }
}

@Composable
private fun answer(value: Boolean?): String = stringResource(
    when (value) {
        null -> R.string.debug_google_not_run
        true -> R.string.debug_google_yes
        false -> R.string.debug_google_no
    },
)
