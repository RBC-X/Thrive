package com.thrive.app.ui.pantry

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.ai.MealSuggestion
import com.thrive.app.data.model.PantryItem
import com.thrive.app.ui.components.QuantityStepper
import com.thrive.app.ui.components.SectionHeader
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.components.categoryIcon
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Dates
import com.thrive.app.util.Money

private val storageGroups = listOf("Fridge", "Freezer", "Pantry")

@Composable
fun PantryScreen(
    vm: PantryViewModel,
    onOpenMeal: (Int) -> Unit,
    onOpenWeekPlan: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var showFocusSheet by remember { mutableStateOf(false) }
    var showWeekSheet by remember { mutableStateOf(false) }
    val catalog = vm.catalog

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            item { PantryHeader(state) }

            if (state.expiringSoon.isNotEmpty()) {
                item { UseItUpStrip(state.expiringSoon, onRemove = { vm.removeItem(it.id) }) }
            }

            item {
                MakeMealCta(
                    enabled = state.items.isNotEmpty() && !state.isLoadingMeals,
                    loading = state.isLoadingMeals,
                    onClick = { showFocusSheet = true },
                )
            }

            item {
                PlanWeekCta(
                    enabled = !state.isPlanningWeek,
                    loading = state.isPlanningWeek,
                    fromPantry = state.items.isNotEmpty(),
                    onClick = { showWeekSheet = true },
                )
            }

            state.weeklyPlan?.let { plan ->
                item {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader(
                        title = "Your week is planned",
                        subtitle = "${plan.nightsCount} dinners · ${Money.fmt(plan.totalCost)} of ${Money.fmt(plan.budget)} budget",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        action = "View",
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Box(
                        Modifier
                            .padding(horizontal = 20.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onOpenWeekPlan() }
                            .padding(16.dp),
                    ) {
                        Column {
                            Text(
                                "Mon–Sun · ${plan.nights.joinToString(" · ") { it.suggestion.recipe.name }}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SoftChip(
                                    text = if (plan.underBudget) "Under budget" else "Over by ${Money.fmt(plan.overshoot)}",
                                    bg = if (plan.underBudget) LocalThriveColors.current.leafSoft else LocalThriveColors.current.dealSoft,
                                    fg = MaterialTheme.colorScheme.onSurface,
                                )
                                SoftChip(
                                    text = "${plan.combinedShopping.size} items to buy",
                                    bg = MaterialTheme.colorScheme.surfaceVariant,
                                    fg = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (state.isLoadingMeals) {
                item { LoadingMealCards() }
            }

            state.suggestions?.let { suggestions ->
                item {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader(
                        title = "Dinner ideas from your pantry",
                        subtitle = "Tap a meal to see the full steps",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                items(suggestions.size, key = { it }) { index ->
                    SuggestionCard(
                        suggestion = suggestions[index],
                        onClick = { onOpenMeal(index) },
                        onDismiss = { vm.clearSuggestions() },
                    )
                }
            }

            if (state.items.isEmpty() && state.suggestions == null) {
                item { EmptyPantry(onAdd = { showAddSheet = true }) }
            }

            storageGroups.forEach { group ->
                val groupItems = state.forLocation(group)
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(
                        title = group,
                        subtitle = "${groupItems.size} item${if (groupItems.size == 1) "" else "s"}",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (groupItems.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing here yet",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                items(groupItems, key = { it.id }) { item ->
                    PantryItemRow(
                        item = item,
                        onChangeQuantity = { delta -> vm.changeQuantity(item.id, delta) },
                        onRemove = { vm.removeItem(item.id) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddSheet = true },
            containerColor = LocalThriveColors.current.deal,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = FloatingActionButtonDefaults.elevation(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add items", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }

    if (showAddSheet) {
        AddItemSheet(vm = vm, onDismiss = { showAddSheet = false })
    }
    if (showFocusSheet) {
        FocusSheet(vm = vm, onDismiss = { showFocusSheet = false })
    }
    if (showWeekSheet) {
        WeekPlanSheet(
            vm = vm,
            onDismiss = { showWeekSheet = false },
            onOpenPlan = onOpenWeekPlan,
        )
    }
}

@Composable
private fun PlanWeekCta(enabled: Boolean, loading: Boolean, fromPantry: Boolean, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) accents.goldSoft else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled) { onClick() }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) accents.gold else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (loading) Icons.Rounded.Schedule else Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (loading) "Planning your week…" else "Plan my week",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Text(
                    text = if (fromPantry) "7 dinners under a weekly budget, from your pantry"
                    else "Starts from scratch — 7 dinners under a weekly budget",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            if (!loading) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = if (enabled) accents.gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekPlanSheet(vm: PantryViewModel, onDismiss: () -> Unit, onOpenPlan: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accents = LocalThriveColors.current
    var people by remember { mutableStateOf(4) }
    var budgetText by remember { mutableStateOf("75") }
    var focus by remember { mutableStateOf("balanced") }
    val quickBudgets = listOf(50.0, 75.0, 100.0, 125.0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 560.dp),
        ) {
            Text("Plan my week", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Seven dinners, one budget, built around what's already in your kitchen.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("People", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { people = (people - 1).coerceAtLeast(1) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("−", style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary)) }
                Text(
                    text = "$people",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { people = (people + 1).coerceAtMost(12) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("+", style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary)) }
            }
            Spacer(Modifier.height(16.dp))

            Text("Weekly budget", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = budgetText,
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() || it == '.' }
                    if (cleaned.length <= 6) budgetText = cleaned
                },
                leadingIcon = { Text("$", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickBudgets.size) { i ->
                    val amount = quickBudgets[i]
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (budgetText.toDoubleOrNull() == amount) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { budgetText = "${amount.toInt()}" }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "$${amount.toInt()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (budgetText.toDoubleOrNull() == amount) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Plan style", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Balanced" to "balanced",
                    "Use expiring" to "use_expiring",
                    "Quick & easy" to "quick",
                ).forEach { (label, key) ->
                    SelectableChip(
                        text = label,
                        selected = focus == key,
                        onClick = { focus = key },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    val budget = budgetText.toDoubleOrNull()
                    if (budget != null && budget > 0) {
                        vm.generateWeeklyPlan(people = people, budget = budget, focus = focus)
                        onDismiss()
                        onOpenPlan()
                    }
                },
                enabled = (budgetText.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accents.gold),
            ) {
                Text("Build my week", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PantryHeader(state: PantryUiState) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(
            text = "Pantry",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = ThriveFont,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${state.totalCount} item${if (state.totalCount == 1) "" else "s"} stocked · " +
                if (state.expiringSoon.isNotEmpty()) "${state.expiringSoon.size} expiring soon" else "all fresh",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Composable
private fun UseItUpStrip(items: List<PantryItem>, onRemove: (PantryItem) -> Unit) {
    val accents = LocalThriveColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accents.goldSoft.copy(alpha = 0.55f))
            .padding(14.dp),
    ) {
        Text(
            text = "Use it up",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "These won't last — cook with them first.",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = Dates.pantryExpiry((((item.expiresAt ?: 0) - System.currentTimeMillis()) / 86400000L).toInt()),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = accents.deal,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MakeMealCta(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    val gradient = Brush.linearGradient(listOf(accents.berry, Color(0xFF7D5BFF)))
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(gradient)
            .clickable(enabled = enabled) { onClick() }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (loading) Icons.Rounded.Restaurant else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (loading) "Cooking up ideas…" else "Make me a meal",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = if (enabled) "AI turns what you have into dinner"
                    else "Add a few items to get started",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.85f)),
                )
            }
            if (!loading) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingMealCards() {
    Column(Modifier.padding(horizontal = 20.dp)) {
        repeat(2) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: MealSuggestion, onClick: () -> Unit, onDismiss: () -> Unit) {
    val accents = LocalThriveColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = suggestion.recipe.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftChip(
                text = "Uses ${suggestion.usesCount} of your items",
                bg = accents.leafSoft,
                fg = MaterialTheme.colorScheme.onSurface,
            )
            if (suggestion.expiringItemsUsed.isNotEmpty()) {
                SoftChip(text = "Saves ${suggestion.expiringItemsUsed.size} expiring", bg = accents.goldSoft, fg = MaterialTheme.colorScheme.onSurface)
            }
            SoftChip(
                text = "${suggestion.recipe.totalMinutes} min · ${Money.fmt(suggestion.recipe.costDollars)}",
                bg = MaterialTheme.colorScheme.surfaceVariant,
                fg = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (suggestion.usedItems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "You have: " + suggestion.usedItems.take(4).joinToString(", ") +
                    if (suggestion.usedItems.size > 4) " +${suggestion.usedItems.size - 4} more" else "",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (suggestion.missingItems.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Grab: " + suggestion.missingItems.take(4).joinToString(", ") { it.name } +
                    if (suggestion.missingItems.size > 4) " +${suggestion.missingItems.size - 4} more" else "" +
                    " (≈ ${Money.fmt(suggestion.estimatedExtraCost)})",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        suggestion.aiTip?.let { tip ->
            if (tip.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(accents.berrySoft.copy(alpha = 0.5f))
                        .padding(10.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accents.berry, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PantryItemRow(
    item: PantryItem,
    onChangeQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(item.category),
                contentDescription = null,
                tint = LocalThriveColors.current.deal,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.expiresAt?.let { expiry ->
                val days = ((expiry - System.currentTimeMillis()) / 86400000L).toInt()
                if (days <= 7) {
                    Text(
                        text = Dates.pantryExpiry(days),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (days <= 1) LocalThriveColors.current.deal else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        QuantityStepper(
            quantity = item.quantity,
            onMinus = { onChangeQuantity(-1) },
            onPlus = { onChangeQuantity(1) },
            compact = true,
        )
    }
}

@Composable
private fun EmptyPantry(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Kitchen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Your pantry is empty", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add what you have and Thrive will turn it into dinner.",
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LocalThriveColors.current.deal),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add your first items")
        }
    }
}

// ---------------------------------------------------------------------------
// Add item sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemSheet(vm: PantryViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val catalog = vm.catalog
    var query by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableStateOf(1) }
    var location by remember { mutableStateOf("Fridge") }
    var expiryDays by remember { mutableStateOf<Int?>(null) }

    val results = remember(query, catalog) {
        val q = query.trim()
        if (q.isBlank()) catalog.take(24)
        else catalog.filter { it.name.contains(q, ignoreCase = true) }.take(24)
    }
    val selected = catalog.firstOrNull { it.name == selectedName }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 640.dp),
        ) {
            Text(
                text = "Add to your pantry",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; selectedName = null },
                placeholder = { Text("Search items…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Spacer(Modifier.height(12.dp))

            if (selected == null) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 320.dp)) {
                    items(results, key = { it.name }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedName = item.name; qty = 1; location = item.location }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(categoryIcon(item.category), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                Text(
                                    text = "${item.category} · ${item.unit}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                )
                            }
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = LocalThriveColors.current.deal)
                        }
                    }
                }
            } else {
                SelectedItemConfig(
                    name = selected.name,
                    category = selected.category,
                    defaultUnit = selected.unit,
                    qty = qty,
                    onQty = { qty = it },
                    location = location,
                    onLocation = { location = it },
                    expiryDays = expiryDays,
                    onExpiry = { expiryDays = it },
                    onConfirm = {
                        vm.addItem(
                            name = selected.name,
                            category = selected.category,
                            location = location,
                            quantity = qty,
                            unit = selected.unit,
                            expiresInDays = expiryDays,
                        )
                        onDismiss()
                    },
                    onBack = { selectedName = null },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SelectedItemConfig(
    name: String,
    category: String,
    defaultUnit: String,
    qty: Int,
    onQty: (Int) -> Unit,
    location: String,
    onLocation: (String) -> Unit,
    expiryDays: Int?,
    onExpiry: (Int?) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val accents = LocalThriveColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.Close, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = "$category · ${if (defaultUnit.isBlank()) "item" else defaultUnit}",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quantity", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            QuantityStepper(quantity = qty, onMinus = { onQty((qty - 1).coerceAtLeast(1)) }, onPlus = { onQty(qty + 1) })
        }
        Spacer(Modifier.height(14.dp))
        Text("Where does it go?", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            storageGroups.forEach { group ->
                SelectableChip(
                    text = group,
                    selected = location == group,
                    onClick = { onLocation(group) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Expires in", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String, Int?>>("Fresh · 3d" to 3, "Week" to 7, "2 weeks" to 14, "Long shelf life" to null).forEach { (label, days) ->
                SelectableChip(
                    text = label,
                    selected = expiryDays == days,
                    onClick = { onExpiry(days) },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
        ) {
            Text("Add to pantry", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Focus sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusSheet(vm: PantryViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accents = LocalThriveColors.current
    val options = listOf(
        Triple("Surprise me", "Our best matches for what you have", "balanced"),
        Triple("Use up expiring items", "Cook with what's about to go bad first", "use_expiring"),
        Triple("Quick & easy", "Fastest meals from counter to table", "quick"),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("What should we cook?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tell Thrive what matters tonight.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(16.dp))
            options.forEach { (title, sub, key) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable {
                            vm.generateMeals(key)
                            onDismiss()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = accents.deal)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


