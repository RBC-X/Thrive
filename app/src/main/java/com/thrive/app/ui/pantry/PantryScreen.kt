package com.thrive.app.ui.pantry

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.thrive.app.ai.GeneratedRecipe
import com.thrive.app.ai.MealSuggestion
import com.thrive.app.ai.PlanIntentParser
import com.thrive.app.ai.ParsedPlanRequest
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.remote.WebRecipeResult
import com.thrive.app.data.remote.WebSearchState
import com.thrive.app.ui.components.FoodImage
import com.thrive.app.ui.components.QuantityStepper
import com.thrive.app.ui.components.SectionHeader
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.components.categoryIcon
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Dates
import com.thrive.app.util.Money
import java.net.URI

private val storageGroups = listOf("Fridge", "Freezer", "Pantry")

@Composable
fun PantryScreen(
    vm: PantryViewModel,
    budgetVm: BudgetViewModel,
    onOpenMeal: (Int) -> Unit,
    onOpenWeekPlan: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var showFocusSheet by remember { mutableStateOf(false) }
    var showWeekSheet by remember { mutableStateOf(false) }
    var showWebSheet by remember { mutableStateOf(false) }
    var addedNote by remember { mutableStateOf<String?>(null) }
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

            item {
                MakeNewRecipeCta(
                    enabled = state.items.isNotEmpty() && !state.isGeneratingRecipe,
                    loading = state.isGeneratingRecipe,
                    onClick = { vm.generateNewRecipe() },
                )
            }

            addedNote?.let { note ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            state.generatedRecipe?.let { gen ->
                item {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader(
                        title = "Your new recipe",
                        subtitle = "Created on-device from your pantry — works offline",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        action = "Hide",
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item {
                    GeneratedRecipeCard(
                        gen = gen,
                        onDismiss = { vm.clearGeneratedRecipe() },
                        onTryAnother = { vm.tryAnotherRecipe() },
                        onAccept = {
                            val toBuy = vm.acceptRecipe()
                            toBuy.forEach { (name, category, _) ->
                                budgetVm.addItem(name = name, category = category, quantity = 1, unit = "", estPrice = 0.0)
                            }
                            addedNote = if (toBuy.isEmpty()) "Saved — everything you need is already in your pantry."
                            else "Added ${toBuy.size} missing item${if (toBuy.size == 1) "" else "s"} to your shopping list."
                        },
                    )
                }
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
                        onWebSearch = {
                            vm.searchWebFor(suggestions[index].recipe.name)
                            showWebSheet = true
                        },
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
    if (showWebSheet) {
        WebSearchSheet(
            search = state.webSearch,
            onRetry = { (state.webSearch as? WebSearchState.Error)?.let { vm.searchWebFor(it.query) } },
            onDismiss = {
                vm.clearWebSearch()
                showWebSheet = false
            },
        )
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
    var people by remember { mutableIntStateOf(4) }
    var budgetText by remember { mutableStateOf("75") }
    var focus by remember { mutableStateOf("balanced") }
    var dinners by remember { mutableIntStateOf(7) }
    var restrictions by remember { mutableStateOf<List<String>>(emptyList()) }
    var maxCook by remember { mutableIntStateOf(0) }
    var appliances by remember { mutableStateOf<Set<String>>(emptySet()) }
    var intent by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParsedPlanRequest?>(null) }
    val applianceOptions = listOf("Air fryer", "Slow cooker", "Oven", "Stovetop", "Microwave")
    val quickBudgets = listOf(50.0, 75.0, 100.0, 125.0)
    val restrictionOptions = listOf(
        "Peanut" to "peanut", "Tree nuts" to "nuts", "Shellfish" to "shellfish", "Dairy" to "dairy",
        "Gluten" to "gluten", "Eggs" to "eggs", "Soy" to "soy", "Pork" to "pork",
        "Vegetarian" to "vegetarian", "Vegan" to "vegan",
    )
    val cookOptions = listOf("Any" to 0, "30 min" to 30, "45 min" to 45, "60 min" to 60)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 660.dp)
                // The sheet content is taller than 660dp on most phones —
                // without this, the appliance chips and "Build my week" button
                // below the fold are unreachable (clipped, no scroll).
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Plan my week", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Type what you need, or set it below. The week is built around what's already in your kitchen.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = intent,
                onValueChange = { intent = it; parsed = null },
                placeholder = { Text("e.g. dinner for two, five nights, under \u002470, no dairy, quick") },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val p = PlanIntentParser.parse(intent)
                        parsed = p
                        val r = p.request
                        people = r.people
                        if (r.budget > 0) budgetText = r.budget.toInt().toString()
                        focus = r.focus
                        dinners = r.nights
                        restrictions = r.restrictions
                        maxCook = r.maxCookMinutes
                        appliances = r.appliances
                    },
                    enabled = intent.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Understand this", fontWeight = FontWeight.Bold)
                }
                parsed?.let { p ->
                    Text(
                        text = p.understood,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            parsed?.notes?.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall.copy(color = accents.deal, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dinners", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 7).forEach { n ->
                        SelectableChip(text = "$n", selected = dinners == n, onClick = { dinners = n })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("People", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Decrease people"
                            role = Role.Button
                        }
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
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Increase people"
                            role = Role.Button
                        }
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
                    val selected = budgetText.toDoubleOrNull() == amount
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { budgetText = "${amount.toInt()}" },
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$${amount.toInt()}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    "High protein" to "high_protein",
                    "Cheap" to "cheap",
                ).forEach { (label, key) ->
                    SelectableChip(
                        text = label,
                        selected = focus == key,
                        onClick = { focus = key },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Avoid", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(restrictionOptions.size) { i ->
                    val (label, key) = restrictionOptions[i]
                    SelectableChip(
                        text = label,
                        selected = key in restrictions,
                        onClick = {
                            restrictions = if (key in restrictions) restrictions - key else restrictions + key
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Max cook time", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cookOptions.size) { i ->
                    val (label, minutes) = cookOptions[i]
                    SelectableChip(
                        text = label,
                        selected = maxCook == minutes,
                        onClick = { maxCook = minutes },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("I have", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(applianceOptions.size) { i ->
                    val name = applianceOptions[i]
                    SelectableChip(
                        text = name,
                        selected = name.lowercase() in appliances,
                        onClick = {
                            appliances = if (name.lowercase() in appliances) appliances - name.lowercase()
                            else appliances + name.lowercase()
                        },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    val budget = budgetText.toDoubleOrNull()
                    if (budget != null && budget > 0) {
                        vm.generateWeeklyPlan(
                            people = people,
                            budget = budget,
                            nights = dinners,
                            focus = focus,
                            restrictions = restrictions,
                            maxCookMinutes = maxCook,
                            appliances = appliances,
                            requestSummary = parsed?.understood?.takeIf { it.isNotBlank() },
                        )
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
                    IconButton(onClick = { onRemove(item) }) {
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
private fun MakeNewRecipeCta(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    val gradient = Brush.linearGradient(listOf(accents.leaf, Color(0xFF2FA87B)))
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 2.dp)
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
                    text = if (loading) "Writing a brand-new recipe…" else "Make me a NEW recipe",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = if (enabled) "On-device AI writes a fresh dinner from your pantry"
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
private fun GeneratedRecipeCard(
    gen: GeneratedRecipe,
    onDismiss: () -> Unit,
    onTryAnother: () -> Unit,
    onAccept: () -> Unit,
) {
    val accents = LocalThriveColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp),
    ) {
        FoodImage(
            seed = gen.recipe.imageSeed,
            imageUrl = gen.recipe.imageUrl,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            corner = 16.dp,
            iconSize = 42.dp,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SoftChip(
                    text = "✦ On-device AI",
                    bg = accents.leafSoft,
                    fg = accents.leaf,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = gen.recipe.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Hide recipe")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = gen.recipe.description,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftChip(
                text = "${gen.recipe.totalMinutes} min",
                bg = MaterialTheme.colorScheme.surfaceVariant,
                fg = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SoftChip(
                text = Money.fmt(gen.recipe.costDollars),
                bg = accents.dealSoft,
                fg = accents.deal,
            )
            SoftChip(
                text = "${gen.recipe.servings} servings",
                bg = MaterialTheme.colorScheme.surfaceVariant,
                fg = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Uses: ${gen.usedItems.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
        if (gen.missingItems.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "You'd need: ${gen.missingItems.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "How to make it",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        gen.recipe.steps.forEachIndexed { i, step ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "${i + 1}.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = accents.deal),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (gen.missingToBuy.isEmpty()) "Looks good — keep it"
                else "Looks good — add missing items to list",
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTryAnother,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Try another recipe", fontWeight = FontWeight.Bold)
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
private fun SuggestionCard(
    suggestion: MealSuggestion,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onWebSearch: () -> Unit,
) {
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
            IconButton(onClick = onDismiss) {
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
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onWebSearch,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find this online", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Web-discovered leads",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
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
        IconButton(onClick = onRemove) {
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
    var qty by remember { mutableIntStateOf(1) }
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
    // Scrollable so the confirm button stays reachable at large font scales
    // (the sheet clamps its height, and an un-scrollable config would clip the
    // action below the fold).
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
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
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
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
// Web search sheet — Exa-backed discovery for meal ideas
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchSheet(
    search: WebSearchState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Web ideas",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            when (search) {
                is WebSearchState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Searching the web for \"${search.query}\"…",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
                is WebSearchState.Error -> {
                    Text(search.message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRetry, modifier = Modifier.height(48.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Try again")
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                            Text("Close")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                is WebSearchState.Results -> {
                    Text(
                        text = search.label,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "\"${search.query}\"",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (search.results.isEmpty()) {
                        Text(
                            text = search.note ?: "No web results for this meal — try a different name.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    } else {
                        search.results.forEach { result ->
                            WebResultRow(
                                result = result,
                                onOpen = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, result.url.toUri()))
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                WebSearchState.Idle -> { /* sheet only opens while a search is in flight or done */ }
            }
        }
    }
}

@Composable
private fun WebResultRow(result: WebRecipeResult, onOpen: () -> Unit) {
    val accents = LocalThriveColors.current
    val host = remember(result.url) {
        runCatching { URI(result.url).host?.removePrefix("www.") }.getOrNull() ?: result.url
    }
    val date = remember(result.publishedDate) {
        val raw = result.publishedDate?.trim()
        if (raw.isNullOrBlank()) null
        else if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') raw.take(10)
        else raw.take(30)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(host, date).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.excerpt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.excerpt,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            SoftChip(
                text = "Web-discovered" + if (result.confidence > 0.5) " · strong lead" else "",
                bg = accents.goldSoft,
                fg = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = null,
            tint = accents.deal,
            modifier = Modifier.size(20.dp),
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


