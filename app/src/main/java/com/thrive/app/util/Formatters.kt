package com.thrive.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Money {
    fun fmt(value: Double): String {
        val v = if (value < 0) 0.0 else value
        return String.format(Locale.US, "$%.2f", v)
    }

    fun fmtCompact(value: Double): String = String.format(Locale.US, "$%.0f", value)
}

object Dates {
    private val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.US)
    private val shortFmt = SimpleDateFormat("MMM d", Locale.US)

    fun endDate(daysFromNow: Int): Date =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysFromNow) }.time

    fun daysUntil(date: Date): Int {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
        then.set(Calendar.HOUR_OF_DAY, 0); then.set(Calendar.MINUTE, 0)
        then.set(Calendar.SECOND, 0); then.set(Calendar.MILLISECOND, 0)
        return ((then.timeInMillis - now.timeInMillis) / TimeUnit.DAYS.toMillis(1)).toInt()
    }

    fun expiryLabel(daysFromNow: Int): String = when {
        daysFromNow <= 0 -> "Ends today"
        daysFromNow == 1 -> "Ends tomorrow"
        daysFromNow <= 7 -> "Ends ${shortFmt.format(endDate(daysFromNow))}"
        else -> "Ends ${shortFmt.format(endDate(daysFromNow))}"
    }

    fun countdownLabel(daysFromNow: Int): String = when {
        daysFromNow <= 0 -> "Ends today — hurry!"
        daysFromNow == 1 -> "1 day left"
        else -> "$daysFromNow days left"
    }

    fun pantryExpiry(daysUntil: Int): String = when {
        daysUntil < 0 -> "Expired"
        daysUntil == 0 -> "Expires today"
        daysUntil == 1 -> "Expires tomorrow"
        daysUntil <= 7 -> "In $daysUntil days"
        else -> dayFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysUntil) }.time)
    }
}

object StorePalette {
    private val colors = mapOf(
        "kroger" to 0xFF0D6E4F.toInt(),
        "aldi" to 0xFF2E6DA4.toInt(),
        "walmart" to 0xFF0071CE.toInt(),
        "target" to 0xFFCC0000.toInt(),
        "trader joe" to 0xFFB82E2E.toInt(),
        "whole foods" to 0xFF4A8C4A.toInt(),
        "costco" to 0xFF9B1C31.toInt(),
        "cvs" to 0xFFCC0000.toInt(),
        "walgreens" to 0xFFD72F2F.toInt(),
        "sam's club" to 0xFF00589B.toInt(),
        "dollar general" to 0xFFE3B505.toInt(),
        "amazon" to 0xFFFF9900.toInt(),
        "domino" to 0xFF006491.toInt(),
        "chipotle" to 0xFF7A3E2B.toInt(),
        "panera" to 0xFF1C6A46.toInt(),
        "doordash" to 0xFFFF3008.toInt(),
        "starbucks" to 0xFF00704A.toInt(),
        "olive garden" to 0xFF6B8E23.toInt(),
        "taco bell" to 0xFF702082.toInt(),
        "uber eats" to 0xFF06C167.toInt(),
        "instacart" to 0xFF4CB944.toInt(),
        "any store" to 0xFF44534C.toInt(),
    )

    fun color(store: String): Int {
        colors.entries.firstOrNull { store.lowercase(Locale.US).contains(it.key) }?.let { return it.value }
        val hash = store.lowercase(Locale.US).fold(7) { acc, c -> acc * 31 + c.code }
        return 0xFF000000.toInt() or (0x4A6FA5 + (hash % 0x55 * 0x10101)).coerceAtMost(0xFFFFFF)
    }

    fun initials(store: String): String {
        val words = store.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words.first().take(2).uppercase(Locale.US)
            else -> (words.first().first().toString() + words[1].first().toString()).uppercase(Locale.US)
        }
    }
}
