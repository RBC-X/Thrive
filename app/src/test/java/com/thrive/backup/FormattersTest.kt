package com.thrive.backup

import com.thrive.app.util.Distances
import com.thrive.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Display formatting must follow the user's locale (a comma-decimal locale
 * reads "$1,50"), while machine values, URLs, and protocol fields stay
 * locale-independent. The default-locale `String.format` bug (which Android
 * lint flags) was the reason for the explicit-locale API here.
 */
class FormattersTest {

    @Test
    fun `us locale formats prices with dot decimals`() {
        assertEquals("$1.50", Money.fmt(1.5, Locale.US))
        assertEquals("$1.00", Money.fmt(1.0, Locale.US))
        assertEquals("$1,234.56", Money.fmt(1234.56, Locale.US))
    }

    @Test
    fun `comma decimal locale formats prices with comma decimals`() {
        assertEquals("$1,50", Money.fmt(1.5, Locale.GERMANY))
        assertEquals("$1,00", Money.fmt(1.0, Locale.GERMANY))
        assertEquals("$1.234,56", Money.fmt(1234.56, Locale.GERMANY))
    }

    @Test
    fun `negative values are clamped to zero for display`() {
        assertEquals("$0.00", Money.fmt(-4.0, Locale.US))
    }

    @Test
    fun `compact whole dollar prices follow locale grouping`() {
        assertEquals("$5", Money.fmtCompact(5.4, Locale.US))
        assertEquals("$1,234", Money.fmtCompact(1234.4, Locale.US))
        assertEquals("$1.234", Money.fmtCompact(1234.4, Locale.GERMANY))
    }

    @Test
    fun `distances use locale aware decimals`() {
        assertEquals("2.5 mi", Distances.mi(2.5, Locale.US))
        assertEquals("2,5 mi", Distances.mi(2.5, Locale.GERMANY))
        assertEquals("12 mi", Distances.mi(12.4, Locale.US))
        assertEquals("12 mi", Distances.mi(12.4, Locale.GERMANY))
    }

    @Test
    fun `machine values stay locale independent`() {
        // Lat/lng and any protocol fields must never depend on the device locale.
        val lat = String.format(Locale.US, "%.6f", 33.749)
        assertEquals("33.749000", lat)
    }
}
