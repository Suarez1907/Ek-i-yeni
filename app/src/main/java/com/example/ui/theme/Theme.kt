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
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 1. OLED Siyah Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#000000`, Kart `#0D0D0D`, Metin `#EDEDED`, Vurgu `#81C784`.
 */
val EksinOledBlackColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF002107),
    primaryContainer = Color(0xFF14351A),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF002107),
    secondaryContainer = Color(0xFF1A291C),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFFA5D6A7),
    onTertiary = Color(0xFF002107),
    tertiaryContainer = Color(0xFF1C2E20),
    onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF101210),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF1E221E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0D0B),
    surfaceContainer = Color(0xFF101210),
    surfaceContainerHigh = Color(0xFF171B17),
    surfaceContainerHighest = Color(0xFF1F241F),
    outline = Color(0xFF2B302B),
    outlineVariant = Color(0xFF1A1E1A),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 2. Koyu (Dark Gray) Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#121212`, Kart `#1E1E1E`, Metin `#E0E0E0`, Vurgu `#81C784`.
 */
val EksinDarkGrayColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF003910),
    primaryContainer = Color(0xFF1E4625),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF003910),
    secondaryContainer = Color(0xFF263829),
    onSecondaryContainer = Color(0xFFD7ECD9),
    tertiary = Color(0xFFA5D6A7),
    onTertiary = Color(0xFF003910),
    tertiaryContainer = Color(0xFF283B2B),
    onTertiaryContainer = Color(0xFFC8E6C9),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE3E5E3),
    surface = Color(0xFF121412),
    onSurface = Color(0xFFE3E5E3),
    surfaceVariant = Color(0xFF1D201D),
    onSurfaceVariant = Color(0xFFA8ACA8),
    surfaceDim = Color(0xFF0E100E),
    surfaceBright = Color(0xFF2D312D),
    surfaceContainerLowest = Color(0xFF0D0F0D),
    surfaceContainerLow = Color(0xFF191C19),
    surfaceContainer = Color(0xFF1E221E),
    surfaceContainerHigh = Color(0xFF262A26),
    surfaceContainerHighest = Color(0xFF2F342F),
    outline = Color(0xFF3E443E),
    outlineVariant = Color(0xFF292E29),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000)
)

/**
 * 3. Klasik Beyaz (Light Mode) Renk Şeması (Material 3 Tonal Yüzey Hiyerarşisi ile):
 * Arka plan `#FFFFFF`, Kart/Yüzey `#F4F6F4`, Metin `#1A1A1A`, İkincil Metin `#555555`,
 * Vurgu (Ekşi Yeşili) `#2E7D32`, Başlık Rozeti `#E8F5E9`.
 */
val EksinClassicLightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7F0D9),
    onPrimaryContainer = Color(0xFF0D3810),
    secondary = Color(0xFF388E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1EDE2),
    onSecondaryContainer = Color(0xFF132915),
    tertiary = Color(0xFF1B5E20),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC8E6C9),
    onTertiaryContainer = Color(0xFF0A2E0D),
    background = Color(0xFFFCFDFC),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFCFDFC),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFF1F4F1),
    onSurfaceVariant = Color(0xFF444944),
    surfaceDim = Color(0xFFDADED9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8F6),
    surfaceContainer = Color(0xFFF0F4F0),
    surfaceContainerHigh = Color(0xFFE9EEE9),
    surfaceContainerHighest = Color(0xFFE2E8E2),
    outline = Color(0xFF747974),
    outlineVariant = Color(0xFFC4CAC4),
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
