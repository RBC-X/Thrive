package com.thrive.app.ai

import java.util.Locale

/**
 * Parses package sizes ("2 lb", "48 oz", "12 ct", "1 gal", "18 ct") into a
 * comparable base unit so deals can be compared per-unit rather than whole-item.
 */
object UnitParser {

    /** Unit families. Items are comparable only within a family. */
    private val FAMILY = mapOf(
        "lb" to 0, "oz" to 0, "kg" to 0, "g" to 0,
        "gal" to 1, "qt" to 1, "pt" to 1, "floz" to 1,
        "ct" to 2, "pack" to 2, "dozen" to 2, "each" to 2, "ea" to 2,
        "bottle" to 2, "jar" to 2, "can" to 2, "bag" to 2, "box" to 2, "tub" to 2,
    )

    /** Multipliers converting each unit to its family's base unit. */
    private val TO_BASE = mapOf(
        // weight -> pounds
        "lb" to 1.0, "oz" to 1.0 / 16.0, "kg" to 2.20462, "g" to 2.20462 / 1000.0,
        // volume -> gallons
        "gal" to 1.0, "qt" to 0.25, "pt" to 0.125, "floz" to 1.0 / 128.0,
        // count -> single units
        "ct" to 1.0, "pack" to 1.0, "dozen" to 12.0, "each" to 1.0, "ea" to 1.0,
        // container items are count-like when sizes are given in units
        "bottle" to 1.0, "jar" to 1.0, "can" to 1.0, "bag" to 1.0, "box" to 1.0, "tub" to 1.0,
    )

    data class Parsed(val qty: Double, val unit: String, val family: Int) {
        /** Quantity converted to the family's base unit. */
        val baseQty: Double get() = qty * (TO_BASE[unit] ?: 1.0)
    }

    /** Parses a bare unit token like "lb", "gal", "dozen" as quantity 1. */
    fun parseUnit(unit: String?): Parsed? {
        if (unit.isNullOrBlank()) return null
        val u = unit.trim().lowercase(Locale.US)
        if (u !in FAMILY) return null
        return Parsed(1.0, u, FAMILY[u]!!)
    }

    /** Parses a free-form size string like "2 lb", "48 oz", "12 ct", "1.5 lb". */
    fun parse(size: String?): Parsed? {
        if (size.isNullOrBlank()) return null
        val m = Regex("([0-9.]+)\\s*([a-zA-Z]+)").find(size.trim().lowercase(Locale.US)) ?: return null
        val qty = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val family = FAMILY[unit] ?: return null
        if (qty <= 0) return null
        return Parsed(qty, unit, family)
    }

    /** A single comparable unit-key: family + base conversion factor. */
    fun comparableKey(parsed: Parsed?): Int? = parsed?.family
}
