package com.thrive.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thrive.app.data.model.BudgetCadence
import com.thrive.app.data.model.HouseholdProfile
import com.thrive.app.ui.account.GoogleSignInButton
import com.thrive.app.ui.savings.SavingsViewModel

private val applianceChoices = listOf(
    "Stovetop", "Oven", "Microwave", "Air fryer", "Slow cooker", "Pressure cooker", "Blender", "Grill",
)

@Composable
fun OnboardingScreen(
    savingsVm: SavingsViewModel,
    initialProfile: HouseholdProfile,
    onComplete: (HouseholdProfile) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var appliances by remember { mutableStateOf(initialProfile.appliances) }
    var cadence by remember { mutableStateOf(initialProfile.budgetCadence) }
    var budgetText by remember {
        mutableStateOf(initialProfile.budgetAmount.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "120")
    }
    var people by remember { mutableIntStateOf(initialProfile.householdSize.coerceIn(1, 12)) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    IconButton(onClick = { step-- }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(3) { index ->
                        Box(
                            Modifier.padding(horizontal = 4.dp).size(if (index == step) 10.dp else 7.dp)
                                .then(Modifier)
                        ) {
                            Surface(
                                color = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                                modifier = Modifier.fillMaxSize(),
                            ) {}
                        }
                    }
                }
                Spacer(Modifier.size(48.dp))
            }

            AnimatedContent(targetState = step, label = "onboarding step", modifier = Modifier.weight(1f)) { current ->
                when (current) {
                    0 -> WelcomeStep(savingsVm = savingsVm, onContinue = { step = 1 })
                    1 -> AppliancesStep(
                        selected = appliances,
                        onToggle = { item -> appliances = if (item in appliances) appliances - item else appliances + item },
                        onContinue = { step = 2 },
                    )
                    else -> BudgetStep(
                        cadence = cadence,
                        budgetText = budgetText,
                        people = people,
                        onCadence = { cadence = it },
                        onBudget = { budgetText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                        onPeople = { people = it.coerceIn(1, 12) },
                        onComplete = {
                            onComplete(
                                initialProfile.copy(
                                    appliances = appliances,
                                    budgetCadence = cadence,
                                    budgetAmount = budgetText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                                    householdSize = people,
                                    onboardingVersion = 1,
                                    onboardingCompletedAt = System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(savingsVm: SavingsViewModel, onContinue: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("Thrive", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Smart savings for smarter meals.", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Find useful grocery deals, plan around what you own, and keep your household on budget.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        item { Benefit(Icons.Rounded.LocalOffer, "Find practical deals", "Compare offers and open the retailer to confirm each price.") }
        item { Benefit(Icons.Rounded.RestaurantMenu, "Plan with confidence", "Build meals around your budget and the food you already have.") }
        item { Benefit(Icons.Rounded.Kitchen, "Made for your kitchen", "Your appliances help Thrive suggest recipes you can actually make.") }
        item {
            Spacer(Modifier.height(12.dp))
            GoogleSignInButton(savingsVm, onSignedIn = onContinue)
            OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Continue without an account")
            }
            Text(
                "Signing in keeps your preferences available across devices. Thrive still works offline without an account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(11.dp).size(22.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppliancesStep(selected: Set<String>, onToggle: (String) -> Unit, onContinue: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("What can you cook with?", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Choose every appliance you have. Thrive will avoid recipes that need equipment you do not own.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            )
            ChangeLater()
        }
        items(applianceChoices.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { appliance ->
                    OutlinedCard(
                        modifier = Modifier.weight(1f).height(72.dp).clickable { onToggle(appliance) },
                        border = BorderStroke(
                            1.dp,
                            if (appliance in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(appliance, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            if (appliance in selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Continue") }
        }
    }
}

@Composable
private fun BudgetStep(
    cadence: BudgetCadence,
    budgetText: String,
    people: Int,
    onCadence: (BudgetCadence) -> Unit,
    onBudget: (String) -> Unit,
    onPeople: (Int) -> Unit,
    onComplete: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Set your grocery budget", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Thrive uses this to prioritize plans and deals that fit your household.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            ChangeLater()
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = cadence == BudgetCadence.WEEKLY,
                    onClick = { onCadence(BudgetCadence.WEEKLY) },
                    label = { Text("Weekly") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = cadence == BudgetCadence.MONTHLY,
                    onClick = { onCadence(BudgetCadence.MONTHLY) },
                    label = { Text("Monthly") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            OutlinedTextField(
                value = budgetText,
                onValueChange = onBudget,
                label = { Text(if (cadence == BudgetCadence.WEEKLY) "Weekly budget" else "Monthly budget") },
                prefix = { Text("$") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("People in your household", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onPeople(people - 1) }, enabled = people > 1) { Text("−") }
                Text(people.toString(), style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = { onPeople(people + 1) }, enabled = people < 12) { Text("+") }
            }
        }
        item {
            Button(
                onClick = onComplete,
                enabled = (budgetText.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Start saving") }
        }
    }
}

@Composable
private fun ChangeLater() {
    Text(
        "You can change this anytime in Settings.",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
