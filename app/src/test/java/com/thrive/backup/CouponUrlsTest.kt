package com.thrive.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards coupon link honesty (v1.3.4):
 *  - a verified product link must be https and point at a real product page,
 *    never a store search or fabricated destination,
 *  - unverified coupons keep an honest store-level search URL and flag it.
 */
class CouponUrlsTest {

    @Serializable
    private data class CouponRow(
        val id: String = "",
        val title: String = "",
        val store: String = "",
        val url: String? = null,
        val urlVerified: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun coupons(): List<CouponRow> {
        val candidates = listOf(
            File("src/main/assets/data/coupons.json"),
            File("app/src/main/assets/data/coupons.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("coupons.json must exist")
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
    fun `verified product links point at a real product page, never a search`() {
        val list = coupons()
        var verified = 0
        for (c in list) {
            if (!c.urlVerified) continue
            verified++
            val url = c.url
            assertTrue("${c.id} (${c.title}) verified link must be https", url != null && url.startsWith("https://"))
            // A verified link must land on the product itself — never a bare
            // store search prompt (`/search?q=` or `?query=`).
            val lower = url!!.lowercase()
            assertTrue("${c.id} verified link must not be a store search: $url",
                !lower.contains("/search") &&
                    !lower.contains("search?q=") &&
                    !lower.contains("?q=") &&
                    !lower.contains("query="))
            // Verified product pages we ship today come from Open Food Facts
            // barcodes; the URL must name the exact product code.
            if (lower.contains("openfoodfacts.org")) {
                assertTrue("${c.id} OFF link must be a product page (/product/<barcode>): $url",
                    lower.contains("/product/"))
            }
        }
        assertTrue("at least some coupons should carry a verified direct product link", verified > 0)
    }

    @Test
    fun `every coupon still has an https destination of some kind`() {
        for (c in coupons()) {
            val url = c.url
            assertTrue("${c.id} must keep a destination URL", !url.isNullOrBlank())
            assertTrue("${c.id} url must be https: $url", url!!.startsWith("https://"))
        }
    }
}
