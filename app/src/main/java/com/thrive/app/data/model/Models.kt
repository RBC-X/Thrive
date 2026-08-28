package com.thrive.app.data.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Household profile / onboarding
// ---------------------------------------------------------------------------

@Serializable
enum class BudgetCadence { WEEKLY, MONTHLY }

/**
 * Preferences collected during first-run onboarding. This stays useful while
 * offline and is included in account backups when the user signs in.
 */
@Serializable
data class HouseholdProfile(
    val budgetAmount: Double = 0.0,
    val budgetCadence: BudgetCadence = BudgetCadence.WEEKLY,
    val householdSize: Int = 1,
    val appliances: Set<String> = emptySet(),
    val onboardingVersion: Int = 0,
    val onboardingCompletedAt: Long? = null,
) {
    val isOnboardingComplete: Boolean
        get() = onboardingVersion > 0 && onboardingCompletedAt != null

    fun normalized(): HouseholdProfile = copy(
        budgetAmount = budgetAmount.takeIf { it.isFinite() }?.coerceIn(0.0, 100_000.0) ?: 0.0,
        householdSize = householdSize.coerceIn(1, 20),
        appliances = appliances.map(String::trim)
            .filter { it in SUPPORTED_APPLIANCES }
            .toSet(),
        onboardingVersion = onboardingVersion.coerceAtLeast(0),
        onboardingCompletedAt = onboardingCompletedAt?.coerceAtLeast(0L),
    )
}

/** Stable values stored in profiles and sent to the server. */
val SUPPORTED_APPLIANCES: List<String> = listOf(
    "Stovetop",
    "Oven",
    "Microwave",
    "Air fryer",
    "Slow cooker",
    "Pressure cooker",
    "Blender",
    "Toaster oven",
    "Grill",
)

// ---------------------------------------------------------------------------
// Savings
// ---------------------------------------------------------------------------

@Serializable
data class Coupon(
    val id: String,
    val store: String,
    val title: String,
    val description: String,
    val category: String,          // Grocery | Dining | Essentials | Beauty | Health | Home | Travel
    val priceBefore: Double,
    val priceAfter: Double,
    val dealType: String = "LINK", // CODE | LINK | IN_STORE | PICKUP
    val code: String? = null,
    val url: String? = null,
    val brand: String? = null,      // product brand ("Great Value", "Equate", "Apple", ...) when known
    val endsInDays: Int,
    val isNew: Boolean = false,
    val terms: String = "",
    val imageSeed: String? = null,
    val imageUrl: String? = null,      // real product photo (verified) — null = fall back to store logo
    val storeLogoUrl: String? = null,  // verified store logo — shown when no product photo exists
    val urlVerified: Boolean = false,  // true only when url points at the exact product/offer
    val estimated: Boolean = true,     // prices are estimates from a curated feed, not live retail
) {
    val discountPercent: Int
        get() = if (priceBefore <= 0) 0
        else ((1 - priceAfter / priceBefore) * 100).toInt().coerceIn(0, 99)

    /** A deal worth highlighting: big cut + little time left. */
    val isHot: Boolean get() = discountPercent >= 45 && endsInDays <= 3
}

// ---------------------------------------------------------------------------
// Recipes
// ---------------------------------------------------------------------------

@Serializable
data class Ingredient(
    val name: String,
    val amount: String = "",
    val brand: String? = null,
    val optional: Boolean = false,
)

@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val description: String,
    val section: String,           // under_10 | under_20 | five_ingredients | family_favorites | one_pot
    val mealType: String = "Dinner",
    val tags: List<String> = emptyList(),
    // Appliances this recipe genuinely requires ("air fryer", "slow cooker").
    // Empty = basic stovetop/oven — always available, never a constraint.
    val requiredAppliances: List<String> = emptyList(),
    val prepMinutes: Int,
    val cookMinutes: Int,
    val servings: Int,
    val costDollars: Double,
    val difficulty: String = "Easy",
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val imageSeed: String? = null,
    val imageUrl: String? = null,  // real food photo (verified) — null = clean branded tile
    val featured: Boolean = false,
) {
    val totalMinutes: Int get() = prepMinutes + cookMinutes
    val costPerServing: Double get() = if (servings <= 0) costDollars else costDollars / servings
}

// ---------------------------------------------------------------------------
// Pantry
// ---------------------------------------------------------------------------

@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val category: String,
    val location: String,          // Fridge | Freezer | Pantry
    val quantity: Int = 1,
    val unit: String = "",
    val expiresAt: Long? = null,   // epoch millis
    val addedAt: Long = 0L,
)

// ---------------------------------------------------------------------------
// Shopping / Budget
// ---------------------------------------------------------------------------

@Serializable
data class ShoppingItem(
    val id: String,
    val name: String,
    val category: String,
    val quantity: Int = 1,
    val unit: String = "",
    val estPrice: Double,
    val checked: Boolean = false,
    val brand: String? = null,        // brand name when the user specified one
) {
    /** Size string built from the item's unit ("2 lb" → per-lb comparison). */
    val sizeString: String? get() = if (unit.isNotBlank() && unit != "item" && unit != "") "1 $unit" else null
}

@Serializable
data class BudgetState(
    val budget: Double = 0.0,
    val people: Int = 1,
    val items: List<ShoppingItem> = emptyList(),
)

// ---------------------------------------------------------------------------
// Deals (budget finder)
// ---------------------------------------------------------------------------

@Serializable
data class Deal(
    val id: String,
    val store: String,
    val productName: String,
    val category: String,
    val price: Double,
    val unitPrice: String = "",
    val savingsPercent: Int = 0,
    val keywords: List<String> = emptyList(),
    val endsInDays: Int = 7,
    val url: String? = null,
    val urlVerified: Boolean = false, // true only when url points at the exact product/offer
    val size: String? = null,     // e.g. "2 lb", "48 oz" — enables per-unit price comparison
    val brand: String? = null,    // brand of the product on offer
    val imageUrl: String? = null, // real product photo (verified) — null = clean fallback tile
    val estimated: Boolean = true,// price is an estimate from a curated feed, not live retail
    val storeDistanceMi: Double? = null, // distance to nearest branch (only when location shared)
    val storeCity: String? = null, // city of the nearest branch (location-aware sync)
)

// ---------------------------------------------------------------------------
// Catalog entries (adders)
// ---------------------------------------------------------------------------

@Serializable
data class CatalogItem(
    val name: String,
    val category: String,
    val unit: String = "",
    val defaultPrice: Double = 0.0,
    val isStaple: Boolean = false,
    val location: String = "Pantry",
)
