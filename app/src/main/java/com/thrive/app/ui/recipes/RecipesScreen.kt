package com.thrive.app.ui.recipes

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.thrive.app.data.model.Recipe
import com.thrive.app.ui.components.FoodImage
import com.thrive.app.ui.components.SectionHeader
import com.thrive.app.ui.components.SoftChip
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

@Composable
fun RecipesScreen(
    vm: RecipesViewModel,
    onOpenRecipe: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val results = state.searchResults(query)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    text = "Recipes",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = ThriveFont,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Family meals that love your budget",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = { Text("Search meals, tags, or ingredients") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                ),
            )
        }

        if (query.isNotBlank()) {
            item {
                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = if (results.isEmpty()) "No matches" else "${results.size} match${if (results.size == 1) "" else "es"}",
                    subtitle = null,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(results, key = { it.id }) { recipe ->
                RecipeRow(recipe, recipe.id in state.favorites, onOpen = { onOpenRecipe(recipe.id) }, onFavorite = { vm.toggleFavorite(recipe.id) })
            }
            return@LazyColumn
        }

        if (state.featured.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.featured, key = { it.id }) { recipe ->
                        FeaturedRecipeCard(
                            recipe = recipe,
                            onClick = { onOpenRecipe(recipe.id) },
                        )
                    }
                }
            }
        }

        state.sections.forEach { section ->
            val recipes = state.forSection(section.key)
            if (recipes.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(
                        title = section.title,
                        subtitle = section.subtitle,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recipes, key = { it.id }) { recipe ->
                            RecipeCard(
                                recipe = recipe,
                                isFavorite = recipe.id in state.favorites,
                                onOpen = { onOpenRecipe(recipe.id) },
                                onFavorite = { vm.toggleFavorite(recipe.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedRecipeCard(recipe: Recipe, onClick: () -> Unit) {
    val accents = LocalThriveColors.current
    Box(
        modifier = Modifier
            .width(250.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        FoodImage(
            seed = recipe.imageSeed,
            imageUrl = recipe.imageUrl,
            modifier = Modifier.fillMaxSize(),
            corner = 24.dp,
            iconSize = 44.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
        ) {
            SoftChip(
                text = "★ Thrive pick",
                bg = accents.gold.copy(alpha = 0.92f),
                fg = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${recipe.totalMinutes} min · ${Money.fmt(recipe.costDollars)} · ${recipe.servings} servings",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.9f)),
            )
        }
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen),
    ) {
        Box {
            FoodImage(
                seed = recipe.imageSeed,
                imageUrl = recipe.imageUrl,
                corner = 20.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                iconSize = 30.dp,
            )
            IconButton(
                onClick = onFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(30.dp)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) LocalThriveColors.current.deal else Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${recipe.totalMinutes}m",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Money.fmt(recipe.costDollars),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = LocalThriveColors.current.deal,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${recipe.servings} sv",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun RecipeRow(
    recipe: Recipe,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodImage(
            seed = recipe.imageSeed,
            imageUrl = recipe.imageUrl,
            corner = 14.dp,
            modifier = Modifier.size(64.dp),
            iconSize = 20.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${recipe.totalMinutes} min · ${Money.fmt(recipe.costDollars)} · ${recipe.servings} servings",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorite) LocalThriveColors.current.deal else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
