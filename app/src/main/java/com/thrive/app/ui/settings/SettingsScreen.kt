package com.thrive.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thrive.app.BuildConfig
import com.thrive.app.ai.OnDeviceLlm
import com.thrive.app.data.LocationProvider
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.model.BudgetCadence
import com.thrive.app.data.model.HouseholdProfile
import com.thrive.app.data.remote.SyncStatus
import com.thrive.app.ui.account.GoogleSignInButton
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.savings.SavingsViewModel
import com.thrive.app.update.GithubUpdateChecker
import com.thrive.app.update.ReEngagement
import com.thrive.app.update.UpdateBus
import kotlinx.coroutines.launch

private enum class SettingsPage(val title: String, val description: String, val icon: ImageVector) {
    ACCOUNT("Account", "Sign-in and secure data sync", Icons.Rounded.AccountCircle),
    BUDGET("Budget", "Weekly or monthly spending target", Icons.Rounded.Wallet),
    APPLIANCES("Appliances", "Equipment available in your kitchen", Icons.Rounded.Kitchen),
    SECURITY("Security", "Privacy, location, and local data", Icons.Rounded.Security),
    UPDATES("Updates", "Deals, app updates, and offline AI", Icons.Rounded.SystemUpdate),
    ABOUT("About Thrive", "Version, purpose, and privacy", Icons.Rounded.Info),
}

private val applianceChoices = listOf(
    "Stovetop", "Oven", "Microwave", "Air fryer", "Slow cooker", "Pressure cooker", "Blender", "Grill",
)

@Composable
fun SettingsScreen(
    repo: ThriveRepository,
    savingsVm: SavingsViewModel,
    budgetVm: BudgetViewModel,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var profile by remember { mutableStateOf(repo.loadHouseholdProfile()) }
    val saveProfile: (HouseholdProfile) -> Unit = { next ->
        profile = next
        repo.saveHouseholdProfile(next)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(page?.title ?: "Settings", onBack = { if (page == null) onBack() else page = null })
            when (page) {
                null -> SettingsHome(onOpen = { page = it })
                SettingsPage.ACCOUNT -> AccountSettings(savingsVm)
                SettingsPage.BUDGET -> BudgetSettings(profile, saveProfile, budgetVm)
                SettingsPage.APPLIANCES -> AppliancesSettings(profile, saveProfile)
                SettingsPage.SECURITY -> SecuritySettings(repo)
                SettingsPage.UPDATES -> UpdatesSettings(repo)
                SettingsPage.ABOUT -> AboutSettings()
            }
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Your Thrive", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            Text(
                "Keep your household, privacy, and app preferences in one place.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        items(SettingsPage.entries) { entry ->
            Card(
                onClick = { onOpen(entry) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                ListItem(
                    headlineContent = { Text(entry.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(entry.description, style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp).size(22.dp))
                        }
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                )
            }
        }
    }
}

@Composable
private fun AccountSettings(savingsVm: SavingsViewModel) {
    val state by savingsVm.state.collectAsState()
    val account = savingsVm.googleAccount()
    val signedIn = savingsVm.googleSignedIn()
    var confirmDelete by remember { mutableStateOf(false) }
    SettingsList {
        hero(
            Icons.Rounded.AccountCircle,
            if (account.email.isNotBlank()) account.name.ifBlank { account.email } else "Your account",
            if (signedIn) "Your Thrive data can securely follow you across signed-in devices." else "Sign in with Google to protect and synchronize your household data.",
        )
        item {
            if (signedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusRow(Icons.Rounded.CloudDone, "Signed in", account.email.ifBlank { "Google account" })
                    Button(onClick = { savingsVm.googleBackupNow() }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    OutlinedButton(onClick = { savingsVm.googleSignOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                    if (!confirmDelete) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete account and server data", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text(
                            "This permanently removes your encrypted Thrive account and every server session. Local data stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = { confirmDelete = false; savingsVm.googleDeleteAccount() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirm permanent deletion", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    }
                }
            } else GoogleSignInButton(savingsVm)
        }
        state.backupMsg?.takeIf { it.isNotBlank() }?.let { message ->
            item { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { PrivacyNote("Google confirms who you are. Thrive stores app data in its own encrypted database; private server keys are never placed inside the app.") }
    }
}

@Composable
private fun BudgetSettings(profile: HouseholdProfile, save: (HouseholdProfile) -> Unit, budgetVm: BudgetViewModel) {
    var text by remember(profile.budgetAmount) { mutableStateOf(profile.budgetAmount.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "") }
    var cadence by remember(profile.budgetCadence) { mutableStateOf(profile.budgetCadence) }
    var people by remember(profile.householdSize) { mutableIntStateOf(profile.householdSize.coerceIn(1, 12)) }
    SettingsList {
        hero(Icons.Rounded.Wallet, "Your grocery budget", "Thrive uses this target to make plans and deal suggestions more useful.")
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(cadence == BudgetCadence.WEEKLY, { cadence = BudgetCadence.WEEKLY }, { Text("Weekly") }, modifier = Modifier.weight(1f))
                FilterChip(cadence == BudgetCadence.MONTHLY, { cadence = BudgetCadence.MONTHLY }, { Text("Monthly") }, modifier = Modifier.weight(1f))
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                label = { Text(if (cadence == BudgetCadence.WEEKLY) "Weekly budget" else "Monthly budget") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("Household size", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ people = (people - 1).coerceAtLeast(1) }, enabled = people > 1) { Text("−") }
                Text(people.toString(), style = MaterialTheme.typography.headlineMedium)
                OutlinedButton({ people = (people + 1).coerceAtMost(12) }, enabled = people < 12) { Text("+") }
            }
        }
        item {
            Button(
                onClick = {
                    val amount = text.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    save(profile.copy(budgetAmount = amount, budgetCadence = cadence, householdSize = people))
                    budgetVm.setBudget(if (cadence == BudgetCadence.WEEKLY) amount else amount / 4.33)
                    budgetVm.setPeople(people)
                },
                enabled = (text.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save budget") }
            ChangeLaterNote()
        }
    }
}

@Composable
private fun AppliancesSettings(profile: HouseholdProfile, save: (HouseholdProfile) -> Unit) {
    var selected by remember(profile.appliances) { mutableStateOf(profile.appliances) }
    SettingsList {
        hero(Icons.Rounded.Kitchen, "Kitchen appliances", "Recipe plans use only the equipment you select here.")
        applianceChoices.chunked(2).forEach { choices ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    choices.forEach { choice ->
                        OutlinedCard(
                            onClick = { selected = if (choice in selected) selected - choice else selected + choice },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(choice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                if (choice in selected) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = { save(profile.copy(appliances = selected)) }, modifier = Modifier.fillMaxWidth()) { Text("Save appliances") }
            ChangeLaterNote()
        }
    }
}

@Composable
private fun SecuritySettings(repo: ThriveRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasLocation by remember { mutableStateOf(LocationProvider.hasPermission(context)) }
    var working by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocation = granted
        if (granted) scope.launch {
            working = true
            LocationProvider.lastKnownLocation(context)?.let { repo.setLocation(it.first, it.second) }
            working = false
        }
    }
    SettingsList {
        hero(Icons.Rounded.Lock, "Privacy by design", "Your signed-in household data is encrypted before it is stored on the Thrive PC server.")
        item { PrivacyNote("Passwords and private provider keys are not shown in Thrive and are never embedded in the APK. Local session credentials are protected by Android Keystore.") }
        item {
            HorizontalDivider()
            Text("Nearby deals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            Text("Approximate location helps rank stores. Thrive works normally when location is off.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (hasLocation) "Location on" else "Location off")
                }
                Switch(
                    checked = hasLocation,
                    onCheckedChange = { on ->
                        if (on) launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        else scope.launch { repo.clearLocation(); hasLocation = false }
                    },
                    enabled = !working,
                )
            }
        }
    }
}

@Composable
private fun UpdatesSettings(repo: ThriveRepository) {
    val context = LocalContext.current
    val app = context.applicationContext as com.thrive.app.ThriveApp
    val sync by repo.syncState.collectAsState()
    val llmState by app.onDeviceLlm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var reminders by remember { mutableStateOf(app.settings.getBoolean(ReEngagement.KEY_ENABLED, true)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) reminders = false }
    SettingsList {
        hero(Icons.Rounded.SystemUpdate, "Updates", "Thrive refreshes deals in the background and checks for new app versions.")
        item {
            StatusRow(
                Icons.Rounded.Refresh,
                "Deal refresh",
                when (sync.status) {
                    SyncStatus.OK -> "Current"
                    SyncStatus.SYNCING -> "Refreshing…"
                    SyncStatus.ERROR -> "Using saved deals; retry scheduled"
                    SyncStatus.OFFLINE -> "Using saved deals"
                },
            )
            OutlinedButton(onClick = { scope.launch { repo.syncNow(force = true) } }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                Text("Refresh deals", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            StatusRow(Icons.Rounded.AutoAwesome, "Offline AI", aiStatus(llmState))
            if (llmState is OnDeviceLlm.State.Downloading) {
                LinearProgressIndicator(progress = { (llmState as OnDeviceLlm.State.Downloading).progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Text(
                "The offline assistant prepares automatically after setup on a suitable connection. Thrive's built-in planning tools keep working while it downloads or if this phone cannot run it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (llmState is OnDeviceLlm.State.Failed || llmState is OnDeviceLlm.State.NotDownloaded) {
                OutlinedButton(onClick = { com.thrive.app.update.OfflineAiWorker.schedule(app) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Retry offline AI setup") }
            }
            if (llmState is OnDeviceLlm.State.Ready) {
                TextButton(onClick = { app.onDeviceLlm.deleteModel() }, modifier = Modifier.fillMaxWidth()) { Text("Remove offline AI files") }
            }
        }
        item {
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Update notifications", style = MaterialTheme.typography.titleMedium)
                    Text("A quiet alert when a new Thrive version is ready.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = reminders, onCheckedChange = { enabled ->
                    reminders = enabled
                    app.settings.putBoolean(ReEngagement.KEY_ENABLED, enabled)
                    if (enabled && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
        }
        item {
            Button(
                onClick = {
                    checking = true
                    scope.launch {
                        val update = GithubUpdateChecker.checkLatest()
                        checking = false
                        if (update == null) updateMessage = "You're up to date."
                        else { updateMessage = "Version ${update.versionName} is available."; UpdateBus.publish(update) }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) "Checking…" else "Check for app update") }
            updateMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun AboutSettings() {
    SettingsList {
        hero(Icons.Rounded.Info, "Thrive", "A practical grocery savings and meal-planning companion built for real households.")
        item {
            StatusRow(Icons.Rounded.Info, "Version", BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(12.dp))
            PrivacyNote("Deal prices can change. Thrive labels planning estimates clearly and opens the retailer so you can confirm the live price before buying.")
            Text("Privacy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Thrive collects only the information needed for the features you use. Account data stays on the configured Thrive server and is not sold.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Open-source notices and third-party model licenses are included with the app distribution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.hero(icon: ImageVector, title: String, body: String) {
    item {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyNote(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun ChangeLaterNote() {
    Text("You can change this anytime in Settings.", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

private fun aiStatus(state: OnDeviceLlm.State): String = when (state) {
    OnDeviceLlm.State.NotDownloaded -> "Waiting to prepare"
    is OnDeviceLlm.State.Downloading -> "Preparing ${((state.progress * 100).toInt()).coerceIn(0, 100)}%"
    OnDeviceLlm.State.Ready -> "Ready on this phone"
    is OnDeviceLlm.State.Failed -> "Setup paused; Thrive still works"
}
