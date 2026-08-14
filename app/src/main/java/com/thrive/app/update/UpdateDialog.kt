package com.thrive.app.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thrive.app.data.remote.UpdateInfo
import com.thrive.app.ui.theme.LocalThriveColors

/**
 * The one-tap update popup. "Update now" downloads the release APK from GitHub
 * and hands it to the system installer — no sync server, API key, or IP
 * address required. The phone asks for "allow installing from this source"
 * once on first use, which is normal Android behavior for any app.
 */
@Composable
fun UpdateDialog(
    update: UpdateInfo,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    val accents = LocalThriveColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text("A new Thrive is ready", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Version ${update.versionName}",
                    style = MaterialTheme.typography.labelLarge.copy(color = accents.deal),
                )
            }
        },
        text = {
            Column {
                Text(
                    "This update is free and takes about a minute: tap Update now, allow the " +
                        "install when your phone asks (once), and you're done.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("What's new:", style = MaterialTheme.typography.labelLarge)
                    update.notes.forEach { note ->
                        Text(
                            "•  $note",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Update now", modifier = Modifier.padding(vertical = 4.dp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        },
    )
}

/** Kicks off the download + install flow (same receiver the notification uses). */
fun startUpdateDownload(context: Context, update: UpdateInfo) {
    val intent = Intent(context, DownloadReceiver::class.java).apply {
        putExtra(UpdateNotifier.EXTRA_URL, update.apkUrl)
        putExtra(UpdateNotifier.EXTRA_VERSION, update.versionName)
    }
    context.sendBroadcast(intent)
}
