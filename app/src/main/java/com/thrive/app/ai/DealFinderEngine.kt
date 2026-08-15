package com.thrive.app.ai

import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.ShoppingItem
import java.util.Locale

/** Where a single shopping item is best bought. */
data class ResolvedItem(
    val item: ShoppingItem,
    val store: String,
    val price: Double,
    val dealFound: Boolean,
    val savings: Double,
    val dealId: String? = null,
    val unitMatched: Boolean = false,
    val unitLabel: String? = null,
    // The actual matched product, so the UI can show exactly what the deal is.
    val matchedName: String? = null,
    val dealBrand: String? = null,
    val dealSize: String? = null,
    val dealUrl: String? = null,
    val dealUrlVerified: Boolean = false,
    val dealImageUrl: String? = null,
    val dealEstimated: Boolean = true,
    val dealCategory: String? = null,
    val dealDistanceMi: Double? = null, // distance to nearest branch (location-aware sync)
)

/** A deal candidate with its effective price expressed in the item's own unit scale. */
data class DealMatch(
    val deal: Deal,
    val price: Double,
    val unitMatched: Boolean = false,
)

/** A cost-saving swap suggestion when the trip is over budget. */
data class SwapSuggestion(
    val itemName: String,
    val suggestion: String,
    val saves: Double,
)

/** One store's share of the trip: its items and the subtotal. */
data class StoreGroup(
    val store: String,
    val items: List<ResolvedItem>,
    val subtotal: Double,
    val storeDistanceMi: Double? = null, // nearest-branch distance when location shared
)

/** The complete deal-finding result for a shopping trip. */
data class TripPlan(
    val items: List<ResolvedItem>,
    val budget: Double,
    val people: Int,
    val totalBefore: Double,
    val totalAfter: Double,
    val totalSavings: Double,
    val storesUsed: List<Pair<String, Double>>,
    val storeGroups: List<StoreGroup>,
    val status: String,            // UNDER_BUDGET | OVER_BUDGET
    val overshoot: Double,
    val swaps: List<SwapSuggestion>,
    val perPersonCost: Double,
    val aiInsights: String? = null,
) {
    val remaining: Double get() = budget - totalAfter
    val isOverBudget: Boolean get() = status == "OVER_BUDGET"
}

/**
 * Local deal-finder engine. Matching is deliberately strict so we never pass
 * off a wrong product as the user's item:
 *  - every significant token of the item name must appear in the deal
 *    (productName + keywords), so "Organic Milk" never matches plain "Milk";
 *  - the categories must agree;
 *  - when both sides carry units, they must be in the same unit family and the
 *    comparison happens per unit (per lb / per oz / per can).
 * Anything that fails is honestly reported as "no deal" instead of a guess.
 */
object DealFinderEngine {

    /**
     * Words that change what a product IS when appended to a base noun, so a
     * plain "Milk" item never matches "Coconut Milk" or "Almond Milk". These
     * are only checked as EXTRAS: if the user's own item says "chocolate milk",
     * the token is in the item and the match is allowed.
     */
    private val IDENTITY_CHANGERS = setOf(
        "coconut", "almond", "soy", "oat", "rice", "cashew", "hemp", "macadamia",
        "plant", "vegan", "oatmilk", "oat-milk", "chocolate", "strawberry", "vanilla",
        "caramel", "mocha", "cinnamon", "maple", "honey", "bbq", "buffalo", "ranch",
        "teriyaki", "sriracha", "garlic", "onion", "chili", "curry", "tikka",
        "sausage", "bacon", "pepperoni", "cheese-stuffed", "breaded", "battered",
        "gluten-free", "keto", "low-fat", "fat-free", "skim", "2%", "whole-fat",
    )

    /** Size/quantity words that add no product identity — pure noise for matching. */
    private val SIZE_TOKENS = setOf(
        "1", "2", "3", "4", "5", "6", "10", "12", "14", "15", "16", "18", "20",
        "24", "28", "32", "38", "40", "42", "45", "48", "60", "64", "150",
        "lb", "lbs", "oz", "ct", "g", "kg", "gal", "floz", "ea", "each", "roll",
        "count", "bottle", "jar", "can", "box", "bag", "pack", "packet", "dozen",
        "loaf", "bunch", "head", "crown", "tub", "gallon", "ounce", "ounces",
        "pound", "pounds",
    )

    private fun tokens(s: String): Set<String> = s.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(" ")
        .filter { it.length > 1 && it !in SIZE_TOKENS }
        .toSet()

    /**
     * True when the deal genuinely covers the item: EVERY item token (including
     * qualifiers like "organic" or "boneless") appears in the deal name or its
     * keywords, and the categories agree. Size words are ignored on both sides.
     * This is deliberately strict — a plain "Milk" deal never matches a list item
     * for "Organic Milk", because "organic" must appear in the deal too.
     */
    private fun covers(item: ShoppingItem, deal: Deal): Boolean {
        val itemTokens = tokens(item.name)
        if (itemTokens.isEmpty()) return false
        val dealPool = tokens(deal.productName) + deal.keywords.flatMap { tokens(it) }
        if (dealPool.isEmpty()) return false
        if (!dealPool.containsAll(itemTokens)) return false
        // A deal may not add an identity-changing word the item never asked for:
        // "Milk" must not match "Coconut Milk" (coconut changes the product).
        val extras = dealPool - itemTokens
        if (extras.any { it in IDENTITY_CHANGERS }) return false
        // Category must agree unless the item carries no real category signal
        // (recipe-added items fall back to "Grocery", which is not a product class).
        val itemCat = item.category.trim().lowercase(Locale.US)
        val categoryOk = itemCat.isBlank() || itemCat == "grocery" ||
            deal.category.equals(item.category, ignoreCase = true)
        return categoryOk
    }

    /**
     * Picks the best deal for one item, comparing per-unit prices when both
     * sides carry comparable units, and falling back to whole-item prices.
     */
    fun bestDealFor(item: ShoppingItem, deals: List<Deal>): DealMatch? {
        val itemParsed = UnitParser.parseUnit(item.unit)
        val candidates = deals
            .filter { covers(item, it) }
            .mapNotNull { deal ->
                val dealParsed = UnitParser.parse(deal.size)
                if (itemParsed != null && dealParsed != null &&
                    itemParsed.family == dealParsed.family
                ) {
                    // Per-unit comparison within the same unit family.
                    val itemBasePer = item.estPrice / itemParsed.baseQty
                    val dealBasePer = deal.price / dealParsed.baseQty
                    if (dealBasePer < itemBasePer) {
                        DealMatch(
                            deal = deal,
                            price = dealBasePer * itemParsed.baseQty,
                            unitMatched = true,
                        )
                    } else {
                        // Deal isn't cheaper per unit; fall back to whole-price only if strictly better.
                        if (deal.price < item.estPrice) DealMatch(deal, deal.price)
                        else null
                    }
                } else {
                    if (deal.price < item.estPrice) DealMatch(deal, deal.price)
                    else null
                }
            }
        if (candidates.isEmpty()) return null
        // Prefer unit-matched deals, then the largest absolute savings, then match coverage.
        return candidates.maxWith(
            compareBy(
                { it.unitMatched },
                { (item.estPrice - it.price) * item.quantity },
                { matchCoverage(item, it.deal) },
            )
        )
    }

    /** How much of the item's identity the deal covers (higher = closer). */
    private fun matchCoverage(item: ShoppingItem, deal: Deal): Int {
        val itemTokens = tokens(item.name)
        val dealPool = tokens(deal.productName) + deal.keywords.flatMap { tokens(it) }
        return itemTokens.count { it in dealPool }
    }

    /**
     * Exposed for tests/UI: explains why an item did not match, so the UI can
     * say something truthful instead of "no deal" without reason.
     */
    fun whyNoMatch(item: ShoppingItem, deals: List<Deal>): String {
        val itemTokens = tokens(item.name)
        if (itemTokens.isEmpty()) return "The item name is too generic to search."
        // Would any deal pass every gate but the category? Then it's a category clash.
        val nameOk = deals.filter {
            val pool = tokens(it.productName) + it.keywords.flatMap { t -> tokens(t) }
            pool.containsAll(itemTokens)
        }
        if (nameOk.isNotEmpty()) {
            return "Found a product by name, but its category (${nameOk.first().category}) doesn't match your item."
        }
        return "No verified deal for this item right now."
    }

    fun plan(
        items: List<ShoppingItem>,
        deals: List<Deal>,
        budget: Double,
        people: Int,
    ): TripPlan {
        val resolved = items.map { item ->
            val match = bestDealFor(item, deals)
            if (match != null) {
                val d = match.deal
                ResolvedItem(
                    item = item,
                    store = d.store,
                    price = match.price,
                    dealFound = true,
                    savings = ((item.estPrice - match.price) * item.quantity).coerceAtLeast(0.0),
                    dealId = d.id,
                    unitMatched = match.unitMatched,
                    unitLabel = match.unitMatched.takeIf { it }?.let { d.unitPrice.ifBlank { null } },
                    matchedName = d.productName,
                    dealBrand = d.brand,
                    dealSize = d.size,
                    dealUrl = d.url,
                    dealUrlVerified = d.urlVerified,
                    dealImageUrl = d.imageUrl,
                    dealEstimated = d.estimated,
                    dealCategory = d.category,
                    dealDistanceMi = d.storeDistanceMi,
                )
            } else {
                ResolvedItem(item, "Any store", item.estPrice, false, 0.0)
            }
        }

        val totalBefore = resolved.sumOf { it.item.estPrice * it.item.quantity }
        val totalAfter = resolved.sumOf { it.price * it.item.quantity }
        val totalSavings = (totalBefore - totalAfter).coerceAtLeast(0.0)

        val storeTotals = resolved
            .groupBy { it.store }
            .mapValues { (_, v) -> v.sumOf { it.price * it.item.quantity } }
            .toList()
            .sortedByDescending { it.second }

        val storeGroups = resolved
            .groupBy { it.store }
            .map { (store, itemsInStore) ->
                StoreGroup(
                    store = store,
                    items = itemsInStore,
                    subtotal = itemsInStore.sumOf { it.price * it.item.quantity },
                    storeDistanceMi = itemsInStore.mapNotNull { it.dealDistanceMi }.minOrNull(),
                )
            }
            .sortedByDescending { it.subtotal }

        val isOver = totalAfter > budget
        val overshoot = (totalAfter - budget).coerceAtLeast(0.0)
        val perPerson = if (people > 0) totalAfter / people else totalAfter

        val swaps = buildSwapSuggestions(resolved, isOver)

        return TripPlan(
            items = resolved,
            budget = budget,
            people = people,
            totalBefore = totalBefore,
            totalAfter = totalAfter,
            totalSavings = totalSavings,
            storesUsed = storeTotals,
            storeGroups = storeGroups,
            status = if (isOver) "OVER_BUDGET" else "UNDER_BUDGET",
            overshoot = overshoot,
            swaps = swaps,
            perPersonCost = perPerson,
        )
    }

    private fun buildSwapSuggestions(
        resolved: List<ResolvedItem>,
        isOver: Boolean,
    ): List<SwapSuggestion> {
        val swaps = mutableListOf<SwapSuggestion>()
        // Name-brand groceries usually have a store-brand alternative ~30% cheaper.
        val nameBrandKeywords = listOf("kellogg", "post", "general mills", "heinz", "kraft",
            "coca", "pepsi", "frito", "lays", "oreo", "progresso", "campbell", "jif", "smucker")
        for (r in resolved) {
            val name = r.item.name.lowercase(Locale.US)
            if (nameBrandKeywords.any { name.contains(it) }) {
                val saves = r.price * r.item.quantity * 0.30
                swaps += SwapSuggestion(
                    r.item.name,
                    "Grab the store-brand version instead — same taste, a fraction of the price.",
                    saves,
                )
            }
        }
        if (!isOver && swaps.isNotEmpty()) return swaps.take(2)
        return swaps.take(3)
    }
}
