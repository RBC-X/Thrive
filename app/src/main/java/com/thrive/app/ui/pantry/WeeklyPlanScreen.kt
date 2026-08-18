package com.thrive.app.ui.pantry

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddShoppingCart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.ai.PlannedNight
import com.thrive.app.ai.WeeklyPlan
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

@Composable
fun WeeklyPlanScreen(
    pantryVm: PantryViewModel,
    budgetVm: BudgetViewModel,
    onBack: () -> Unit,
) {
    val state by pantryVm.state.collectAsState()
    val plan = state.weeklyPlan
    if (plan == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Planning…")
        }
        return
    }
    val accents = LocalThriveColors.current
    val context = LocalContext.current

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
                    text = "Your week of meals",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ThriveFont,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { pantryVm.rePlan() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Re-plan", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            WeekSummaryCard(plan)
        }

        plan.aiTip?.let { tip ->
            if (tip.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(accents.berrySoft.copy(alpha = 0.55f))
                            .padding(14.dp),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accents.berry, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Meal coach", style = MaterialTheme.typography.labelLarge.copy(color = accents.berry, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(2.dp))
                            Text(tip, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }

        plan.repairNote?.let { note ->
            if (note.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(accents.goldSoft.copy(alpha = 0.55f))
                            .padding(14.dp),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = accents.gold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Budget check",
                                style = MaterialTheme.typography.labelLarge.copy(color = accents.gold, fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(note, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }

        if (!plan.underBudget && !state.isPlanningWeek) {
            item {
                Button(
                    onClick = { pantryVm.optimizePlan() },
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Optimize to fit ${Money.fmt(plan.budget)}", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (plan.combinedShopping.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AddShoppingCart, contentDescription = null, tint = accents.deal, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Combined shopping list",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${plan.combinedShopping.size} items · ≈ ${Money.fmt(plan.extraCost)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Button(
                        onClick = {
                            plan.combinedShopping.forEach { missing ->
                                budgetVm.addItem(
                                    name = missing.name,
                                    category = "Grocery",
                                    quantity = 1,
                                    unit = "",
                                    estPrice = missing.estCost,
                                )
                            }
                            Toast.makeText(
                                context,
                                "Added ${plan.combinedShopping.size} items to your shopping list",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                    ) {
                        Text("Add all", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
            item {
                Text(
                    text = "Grab these once, cook all week",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (plan.shoppingGroups.isNotEmpty()) {
                plan.shoppingGroups.forEach { group ->
                    item {
                        Row(
                            modifier = Modifier
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group.category.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            if (group.subtotal > 0) {
                                Text(
                                    text = "≈ ${Money.fmt(group.subtotal)}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                            }
                        }
                    }
                    items(group.items.size, key = { group.category + it }) { i ->
                        val line = group.items[i]
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = line.name.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = if (line.haveInPantry) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (line.haveInPantry) TextDecoration.LineThrough else TextDecoration.None,
                                    ),
                                )
                                Text(
                                    text = line.label + if (line.recipes > 1) " · used in ${line.recipes} meals" else "",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                            }
                            if (line.haveInPantry) {
                                SoftChip(text = "In pantry", bg = accents.leafSoft, fg = accents.leaf)
                            } else {
                                Text(
                                    text = Money.fmt(line.estCost),
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                            }
                        }
                    }
                }
            } else {
                items(plan.combinedShopping.size, key = { it }) { i ->
                    val missing = plan.combinedShopping[i]
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = missing.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = Money.fmt(missing.estCost),
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(accents.leafSoft)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accents.leaf, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Nothing to buy — your pantry covers the whole week!",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            Text(
                text = "The plan",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        items(plan.nights.size, key = { it }) { i ->
            NightCard(
                night = plan.nights[i],
                swapEnabled = !state.isPlanningWeek,
                onSwap = { pantryVm.swapNight(i) },
            )
        }

        item {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = { pantryVm.rePlan() },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Re-plan this week")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun WeekSummaryCard(plan: WeeklyPlan) {
    val accents = LocalThriveColors.current
    val gradient = if (plan.underBudget)
        Brush.linearGradient(listOf(Color(0xFF0B6E4F), Color(0xFF128F6A)))
    else
        Brush.linearGradient(listOf(Color(0xFFC2410C), Color(0xFFE4572E)))
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(20.dp),
    ) {
        Text(
            text = "${plan.nights.size} NIGHTS · ${plan.people} PEOPLE",
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
        )
        plan.requestSummary?.let { summary ->
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.85f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Money.fmt(plan.totalCost)} of ${Money.fmt(plan.budget)} budget",
            style = MaterialTheme.typography.headlineMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (plan.budget > 0) (plan.totalCost / plan.budget).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                plan.underBudget -> "${Money.fmt(plan.remaining)} to spare — treat the last night"
                else -> "Over by ${Money.fmt(plan.overshoot)} — optimize or swap a meal"
            },
            style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.9f)),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftChip(
                text = "${plan.nights.size} dinners",
                bg = Color.White.copy(alpha = 0.2f),
                fg = Color.White,
            )
            SoftChip(
                text = "${plan.combinedShopping.size} items to buy",
                bg = Color.White.copy(alpha = 0.2f),
                fg = Color.White,
            )
            if (plan.pantryValueUsed > 0.01) {
                SoftChip(
                    text = "≈ ${Money.fmt(plan.pantryValueUsed)} from your pantry",
                    bg = Color.White.copy(alpha = 0.2f),
                    fg = Color.White,
                )
            }
            SoftChip(
                text = "≈ ${Money.fmt(plan.costPerMeal)}/meal",
                bg = Color.White.copy(alpha = 0.2f),
                fg = Color.White,
            )
            if (plan.avgMinutes > 0) {
                SoftChip(
                    text = "~${plan.avgMinutes} min avg",
                    bg = Color.White.copy(alpha = 0.2f),
                    fg = Color.White,
                )
            }
        }
    }
}

@Composable
private fun NightCard(night: PlannedNight, swapEnabled: Boolean, onSwap: () -> Unit) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accents.leafSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = night.day.take(1),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = accents.leaf,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = ThriveFont,
                ),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = night.day,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                if (night.suggestion.usedItems.isNotEmpty()) {
                    SoftChip(
                        text = "Uses ${night.suggestion.usesCount}",
                        bg = accents.leafSoft,
                        fg = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = night.suggestion.recipe.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // What this dinner actually costs the household: new groceries
                // plus the value of pantry items it consumes (never both twice).
                text = Money.fmt(night.suggestion.estimatedExtraCost + night.suggestion.pantryValueUsed),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "${night.suggestion.recipe.totalMinutes} min",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        IconButton(onClick = onSwap, enabled = swapEnabled) {
            Icon(
                Icons.Rounded.SwapHoriz,
                contentDescription = "Swap this meal",
                tint = if (swapEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            )
        }
    }
}
