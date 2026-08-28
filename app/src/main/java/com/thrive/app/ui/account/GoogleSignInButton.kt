package com.thrive.app.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.thrive.app.BuildConfig
import com.thrive.app.data.remote.googleSignInConfigured
import com.thrive.app.ui.savings.SavingsViewModel

/** Shared, honest Google sign-in entry point for onboarding and Settings. */
@Composable
fun GoogleSignInButton(
    savingsVm: SavingsViewModel,
    modifier: Modifier = Modifier,
    label: String = "Continue with Google",
    onSignedIn: () -> Unit = {},
) {
    val context = LocalContext.current
    val configured = googleSignInConfigured()
    val client = remember(configured) {
        if (!configured) null else GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)
                .requestEmail()
                .build(),
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (data == null) {
            savingsVm.setBackupMsg("Google sign-in was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        }.onSuccess { account ->
            val token = account.idToken
            if (token.isNullOrBlank()) {
                savingsVm.setBackupMsg("Google sign-in did not return a secure token. Please try again.")
            } else {
                savingsVm.googleCompleteSignIn(token, onSignedIn)
            }
        }.onFailure {
            savingsVm.setBackupMsg("Google sign-in was cancelled or could not finish.")
        }
    }

    Button(
        onClick = {
            if (client == null) {
                savingsVm.setBackupMsg("Google sign-in is not configured in this test build yet.")
            } else {
                launcher.launch(client.signInIntent)
            }
        },
        modifier = modifier.fillMaxWidth(),
        enabled = configured,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(if (configured) label else "Google sign-in needs setup")
        }
    }
}
