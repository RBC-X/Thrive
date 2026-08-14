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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

@Composable
fun MealStepsScreen(
    pantryVm: PantryViewModel,
    budgetVm: BudgetViewModel,
    index: Int,
    onBack: () -> Unit,
) {
    val state by pantryVm.state.collectAsState()
    val suggestion = state.suggestions?.getOrNull(index) ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Suggestion no longer available")
        }
        return
    }
    val recipe = suggestion.recipe
    val accents = LocalThriveColors.current
    val context = LocalContext.current
    var checkedSteps by remember { mutableStateOf(setOf<Int>()) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Box {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(
                            Brush.linearGradient(listOf(accents.berry, Color(0xFF7D5BFF)))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.size(110.dp),
                    )
                }
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.92f)),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Your pantry meal",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                        ),
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = recipe.description,
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftChip(
                        text = "${recipe.totalMinutes} min",
                        bg = MaterialTheme.colorScheme.surfaceVariant,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SoftChip(
                        text = Money.fmt(recipe.costDollars),
                        bg = MaterialTheme.colorScheme.surfaceVariant,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SoftChip(
                        text = "${recipe.servings} servings",
                        bg = MaterialTheme.colorScheme.surfaceVariant,
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(accents.leafSoft)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accents.leaf, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Uses ${suggestion.usesCount} item${if (suggestion.usesCount == 1) "" else "s"} you already have" +
                            if (suggestion.expiringItemsUsed.isNotEmpty())
                                " — including ${suggestion.expiringItemsUsed.joinToString(", ") { it.lowercase() }}"
                            else "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (suggestion.missingItems.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Text("Grab these (≈ ${Money.fmt(suggestion.estimatedExtraCost)})", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = suggestion.missingItems.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }

        suggestion.aiTip?.let { tip ->
            if (tip.isNotBlank()) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(accents.berrySoft.copy(alpha = 0.55f))
                                .padding(14.dp),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = accents.berry, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Chef's tip", style = MaterialTheme.typography.labelLarge.copy(color = accents.berry, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = tip,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(22.dp))
                Text("Step by step", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap a step to check it off",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(12.dp))
                recipe.steps.forEachIndexed { i, step ->
                    MealStepRow(
                        index = i + 1,
                        step = step,
                        checked = i in checkedSteps,
                        onToggle = {
                            checkedSteps = if (i in checkedSteps) checkedSteps - i else checkedSteps + i
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Button(
            onClick = {
                if (suggestion.missingItems.isNotEmpty()) {
                    suggestion.missingItems.forEach { missing ->
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
                        "Added ${suggestion.missingItems.size} missing items to your shopping list",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    Toast.makeText(context, "You have everything you need!", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accents.deal),
        ) {
            Icon(Icons.Rounded.AddShoppingCart, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (suggestion.missingItems.isNotEmpty())
                    "Add missing items to shopping list"
                else
                    "All set — happy cooking!",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
    }
}

@Composable
private fun MealStepRow(index: Int, step: String, checked: Boolean, onToggle: () -> Unit) {
    val accents = LocalThriveColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) accents.leafSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (checked) accents.leaf else accents.berry),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            } else {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = ThriveFont,
                    ),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = step,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (checked) FontWeight.Normal else FontWeight.Medium,
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
