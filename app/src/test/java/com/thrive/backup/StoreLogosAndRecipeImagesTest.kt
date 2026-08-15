package com.thrive.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the image work shipped in v1.3.3:
 *  - every coupon carries a verified https store-logo URL (fallback when the
 *    product photo is unavailable),
 *  - every recipe's food photo is https on a known image host,
 *  - never a random/stock photo or a storefront pretending to be a logo.
 */
class StoreLogosAndRecipeImagesTest {

    @Serializable
    private data class CouponRow(
        val id: String = "",
        val store: String = "",
        val imageUrl: String? = null,
        val storeLogoUrl: String? = null,
    )

    @Serializable
    private data class RecipeRow(
        val id: String = "",
        val name: String = "",
        val imageUrl: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/data/$name"),
            File("app/src/main/assets/data/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("$name must exist")
    }

    private fun readList(name: String, field: String? = null): String {
        val text = asset(name).readText().trim()
        if (field != null && !text.startsWith("[")) {
            return json.parseToJsonElement(text).jsonObject[field]?.toString() ?: text
        }
        return text
    }

    @Test
    fun `every coupon has a verified https store logo`() {
        val list = json.decodeFromString<List<CouponRow>>(readList("coupons.json"))
        assertTrue("catalog must be non-empty", list.isNotEmpty())
        for (c in list) {
            val url = c.storeLogoUrl
            assertTrue("coupon ${c.id} (${c.store}) must have storeLogoUrl", !url.isNullOrBlank())
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: ""
            assertTrue("${c.id} storeLogoUrl must be https on wikimedia: $url",
                url!!.startsWith("https://") &&
                    (host.endsWith("wikimedia.org") || host.endsWith("wikipedia.org")))
        }
    }

    @Test
    fun `recipe food photos are https on a known image host`() {
        val list = json.decodeFromString<List<RecipeRow>>(readList("recipes.json"))
        assertTrue("recipes must be non-empty", list.isNotEmpty())
        var withPhoto = 0
        for (r in list) {
            val url = r.imageUrl ?: continue
            withPhoto++
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: ""
            assertTrue("${r.id} (${r.name}) food photo must be https on wikimedia: $url",
                url.startsWith("https://") && host.endsWith("wikimedia.org"))
        }
        assertTrue("at least some recipes should carry a real food photo", withPhoto >= 10)
    }
}
