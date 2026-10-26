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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

object AppThemePreference {
    private const val PREFS = "qrdine_preferences"
    private const val MODE_KEY = "theme_mode"
    private val _mode = MutableStateFlow(AppThemeMode.SYSTEM)
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
    primary = Color(0xFF3659C9), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF111C4B),
    secondary = Color(0xFF52627A), secondaryContainer = Color(0xFFE3E9F3), onSecondaryContainer = Color(0xFF18263B),
    tertiary = Color(0xFF9A571D), tertiaryContainer = Color(0xFFFFDFC4), onTertiaryContainer = Color(0xFF341900),
    background = Color(0xFFF5F7FC), surface = Color(0xFFFAFBFF),
    surfaceVariant = Color(0xFFE6E9F2), onSurfaceVariant = Color(0xFF454B5B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F9),
    surfaceContainer = Color(0xFFEAEFF8),
    surfaceContainerHigh = Color(0xFFE5EAF4),
    surfaceContainerHighest = Color(0xFFDEE5F1),
    outline = Color(0xFF777F90), outlineVariant = Color(0xFFC6CBD7),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C3FF), onPrimary = Color(0xFF172D8A),
    primaryContainer = Color(0xFF2945AB), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFBBC7DD), secondaryContainer = Color(0xFF39465C), onSecondaryContainer = Color(0xFFDDE5F5),
    tertiary = Color(0xFFFFB77E), tertiaryContainer = Color(0xFF713B0A), onTertiaryContainer = Color(0xFFFFDFC4),
    background = Color(0xFF111521), surface = Color(0xFF151A28),
    surfaceVariant = Color(0xFF303647), onSurfaceVariant = Color(0xFFC2C8D7),
    surfaceContainerLowest = Color(0xFF0D111B),
    surfaceContainerLow = Color(0xFF1A2030),
    surfaceContainer = Color(0xFF202738),
    surfaceContainerHigh = Color(0xFF293044),
    surfaceContainerHighest = Color(0xFF333B50),
    outline = Color(0xFF8C93A5), outlineVariant = Color(0xFF41495C),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
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
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.11f), Color.Transparent), radius = 1050f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(scheme.tertiary.copy(alpha = 0.08f), Color.Transparent), radius = 900f)),
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
