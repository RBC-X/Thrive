package com.thrive.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---- Brand palette (light) ----
val Emerald = Color(0xFF0D7C5F)
val EmeraldDark = Color(0xFF0A5F49)
val EmeraldDeep = Color(0xFF07382A)
val Mint = Color(0xFFC9F0DE)
val MintLight = Color(0xFFE6F7EF)
val DealCoral = Color(0xFFFF5A3C)
val DealCoralSoft = Color(0xFFFFE3DA)
val Gold = Color(0xFFF5A623)
val GoldSoft = Color(0xFFFFF1D6)
val Berry = Color(0xFF5B7CFA)
val BerrySoft = Color(0xFFE4EAFF)
val Tomato = Color(0xFFE4572E)
val TomatoSoft = Color(0xFFFFE9E0)
val Leaf = Color(0xFF4CAF50)
val LeafSoft = Color(0xFFE3F5E4)

// ---- Neutrals ----
val Canvas = Color(0xFFFBF8F2)
val Ink = Color(0xFF18211D)
val InkSoft = Color(0xFF44534C)
val OutlineSoft = Color(0xFFD6DDD8)

// ---- Dark palette ----
val EmeraldNight = Color(0xFF7FD8B4)
val MintNight = Color(0xFF0B523C)
val CoralNight = Color(0xFFFFB59E)
val GoldNight = Color(0xFFFFC46E)
val BerryNight = Color(0xFFA9BBFF)
val TomatoNight = Color(0xFFFFB59E)
val CanvasNight = Color(0xFF101412)
val SurfaceNight = Color(0xFF161B18)
val InkNight = Color(0xFFE3E9E4)
val InkSoftNight = Color(0xFFA7B4AC)
val OutlineNight = Color(0xFF39423D)

/** Brand accents exposed app-wide. */
data class ThriveColors(
    val deal: Color = DealCoral,
    val dealSoft: Color = DealCoralSoft,
    val gold: Color = Gold,
    val goldSoft: Color = GoldSoft,
    val berry: Color = Berry,
    val berrySoft: Color = BerrySoft,
    val tomato: Color = Tomato,
    val tomatoSoft: Color = TomatoSoft,
    val leaf: Color = Leaf,
    val leafSoft: Color = LeafSoft,
)

val LightThriveColors = ThriveColors()
val DarkThriveColors = ThriveColors(
    deal = CoralNight,
    dealSoft = Color(0xFF4A1C12),
    gold = GoldNight,
    goldSoft = Color(0xFF443112),
    berry = BerryNight,
    berrySoft = Color(0xFF252C4A),
    tomato = TomatoNight,
    tomatoSoft = Color(0xFF4A1C12),
    leaf = Color(0xFF8BDCA0),
    leafSoft = Color(0xFF1E3A24),
)
