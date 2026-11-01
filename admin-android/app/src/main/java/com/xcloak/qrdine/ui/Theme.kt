package com.xcloak.qrdine.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: LIGHT
    }
}

object AppThemePreference {
    private const val PREFS = "qrdine_preferences"
    private const val MODE_KEY = "theme_mode"
    private val _mode = MutableStateFlow(AppThemeMode.LIGHT)
    val mode: StateFlow<AppThemeMode> = _mode.asStateFlow()

    fun initialize(context: Context) {
        _mode.value = AppThemeMode.fromStored(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE_KEY, null),
        )
    }

    fun set(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE_KEY, mode.name).apply()
        _mode.value = mode
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF0369A1), onPrimary = Color.White,
    primaryContainer = Color(0xFFBAE6FD), onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFF4C6475), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEEF8), onSecondaryContainer = Color(0xFF102B3B),
    tertiary = Color(0xFF0284C7), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEBFA), onTertiaryContainer = Color(0xFF0C344A),
    background = Color(0xFFF5FAFE), surface = Color(0xFFEAF4FA),
    surfaceVariant = Color(0xFFDDECF5), onSurfaceVariant = Color(0xFF405968),
    surfaceContainerLowest = Color(0xFFF0F8FC),
    surfaceContainerLow = Color(0xFFE4F1F8),
    surfaceContainer = Color(0xFFDCECF5),
    surfaceContainerHigh = Color(0xFFD4E7F2),
    surfaceContainerHighest = Color(0xFFC9E0EE),
    outline = Color(0xFF718B9B), outlineVariant = Color(0xFFBCCFDA),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DD3FC), onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF075985), onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFB3CCDC), onSecondary = Color(0xFF1B3445),
    secondaryContainer = Color(0xFF304B5E), onSecondaryContainer = Color(0xFFD3EAF7),
    tertiary = Color(0xFFBAE6FD), onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF164C6B), onTertiaryContainer = Color(0xFFCDEBFA),
    background = Color(0xFF071A2B), surface = Color(0xFF0B2235),
    surfaceVariant = Color(0xFF243F53), onSurfaceVariant = Color(0xFFB9CEDB),
    surfaceContainerLowest = Color(0xFF061522),
    surfaceContainerLow = Color(0xFF10283A),
    surfaceContainer = Color(0xFF153044),
    surfaceContainerHigh = Color(0xFF1B394F),
    surfaceContainerHighest = Color(0xFF25485F),
    outline = Color(0xFF89A7B9), outlineVariant = Color(0xFF3A586D),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(18.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp),
)

private val base = Typography()
private fun androidx.compose.ui.text.TextStyle.brand(weight: FontWeight = fontWeight ?: FontWeight.Normal) =
    copy(fontFamily = FontFamily.SansSerif, fontWeight = weight)

private val Type = base.copy(
    displayLarge = base.displayLarge.brand(FontWeight.Bold),
    displayMedium = base.displayMedium.brand(FontWeight.Bold),
    displaySmall = base.displaySmall.brand(FontWeight.Bold),
    headlineLarge = base.headlineLarge.brand(FontWeight.Bold),
    headlineMedium = base.headlineMedium.brand(FontWeight.Bold),
    headlineSmall = base.headlineSmall.brand(FontWeight.Bold),
    titleLarge = base.titleLarge.brand(FontWeight.SemiBold),
    titleMedium = base.titleMedium.brand(FontWeight.SemiBold),
    titleSmall = base.titleSmall.brand(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.brand(),
    bodyMedium = base.bodyMedium.brand(),
    bodySmall = base.bodySmall.brand(),
    labelLarge = base.labelLarge.brand(FontWeight.SemiBold),
    labelMedium = base.labelMedium.brand(FontWeight.SemiBold).copy(letterSpacing = 0.3.sp),
    labelSmall = base.labelSmall.brand(FontWeight.Medium).copy(letterSpacing = 0.2.sp),
)

@Composable
fun QrDineTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, shapes = Shape, typography = Type, content = content)
}

@Composable
fun GlassBackdrop(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(scheme.background, scheme.surface))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent), radius = 1050f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(scheme.tertiary.copy(alpha = 0.06f), Color.Transparent), radius = 900f)),
        )
        content()
    }
}

@Composable
fun ThemePreferenceCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected by AppThemePreference.mode.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.SettingsBrightness, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text("Choose how QR Dine looks on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { AppThemePreference.set(context, mode) },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(
                                when (mode) {
                                    AppThemeMode.SYSTEM -> Icons.Default.SettingsBrightness
                                    AppThemeMode.LIGHT -> Icons.Default.LightMode
                                    AppThemeMode.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
