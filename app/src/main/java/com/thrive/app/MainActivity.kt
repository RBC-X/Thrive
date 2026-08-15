package com.thrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thrive.app.ui.ThriveRoot
import com.thrive.app.ui.theme.ThriveTheme
import com.thrive.app.update.DownloadReceiver

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Notification permission is NOT requested here: the first-run prompt
        // was contextless and premature. Users opt in from Settings → Update
        // notifications, where the benefit is explained. The app is fully
        // usable without it (the update dialog works in-app regardless).
        setContent {
            ThriveTheme {
                ThriveRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After the user grants "install unknown apps" in Settings and returns,
        // finish the install that was waiting.
        DownloadReceiver.resumePending(this)
        // Any foreground visit resets the 42h re-engagement idle timer.
        (application as ThriveApp).markAppUsed()
    }
}
