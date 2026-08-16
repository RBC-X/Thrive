package com.thrive.app.ai

import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.Ingredient
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.Recipe
import com.thrive.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional AI enrichment layer. Talks to any OpenAI-compatible chat API
 * (OpenAI, Groq, OpenRouter, local servers). All core features work fully
 * offline without it — when enabled, it adds practical cooking tips and
 * deal insights on top of the deterministic local engines.
 */
class AiService(private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    val isEnabled: Boolean get() = apiKey.isNotBlank()

    val apiKey: String get() = settings.getString(KEY_API_KEY, "").orEmpty()
    val baseUrl: String get() = settings.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
    val model: String get() = settings.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()

    suspend fun chat(system: String, user: String): String? {
        if (!isEnabled) return null
        return withContext(Dispatchers.IO) {
            runCatching { post(system, user) }.getOrNull()
        }
    }

    private fun post(system: String, user: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.6)
            put("max_tokens", 220)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                ?: return ""
            val response = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) return ""
            return parseContent(response) ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContent(response: String): String? = runCatching {
        val root = json.parseToJsonElement(response).jsonObject
        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_BASE_URL = "ai_base_url"
        const val KEY_MODEL = "ai_model"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/**
 * Real generative recipe maker. When an AI provider is configured, the pantry
 * is sent to the model and a genuinely new dish is composed from it — name,
 * description, ingredients, and steps written fresh for THIS pantry, not a
 * template fill-in. Any failure (no key, network, bad JSON, empty pantry)
 * falls back to the deterministic on-device engine so the feature always
 * works, with or without an API key.
 */
class AiRecipeMaker(private val ai: AiService) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun generate(items: List<PantryItem>, variant: Int = 0): GeneratedRecipe? {
        if (!ai.isEnabled) return null
        if (items.isEmpty()) return null
        val names = items.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null

        val system = "You are Thrive, a budget family cooking AI. You write one " +
            "original dinner recipe that USES the exact ingredients the user has " +
            "in their pantry. Reply with ONLY a JSON object, no markdown, no code " +
            "fences, no commentary. The JSON shape must be exactly:\n" +
            "{\"name\": string, \"description\": string, \"prepMinutes\": int, " +
            "\"cookMinutes\": int, \"servings\": int, \"ingredients\": " +
            "[{\"name\": string, \"amount\": string}], \"steps\": [string, ...], " +
            "\"costDollars\": number}\n" +
            "Rules: every ingredient in the recipe should come from the user's " +
            "pantry when possible. Keep the recipe affordable (family meals). " +
            "Never repeat the same dish if the user asks for a different one."
        val user = "My pantry has: ${names.joinToString(", ")}. " +
            "Write a different, original dinner recipe from these ingredients " +
            "(roll #${variant + 1} — make it distinct from earlier rolls). " +
            "Use 4 servings. Include which pantry items you used."
        val raw = ai.chat(system, user) ?: return null
        val parsed = parseJson(raw) ?: return null
        return toGeneratedRecipe(parsed, items, variant)
    }

    private fun parseJson(raw: String): ParsedRecipe? {
        // The model may wrap JSON in ``` fences — strip them defensively.
        val cleaned = raw
            .trim()
            .removePrefix("```json").removePrefix("```")
            .trim()
            .removeSuffix("```").trim()
        return runCatching { json.decodeFromString<ParsedRecipe>(cleaned) }.getOrNull()
    }

    private fun toGeneratedRecipe(p: ParsedRecipe, items: List<PantryItem>, variant: Int): GeneratedRecipe? {
        if (p.name.isBlank() || p.steps.isEmpty()) return null
        val used = items.map { it.name }.filter { n ->
            val lower = n.lowercase()
            p.name.lowercase().contains(lower) ||
                p.ingredients.any { it.name.lowercase().contains(lower) } ||
                p.description.lowercase().contains(lower)
        }.distinct()
        val cost = if (p.costDollars > 0) p.costDollars else 8.0
        val recipe = Recipe(
            id = "ai-" + Math.abs((p.name.hashCode() + variant * 7919).toLong()).toString(),
            name = p.name,
            description = p.description,
            section = "under_20",
            mealType = "Dinner",
            tags = listOf("ai-generated", "pantry"),
            prepMinutes = p.prepMinutes.coerceIn(5, 60),
            cookMinutes = p.cookMinutes.coerceIn(5, 240),
            servings = p.servings.coerceIn(1, 12),
            costDollars = cost,
            difficulty = "Easy",
            ingredients = p.ingredients.map { Ingredient(name = it.name, amount = it.amount) },
            steps = p.steps,
            imageSeed = used.firstOrNull(),
            imageUrl = com.thrive.app.ai.RecipeMakerEngine.photoFor(used.firstOrNull() ?: ""),
            featured = false,
        )
        val pantryNames = items.map { it.name.lowercase() }
        val missing = p.ingredients
            .map { it.name }
            .filter { name ->
                val n = name.lowercase()
                pantryNames.none { pn -> n.contains(pn) || pn.contains(n) }
            }
            .distinct()
        return GeneratedRecipe(
            recipe = recipe,
            usedItems = used,
            missingItems = missing.take(3),
            missingToBuy = missing.take(4).map { Triple(it, "Grocery", it) },
            estimatedCost = cost,
        )
    }

    @Serializable
    private data class ParsedRecipe(
        val name: String = "",
        val description: String = "",
        val prepMinutes: Int = 10,
        val cookMinutes: Int = 20,
        val servings: Int = 4,
        val costDollars: Double = 0.0,
        val ingredients: List<ParsedIngredient> = emptyList(),
        val steps: List<String> = emptyList(),
    )

    @Serializable
    private data class ParsedIngredient(
        val name: String = "",
        val amount: String = "",
    )
}

/** Merge AI tips into meal plans and trip plans. */
class AiAdvisor(private val ai: AiService) {

    suspend fun mealTip(
        mealName: String,
        usedItems: List<String>,
        missingItems: List<String>,
        budgetHint: String,
    ): String? {
        if (!ai.isEnabled) return null
        val system = "You are a practical budget family cooking assistant. " +
            "Answer in one or two short sentences. No markdown, no bullets."
        val user = "I'm making \"$mealName\" and already have: ${usedItems.joinToString(", ")}. " +
            "I still need: ${if (missingItems.isEmpty()) "nothing!" else missingItems.joinToString(", ")}. " +
            "Budget: $budgetHint. Give me one useful substitution or timesaving tip."
        return ai.chat(system, user)
    }

    suspend fun weekTip(
        nightCount: Int,
        totalCost: Double,
        budget: Double,
        shoppingCount: Int,
        underBudget: Boolean,
    ): String? {
        if (!ai.isEnabled) return null
        val system = "You are a practical budget family meal planner. Answer in one or two short sentences. No markdown, no bullets."
        val user = "My week plan: $nightCount dinners totaling \$%.2f of a \$%.2f budget, %d items to buy. Under budget: %s. Give me one concrete tip."
            .format(totalCost, budget, shoppingCount, underBudget)
        return ai.chat(system, user)
    }

    suspend fun dealInsights(
        stores: List<Pair<String, Double>>,
        savings: Double,
        isOverBudget: Boolean,
        swapHints: List<String>,
    ): String? {
        if (!ai.isEnabled) return null
        val system = "You are a savvy grocery shopping coach. Answer in one or two " +
            "short sentences. No markdown, no bullets."
        val storeText = stores.joinToString(", ") { "${it.first} ($${"%.2f".format(it.second)})" }
        val user = "My best plan: $storeText. Total savings: $${"%.2f".format(savings)}. " +
            "Over budget: $isOverBudget. ${if (swapHints.isNotEmpty()) "Swap ideas: ${swapHints.joinToString("; ")}." else ""} " +
            "Give me one practical next step."
        return ai.chat(system, user)
    }
}
