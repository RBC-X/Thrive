package com.thrive.app.ui.settings

import android.app.Application
import android.widget.Toast
import com.thrive.app.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.thrive.app.ai.AiService
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import com.thrive.app.ui.savings.SavingsViewModel
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.update.GithubUpdateChecker
import com.thrive.app.update.UpdateBus
import com.thrive.app.util.Clipboard
import kotlinx.coroutines.launch

private fun syncStatusLabel(sync: SyncState, message: String?): String {
    message?.let { return it }
    return when (sync.status) {
        SyncStatus.OK -> "Live feed"
        SyncStatus.ERROR -> "Last sync failed — offline feed"
        SyncStatus.SYNCING -> "…"
        SyncStatus.OFFLINE -> "Offline feed"
    }
}

@Composable
fun SettingsScreen(savingsVm: SavingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.thrive.app.ThriveApp
    val settings = app.settings
    val ai = AiService(settings)
    val repo = remember { com.thrive.app.data.ThriveRepository(app, settings) }
    val savingsState by savingsVm.state.collectAsState()
    var syncStatus by remember { mutableStateOf(repo.syncState.value) }

    var aiKey by remember { mutableStateOf(ai.apiKey) }
    var aiUrl by remember { mutableStateOf(ai.baseUrl) }
    var aiModel by remember { mutableStateOf(ai.model) }
    var aiEnabled by remember { mutableStateOf(ai.isEnabled) }
    var syncUrl by remember { mutableStateOf(repo.syncBaseUrl) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateCheckMsg by remember { mutableStateOf<String?>(null) }
    var restoreCode by remember { mutableStateOf("") }
    val accents = LocalThriveColors.current

    val scope = rememberCoroutineScope()
    fun runSync() {
        if (syncing) return
        syncing = true
        syncMessage = null
        scope.launch {
            settings.putString(com.thrive.app.data.ThriveRepository.SYNC_URL_KEY, syncUrl.trim())
            repo.syncNow(force = true)
            syncing = false
            val s = repo.syncState.value
            syncStatus = s
            syncMessage = when (s.status) {
                com.thrive.app.data.remote.SyncStatus.OK -> "Synced · ${s.source.joinToString(", ")} · ${if (s.lastSyncedAt != null) "${(System.currentTimeMillis() - s.lastSyncedAt) / 1000}s ago" else ""}"
                com.thrive.app.data.remote.SyncStatus.ERROR -> "Sync failed: ${s.error ?: "no server reachable"}. Bundled feed still active."
                else -> "No server configured — bundled feed active."
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ThriveFont,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("AI assistant", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Thrive works fully offline with its built-in recipe & deal engine. " +
                        "Connect any OpenAI-compatible API for richer tips and insights.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Sync server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Point Thrive at your running sync API (backend/ → npm start). " +
                        "Emulator default: http://10.0.2.2:4000. On a phone, use your computer's LAN IP.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = syncUrl,
                    onValueChange = { syncUrl = it },
                    label = { Text("Sync API base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { runSync() },
                        enabled = !syncing,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (syncing) "Syncing…" else "Sync now")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = syncStatusLabel(syncStatus, syncMessage),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (syncStatus.status == com.thrive.app.data.remote.SyncStatus.ERROR)
                                Color(0xFFB33A1F) else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Offline-first: until a sync succeeds (or if the server is unreachable), Thrive uses its bundled feed with no feature loss.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Backup & sync", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your saved deals, pantry, and shopping list sync free between your own " +
                        "devices with a backup code — no account or email. It works whenever your " +
                        "sync server is reachable.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Your code: ",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = savingsState.backupCode.ifBlank { "…" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = ThriveFont,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val ok = Clipboard.copy(context, "Thrive backup code", savingsState.backupCode)
                            savingsVm.setBackupMsg(if (ok) "Backup code copied." else "Copy blocked on this device — write the code down.")
                        },
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { savingsVm.backupNow() },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accents.leaf),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back up now")
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = restoreCode,
                        onValueChange = { restoreCode = it },
                        label = { Text("Restore from a code") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { savingsVm.restoreBackup(restoreCode) },
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore")
                    }
                }
                savingsState.backupMsg?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (msg.contains("failed") || msg.contains("Couldn't") || msg.contains("doesn't"))
                                Color(0xFFB33A1F) else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { aiEnabled = !aiEnabled }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accents.berrySoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accents.berry, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "AI enrichment",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = if (aiEnabled) "Tips will be added to meals & plans" else "Using built-in engine only",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Switch(
                        checked = aiEnabled,
                        onCheckedChange = { aiEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = accents.berry),
                    )
                }
            }
        }

        if (aiEnabled) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OutlinedTextField(
                        value = aiKey,
                        onValueChange = { aiKey = it },
                        label = { Text("API key") },
                        placeholder = { Text("sk-…") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aiUrl,
                        onValueChange = { aiUrl = it },
                        label = { Text("Base URL (OpenAI-compatible)") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aiModel,
                        onValueChange = { aiModel = it },
                        label = { Text("Model") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            settings.putString(AiService.KEY_API_KEY, aiKey.trim())
                            settings.putString(AiService.KEY_BASE_URL, aiUrl.trim())
                            settings.putString(AiService.KEY_MODEL, aiModel.trim())
                            Toast.makeText(context, "AI settings saved", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.berry),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save AI settings")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your key stays on this device. Requests go directly to your chosen endpoint.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        listOf(
                            "pantry_items", "budget_state", "fav_coupons", "fav_recipes",
                        ).forEach { settings.remove(it) }
                        Toast.makeText(context, "Pantry, list & favorites cleared", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear my data")
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Updates", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (!checkingUpdates) {
                                checkingUpdates = true
                                updateCheckMsg = null
                                scope.launch {
                                    val found = GithubUpdateChecker.checkLatest()
                                    checkingUpdates = false
                                    if (found == null) {
                                        updateCheckMsg = "You're on the latest version (${BuildConfig.VERSION_NAME})."
                                    } else {
                                        updateCheckMsg = "Update available: v${found.versionName}"
                                        UpdateBus.publish(found)
                                    }
                                }
                            }
                        },
                        enabled = !checkingUpdates,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.leaf),
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (checkingUpdates) "Checking…" else "Check for updates")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = updateCheckMsg ?: "Checks GitHub releases every 15 minutes.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("About Thrive", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Thrive helps families save on groceries, cook affordable meals, " +
                        "use what they have, and shop on a plan. Version ${BuildConfig.VERSION_NAME}. " +
                        "Deals are illustrative demo data refreshed in-app.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}
