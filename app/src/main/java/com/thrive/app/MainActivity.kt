package com.thrive.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thrive.app.data.remote.UpdateInfo
import com.thrive.app.ui.ThriveRoot
import com.thrive.app.ui.theme.ThriveTheme
import com.thrive.app.update.DealSyncWorker
import com.thrive.app.update.DownloadReceiver
import com.thrive.app.update.UpdateBus
import com.thrive.app.update.UpdateNotifier

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Notification permission is NOT requested here: the first-run prompt
        // was contextless and premature. Users opt in from Settings → Update
        // notifications, where the benefit is explained. The app is fully
        // usable without it (the update dialog works in-app regardless).
        surfaceUpdateFromIntent(intent)
        setContent {
            ThriveTheme {
                ThriveRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        surfaceUpdateFromIntent(intent)
    }

    /**
     * When the update notification's body was tapped, the update details ride
     * along in the intent so the in-app update dialog appears on open — the
     * user decides to download there, no surprise background download.
     */
    private fun surfaceUpdateFromIntent(intent: Intent?) {
        val url = intent?.getStringExtra(UpdateNotifier.EXTRA_URL) ?: return
        val version = intent.getStringExtra(UpdateNotifier.EXTRA_VERSION) ?: return
        if (url.isBlank() || version.isBlank()) return
        UpdateBus.publish(
            UpdateInfo(
                versionName = version,
                apkUrl = url,
                apkSizeBytes = intent.getLongExtra(UpdateNotifier.EXTRA_SIZE, 0L),
            )
        )
    }

    override fun onResume() {
        super.onResume()
        // After the user grants "install unknown apps" in Settings and returns,
        // finish the install that was waiting.
        DownloadReceiver.resumePending(this)
        // Any foreground visit resets the 42h re-engagement idle timer.
        (application as ThriveApp).markAppUsed()
        // Always-up-to-date deals: refresh the feed in the background if the
        // last sync is older than 30 minutes — new coupons and live prices
        // arrive without needing an app update.
        DealSyncWorker.refreshOnResume(this)
    }
}
