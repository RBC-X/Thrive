package com.thrive.app.ui.recipes

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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalDining
import androidx.compose.material.icons.rounded.People
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrive.app.ai.PantryMealEngine
import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.Recipe
import com.thrive.app.ui.budget.BudgetViewModel
import com.thrive.app.ui.components.FoodImage
import com.thrive.app.ui.components.PriceTag
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

@Composable
fun RecipeDetailScreen(
    recipesVm: RecipesViewModel,
    budgetVm: BudgetViewModel,
    recipeId: String,
    onBack: () -> Unit,
    onAddToShoppingList: () -> Unit,
) {
    val state by recipesVm.state.collectAsState()
    val recipe = state.recipes.firstOrNull { it.id == recipeId } ?: return
    val isFavorite = recipe.id in state.favorites
    val context = LocalContext.current

    var checkedSteps by remember { mutableStateOf(setOf<Int>()) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        item {
            Box {
                FoodImage(
                    seed = recipe.imageSeed,
                    imageUrl = recipe.imageUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp),
                    iconSize = 56.dp,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent)
                            )
                        ),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.92f)),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(
                    onClick = { recipesVm.toggleFavorite(recipe.id) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.92f)),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) LocalThriveColors.current.deal else MaterialTheme.colorScheme.onSurface,
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(Icons.Rounded.Schedule, "${recipe.totalMinutes} min")
                    MetaChip(Icons.Rounded.LocalDining, Money.fmt(recipe.costDollars))
                    MetaChip(Icons.Rounded.People, "${recipe.servings} servings")
                    SoftChip(recipe.difficulty, bg = MaterialTheme.colorScheme.surfaceVariant, fg = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ingredients",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    SoftChip(
                        text = "≈ ${Money.fmt(recipe.costPerServing)} per serving",
                        bg = LocalThriveColors.current.leafSoft,
                        fg = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                recipe.ingredients.forEach { ing ->
                    IngredientRow(ing)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("How to make it", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap a step to check it off",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(12.dp))
                recipe.steps.forEachIndexed { index, step ->
                    StepRow(
                        index = index + 1,
                        step = step,
                        checked = index in checkedSteps,
                        onToggle = {
                            checkedSteps = if (index in checkedSteps) checkedSteps - index else checkedSteps + index
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
                var added = 0
                recipe.ingredients.forEach { ing ->
                    if (!PantryMealEngine.isStaple(ing.name)) {
                        budgetVm.addItem(
                            name = ing.name,
                            category = "Grocery",
                            quantity = 1,
                            unit = ing.amount,
                            estPrice = PantryMealEngine.estimateIngredientPrice(ing.name),
                        )
                        added++
                    }
                }
                Toast.makeText(context, "Added $added items to your shopping list", Toast.LENGTH_SHORT).show()
                onAddToShoppingList()
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LocalThriveColors.current.deal),
        ) {
            Icon(Icons.Rounded.AddShoppingCart, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Add ingredients to shopping list",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun IngredientRow(ingredient: Ingredient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = ingredient.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            if (!ingredient.brand.isNullOrBlank()) {
                Text(
                    text = "Try: ${ingredient.brand}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    ),
                )
            }
        }
        if (ingredient.amount.isNotBlank()) {
            Text(
                text = ingredient.amount,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun StepRow(index: Int, step: String, checked: Boolean, onToggle: () -> Unit) {
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
                .background(if (checked) accents.leaf else MaterialTheme.colorScheme.primary),
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
