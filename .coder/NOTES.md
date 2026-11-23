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

## Öne Çıkan Akışlar
- Profil/Mesajlar — `ProfileScreen` ve `AuthorProfileScreen` yazar istatistikleri,
  son entry'ler ve mesaj thread'lerini listeler; `MainScaffold` "Profilim" sekmesi
  ve `activeAuthorNick` overlay'i ile açılır.
- Entry yazma — `EntryComposerBottomSheet` başlık detayındaki "Entry Gir" FAB'ından
  açılır; doğrulama token'ı `parseEntryFormMetadata` ile alınır, gönderim
  `WebViewFetchBridge.postForm` POST akışıyla yapılır.
- Mesaj gönderme — `SendMessageDialog` reply token ile POST isteğini tetikler.
- Ayarlar — `MainScaffold` üst çubuğundaki dişli ikonu `SettingsBottomSheet` açar; Ekşi Sözlük giriş/çıkışı, Gemini API anahtarı, tema ve Cloudflare kısayolları buradan yönetilir.

## Kurallar
- Mevcut public sembol isimleri ve `storageKey` değerleri korunur.
- UI tamamen `MaterialTheme` semantik renkleri üzerinden çizilir.
- Tema değişimleri anında yeniden çizilir (recompose).

## Sürümleme
- Sürüm bilgisi `app/build.gradle.kts` içindeki `versionCode`/`versionName` alanlarındadır.
- Release akışı: sürüm artışı PR ile `main`'e -> `.github/workflows/release.yml` `workflow_dispatch`
  (input: `tag`, `title`) -> GitHub Release + `app-release.apk` / `app-debug.apk` varlıkları.
- Sürüm notları `CHANGELOG.md` dosyasında tutulur.

## İmzalama (Keystore)
- Sabit imza anahtarı `keystore/debug.keystore.b64` (base64 PKCS12, alias `androiddebugkey`,
  store/key password `android`) olarak repoda tutulur.
- CI iş akışları (`release.yml`, `android-apk.yml`) bu dosyayı `debug.keystore` olarak çözer;
  yoksa geçici anahtar üretir. Böylece APK'lar her derlemede aynı imzayı alır ve
  üzerine kurulabilir.
- `tools/gen_keystore.py` ile üretilir, `tools/verify_keystore.py` ile doğrulanır.

## Derleme ve Test
GitHub Actions:
```
./gradlew testDebugUnitTest assembleDebug
```
