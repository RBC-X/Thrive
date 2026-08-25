package com.thrive.app.ui.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.thrive.app.BuildConfig
import com.thrive.app.data.remote.googleSignInConfigured
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.thrive.app.data.remote.BackupPolicy
import com.thrive.app.data.remote.SyncState
import com.thrive.app.data.remote.SyncStatus
import com.thrive.app.ui.savings.SavingsViewModel
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.update.GithubUpdateChecker
import com.thrive.app.update.UpdateBus
import com.thrive.app.util.Clipboard
import com.thrive.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
fun SettingsScreen(
    repo: com.thrive.app.data.ThriveRepository,
    savingsVm: SavingsViewModel,
    budgetVm: com.thrive.app.ui.budget.BudgetViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.thrive.app.ThriveApp
    val settings = app.settings
    val ai = AiService(settings)
    val savingsState by savingsVm.state.collectAsState()
    // Listen to the SHARED repository (the one the Savings VM also uses), so a
    // sync started here is visible everywhere the moment it finishes — not just
    // after the next periodic worker tick.
    var syncStatus by remember { mutableStateOf(repo.syncState.value) }
    LaunchedEffect(repo) {
        repo.syncState.collect { s -> syncStatus = s }
    }

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
    var remindersEnabled by remember {
        mutableStateOf(app.settings.getBoolean(com.thrive.app.update.ReEngagement.KEY_ENABLED, true))
    }
    var locMessage by remember { mutableStateOf<String?>(null) }
    var locWorking by remember { mutableStateOf(false) }
    var locDenied by remember { mutableStateOf(false) }
    var appliances by remember { mutableStateOf(settings.getAppliances()) }
    val applianceOptions = listOf("Air fryer", "Slow cooker", "Oven", "Stovetop", "Microwave")
    var publicServer by remember { mutableStateOf<String?>(null) }
    var discoveringServer by remember { mutableStateOf(false) }
    var serverMsg by remember { mutableStateOf<String?>(null) }
    // Google Sign-In backup: a card above the legacy code section. Hidden when
    // the build has no client ID configured. The signed-in account comes from
    // the savings VM (persisted); the ID token lives only in the VM.
    val googleConfigured = googleSignInConfigured()
    val googleAccount = savingsVm.googleAccount()
    val googleSignedIn = savingsVm.googleSignedIn()
    val googleSignInClient = remember {
        if (googleConfigured) {
            val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(context, opts)
        } else null
    }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken: String? = account.idToken
            if (idToken.isNullOrBlank()) {
                savingsVm.setBackupMsg("Google sign-in returned no ID token — try again.")
            } else {
                savingsVm.googleCompleteSignIn(idToken)
            }
        } catch (e: ApiException) {
            savingsVm.setBackupMsg("Google sign-in was cancelled or failed.")
        }
    }
    // Grocery budget editing: shown as a card with an edit dialog, always
    // available so the user can change their trip budget any time (not only
    // during first-time onboarding).
    val budgetState by budgetVm.state.collectAsState()
    var showBudgetEdit by remember { mutableStateOf(false) }
    val accents = LocalThriveColors.current

    // Backup only sends the code over HTTPS (or loopback in debug builds). On a
    // plain HTTP non-loopback URL the card below is honest: backup is off.
    val backupPermitted = BackupPolicy.isPermitted(syncUrl, BuildConfig.DEBUG)

    val scope = rememberCoroutineScope()

    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(context, "Update notifications on", Toast.LENGTH_SHORT).show()
            }
        }

    // Nearby deals: approximate location is asked in context (never at launch),
    // the app is fully usable after denial, and Settings offers re-enable.
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scope.launch {
                    locWorking = true
                    locMessage = "Finding your nearest stores…"
                    val loc = com.thrive.app.data.LocationProvider.lastKnownLocation(context)
                    locWorking = false
                    if (loc != null) {
                        locMessage = null
                        repo.setLocation(loc.first, loc.second)
                        locDenied = false
                        Toast.makeText(context, "Nearby deals on — deals ranked by distance", Toast.LENGTH_LONG).show()
                    } else {
                        locMessage = "Can't get a location fix yet — deals are shown unranked. Try again in a moment."
                    }
                }
            } else {
                locDenied = true
                locMessage = "Location off — Thrive shows all deals, not ranked by distance."
            }
        }

    fun enableNearby() {
        if (locWorking) return
        if (!com.thrive.app.data.LocationProvider.hasPermission(context)) {
            locDenied = false
            locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            scope.launch {
                locWorking = true
                locMessage = "Finding your nearest stores…"
                val loc = com.thrive.app.data.LocationProvider.lastKnownLocation(context)
                locWorking = false
                if (loc != null) {
                    locMessage = null
                    repo.setLocation(loc.first, loc.second)
                    Toast.makeText(context, "Nearby deals on — deals ranked by distance", Toast.LENGTH_LONG).show()
                } else {
                    locMessage = "Can't get a location fix yet — deals are shown unranked. Try again in a moment."
                }
            }
        }
    }

    fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // One-tap connect: read the operator's public backup server from the
    // latest GitHub release (tools/tunnel.sh publishes it there). Users never
    // type IPs or URLs.
    LaunchedEffect(Unit) {
        if (syncUrl.isBlank()) {
            discoveringServer = true
            val found = withContext(Dispatchers.IO) {
                com.thrive.app.update.GithubUpdateChecker.discoverSyncServer()
            }
            publicServer = found
            discoveringServer = false
        }
    }

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
            if (syncUrl == publicServer) {
                serverMsg = when (s.status) {
                    com.thrive.app.data.remote.SyncStatus.OK -> "Public backup server verified and connected."
                    com.thrive.app.data.remote.SyncStatus.ERROR ->
                        "Couldn't verify the public backup server. Bundled offline features are still available."
                    else -> "No public backup server is available right now."
                }
            }
        }
    }

    fun connectToPublicServer() {
        val url = publicServer ?: return
        syncUrl = url
        serverMsg = "Checking the public backup server…"
        runSync()
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

        // ── Account ──────────────────────────────────────────────────────────
        item { SettingsSectionHeader("Account") }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("AI assistant", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Thrive works fully offline with its built-in recipe & deal engine. " +
                        "Connect any OpenAI-compatible API (OpenAI, Groq, OpenRouter, local " +
                        "servers) and the pantry AI becomes a genuine generative AI: it " +
                        "writes brand-new recipes from what you have, plus adds richer tips " +
                        "and insights. Without a key, the built-in engine still cooks from " +
                        "your pantry offline.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            val llm = app.onDeviceLlm
            val llmState by llm.state.collectAsState()
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("On-device AI — no keys", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                when (val s = llmState) {
                    is com.thrive.app.ai.OnDeviceLlm.State.Ready -> {
                        Text(
                            text = "A real language model is installed on this phone (546 MB). " +
                                "Pantry recipes are now written by it — fully offline, no account, " +
                                "no API key.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { llm.deleteModel() }) {
                            Text("Remove model")
                        }
                    }
                    is com.thrive.app.ai.OnDeviceLlm.State.Downloading -> {
                        Text(
                            text = "Downloading the on-device AI model (546 MB)… keep the app open. " +
                                "You can keep using Thrive while it downloads.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${(s.progress * 100).toInt()}% downloaded",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { llm.cancelDownload() }) {
                            Text("Cancel download")
                        }
                    }
                    is com.thrive.app.ai.OnDeviceLlm.State.Failed -> {
                        Text(
                            text = s.reason,
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.error),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { llm.startDownload() }) {
                            Text("Try again")
                        }
                    }
                    else -> {
                        Text(
                            text = "Download a small language model (546 MB, one-time) so recipes are " +
                                "written by a real AI on your phone — no API keys, works offline. " +
                                "Newer phones recommended; Thrive works fully without it.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { llm.startDownload() }) {
                            Text("Download 546 MB model")
                        }
                    }
                }
            }
        }

        // ── Appliances ──────────────────────────────────────────────────────
        item { SettingsSectionHeader("Appliances") }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "Which appliances do you have? The weekly planner uses these to " +
                        "pick recipes your kitchen can actually make. You can change this any time.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(applianceOptions.size) { i ->
                        val name = applianceOptions[i]
                        val selected = name.lowercase() in appliances
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    appliances = if (selected) appliances - name.lowercase()
                                    else appliances + name.lowercase()
                                    settings.setAppliances(appliances)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Sync server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Live prices and cross-device backup need a compatible Thrive sync service. " +
                        "If your administrator gave you a secure HTTPS address, enter it below. " +
                        "Without one, Savings, Recipes, Pantry, Budget, and weekly planning keep " +
                        "working offline; backup clearly remains unavailable.",
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
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Nearby deals", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Share your ${com.thrive.app.data.LocationProvider.ACCURACY_LABEL} so Thrive " +
                        "ranks deals by the nearest store and shows how far each one is. It only goes " +
                        "to the sync server you chose above, and you can turn it off any time.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(8.dp))
                val locOn = syncStatus.hasNearby || repo.sharedLocation != null
                if (locOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = accents.leaf,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Location on — deals ranked by distance",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = accents.leaf,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    if (syncStatus.nearbyStores.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Nearest stores: " + syncStatus.nearbyStores.joinToString(" · ") {
                                "${it.store} ${com.thrive.app.util.Distances.mi(it.distMi)}"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { scope.launch { repo.clearLocation() } }) {
                        Text("Turn off nearby deals", color = Color(0xFFB33A1F))
                    }
                } else if (locDenied && !com.thrive.app.data.LocationProvider.hasPermission(context)) {
                    Text(
                        text = "Location is off in system settings — deals aren't ranked by distance.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFFB33A1F),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = { openAppSettings() },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text("Open app settings")
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { enableNearby() }) {
                            Text("Try again")
                        }
                    }
                } else {
                    Button(
                        onClick = { enableNearby() },
                        enabled = !locWorking,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (locWorking) "Finding nearest stores…" else "Use approximate location")
                    }
                }
                if (locMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = locMessage!!,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }

        if (publicServer != null) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Public backup server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Thrive runs a free public backup server you can connect to with one " +
                            "tap — no account, no URL to type.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = syncUrl != publicServer,
                        onClick = { connectToPublicServer() },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.leaf),
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (syncUrl == publicServer && syncStatus.status == com.thrive.app.data.remote.SyncStatus.OK)
                                "Connected"
                            else
                                "Connect to public backup server"
                        )
                    }
                    if (serverMsg != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = serverMsg!!,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Backup & sync", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (googleConfigured)
                        "Sign in with Google to carry your saved deals, pantry, and shopping list " +
                            "to any device — or use a backup code to move between devices without " +
                            "an account. Both need a secure (HTTPS) sync server."
                    else
                        "Your saved deals, pantry, and shopping list sync free between your own " +
                            "devices with a backup code — no account or email. Backup requires a " +
                            "secure (HTTPS) sync server.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                if (!backupPermitted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (syncUrl.isBlank())
                            "Backup is unavailable: no sync server is configured. Set an HTTPS " +
                                "Sync API base URL above to turn it on."
                        else
                            "Backup is off: the current server URL isn't a secure HTTPS endpoint " +
                                "(or isn't the emulator loopback in a debug build). Codes are never " +
                                "sent over plain HTTP.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFFB33A1F),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }

        if (googleConfigured) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (googleSignedIn) accents.leafSoft.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                            )
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (googleSignedIn) accents.leafSoft else accents.berrySoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (googleSignedIn) "✓" else "G",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = if (googleSignedIn) accents.leaf else accents.berry,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (googleSignedIn)
                                    (googleAccount.name.ifBlank { googleAccount.email }.ifBlank { "Google account" })
                                else
                                    "Sign in with Google",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = if (googleSignedIn)
                                    "Your saved deals, pantry, and list live in this account — " +
                                        "sign in on any device to bring them with you."
                                else
                                    "Back up your saved deals, pantry, and shopping list to your " +
                                        "Google account — no code to remember, and it follows you " +
                                        "to any device you sign into.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (googleSignedIn) {
                        Row {
                            Button(
                                enabled = backupPermitted,
                                onClick = { savingsVm.googleBackupNow() },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accents.leaf),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Back up now")
                            }
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = { savingsVm.googleSignOut() },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Sign out")
                            }
                        }
                    } else {
                        Button(
                            enabled = backupPermitted && googleSignInClient != null,
                            onClick = { googleSignInClient?.signInIntent?.let { googleSignInLauncher.launch(it) } },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accents.berry),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continue with Google", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        }
                        if (!backupPermitted) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Connect a secure (HTTPS) sync server above to turn on Google backup.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
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
                        enabled = backupPermitted,
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
                    enabled = backupPermitted,
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
                        enabled = backupPermitted,
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

        // ── Budget ──────────────────────────────────────────────────────────
        item { SettingsSectionHeader("Budget") }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Grocery budget", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showBudgetEdit = true }
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
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = accents.berry, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (budgetState.budget > 0)
                                "${Money.fmt(budgetState.budget)} for ${budgetState.people} ${if (budgetState.people == 1) "person" else "people"}"
                            else
                                "No budget set yet",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Change your shopping budget or people count any time — your list and " +
                                "trip plan update to match.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Updates ─────────────────────────────────────────────────────────
        item { SettingsSectionHeader("Updates") }

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
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { requestNotifications() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accents.dealSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = accents.deal, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Update notifications",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "Get a heads-up when a new Thrive version is ready. You can turn this " +
                                "on or off any time — the app works fully without it.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = Build.VERSION.SDK_INT < 33 ||
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED,
                        onCheckedChange = { requestNotifications() },
                        colors = SwitchDefaults.colors(checkedTrackColor = accents.deal),
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accents.leafSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = accents.leaf,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Deal reminders",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = "If you haven't opened Thrive in over 42 hours, a short reminder " +
                                "brings you back to fresh deals. At most one per absence — no spam.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = remindersEnabled,
                        onCheckedChange = { on ->
                            remindersEnabled = on
                            app.settings.putBoolean(com.thrive.app.update.ReEngagement.KEY_ENABLED, on)
                            if (on && Build.VERSION.SDK_INT >= 33 &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                // Reminders need the same permission as update alerts.
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = accents.leaf),
                    )
                }
            }
        }

        // ── About ───────────────────────────────────────────────────────────
        item { SettingsSectionHeader("About Thrive") }

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

    if (showBudgetEdit) {
        BudgetEditDialog(
            budget = budgetState.budget,
            people = budgetState.people,
            onDismiss = { showBudgetEdit = false },
            onSave = { newBudget, newPeople ->
                budgetVm.setBudget(newBudget)
                budgetVm.setPeople(newPeople)
                // A changed budget invalidates any computed trip plan so the
                // user re-runs "Find me the best deals" against the new numbers.
                budgetVm.clearPlanForEdit()
                showBudgetEdit = false
                Toast.makeText(context, "Budget updated — your list and plan adjust", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Section headers used to group settings by category
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSectionHeader(title: String) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accents.deal),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = ThriveFont,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Grocery budget editing (Settings → Grocery budget)
// ---------------------------------------------------------------------------

@Composable
private fun BudgetEditDialog(
    budget: Double,
    people: Int,
    onDismiss: () -> Unit,
    onSave: (Double, Int) -> Unit,
) {
    val accents = LocalThriveColors.current
    var budgetText by remember { mutableStateOf(if (budget > 0) Money.fmt(budget).removePrefix("$") else "") }
    var peopleCount by remember { mutableStateOf(people.coerceIn(1, 12)) }
    val quickAmounts = listOf(40.0, 75.0, 100.0, 150.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Grocery budget",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = ThriveFont,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
        },
        text = {
            Column {
                Text(
                    text = "How much can you spend per shopping trip?",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { input ->
                        val cleaned = input.filter { it.isDigit() || it == '.' }
                        if (cleaned.length <= 6) budgetText = cleaned
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. 75") },
                    leadingIcon = { Text("$", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickAmounts.forEach { amount ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (budgetText.toDoubleOrNull() == amount) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { budgetText = if (amount % 1.0 == 0.0) amount.toInt().toString() else amount.toString() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = Money.fmtCompact(amount),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (budgetText.toDoubleOrNull() == amount) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "How many people are you shopping for?",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { peopleCount = (peopleCount - 1).coerceAtLeast(1) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        text = "$peopleCount",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = ThriveFont,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { peopleCount = (peopleCount + 1).coerceAtMost(12) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = budgetText.toDoubleOrNull()
                    if (value != null && value > 0) onSave(value, peopleCount)
                },
                enabled = (budgetText.toDoubleOrNull() ?: 0.0) > 0,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
            ) {
                Text("Save budget", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
