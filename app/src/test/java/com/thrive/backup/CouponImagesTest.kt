package com.thrive.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the v1.3.0 coupon-image work: every coupon's imageUrl (when present)
 * must be a real HTTPS product photo that plausibly matches the product, and
 * coupons without a reliable photo must keep the clean category-tile fallback
 * (null imageUrl) — never a random or stock photo, and never an archive scan.
 */
class CouponImagesTest {

    @Serializable
    private data class CouponRow(
        val id: String = "",
        val title: String = "",
        val imageUrl: String? = null,
        val imageSeed: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun coupons(): List<CouponRow> {
        // JVM unit tests run with the module dir (app/) as working directory.
        val candidates = listOf(
            File("src/main/assets/data/coupons.json"),
            File("app/src/main/assets/data/coupons.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("coupons.json must exist (looked in ${candidates.joinToString()})")
        val text = file.readText().trim()
        return if (text.startsWith("[")) {
            json.decodeFromString<List<CouponRow>>(text)
        } else {
            json.decodeFromString<List<CouponRow>>(
                json.parseToJsonElement(text).let { el ->
                    el.jsonObject["coupons"]?.toString() ?: text
                }
            )
        }
    }

    @Test
    fun `every coupon has a fallback seed and honest image state`() {
        val list = coupons()
        assertTrue("catalog must be non-empty", list.isNotEmpty())
        for (c in list) {
            assertTrue("coupon ${c.id} must keep imageSeed for the fallback tile", !c.imageSeed.isNullOrBlank())
            val url = c.imageUrl
            if (url != null) {
                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: ""
                assertTrue("${c.id} imageUrl must be https on a known image host: $url",
                    url.startsWith("https://") &&
                        (host.endsWith("openfoodfacts.org") || host.endsWith("wikimedia.org")))
            }
        }
    }

    @Test
    fun `no archive scans or placeholder junk ever ships as a product photo`() {
        val junk = listOf(
            "%28IA", "djvu", ".pdf", "Journal", "Gazette", "Register", "annual",
            "Story Book", "Mongolian", "Board of Trade", "Abendpost", "Courier",
            "Knapsack", "iron crown", "seed catalog", "Pony_Rider", "Copyright"
        )
        for (c in coupons()) {
            val url = c.imageUrl ?: continue
            for (marker in junk) {
                if (url.contains(marker)) {
                    fail("${c.id} (${c.title}) shipped an archive/scan image: $url")
                }
            }
        }
    }

    @Test
    fun `matched photos share a meaningful product token with the coupon title`() {
        val stop = setOf(
            "organic", "fresh", "large", "small", "bunch", "loaf", "pack", "bag",
            "ct", "lb", "oz", "gallon", "dozen", "count", "each", "grade",
            "a", "the", "of", "and", "trail", "box", "can", "jar", "bottle",
            "size", "value", "great", "best", "with", "for", "in", "on", "or",
            "new", "old", "digital", "smart"
        )

        fun tokens(s: String): Set<String> {
            val words = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ")
            return words.filter { it.length > 1 && it !in stop && !it.all { ch -> ch.isDigit() } }.toSet()
        }

        var matched = 0
        for (c in coupons()) {
            val url = c.imageUrl ?: continue
            matched++
            if (url.contains("wikimedia.org")) {
                // Commons URLs encode the file title — it must share a token with the product.
                var fn = url.substringAfter("/thumb/").substringAfterLast("/")
                fn = fn.substringBefore("?")
                fn = fn.replace(Regex("""/\d+px-"""), " ")
                fn = fn.replace(Regex("""\.(jpg|jpeg|png|webp|svg).*$""", RegexOption.IGNORE_CASE), "")
                fn = fn.replace(Regex("[_%]+"), " ").replace("%28", "(")
                val shared = tokens(c.title) intersect tokens(fn)
                assertTrue("${c.id} (${c.title}) photo shares no product token: $url", shared.isNotEmpty())
            }
        }
        assertTrue("at least some coupons should carry a real photo", matched > 0)
    }
}
