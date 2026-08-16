package com.thrive.backup

import com.thrive.app.data.remote.WebRecipeSearch
import com.thrive.app.data.remote.WebSearchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "search the web for this" meal-idea flow: parsing, honest error
 * states, and offline/not-configured degradation. Web results are discovery
 * leads only — never verified claims.
 */
class WebRecipeSearchTest {

    // ---- parseResponse: happy path ----

    @Test
    fun `parses a valid results payload`() {
        val body = """
            {
              "results": [
                {
                  "title": "Easy Chicken & Rice",
                  "url": "https://www.allrecipes.com/easy-chicken-rice",
                  "publishedDate": "2026-03-14T00:00:00.000Z",
                  "excerpt": "A one-pan family dinner.",
                  "confidence": 0.4,
                  "verified": false,
                  "kind": "web-discovery"
                }
              ],
              "source": "exa",
              "kind": "recipes",
              "label": "Web-discovered recipes — read the source before cooking."
            }
        """.trimIndent()

        val state = WebRecipeSearch.parseResponse("Chicken & Rice", body)

        assertTrue(state is WebSearchState.Results)
        val r = state as WebSearchState.Results
        assertEquals("Chicken & Rice", r.query)
        assertEquals(1, r.results.size)
        assertEquals("Easy Chicken & Rice", r.results[0].title)
        assertEquals("https://www.allrecipes.com/easy-chicken-rice", r.results[0].url)
        assertEquals("2026-03-14T00:00:00.000Z", r.results[0].publishedDate)
        assertEquals("A one-pan family dinner.", r.results[0].excerpt)
        assertEquals(0.4, r.results[0].confidence, 0.0001)
        assertTrue(r.label.contains("Web-discovered"))
    }

    @Test
    fun `uses default label when server sends none`() {
        val body = """{"results": [], "source": "exa"}"""
        val state = WebRecipeSearch.parseResponse("Soup", body)
        assertTrue(state is WebSearchState.Results)
        assertEquals(WebRecipeSearch.DEFAULT_LABEL, (state as WebSearchState.Results).label)
    }

    @Test
    fun `keeps server note for empty results`() {
        val body = """{"results": [], "note": "Search provider unavailable.", "source": "exa"}"""
        val state = WebRecipeSearch.parseResponse("Soup", body)
        assertTrue(state is WebSearchState.Results)
        assertEquals("Search provider unavailable.", (state as WebSearchState.Results).note)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `falls back to url when title is missing`() {
        val body = """{"results": [{"url": "https://example.com/recipe"}]}"""
        val state = WebRecipeSearch.parseResponse("Pasta", body)
        assertTrue(state is WebSearchState.Results)
        assertEquals("https://example.com/recipe", (state as WebSearchState.Results).results[0].title)
    }

    // ---- parseResponse: malformed / unsafe payloads ----

    @Test
    fun `missing results key is a parse error not an empty result`() {
        val body = """{"source": "exa"}"""
        val state = WebRecipeSearch.parseResponse("Pasta", body)
        assertTrue(state is WebSearchState.Error)
    }

    @Test
    fun `malformed json is an honest error`() {
        val body = "<html>not json"
        val state = WebRecipeSearch.parseResponse("Pasta", body)
        assertTrue(state is WebSearchState.Error)
    }

    @Test
    fun `results without urls are dropped`() {
        val body = """{"results": [{"title": "No URL"}, {"url": ""}, {"title": "OK", "url": "https://ok.example.com"}]}"""
        val state = WebRecipeSearch.parseResponse("Pasta", body)
        assertTrue(state is WebSearchState.Results)
        assertEquals(1, (state as WebSearchState.Results).results.size)
        assertEquals("https://ok.example.com", state.results[0].url)
    }

    // ---- errorFor ----

    @Test
    fun `maps http statuses to honest messages`() {
        assertTrue(WebRecipeSearch.errorFor("Pasta", 400) is WebSearchState.Error)
        assertTrue(WebRecipeSearch.errorFor("Pasta", 429) is WebSearchState.Error)
        assertTrue(WebRecipeSearch.errorFor("Pasta", 503) is WebSearchState.Error)
        assertTrue(WebRecipeSearch.errorFor("Pasta", 500) is WebSearchState.Error)
        val generic = WebRecipeSearch.errorFor("Pasta", 500) as WebSearchState.Error
        assertTrue(generic.message.contains("500"))
    }

    // ---- fetch: offline / not configured degradation ----

    @Test
    fun `blank base url degrades honestly without network`() = runBlocking {
        val state = WebRecipeSearch.fetch("", "Chicken Soup")
        assertTrue(state is WebSearchState.Error)
        val e = state as WebSearchState.Error
        assertTrue(e.message.contains("sync server"))
        assertEquals("Chicken Soup", e.query)
    }

    @Test
    fun `blank meal name degrades honestly`() = runBlocking {
        val state = WebRecipeSearch.fetch("https://example.com", "   ")
        assertTrue(state is WebSearchState.Error)
    }

    @Test
    fun `unreachable server degrades honestly`() = runBlocking {
        // Nothing is listening on this port — fetch must not throw.
        val state = WebRecipeSearch.fetch("https://127.0.0.1:1", "Chicken Soup")
        assertTrue(state is WebSearchState.Error)
    }
}
