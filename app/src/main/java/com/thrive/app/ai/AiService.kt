package com.thrive.app.ai

import com.thrive.app.data.local.SettingsStore
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
