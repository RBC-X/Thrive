package com.thrive.app.data.local

import android.content.Context
import com.thrive.app.data.model.CatalogItem
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.model.Deal
import com.thrive.app.data.model.Recipe
import kotlinx.serialization.json.Json

/** Reads the bundled content datasets shipped in assets/data. */
object AssetLoader {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun read(context: Context, file: String): String =
        context.assets.open("data/$file").bufferedReader().use { it.readText() }

    fun coupons(context: Context): List<Coupon> =
        json.decodeFromString<List<Coupon>>(read(context, "coupons.json"))

    fun recipes(context: Context): List<Recipe> =
        json.decodeFromString<List<Recipe>>(read(context, "recipes.json"))

    fun deals(context: Context): List<Deal> =
        json.decodeFromString<List<Deal>>(read(context, "deals.json"))

    fun catalog(context: Context): List<CatalogItem> =
        json.decodeFromString<List<CatalogItem>>(read(context, "catalog.json"))
}
