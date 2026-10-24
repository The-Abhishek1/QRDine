package com.xcloak.qrdine.ui

import androidx.compose.foundation.isSystemInDarkTheme
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

private val Light = lightColorScheme(
    primary = Color(0xFF0B7A6B), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEE7), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF5B6B65), secondaryContainer = Color(0xFFDDE7E3),
    tertiary = Color(0xFF8A6100), tertiaryContainer = Color(0xFFFFE3A3), onTertiaryContainer = Color(0xFF2B1E00),
    background = Color(0xFFF6F8F7), surface = Color(0xFFF6F8F7),
    surfaceVariant = Color(0xFFE4EBE8), onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainer = Color(0xFFEDF2F0), surfaceContainerHigh = Color(0xFFE7EDEA),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4FD1BD), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046), onPrimaryContainer = Color(0xFFB4F0E5),
    secondary = Color(0xFFB9CBC4), secondaryContainer = Color(0xFF3A4A45),
    tertiary = Color(0xFFF2B632), tertiaryContainer = Color(0xFF4F3A00), onTertiaryContainer = Color(0xFFFFE3A3),
    background = Color(0xFF0F1916), surface = Color(0xFF0F1916),
    surfaceVariant = Color(0xFF2A3A35), onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainer = Color(0xFF16231F), surfaceContainerHigh = Color(0xFF1E2D28),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val Type = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun QrDineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = Shape, typography = Type, content = content)
}
