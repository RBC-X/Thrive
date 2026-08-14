package com.thrive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = EmeraldDeep,
    secondary = Color(0xFF4A6359),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DA),
    onSecondaryContainer = Color(0xFF072018),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = GoldSoft,
    onTertiaryContainer = Color(0xFF2A1A00),
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDE9),
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDFEFC),
    surfaceContainer = Color(0xFFF1F4F1),
    surfaceContainerHigh = Color(0xFFEBEFEC),
    surfaceContainerHighest = Color(0xFFE5EAE6),
    outline = OutlineSoft,
    outlineVariant = Color(0xFFE2E8E3),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = EmeraldNight,
    onPrimary = Color(0xFF003827),
    primaryContainer = MintNight,
    onPrimaryContainer = Color(0xFFC9F0DE),
    secondary = Color(0xFFB1CCC0),
    onSecondary = Color(0xFF1C352B),
    secondaryContainer = Color(0xFF334B41),
    onSecondaryContainer = Color(0xFFCCE8DA),
    tertiary = GoldNight,
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF5A3A00),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = CanvasNight,
    onBackground = InkNight,
    surface = SurfaceNight,
    onSurface = InkNight,
    surfaceVariant = Color(0xFF1E2722),
    onSurfaceVariant = InkSoftNight,
    surfaceContainerLowest = Color(0xFF0C100E),
    surfaceContainerLow = Color(0xFF121715),
    surfaceContainer = Color(0xFF161B18),
    surfaceContainerHigh = Color(0xFF1A201D),
    surfaceContainerHighest = Color(0xFF202723),
    outline = OutlineNight,
    outlineVariant = Color(0xFF2A332E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

val LocalThriveColors = staticCompositionLocalOf { LightThriveColors }

@Composable
fun ThriveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkThriveColors else LightThriveColors
    CompositionLocalProvider(LocalThriveColors provides accents) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ThriveTypography,
            content = content,
        )
    }
}
