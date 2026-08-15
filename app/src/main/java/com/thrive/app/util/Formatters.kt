package com.thrive.app.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * User-facing display formatting. Display values follow the user's locale
 * (so a comma-decimal locale reads "$1,50" and grouping reads naturally);
 * machine values, URLs, and stored/protocol fields must never go through
 * these — they use explicit Locale.US/ROOT formatting at the call site.
 */
object Money {
    /** User-facing price with a locale-aware decimal separator and grouping. */
    fun fmt(value: Double, locale: Locale = Locale.getDefault()): String {
        val v = if (value < 0) 0.0 else value
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "$" + nf.format(v)
    }

    /** User-facing whole-dollar price ("$5") with locale-aware grouping. */
    fun fmtCompact(value: Double, locale: Locale = Locale.getDefault()): String {
        val v = if (value < 0) 0.0 else value
        return "$" + NumberFormat.getIntegerInstance(locale).format(Math.round(v))
    }
}

/** User-facing distances (miles) with a locale-aware decimal separator. */
object Distances {
    fun mi(mi: Double, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getNumberInstance(locale).apply {
            if (mi < 10) {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            } else {
                minimumFractionDigits = 0
                maximumFractionDigits = 0
            }
        }
        return nf.format(mi) + " mi"
    }
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
