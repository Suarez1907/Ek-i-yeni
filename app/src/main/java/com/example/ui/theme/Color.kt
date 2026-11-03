package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Kullanıcı tarafından seçilebilen ve SharedPreferences ile kalıcı olarak kaydedilen 3 tema modu:
 * 1. [OLED_BLACK]: Arka plan `#000000`, Kart `#0D0D0D`, Metin `#EDEDED`, Vurgu `#81C784`.
 * 2. [DARK_GRAY]: Arka plan `#121212`, Kart `#1E1E1E`, Metin `#E0E0E0`, Vurgu `#81C784`.
 * 3. [CLASSIC_LIGHT]: Arka plan `#FFFFFF`, Kart/Yüzey `#F4F6F4`, Metin `#1A1A1A`,
 *    İkincil Metin `#555555`, Vurgu (Ekşi Yeşili) `#2E7D32`, Başlık Rozeti `#E8F5E9`.
 */
enum class AppThemeMode(
    val storageKey: String,
    val title: String,
    val subtitle: String,
    val isLight: Boolean,
    val previewBgHex: Color,
    val previewCardHex: Color,
    val previewTextHex: Color,
    val previewAccentHex: Color
) {
    OLED_BLACK(
        storageKey = "oled_black",
        title = "OLED Siyah",
        subtitle = "Arka plan #000000 • Kart #0D0D0D • Vurgu #81C784",
        isLight = false,
        previewBgHex = Color(0xFF000000),
        previewCardHex = Color(0xFF0D0D0D),
        previewTextHex = Color(0xFFEDEDED),
        previewAccentHex = Color(0xFF81C784)
    ),
    DARK_GRAY(
        storageKey = "dark_gray",
        title = "Koyu (Dark Gray)",
        subtitle = "Arka plan #121212 • Kart #1E1E1E • Vurgu #81C784",
        isLight = false,
        previewBgHex = Color(0xFF121212),
        previewCardHex = Color(0xFF1E1E1E),
        previewTextHex = Color(0xFFE0E0E0),
        previewAccentHex = Color(0xFF81C784)
    ),
    CLASSIC_LIGHT(
        storageKey = "classic_light",
        title = "Klasik Beyaz (Light Mode)",
        subtitle = "Arka plan #FFFFFF • Kart #F4F6F4 • Vurgu #2E7D32",
        isLight = true,
        previewBgHex = Color(0xFFFFFFFF),
        previewCardHex = Color(0xFFF4F6F4),
        previewTextHex = Color(0xFF1A1A1A),
        previewAccentHex = Color(0xFF2E7D32)
    );

    companion object {
        fun fromStorageKey(key: String?): AppThemeMode {
            return entries.firstOrNull { it.storageKey == key } ?: OLED_BLACK
        }
    }
}

/**
 * Uygulama genelinde aktif tema durumunu reaktif olarak tutan nesne.
 * Hem `MaterialTheme.colorScheme` hem de semantik renk özellikleri (`OledPitchBlack`, `OledCardSurface`, vb.)
 * bu durum değiştiğinde anında yeniden çizilir (recompose).
 */
object ActiveThemeState {
    var currentMode: AppThemeMode by mutableStateOf(AppThemeMode.OLED_BLACK)
}

// Dinamik Arka Plan, Kart ve Yüzey Renkleri
val OledPitchBlack: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF000000)
        AppThemeMode.DARK_GRAY -> Color(0xFF121212)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFFFFFFF)
    }

val OledCardSurface: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF0D0D0D)
        AppThemeMode.DARK_GRAY -> Color(0xFF1E1E1E)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFF4F6F4)
    }

val OledCardDark: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF121212)
        AppThemeMode.DARK_GRAY -> Color(0xFF242424)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFEEF2EE)
    }

val OledSurfaceDark: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF080808)
        AppThemeMode.DARK_GRAY -> Color(0xFF181818)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFF8FAF8)
    }

val OledElevatedCard: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF161616)
        AppThemeMode.DARK_GRAY -> Color(0xFF282828)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE6ECE6)
    }

val OledDivider: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF161616)
        AppThemeMode.DARK_GRAY -> Color(0xFF2C2C2C)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE0E5E0)
    }

val OledHairlineDivider: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF161616)
        AppThemeMode.DARK_GRAY -> Color(0xFF262626)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE4E8E4)
    }

// Sol Frame & Başlık Renkleri
val OledTopicUnread: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFFEDEDED)
        AppThemeMode.DARK_GRAY -> Color(0xFFE0E0E0)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1A1A1A)
    }

val OledTopicRead: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF888888)
        AppThemeMode.DARK_GRAY -> Color(0xFF8E8E8E)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF757575)
    }

val OledPillGreenBg: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF112415)
        AppThemeMode.DARK_GRAY -> Color(0xFF1B3320)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE8F5E9)
    }

// Ekşi Yeşili Vurgu Tonları
val EksiNeonGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF81C784)
        AppThemeMode.DARK_GRAY -> Color(0xFF81C784)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF2E7D32)
    }

val EksiBrightGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF66BB6A)
        AppThemeMode.DARK_GRAY -> Color(0xFF66BB6A)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF388E3C)
    }

val EksiDeepGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF112415)
        AppThemeMode.DARK_GRAY -> Color(0xFF1B3320)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE8F5E9)
    }

val EksiSoftMint: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFFA5D6A7)
        AppThemeMode.DARK_GRAY -> Color(0xFFA5D6A7)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1B5E20)
    }

val OledOnAccentColor: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK,
        AppThemeMode.DARK_GRAY -> Color(0xFF000000)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFFFFFFF)
    }

// Metin ve İkincil Renkler
val OledTextPrimary: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFFEDEDED)
        AppThemeMode.DARK_GRAY -> Color(0xFFE0E0E0)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1A1A1A)
    }

val OledTextSecondary: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF9E9E9E)
        AppThemeMode.DARK_GRAY -> Color(0xFFA6A6A6)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF555555)
    }

val OledTextMuted: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF6E6E6E)
        AppThemeMode.DARK_GRAY -> Color(0xFF787878)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF777777)
    }

val OledErrorRed = Color(0xFFEF5350)
val OledAmberWarning = Color(0xFFFFB74D)
val OledSwipeAuthorBlue: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK,
        AppThemeMode.DARK_GRAY -> Color(0xFF64B5F6)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1565C0)
    }
