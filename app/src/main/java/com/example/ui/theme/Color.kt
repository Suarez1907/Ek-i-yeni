package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Kullanıcı tarafından seçilebilen ve SharedPreferences ile kalıcı olarak kaydedilen 3 tema modu:
 * 1. [OLED_BLACK]: Arka plan `#000000`, Kart `#0B0E0C`, Metin `#EDF2EE`, Vurgu `#74E092`.
 * 2. [DARK_GRAY]: Arka plan `#0F1210`, Kart `#171B18`, Metin `#E4EAE6`, Vurgu `#6EE798`.
 * 3. [CLASSIC_LIGHT]: Arka plan `#F7F9F8`, Kart/Yüzey `#FFFFFF`, Metin `#111814`,
 *    İkincil Metin `#4B5750`, Vurgu (Ekşi Yeşili) `#1B7F3B`, Başlık Rozeti `#E6F5EC`.
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
        subtitle = "Arka plan #000000 • Kart #0B0E0C • Vurgu #74E092",
        isLight = false,
        previewBgHex = Color(0xFF000000),
        previewCardHex = Color(0xFF0B0E0C),
        previewTextHex = Color(0xFFEDF2EE),
        previewAccentHex = Color(0xFF74E092)
    ),
    DARK_GRAY(
        storageKey = "dark_gray",
        title = "Koyu Gri",
        subtitle = "Arka plan #0F1210 • Kart #171B18 • Vurgu #6EE798",
        isLight = false,
        previewBgHex = Color(0xFF0F1210),
        previewCardHex = Color(0xFF171B18),
        previewTextHex = Color(0xFFE4EAE6),
        previewAccentHex = Color(0xFF6EE798)
    ),
    CLASSIC_LIGHT(
        storageKey = "classic_light",
        title = "Açık Tema",
        subtitle = "Arka plan #F7F9F8 • Kart #FFFFFF • Vurgu #1B7F3B",
        isLight = true,
        previewBgHex = Color(0xFFF7F9F8),
        previewCardHex = Color(0xFFFFFFFF),
        previewTextHex = Color(0xFF111814),
        previewAccentHex = Color(0xFF1B7F3B)
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
        AppThemeMode.DARK_GRAY -> Color(0xFF0F1210)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFF7F9F8)
    }

val OledCardSurface: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF0B0E0C)
        AppThemeMode.DARK_GRAY -> Color(0xFF171B18)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFFFFFFF)
    }

val OledCardDark: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF121815)
        AppThemeMode.DARK_GRAY -> Color(0xFF1E231F)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFF0F4F2)
    }

val OledSurfaceDark: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF050706)
        AppThemeMode.DARK_GRAY -> Color(0xFF141815)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFF3F6F4)
    }

val OledElevatedCard: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF141916)
        AppThemeMode.DARK_GRAY -> Color(0xFF212723)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE9F0EC)
    }

val OledDivider: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF1B211D)
        AppThemeMode.DARK_GRAY -> Color(0xFF252C27)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE2E8E5)
    }

val OledHairlineDivider: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF1B211D)
        AppThemeMode.DARK_GRAY -> Color(0xFF252C27)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE8EEEB)
    }

// Sol Frame & Başlık Renkleri
val OledTopicUnread: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFFF2F7F4)
        AppThemeMode.DARK_GRAY -> Color(0xFFE4EAE6)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF111814)
    }

val OledTopicRead: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF8A9690)
        AppThemeMode.DARK_GRAY -> Color(0xFF909C95)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF7A867F)
    }

val OledPillGreenBg: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF102819)
        AppThemeMode.DARK_GRAY -> Color(0xFF163020)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE6F5EC)
    }

// Ekşi Yeşili Vurgu Tonları
val EksiNeonGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF74E092)
        AppThemeMode.DARK_GRAY -> Color(0xFF6EE798)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1B7F3B)
    }

val EksiBrightGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF63CE80)
        AppThemeMode.DARK_GRAY -> Color(0xFF78DC96)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF229948)
    }

val EksiDeepGreen: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF102819)
        AppThemeMode.DARK_GRAY -> Color(0xFF163020)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFFE6F5EC)
    }

val EksiSoftMint: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFFB6EDC5)
        AppThemeMode.DARK_GRAY -> Color(0xFFB6EDC5)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF10632F)
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
        AppThemeMode.OLED_BLACK -> Color(0xFFEDF2EE)
        AppThemeMode.DARK_GRAY -> Color(0xFFE4EAE6)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF111814)
    }

val OledTextSecondary: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF9BA89F)
        AppThemeMode.DARK_GRAY -> Color(0xFFA3B0A7)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF4B5750)
    }

val OledTextMuted: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> Color(0xFF6B7A70)
        AppThemeMode.DARK_GRAY -> Color(0xFF74827A)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF7A867F)
    }

val OledErrorRed = Color(0xFFEF5350)
val OledAmberWarning = Color(0xFFFFB74D)
val OledSwipeAuthorBlue: Color
    get() = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK,
        AppThemeMode.DARK_GRAY -> Color(0xFF64B5F6)
        AppThemeMode.CLASSIC_LIGHT -> Color(0xFF1565C0)
    }
