package com.thrive.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * One web-discovered recipe result returned by the sync server's
 * Exa-backed `/api/v1/search/recipes` endpoint.
 *
 * These are DISCOVERY leads, never verified claims: the server always marks
 * them `verified: false` and the UI labels them accordingly.
 */
data class WebRecipeResult(
    val title: String,
    val url: String,
    val publishedDate: String?,
    val excerpt: String,
    val confidence: Double,
)

/** Honest state machine for a "search the web for this meal" action. */
sealed interface WebSearchState {
    data object Idle : WebSearchState
    data class Loading(val query: String) : WebSearchState

    /** A valid server response — `results` may legitimately be empty. */
    data class Results(
        val query: String,
        val results: List<WebRecipeResult>,
        val label: String,
        val note: String?,
    ) : WebSearchState

    /** Server unreachable, not configured, malformed payload, or HTTP error. */
    data class Error(val query: String, val message: String) : WebSearchState
}

/**
 * Client for the backend's web-recipe discovery endpoint. Purely optional:
 * every failure degrades to an honest [WebSearchState.Error] or empty
 * [WebSearchState.Results], and the rest of Thrive (bundled recipes, pantry
 * engine, offline generation) is untouched.
 */
object WebRecipeSearch {

    const val DEFAULT_LABEL = "Web-discovered recipes — read the source before cooking."
    const val MAX_RESULTS = 5

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches recipe-discovery leads for a meal idea. Never throws: network
     * failure, missing server, and malformed responses all become an honest
     * state instead of crashing the meal-ideas flow.
     */
    suspend fun fetch(baseUrl: String, mealName: String): WebSearchState = withContext(Dispatchers.IO) {
        val query = mealName.trim()
        if (query.isBlank()) {
            return@withContext WebSearchState.Error(query, "Nothing to search for yet.")
        }
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext WebSearchState.Error(
                query,
                "Web search needs a sync server — connect to the Thrive server in Settings first.",
            )
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        runCatching {
            ApiClient.get("$base/api/v1/search/recipes?q=$encoded&limit=$MAX_RESULTS")
        }.fold(
            onSuccess = { res ->
                if (res.code !in 200..299) errorFor(query, res.code)
                else parseResponse(query, res.body)
            },
            onFailure = {
                WebSearchState.Error(query, "Couldn't reach the search server — check your connection.")
            },
        )
    }

    /** Maps a non-2xx HTTP status to an honest, user-facing error. */
    fun errorFor(query: String, status: Int): WebSearchState {
        val message = when (status) {
            400 -> "That search didn't work — try a shorter meal name."
            429 -> "Search is busy right now — try again in a minute."
            503 -> "The search server is temporarily down — try again later."
            else -> "The search server had a problem (HTTP $status)."
        }
        return WebSearchState.Error(query, message)
    }

    /**
     * Parses the endpoint's response body into an honest state. Any payload
     * that isn't a JSON object with a `results` array is a parse failure —
     * never a fabricated empty result.
     */
    fun parseResponse(query: String, body: String): WebSearchState {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                return WebSearchState.Error(query, "The search server returned something unexpected.")
            }
        val rawResults = root["results"]?.jsonArray
            ?: return WebSearchState.Error(query, "The search server returned something unexpected.")
        val results = rawResults.mapNotNull { el ->
            val o = el.jsonObject
            val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WebRecipeResult(
                title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: url,
                url = url,
                publishedDate = o["publishedDate"]?.jsonPrimitive?.contentOrNull,
                excerpt = o["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                confidence = o["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
            )
        }
        val label = root["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL
        val note = root["note"]?.jsonPrimitive?.contentOrNull
        return WebSearchState.Results(query, results, label, note)
    }
}
