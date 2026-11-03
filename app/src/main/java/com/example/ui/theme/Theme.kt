package com.example.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
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
 * 1. OLED Siyah Renk Şeması:
 * Arka plan `#000000`, Kart `#0D0D0D`, Metin `#EDEDED`, Vurgu `#81C784`.
 */
val EksinOledBlackColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF112415),
    onPrimaryContainer = Color(0xFF81C784),
    secondary = Color(0xFF66BB6A),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF161616),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFA5D6A7),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF0D0D0D),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF161616),
    outline = Color(0xFF161616),
    outlineVariant = Color(0xFF161616),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 2. Koyu (Dark Gray) Renk Şeması:
 * Arka plan `#121212`, Kart `#1E1E1E`, Metin `#E0E0E0`, Vurgu `#81C784`.
 */
val EksinDarkGrayColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1B3320),
    onPrimaryContainer = Color(0xFF81C784),
    secondary = Color(0xFF66BB6A),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF282828),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFFA5D6A7),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
    outline = Color(0xFF2C2C2C),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 3. Klasik Beyaz (Light Mode) Renk Şeması:
 * Arka plan `#FFFFFF`, Kart/Yüzey `#F4F6F4`, Metin `#1A1A1A`, İkincil Metin `#555555`,
 * Vurgu (Ekşi Yeşili) `#2E7D32`, Başlık Rozeti `#E8F5E9`.
 */
val EksinClassicLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F5E9),
    onPrimaryContainer = Color(0xFF2E7D32),
    secondary = Color(0xFF388E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6ECE6),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF1B5E20),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF4F6F4),
    onSurfaceVariant = Color(0xFF555555),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAF8),
    surfaceContainer = Color(0xFFF4F6F4),
    surfaceContainerHigh = Color(0xFFE6ECE6),
    outline = Color(0xFFE0E5E0),
    outlineVariant = Color(0xFFE4E8E4),
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
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    EksinOledTheme(content = content)
}
