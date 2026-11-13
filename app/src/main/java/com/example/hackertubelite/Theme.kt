package com.example.hackertubelite

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The HackerTube / Hacker News orange. Used for the icon, wordmark and accents. */
val Orange = Color(0xFFFF6600)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD95700),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9D9),
    onPrimaryContainer = Color(0xFF4A1E00),
    secondaryContainer = Color(0xFFEFEEE8),
    onSecondaryContainer = Color(0xFF1B1B19),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF1B1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B19),
    surfaceVariant = Color(0xFFEFEEE8),
    onSurfaceVariant = Color(0xFF6B6A63),
    outline = Color(0xFFD5D4CC),
    outlineVariant = Color(0xFFE6E5DE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A1A),
    onPrimary = Color(0xFF1A0A00),
    primaryContainer = Color(0xFF3B1D08),
    onPrimaryContainer = Color(0xFFFFD9BF),
    secondaryContainer = Color(0xFF2A2926),
    onSecondaryContainer = Color(0xFFECEBE6),
    background = Color(0xFF111111),
    onBackground = Color(0xFFECEBE6),
    surface = Color(0xFF181818),
    onSurface = Color(0xFFECEBE6),
    surfaceVariant = Color(0xFF242321),
    onSurfaceVariant = Color(0xFFA3A29B),
    outline = Color(0xFF3C3B37),
    outlineVariant = Color(0xFF2A2926),
)

private val base = Typography()
private val AppTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = base.bodyLarge.copy(lineHeight = 25.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun HackerTubeTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
