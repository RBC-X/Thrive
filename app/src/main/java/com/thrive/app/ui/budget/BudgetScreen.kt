package com.thrive.app.ui.budget

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.ai.ResolvedItem
import com.thrive.app.ai.TripPlan
import com.thrive.app.data.model.ShoppingItem
import com.thrive.app.ui.components.QuantityStepper
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.components.StoreAvatar
import com.thrive.app.ui.components.categoryIcon
import androidx.compose.ui.platform.LocalContext
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

@Composable
fun BudgetScreen(vm: BudgetViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()

    when {
        !state.hasBudget -> BudgetOnboarding(vm)
        state.plan != null -> TripPlanView(vm, state.plan!!, onBack = { vm.clearPlanForEdit() })
        else -> ShoppingListScreen(vm, onOpenSettings)
    }
}

// ---------------------------------------------------------------------------
// Onboarding
// ---------------------------------------------------------------------------

@Composable
private fun BudgetOnboarding(vm: BudgetViewModel) {
    val state by vm.state.collectAsState()
    val accents = LocalThriveColors.current
    var budgetText by remember { mutableStateOf("") }
    val quickAmounts = listOf(40.0, 75.0, 100.0, 150.0)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = "Budget",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ThriveFont,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Plan the trip, beat the store, keep the change.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }

        item {
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF0B6E4F), Color(0xFF128F6A)))
                    )
                    .padding(24.dp),
            ) {
                Column {
                    Text(
                        text = "Let's plan your grocery trip",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Two quick questions, then we'll hunt down the best deals for your list.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.85f)),
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(26.dp))
                Text(
                    text = "How much can you spend?",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "For this whole shopping trip",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickAmounts) { amount ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (budgetText.toDoubleOrNull() == amount) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { budgetText = if (amount % 1.0 == 0.0) amount.toInt().toString() else amount.toString() }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
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
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.People, contentDescription = null, tint = accents.deal)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "How many people are you shopping for?",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.setPeople(state.people - 1) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        text = "${state.people}",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = ThriveFont,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.setPeople(state.people + 1) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = if (state.people <= 2) "small crew" else if (state.people <= 4) "family size" else "big crowd",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                Spacer(Modifier.height(30.dp))
                Button(
                    onClick = {
                        val value = budgetText.toDoubleOrNull()
                        if (value != null && value > 0) {
                            vm.setBudget(value)
                        }
                    },
                    enabled = (budgetText.toDoubleOrNull() ?: 0.0) > 0,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
                ) {
                    Text(
                        text = "Build my shopping list",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// List building
// ---------------------------------------------------------------------------

@Composable
private fun ShoppingListScreen(vm: BudgetViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    val accents = LocalThriveColors.current
    var showAddSheet by remember { mutableStateOf(false) }

    val grouped = state.items.groupBy { it.category }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 130.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Budget",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = ThriveFont,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                        Text(
                            text = "Shopping for ${state.people} · ${Money.fmt(state.budget)} budget",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { BudgetProgressCard(state) }

            if (state.items.isEmpty()) {
                item { EmptyList(onAdd = { showAddSheet = true }) }
            } else {
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${state.itemsCount} item${if (state.itemsCount == 1) "" else "s"} on your list",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                        )
                        TextButton(onClick = { showAddSheet = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Add items", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            grouped.forEach { (category, items) ->
                item {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(items, key = { it.id }) { item ->
                    ShoppingItemRow(
                        item = item,
                        onChangeQuantity = { delta -> vm.changeQuantity(item.id, delta) },
                        onRemove = { vm.removeItem(item.id) },
                        onToggle = { vm.toggleChecked(item.id) },
                    )
                }
            }
        }

        if (state.items.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Est. total  ${Money.fmt(state.currentTotal)}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "of ${Money.fmt(state.budget)}",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.findDeals() },
                        enabled = !state.isPlanning,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accents.berry),
                    ) {
                        if (state.isPlanning) {
                            Text("Finding the best prices…")
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Find me the best deals",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddShoppingSheet(vm = vm, onDismiss = { showAddSheet = false })
    }
}

@Composable
private fun BudgetProgressCard(state: BudgetUiState) {
    val accents = LocalThriveColors.current
    val ratio = if (state.budget > 0) (state.currentTotal / state.budget).toFloat() else 0f
    val over = ratio > 1f
    val barColor = when {
        over -> accents.deal
        ratio > 0.8f -> accents.gold
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Budget so far", style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                Text(
                    text = Money.fmt(state.currentTotal),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                )
            }
            SoftChip(
                text = if (over) "Over by ${Money.fmt(state.currentTotal - state.budget)}" else "${Money.fmt(state.budget - state.currentTotal)} left",
                bg = if (over) accents.dealSoft else accents.leafSoft,
                fg = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItem,
    onChangeQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
    onToggle: () -> Unit,
) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (item.checked) accents.leaf else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(categoryIcon(item.category), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Money.fmt(item.estPrice),
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        QuantityStepper(
            quantity = item.quantity,
            onMinus = { onChangeQuantity(-1) },
            onPlus = { onChangeQuantity(1) },
            compact = true,
        )
    }
}

@Composable
private fun EmptyList(onAdd: () -> Unit) {
    val accents = LocalThriveColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Build your shopping list", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add what you need — Thrive will find where it's cheapest.",
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add items")
        }
    }
}

// ---------------------------------------------------------------------------
// Add shopping items sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddShoppingSheet(vm: BudgetViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val catalog = vm.catalog
    var query by remember { mutableStateOf("") }

    val results = remember(query, catalog) {
        val q = query.trim()
        if (q.isBlank()) catalog.take(24)
        else catalog.filter { it.name.contains(q, ignoreCase = true) }.take(24)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 640.dp),
        ) {
            Text("Add to shopping list", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
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
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(results, key = { it.name }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                vm.addItem(
                                    name = item.name,
                                    category = item.category,
                                    quantity = 1,
                                    unit = item.unit,
                                    estPrice = if (item.defaultPrice > 0) item.defaultPrice else 2.0,
                                )
                                onDismiss()
                            }
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
                                text = "${item.category} · ${Money.fmt(if (item.defaultPrice > 0) item.defaultPrice else 2.0)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = LocalThriveColors.current.deal)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Trip plan results
// ---------------------------------------------------------------------------

@Composable
private fun TripPlanView(vm: BudgetViewModel, plan: TripPlan, onBack: () -> Unit) {
    val accents = LocalThriveColors.current
    val headerGradient = if (plan.isOverBudget)
        Brush.linearGradient(listOf(Color(0xFFC2410C), Color(0xFFE4572E)))
    else
        Brush.linearGradient(listOf(Color(0xFF0B6E4F), Color(0xFF128F6A)))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerGradient)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Edit list", tint = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Your deal plan",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "You'll spend",
                            style = MaterialTheme.typography.labelMedium.copy(color = Color.White.copy(alpha = 0.85f)),
                        )
                        Text(
                            text = Money.fmt(plan.totalAfter),
                            style = MaterialTheme.typography.displayMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                        Text(
                            text = "was ${Money.fmt(plan.totalBefore)} · for ${plan.people}",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.85f)),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        SoftChip(
                            text = if (plan.isOverBudget) "Over by ${Money.fmt(plan.overshoot)}" else "${Money.fmt(plan.remaining)} to spare",
                            bg = Color.White.copy(alpha = 0.2f),
                            fg = Color.White,
                        )
                        Spacer(Modifier.height(6.dp))
                        SoftChip(
                            text = "Save ${Money.fmt(plan.totalSavings)} with deals",
                            bg = Color.White.copy(alpha = 0.2f),
                            fg = Color.White,
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (plan.totalSavings > 0) accents.leafSoft else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (plan.totalSavings > 0) Icons.Rounded.CheckCircle else Icons.Rounded.Storefront,
                    contentDescription = null,
                    tint = if (plan.totalSavings > 0) accents.leaf else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (plan.totalSavings > 0)
                            "Found deals on ${plan.items.count { it.dealFound }} of ${plan.items.size} items"
                        else
                            "No deals matched your list yet",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = if (plan.totalSavings > 0)
                            "Shop ${plan.storesUsed.size} place${if (plan.storesUsed.size == 1) "" else "s"} to get every deal"
                        else
                            "We only match verified offers — unmatched items show their estimate below.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }

        if (plan.storeGroups.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Trip by store",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    text = "Complete totals per store, including unmatched items at estimate.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            items(plan.storeGroups.size, key = { "store-" + plan.storeGroups[it].store }) { i ->
                val group = plan.storeGroups[i]
                StoreGroupCard(group)
            }
            item {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Complete trip total",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Money.fmt(plan.totalAfter),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }

        plan.aiInsights?.let { insight ->
            if (insight.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(accents.berrySoft.copy(alpha = 0.55f))
                            .padding(14.dp),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accents.berry, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI coach", style = MaterialTheme.typography.labelLarge.copy(color = accents.berry, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = insight,
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            )
                        }
                    }
                }
            }
        }

        if (plan.swaps.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Swap to save more",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            items(plan.swaps) { swap ->
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
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accents.goldSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = accents.gold, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(swap.itemName, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = swap.suggestion,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    SoftChip(
                        text = "save ${Money.fmt(swap.saves)}",
                        bg = accents.goldSoft,
                        fg = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Item by item",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        items(plan.items, key = { it.item.id }) { resolved ->
            ResolvedItemRow(resolved, onToggle = { vm.toggleChecked(resolved.item.id) })
        }

        item {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit list")
                }
                OutlinedButton(
                    onClick = {
                        vm.clearItems()
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New trip")
                }
            }
        }
    }
}

@Composable
private fun StoreGroupCard(group: com.thrive.app.ai.StoreGroup) {
    val accents = LocalThriveColors.current
    val found = group.items.count { it.dealFound }
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.thrive.app.ui.components.StoreAvatar(group.store, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.store,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "${group.items.size} item${if (group.items.size == 1) "" else "s"} · $found with deals",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Money.fmt(group.subtotal),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                )
                if (found > 0) {
                    val saved = group.items.sumOf { it.savings }
                    Text(
                        text = "save ${Money.fmt(saved)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = accents.leaf,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(4.dp))
        group.items.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = r.item.name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (r.dealFound) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (r.dealFound) FontWeight.Medium else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (r.dealFound && r.matchedName != null && r.matchedName != r.item.name) {
                    Text(
                        text = r.matchedName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.9f),
                    )
                }
                Text(
                    text = Money.fmt(r.price * r.item.quantity),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun ResolvedItemRow(resolved: ResolvedItem, onToggle: () -> Unit) {
    val accents = LocalThriveColors.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (resolved.item.checked) accents.leaf else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (resolved.item.checked) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = resolved.item.name.replaceFirstChar { it.uppercase() } +
                    if (resolved.item.quantity > 1) " ×${resolved.item.quantity}" else "",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (resolved.item.checked) TextDecoration.LineThrough else null,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    text = resolved.store,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                if (resolved.unitMatched) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = resolved.unitLabel?.let { "· $it" } ?: "· per ${resolved.item.unit}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = LocalThriveColors.current.leaf,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                if (resolved.dealEstimated) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "est.",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        ),
                    )
                }
            }
            if (resolved.dealFound) {
                resolved.matchedName?.let { matched ->
                    if (matched != resolved.item.name) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Matched: $matched" +
                                (resolved.dealSize?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            maxLines = 2,
                        )
                    }
                }
                if (resolved.dealUrl != null && resolved.dealUrlVerified) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "View deal ↗",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = accents.deal,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(resolved.dealUrl))
                                )
                            }
                        },
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "In-store offer · no online link",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No verified deal — check in store",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = accents.gold,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        if (resolved.dealFound) {
            Text(
                text = Money.fmt(resolved.item.estPrice),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                ),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = Money.fmt(resolved.price * resolved.item.quantity),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        if (resolved.dealFound) {
            Spacer(Modifier.width(6.dp))
            SoftChip(
                text = "save ${Money.fmt(resolved.savings)}",
                bg = accents.leafSoft,
                fg = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
