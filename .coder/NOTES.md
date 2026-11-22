# Eksi — Modern Ekşi Sözlük İstemcisi

Jetpack Compose ile yazılmış modern Ekşi Sözlük istemcisi.

## Paket Yapısı
- `ui/` — Compose ekranları, bileşenler ve tema (`ui/theme/`).
- `data/` — Yerel depolama, modeller ve repository katmanı.
- `network/` — Sözlük API istemcisi ve DTO eşlemeleri.
- `ai/` — Akıllı özet/öneri yardımcıları.

## Tema Sistemi
`Color.kt` üç tema modu tanımlar (`AppThemeMode`):
- `OLED_BLACK` — Saf siyah arka plan, minimal yüzeyler.
- `DARK_GRAY` — Koyu arduvaz-zümrüt tonları.
- `CLASSIC_LIGHT` — Modern kağıt/slate-zümrüt açık tema.

Aktif tema `ActiveThemeState` ile reaktif tutulur; `Theme.kt` içindeki
`ColorScheme`, `EksinShapes` ve edge-to-edge sistem çubukları bu moda göre
güncellenir. Tercih `ThemePreferencesManager` ile `SharedPreferences`'a
`selected_theme_mode` anahtarı altında yazılır.

## Kurallar
- Mevcut public sembol isimleri ve `storageKey` değerleri korunur.
- UI tamamen `MaterialTheme` semantik renkleri üzerinden çizilir.
- Tema değişimleri anında yeniden çizilir (recompose).

## Derleme ve Test
GitHub Actions:
```
./gradlew testDebugUnitTest assembleDebug
```
