package com.thrive.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Egg
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.thrive.app.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.thrive.app.ui.theme.Berry
import com.thrive.app.ui.theme.BerrySoft
import com.thrive.app.ui.theme.DealCoral
import com.thrive.app.ui.theme.DealCoralSoft
import com.thrive.app.ui.theme.Emerald
import com.thrive.app.ui.theme.Gold
import com.thrive.app.ui.theme.GoldSoft
import com.thrive.app.ui.theme.LocalThriveColors
import com.thrive.app.ui.theme.TomatoSoft
import com.thrive.app.ui.theme.ThriveFont
import com.thrive.app.util.Money

// ---------------------------------------------------------------------------
// Price helpers
// ---------------------------------------------------------------------------

@Composable
fun PriceTag(price: Double, modifier: Modifier = Modifier, size: Int = 22) {
    Text(
        text = Money.fmt(price),
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = ThriveFont,
            fontWeight = FontWeight.ExtraBold,
            fontSize = size.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun StrikePrice(price: Double, modifier: Modifier = Modifier) {
    Text(
        text = Money.fmt(price),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
        ),
    )
}

@Composable
fun SavingsPill(percent: Int, modifier: Modifier = Modifier, filled: Boolean = true) {
    // No fake savings: a live regular-price item (no promo) gets no pill at all.
    if (percent <= 0) return
    val bg = if (filled) DealCoral else DealCoralSoft
    val fg = if (filled) Color.White else DealCoral
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "-$percent%",
            style = MaterialTheme.typography.labelMedium.copy(
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
fun NewPill(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Berry)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "NEW",
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}

@Composable
fun SoftChip(text: String, modifier: Modifier = Modifier, bg: Color = TomatoSoft, fg: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = fg,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Store avatar
// ---------------------------------------------------------------------------

@Composable
fun StoreAvatar(store: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = Color(com.thrive.app.util.StorePalette.color(store))
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = com.thrive.app.util.StorePalette.initials(store),
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.3).sp,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Category visual system
// ---------------------------------------------------------------------------

private val categoryGradients: Map<String, Pair<Color, Color>> = mapOf(
    "Grocery" to (Color(0xFF0D7C5F) to Color(0xFF35B07F)),
    "Dining" to (Color(0xFFE4572E) to Color(0xFFFF7A45)),
    "Essentials" to (Color(0xFF3D5A80) to Color(0xFF5B7CFA)),
    "Home" to (Color(0xFF44534C) to Color(0xFF7C8A83)),
    "Beauty" to (Color(0xFFB4539A) to Color(0xFFE07BC2)),
    "Health" to (Color(0xFF0F766E) to Color(0xFF14B8A6)),
    "Travel" to (Color(0xFF4A6FA5) to Color(0xFF7DA2D9)),
    "Produce" to (Color(0xFF3E9B4F) to Color(0xFF6FBF62)),
    "Meat" to (Color(0xFF9E3B33) to Color(0xFFC96A5B)),
    "Dairy" to (Color(0xFFB58A3C) to Color(0xFFE0B45C)),
    "Bakery" to (Color(0xFFC07830) to Color(0xFFE8A24C)),
    "Pantry" to (Color(0xFFB07A1F) to Color(0xFFF2A93B)),
    "Frozen" to (Color(0xFF3E7CB1) to Color(0xFF7FB3E0)),
    "Snacks" to (Color(0xFFC96A2E) to Color(0xFFF08C3E)),
    "Drinks" to (Color(0xFF2A8C9E) to Color(0xFF57C1D4)),
    "Condiments" to (Color(0xFF8A6D3B) to Color(0xFFC4A35A)),
    "Household" to (Color(0xFF5C6B64) to Color(0xFF8A9A92)),
    "Health & Beauty" to (Color(0xFF4A6FA5) to Color(0xFF7DA2D9)),
)

private fun categoryColors(category: String): Pair<Color, Color> =
    categoryGradients[category] ?: (Emerald to Color(0xFF35B07F))

private val categoryIcons: Map<String, ImageVector> = mapOf(
    "Grocery" to Icons.Rounded.ShoppingCart,
    "Dining" to Icons.Rounded.RestaurantMenu,
    "Essentials" to Icons.Rounded.Home,
    "Home" to Icons.Rounded.Home,
    "Beauty" to Icons.Rounded.Face,
    "Health" to Icons.Rounded.Favorite,
    "Travel" to Icons.Rounded.Flight,
    "Produce" to Icons.Rounded.Eco,
    "Meat" to Icons.Rounded.SetMeal,
    "Dairy" to Icons.Rounded.Egg,
    "Bakery" to Icons.Rounded.BakeryDining,
    "Pantry" to Icons.Rounded.Inventory,
    "Frozen" to Icons.Rounded.AcUnit,
    "Snacks" to Icons.Rounded.Cookie,
    "Drinks" to Icons.Rounded.LocalDrink,
    "Condiments" to Icons.Rounded.RestaurantMenu,
    "Household" to Icons.Rounded.CleaningServices,
    "Health & Beauty" to Icons.Rounded.Face,
)

fun categoryIcon(category: String): ImageVector =
    categoryIcons[category] ?: Icons.Rounded.ShoppingBag

// ---------------------------------------------------------------------------
// Food visuals (recipes) — varied appetizing gradients per recipe
// ---------------------------------------------------------------------------

private val foodGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFF0D7C5F) to Color(0xFF35B07F),
    Color(0xFFE4572E) to Color(0xFFFF7A45),
    Color(0xFFB07A1F) to Color(0xFFF2A93B),
    Color(0xFF9E3B33) to Color(0xFFC96A5B),
    Color(0xFFC07830) to Color(0xFFE8A24C),
    Color(0xFF3E9B4F) to Color(0xFF6FBF62),
    Color(0xFF8A6D3B) to Color(0xFFC4A35A),
    Color(0xFF3E7CB1) to Color(0xFF7FB3E0),
    Color(0xFF2A8C9E) to Color(0xFF57C1D4),
)

fun foodColors(seed: String?): Pair<Color, Color> {
    if (seed.isNullOrBlank()) return foodGradients[0]
    val idx = (seed.hashCode() % foodGradients.size + foodGradients.size) % foodGradients.size
    return foodGradients[idx]
}

/**
 * Recipe image panel: a real photo when one exists, otherwise a clean branded
 * gradient tile. No random stock photos — an unverified image is never shown.
 */
@Composable
fun FoodImage(
    seed: String?,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    corner: Dp = 0.dp,
    iconSize: Dp = 30.dp,
) {
    val (c1, c2) = foodColors(seed)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(c1, c2))),
    ) {
        Icon(
            imageVector = Icons.Rounded.RestaurantMenu,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.25f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(iconSize),
        )
        if (!imageUrl.isNullOrBlank()) {
            var failed by remember(imageUrl) { mutableStateOf(false) }
            if (!failed) {
                val ctx = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Bundled store logos (res/drawable-nodpi) — every retail chain the catalog
 * covers ships with its logo baked into the APK, so the logo shows instantly
 * and offline, never depending on the network. Maps a store name to its
 * drawable; null when the store isn't in the bundled set.
 */
object StoreLogos {
    fun drawable(store: String): Int? = when (store.lowercase()) {
        "aldi" -> R.drawable.store_logo_aldi
        "amazon" -> R.drawable.store_logo_amazon
        "apple" -> R.drawable.store_logo_apple
        "best buy" -> R.drawable.store_logo_best_buy
        "chick-fil-a" -> R.drawable.store_logo_chick_fil_a
        "chipotle" -> R.drawable.store_logo_chipotle
        "costco" -> R.drawable.store_logo_costco
        "cvs" -> R.drawable.store_logo_cvs
        "dollar general" -> R.drawable.store_logo_dollar_general
        "dollar tree" -> R.drawable.store_logo_dollar_tree
        "domino's" -> R.drawable.store_logo_dominos
        "ebay" -> R.drawable.store_logo_ebay
        "harbor freight" -> R.drawable.store_logo_harbor_freight
        "home depot" -> R.drawable.store_logo_home_depot
        "ikea" -> R.drawable.store_logo_ikea
        "kroger" -> R.drawable.store_logo_kroger
        "lowe's" -> R.drawable.store_logo_lowes
        "mcdonald's" -> R.drawable.store_logo_mcdonalds
        "newegg" -> R.drawable.store_logo_newegg
        "office depot" -> R.drawable.store_logo_office_depot
        "olive garden" -> R.drawable.store_logo_olive_garden
        "panera" -> R.drawable.store_logo_panera
        "pizza hut" -> R.drawable.store_logo_pizza_hut
        "sam's club" -> R.drawable.store_logo_sams_club
        "sephora" -> R.drawable.store_logo_sephora
        "staples" -> R.drawable.store_logo_staples
        "starbucks" -> R.drawable.store_logo_starbucks
        "subway" -> R.drawable.store_logo_subway
        "taco bell" -> R.drawable.store_logo_taco_bell
        "target" -> R.drawable.store_logo_target
        "trader joe's" -> R.drawable.store_logo_trader_joes
        "ulta" -> R.drawable.store_logo_ulta
        "walgreens" -> R.drawable.store_logo_walgreens
        "walmart" -> R.drawable.store_logo_walmart
        "whole foods" -> R.drawable.store_logo_whole_foods
        else -> null
    }
}

/**
 * Product image panel: the real product photo when one is verified; otherwise
 * the store's bundled logo (always renders, even offline); otherwise the
 * remote logo URL; otherwise a clean branded gradient tile with the category
 * icon. A failed load steps down to the next tier — never a blank box.
 */
@Composable
fun ProductImage(
    seed: String?,
    category: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    logoUrl: String? = null,
    store: String? = null,
    corner: Dp = 0.dp,
    iconSize: Dp = 36.dp,
) {
    val (c1, c2) = categoryColors(category)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(c1, c2))),
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(iconSize),
        )
        var photoFailed by remember(imageUrl) { mutableStateOf(false) }
        var logoFailed by remember(logoUrl) { mutableStateOf(false) }
        val bundledLogo = store?.let { StoreLogos.drawable(it) }
        val showRemote = when {
            !imageUrl.isNullOrBlank() && !photoFailed -> imageUrl
            bundledLogo == null && !logoUrl.isNullOrBlank() && !logoFailed -> logoUrl
            else -> null
        }
        when {
            !showRemote.isNullOrBlank() -> {
                val isPhoto = showRemote == imageUrl
                val ctx = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(showRemote)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = if (isPhoto) ContentScale.Crop else ContentScale.Fit,
                    onError = { if (isPhoto) photoFailed = true else logoFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            bundledLogo != null -> {
                Image(
                    painter = painterResource(bundledLogo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section headers
// ---------------------------------------------------------------------------

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, action: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = LocalThriveColors.current.deal,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Quantity stepper
// ---------------------------------------------------------------------------

@Composable
fun QuantityStepper(
    quantity: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val size = if (compact) 26.dp else 30.dp
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("-", onMinus, size)
        Text(
            text = "$quantity",
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
        StepperButton("+", onPlus, size)
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit, size: Dp) {
    // 48dp touch target with the smaller visible circle centered inside it.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

// Simple clickable helper (keeps steppers quiet)
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
