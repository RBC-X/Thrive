package com.thrive.app.ai

import java.util.Locale

/**
 * A normalized planning request. All fields have sane defaults so the parser
 * (and any caller) can fill in only what is known; the planner never sees a
 * half-valid request.
 */
data class PlanRequest(
    val people: Int = 4,
    val nights: Int = 7,
    val budget: Double = 75.0,
    val focus: String = "balanced",          // balanced | quick | cheap | use_expiring | high_protein
    val preferredStore: String? = null,
    val appliances: Set<String> = emptySet(), // air fryer | slow cooker | oven | stovetop | microwave
    val restrictions: List<String> = emptyList(), // peanut | nuts | shellfish | dairy | gluten | eggs | soy | pork | vegetarian | vegan
    val maxCookMinutes: Int = 0,             // 0 = no limit
) {
    val summary: String
        get() {
            val parts = mutableListOf(
                "$nights dinner${if (nights == 1) "" else "s"}",
                if (people == 1) "1 person" else "$people people",
            )
            if (budget > 0) parts += "≈ \$${budget.toInt()} budget"
            parts += when (focus) {
                "quick" -> "quick & easy"
                "cheap" -> "budget-friendly"
                "use_expiring" -> "use what's expiring"
                "high_protein" -> "high protein"
                else -> "balanced"
            }
            preferredStore?.let { parts += "at $it" }
            if (maxCookMinutes > 0) parts += "${maxCookMinutes}-min max"
            restrictions.forEach { parts += "no $it" }
            return parts.joinToString(" · ")
        }
}

/** What [PlanIntentParser] understood from a sentence, and what it didn't. */
data class ParsedPlanRequest(
    val request: PlanRequest,
    val matched: List<String>,
    val notes: List<String>,
) {
    val understood: String
        get() = if (matched.isEmpty()) "I couldn't find any details in that — try the controls below."
        else matched.joinToString(" · ")
}

/**
 * Deterministic intent parser: turns a plain-English planning sentence into a
 * [PlanRequest]. Pure Kotlin, fully offline, zero API cost. It only fills the
 * fields it can honestly detect — everything else keeps its default and the
 * editable controls below the field let the user fix the rest.
 *
 * Handles: people, night count, budget, plan style, a preferred store,
 * appliances, common allergies/restrictions, and max cook time.
 */
object PlanIntentParser {

    private val LOCALE = Locale.US
    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    private val STORE_NAMES = mapOf(
        "aldi" to "Aldi", "walmart" to "Walmart", "kroger" to "Kroger",
        "target" to "Target", "costco" to "Costco", "whole foods" to "Whole Foods",
        "trader joe" to "Trader Joe's", "trader joes" to "Trader Joe's",
        "publix" to "Publix", "wegmans" to "Wegmans", "heb" to "H-E-B",
        "safeway" to "Safeway", "meijer" to "Meijer", "food lion" to "Food Lion",
        "giant" to "Giant", "sam's club" to "Sam's Club", "sams club" to "Sam's Club",
        "dollar general" to "Dollar General", "winn-dixie" to "Winn-Dixie",
        "winndixie" to "Winn-Dixie", "aldi's" to "Aldi",
    )

    private val FOCUS_PHRASES = listOf(
        "high protein" to "high_protein", "high-protein" to "high_protein",
        "protein" to "high_protein",
        "quick" to "quick", "quick & easy" to "quick", "quick and easy" to "quick",
        "fast" to "quick", "easy" to "quick",
        "cheap" to "cheap", "frugal" to "cheap", "budget" to "cheap", "budget-friendly" to "cheap",
        "use expiring" to "use_expiring", "use what i have" to "use_expiring",
        "use what's in my pantry" to "use_expiring", "use my pantry" to "use_expiring",
        "from my pantry" to "use_expiring",
        "balanced" to "balanced", "healthy" to "balanced", "normal" to "balanced",
    )

    private val APPLIANCES = listOf(
        "air fryer" to "air fryer", "airfryer" to "air fryer",
        "slow cooker" to "slow cooker", "crockpot" to "slow cooker", "crock pot" to "slow cooker",
        "oven" to "oven", "stovetop" to "stovetop", "stove top" to "stovetop", "stove" to "stovetop",
        "microwave" to "microwave",
    )

    /** Restrictions that can be detected from allergy/avoid phrasing. */
    private val RESTRICTION_PHRASES = listOf(
        "peanut" to "peanut", "peanuts" to "peanut", "tree nut" to "nuts", "nuts" to "nuts",
        "shellfish" to "shellfish", "shrimp" to "shellfish", "crab" to "shellfish", "lobster" to "shellfish",
        "dairy" to "dairy", "lactose" to "dairy", "milk allergy" to "dairy",
        "gluten" to "gluten", "wheat" to "gluten",
        "egg" to "eggs", "eggs" to "eggs", "soy" to "soy", "pork" to "pork", "bacon" to "pork",
        "vegetarian" to "vegetarian", "vegan" to "vegan",
    )

    private val NOT_AVOID = setOf("nutritious", "nutrition", "nutmeg")

    fun parse(raw: String): ParsedPlanRequest {
        val text = (raw ?: "").lowercase(LOCALE).trim()
        val matched = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (text.isBlank()) {
            return ParsedPlanRequest(PlanRequest(), matched, listOf("Type what you need — e.g. \"dinner for two, five nights, under \$70\"."))
        }

        // People.
        var people = 4
        var peopleMatched: String? = null
        regexFind(text, Regex("""for (\d+)\b"""))?.also { people = it; peopleMatched = "for $it" }
        if (peopleMatched == null) regexFind(text, Regex("""(\d+) (?:people|adults|persons)\b"""))?.also { people = it; peopleMatched = "$it people" }
        if (peopleMatched == null) regexFind(text, Regex("""feeding (\d+)\b"""))?.also { people = it; peopleMatched = "feeding $it" }
        if (peopleMatched == null) regexFind(text, Regex("""family of (\d+)\b"""))?.also { people = it; peopleMatched = "family of $it" }
        if (peopleMatched == null) {
            regexWord(text, Regex("""for (one|two|three|four|five|six|seven|eight|nine|ten)\b"""))
                ?.let { w -> NUMBER_WORDS[w]?.also { n -> people = n; peopleMatched = "for $w" } }
        }
        peopleMatched?.let { matched += it }

        // Night count.
        var nights = 7
        var nightsMatched: String? = null
        regexFind(text, Regex("""(\d+) (?:dinners|meals|nights|suppers)\b"""))?.also { n -> nights = n.coerceIn(1, 7); nightsMatched = "$n dinners" }
        if (nightsMatched == null && (text.contains("for the week") || text.contains("this week") || text.contains("a week of"))) {
            nights = 7; nightsMatched = "a full week"
        }
        if (nightsMatched == null) {
            regexWord(text, Regex("""(one|two|three|four|five|six|seven) (?:dinners|meals|nights)\b"""))
                ?.let { w -> NUMBER_WORDS[w]?.also { n -> nights = n.coerceIn(1, 7); nightsMatched = "$n dinners" } }
        }
        nightsMatched?.let { matched += it }

        // Budget.
        var budget = 75.0
        var budgetMatched: String? = null
        regexFindDouble(text, Regex("""under \\?\$?(\d+(?:\.\d+)?)\b"""))?.also { b -> budget = b; budgetMatched = "under \$${b.toInt()}" }
        if (budgetMatched == null) regexFindDouble(text, Regex("""\\?\$(\d+(?:\.\d+)?)\b"""))?.also { b -> budget = b; budgetMatched = "\$${b.toInt()}" }
        if (budgetMatched == null) regexFindDouble(text, Regex("""(\d+(?:\.\d+)?) dollars\b"""))?.also { b -> budget = b; budgetMatched = "\$${b.toInt()}" }
        if (budgetMatched == null) regexFindDouble(text, Regex("""budget (?:of )?(\d+(?:\.\d+)?)\b"""))?.also { b -> budget = b; budgetMatched = "\$${b.toInt()} budget" }
        budgetMatched?.let { matched += it }

        // Focus.
        var focus = "balanced"
        var focusMatched: String? = null
        val focusOrder = listOf("use what's in my pantry", "use what i have", "use my pantry", "from my pantry",
            "high protein", "high-protein", "quick & easy", "quick and easy", "budget-friendly",
            "balanced", "healthy", "protein", "quick", "fast", "easy", "cheap", "frugal", "budget", "normal")
        for (phrase in focusOrder) {
            if (text.contains(phrase)) {
                focus = FOCUS_PHRASES.firstOrNull { it.first == phrase }?.second ?: focus
                focusMatched = when (focus) {
                    "high_protein" -> "high protein"
                    "quick" -> "quick & easy"
                    "cheap" -> "budget-friendly"
                    "use_expiring" -> "use what's in the pantry"
                    else -> "balanced"
                }
                break
            }
        }
        focusMatched?.let { matched += it }

        // Preferred store.
        var store: String? = null
        val storeHit = STORE_NAMES.entries.firstOrNull { (key, _) -> text.contains(key) }
        if (storeHit != null) {
            store = storeHit.value
            matched += "at ${storeHit.value}"
        }

        // Appliances.
        val appliances = APPLIANCES.filter { (phrase, _) -> text.contains(phrase) }.map { it.second }.toSet()
        appliances.forEach { matched += "have an $it" }

        // Restrictions (allergy/avoid phrasing).
        val restrictions = mutableListOf<String>()
        val avoidedPhrases = listOf(
            "peanut allergy", "peanut allergies", "allergic to peanuts", "allergic to peanut",
            "tree nut allergy", "nut allergy", "allergic to nuts",
            "shellfish allergy", "allergic to shellfish", "allergic to shrimp", "allergic to crab",
            "dairy allergy", "lactose", "allergic to dairy",
            "gluten", "celiac", "allergic to wheat",
            "egg allergy", "allergic to eggs",
            "soy allergy", "allergic to soy",
            "no pork", "no bacon", "vegetarian", "vegan",
        )
        for (phrase in avoidedPhrases) {
            if (text.contains(phrase)) {
                val r = RESTRICTION_PHRASES.firstOrNull { (k, _) -> phrase.contains(k) }?.second
                if (r != null && r !in restrictions) restrictions += r
            }
        }
        // "no <x>" pattern for known allergens.
        Regex("""no(?:t)?\s+(peanuts?|shellfish|nuts|shrimp|pork|bacon|dairy|gluten|eggs?|soy|tree nuts)\b""")
            .findAll(text).forEach { m ->
                val word = m.groupValues[1]
                RESTRICTION_PHRASES.firstOrNull { it.first == word }?.second?.let { r ->
                    if (r !in restrictions && word !in NOT_AVOID) restrictions += r
                }
            }
        restrictions.forEach { matched += "no $it" }

        // Max cook time.
        var maxCook = 0
        var cookMatched: String? = null
        regexFind(text, Regex("""(?:under|within|less than|max|maximum)\s+(\d+)\s*(?:min|minutes|mins)\b"""))?.also { m -> maxCook = m; cookMatched = "under $m min" }
        if (cookMatched == null) regexFind(text, Regex("""(\d+)-minute\b"""))?.also { m -> maxCook = m; cookMatched = "$m-minute max" }
        if (cookMatched == null) regexFind(text, Regex("""(\d+)\s*min\s*(?:or less|max)\b"""))?.also { m -> maxCook = m; cookMatched = "$m-min max" }
        cookMatched?.let { matched += it }

        // Anything left that looks like a constraint we didn't handle.
        val knownTerms = matched.flatMap { it.lowercase(LOCALE).split(Regex("[^a-z0-9]+")) }.filter { it.length > 2 }.toSet()
        val leftover = text.split(Regex("[,.!;]"))
            .map { it.trim() }
            .filter { it.length > 12 && it.split(" ").size >= 3 }
            .filterNot { seg -> knownTerms.any { seg.contains(it) } }
        if (leftover.isNotEmpty() && matched.isNotEmpty()) {
            notes += "Couldn't use: " + leftover.take(2).joinToString(" · ")
        }

        val request = PlanRequest(
            people = people,
            nights = nights,
            budget = budget,
            focus = focus,
            preferredStore = store,
            appliances = appliances,
            restrictions = restrictions,
            maxCookMinutes = maxCook,
        )
        return ParsedPlanRequest(request, matched, notes)
    }

    private fun regexFind(text: String, regex: Regex): Int? =
        regex.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** First capture group as a word (for "for two", "five nights"). */
    private fun regexWord(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.get(1)

    private fun regexFindDouble(text: String, regex: Regex): Double? =
        regex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
}
