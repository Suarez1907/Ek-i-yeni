package com.example.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Kullanıcının tema tercihini (`OLED_BLACK`, `DARK_GRAY`, `CLASSIC_LIGHT`)
 * `SharedPreferences` üzerinde kalıcı olarak saklayan yönetici.
 */
object ThemePreferencesManager {
    private const val PREFS_NAME = "eksin_theme_prefs"
    private const val KEY_THEME_MODE = "selected_theme_mode"

    fun loadSavedTheme(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_THEME_MODE, AppThemeMode.OLED_BLACK.storageKey)
        val mode = AppThemeMode.fromStorageKey(raw)
        ActiveThemeState.currentMode = mode
        return mode
    }

    fun saveTheme(context: Context, mode: AppThemeMode) {
        ActiveThemeState.currentMode = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
    }
}

/**
 * Google Material 3 Şekil Ölçeği (Shape Scale).
 */
val EksinShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 1. OLED Siyah Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#000000`, Kart `#0D0D0D`, Metin `#EDEDED`, Vurgu `#81C784`.
 */
val EksinOledBlackColorScheme = darkColorScheme(
    primary = Color(0xFF74E092),
    onPrimary = Color(0xFF002107),
    primaryContainer = Color(0xFF102819),
    onPrimaryContainer = Color(0xFFB6EDC5),
    secondary = Color(0xFF74E092),
    onSecondary = Color(0xFF002107),
    secondaryContainer = Color(0xFF102819),
    onSecondaryContainer = Color(0xFFB6EDC5),
    tertiary = Color(0xFF74E092),
    onTertiary = Color(0xFF002107),
    tertiaryContainer = Color(0xFF102819),
    onTertiaryContainer = Color(0xFFB6EDC5),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDF2EE),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDF2EE),
    surfaceVariant = Color(0xFF0B0E0C),
    onSurfaceVariant = Color(0xFF9BA89F),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF141916),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0E0C),
    surfaceContainer = Color(0xFF0B0E0C),
    surfaceContainerHigh = Color(0xFF141916),
    surfaceContainerHighest = Color(0xFF141916),
    outline = Color(0xFF2B322C),
    outlineVariant = Color(0xFF1B211D),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 2. Koyu (Dark Gray) Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#121212`, Kart `#1E1E1E`, Metin `#E0E0E0`, Vurgu `#81C784`.
 */
val EksinDarkGrayColorScheme = darkColorScheme(
    primary = Color(0xFF6EE798),
    onPrimary = Color(0xFF002107),
    primaryContainer = Color(0xFF163020),
    onPrimaryContainer = Color(0xFFB6EDC5),
    secondary = Color(0xFF6EE798),
    onSecondary = Color(0xFF002107),
    secondaryContainer = Color(0xFF163020),
    onSecondaryContainer = Color(0xFFB6EDC5),
    tertiary = Color(0xFF6EE798),
    onTertiary = Color(0xFF002107),
    tertiaryContainer = Color(0xFF163020),
    onTertiaryContainer = Color(0xFFB6EDC5),
    background = Color(0xFF0F1210),
    onBackground = Color(0xFFE4EAE6),
    surface = Color(0xFF0F1210),
    onSurface = Color(0xFFE4EAE6),
    surfaceVariant = Color(0xFF171B18),
    onSurfaceVariant = Color(0xFFA3B0A7),
    surfaceDim = Color(0xFF0B0E0C),
    surfaceBright = Color(0xFF212723),
    surfaceContainerLowest = Color(0xFF0B0E0C),
    surfaceContainerLow = Color(0xFF141815),
    surfaceContainer = Color(0xFF171B18),
    surfaceContainerHigh = Color(0xFF1E231F),
    surfaceContainerHighest = Color(0xFF252C27),
    outline = Color(0xFF3E463F),
    outlineVariant = Color(0xFF252C27),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 3. Klasik Beyaz (Light Mode) Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#FFFFFF`, Kart/Yüzey `#F4F6F4`, Metin `#1A1A1A`, İkincil Metin `#555555`,
 * Vurgu (Ekşi Yeşili) `#2E7D32`, Başlık Rozeti `#E8F5E9`.
 */
val EksinClassicLightColorScheme = lightColorScheme(
    primary = Color(0xFF1B7F3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6F5EC),
    onPrimaryContainer = Color(0xFF0A3D1C),
    secondary = Color(0xFF229948),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6F5EC),
    onSecondaryContainer = Color(0xFF0A3D1C),
    tertiary = Color(0xFF10632F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6F5EC),
    onTertiaryContainer = Color(0xFF0A3D1C),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF111814),
    surface = Color(0xFFF7F9F8),
    onSurface = Color(0xFF111814),
    surfaceVariant = Color(0xFFE9F0EC),
    onSurfaceVariant = Color(0xFF4B5750),
    surfaceDim = Color(0xFFDDE4E0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F4F2),
    surfaceContainerHigh = Color(0xFFE9F0EC),
    surfaceContainerHighest = Color(0xFFE3EAE6),
    outline = Color(0xFF7A867F),
    outlineVariant = Color(0xFFE3EAE6),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF)
)

fun colorSchemeForMode(mode: AppThemeMode): ColorScheme {
    return when (mode) {
        AppThemeMode.OLED_BLACK -> EksinOledBlackColorScheme
        AppThemeMode.DARK_GRAY -> EksinDarkGrayColorScheme
        AppThemeMode.CLASSIC_LIGHT -> EksinClassicLightColorScheme
    }
}

@Composable
fun EksinOledTheme(
    themeMode: AppThemeMode = ActiveThemeState.currentMode,
    content: @Composable () -> Unit
) {
    ActiveThemeState.currentMode = themeMode
    val colorScheme = colorSchemeForMode(themeMode)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val barColor = colorScheme.background.toArgb()
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = themeMode.isLight
                controller.isAppearanceLightNavigationBars = themeMode.isLight
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = EksinShapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    EksinOledTheme(content = content)
}
