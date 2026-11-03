package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Gerçek OLED Siyah (#000000 arka plan, #121212 kart yüzeyi, #81C784 Ekşi yeşili vurgu rengi)
 * Material 3 renk şeması.
 */
val EksinOledColorScheme = darkColorScheme(
    primary = EksiNeonGreen,
    onPrimary = Color.Black,
    primaryContainer = EksiDeepGreen,
    onPrimaryContainer = EksiSoftMint,
    secondary = EksiBrightGreen,
    onSecondary = Color.Black,
    secondaryContainer = OledElevatedCard,
    onSecondaryContainer = OledTextPrimary,
    tertiary = EksiSoftMint,
    onTertiary = Color.Black,
    background = OledPitchBlack,           // #000000 gerçek OLED siyah arka plan
    onBackground = OledTextPrimary,
    surface = OledPitchBlack,              // #000000 üst/alt bar yüzeyi
    onSurface = OledTextPrimary,
    surfaceVariant = OledCardSurface,      // #121212 kart yüzeyi
    onSurfaceVariant = OledTextSecondary,
    surfaceContainerLowest = OledPitchBlack,
    surfaceContainerLow = OledSurfaceDark,
    surfaceContainer = OledCardSurface,    // #121212
    surfaceContainerHigh = OledElevatedCard,
    outline = OledDivider,
    error = OledErrorRed,
    onError = Color.Black
)

@Composable
fun EksinOledTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = OledPitchBlack.toArgb()
                window.navigationBarColor = OledPitchBlack.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = EksinOledColorScheme,
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
