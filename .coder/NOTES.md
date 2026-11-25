# Eksi — Modern Ekşi Sözlük İstemcisi

Amaç: Jetpack Compose ile yazılmış modern Ekşi Sözlük Android istemcisi.

## Yapı
- `data/repository/EksiRepository.kt` — API çağrıları, entry gönderimi ve arama önerileri.
- `data/remote/EksiParser.kt` — HTML/API yanıtlarının modellere dönüştürülmesi.
- `network/WebViewFetchBridge.kt` — Çerezli AJAX ve form POST işlemleri.
- `ui/EksinViewModel.kt` — Ekran durumu, yerel/uzak veri birleştirme.
- `ui/MainScaffold.kt` — Ana ekran, sekmeler ve öneri kartları.

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
  entry sekmeleri ve mesaj thread'lerini listeler; `MainScaffold` "Profilim" sekmesi
  ve `activeAuthorNick` overlay'i ile açılır.
- Entry yazma — `EntryComposerBottomSheet` başlık detayındaki "Entry Gir" FAB'ından
  açılır; doğrulama token'ı `parseEntryFormMetadata` ile alınır.
- Mesaj gönderme — `SendMessageDialog` reply token ile POST isteğini tetikler.
- Ayarlar — `MainScaffold` üst çubuğundaki dişli ikonu `SettingsBottomSheet` açar.

## Kritik Kurallar
- Entry gönderimi `POST /entry/ekle` adresine yapılır; bu, form action URL'sidir.
  Başlık URL'sine POST göndermek 404 döner.
- Profil sekmeleri son-entryleri, en-cok-favorilenen-entryleri, en-begenilenleri ve
  favori-entryleri endpoint'lerinden AJAX ile alınır; çerez gerekir ve istekler
  `WebViewFetchBridge` üzerinden yapılır.
- Autocomplete `/autocomplete/query` AJAX endpoint'ini kullanır; hata/boş sonuçta
  `/basliklar/ara` düz GET adresine düşer.
- Yerel eşleşmeler uzak sonuçla birleştirilir; uzak sonuç boşsa yerel öneriler korunur.
- Mevcut public sembol isimleri ve `storageKey` değerleri korunur.
- UI tamamen `MaterialTheme` semantik renkleri üzerinden çizilir.

## Tuzaklar
- Profil entry kodu `main` dalında yoktur; özellik dalı `feat/author-profile-entries-tabs`'tir.
- Canlı testlerde bazı endpoint'ler çerez olmadan 500 dönebilir.
- Tema değişimleri anında yeniden çizilir (recompose).

## Derleme ve Test
GitHub Actions derlemesi: `./gradlew testDebugUnitTest assembleDebug`
