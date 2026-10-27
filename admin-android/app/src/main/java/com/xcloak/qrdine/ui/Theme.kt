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
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: DARK
    }
}

object AppThemePreference {
    private const val PREFS = "qrdine_preferences"
    private const val MODE_KEY = "theme_mode"
    private val _mode = MutableStateFlow(AppThemeMode.DARK)
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
    primary = Color(0xFF006B59), onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F1DF), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4C655D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E9E1), onSecondaryContainer = Color(0xFF10261F),
    tertiary = Color(0xFF8A5B00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA0), onTertiaryContainer = Color(0xFF2A1B00),
    background = Color(0xFFF1F7F3), surface = Color(0xFFF8FCF9),
    surfaceVariant = Color(0xFFDCE9E2), onSurfaceVariant = Color(0xFF40574E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEAF3ED),
    surfaceContainer = Color(0xFFE3EEE7),
    surfaceContainerHigh = Color(0xFFDDE9E2),
    surfaceContainerHighest = Color(0xFFD5E3DA),
    outline = Color(0xFF70877C), outlineVariant = Color(0xFFBDCEC4),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF64DCC0), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144), onPrimaryContainer = Color(0xFFB9F4E3),
    secondary = Color(0xFFB2CDC2), onSecondary = Color(0xFF1D352D),
    secondaryContainer = Color(0xFF354E45), onSecondaryContainer = Color(0xFFD0E8DD),
    tertiary = Color(0xFFF2C56D), onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5E430A), onTertiaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF071B16), surface = Color(0xFF0B211B),
    surfaceVariant = Color(0xFF243B33), onSurfaceVariant = Color(0xFFBDCEC5),
    surfaceContainerLowest = Color(0xFF061611),
    surfaceContainerLow = Color(0xFF102820),
    surfaceContainer = Color(0xFF143129),
    surfaceContainerHigh = Color(0xFF193A30),
    surfaceContainerHighest = Color(0xFF22483B),
    outline = Color(0xFF82988D), outlineVariant = Color(0xFF3A564A),
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
