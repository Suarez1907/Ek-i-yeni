# Eksi — Modern Ekşi Sözlük İstemcisi

Jetpack Compose ile yazılmış modern Ekşi Sözlük istemcisi.

## Paket Yapısı
- `ui/` — Compose ekranları, bileşenler ve tema (`ui/theme/`).
- `data/` — Yerel depolama, modeller ve repository katmanı.
- `network/` — Sözlük API istemcisi ve DTO eşlemeleri.
- `ai/` — Akıllı özet/öneri yardımcıları (OpenRouter).

## Tema Sistemi
`Color.kt` üç tema modu tanımlar (`AppThemeMode`):
- `OLED_BLACK` — Saf siyah arka plan, minimal yüzeyler.
- `DARK_GRAY` — Koyu arduvaz-zümrüt tonları.
- `CLASSIC_LIGHT` — Modern kağıt/slate-zümrüt açık tema.

Aktif tema `ActiveThemeState` ile reaktif tutulur; tercih
`ThemePreferencesManager` ile `selected_theme_mode` anahtarı altında saklanır.

## AI (OpenRouter)
- API anahtarı `.env.example` -> `BuildConfig.OPENROUTER_*` ile sağlanır.
- Gemini/DeepSeek entegrasyonları kapalı; uyumluluk için isimleri korunur.
- WebView köprüsü: `WebViewFetchBridge.fetchHtml` / `WebViewFetchBridge.postForm`.

## Oturum ve İçerik
- Ekşi girişi WebView + çerez ile yapılır; entry ve mesaj aynı oturumu paylaşır.
- Otomatik tamamlama: 1+ harf sonrası 250ms debounce ile öneri kuralı.

## Kurallar
- Mevcut public sembol isimleri ve `storageKey` değerleri korunur.
- UI tamamen `MaterialTheme` semantik renkleri üzerinden çizilir.

## Derleme ve Test
CI: `./gradlew testDebugUnitTest assembleDebug`
