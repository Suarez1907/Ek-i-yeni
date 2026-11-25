# Changelog

Tüm önemli değişiklikler bu dosyada listelenir.

## [1.2.1] - 2026-11-25
### Düzeltilen
- Sürüm artışı (versionCode 4); v1.2.0 sonrası CI/bulut araç (coder-toolbox, coder-index) ve NOTES.md güncellemelerini içeren bakım sürümü.

## [1.2.0] - 2026-11-23
### Eklenen
- Ayarlar ekranı (`SettingsBottomSheet`): Ekşi Sözlük giriş/çıkışı, Gemini API anahtarı yönetimi, tema seçimi ve Cloudflare kısayolları.
- Profil ekranları: `ProfileScreen` (kendi profilim) ve `AuthorProfileScreen` (yazar profili) — yazar istatistikleri, son entry'ler ve mesaj thread'leri.
- Entry girme: `EntryComposerBottomSheet` ile başlık detayından entry gönderme, doğrulama token'ı ile POST akışı.
- Mesaj gönderme: `SendMessageDialog` ile reply token bazlı mesaj gönderimi.
- Tema sistemi: `OLED_BLACK`, `DARK_GRAY` ve `CLASSIC_LIGHT` modları; seçim kalıcı olarak saklanır.
### Düzeltilen
- Topic detay ekranında pager bar taşması ve FAB ile çakışma sorunu.
- Profil ve mesaj ekranlarındaki derleme hataları, form token doğrulaması ve form sonuç alan eşleşmeleri.

## [1.0.2]
### Düzeltilen
- Cloudflare koruması aşılamayan istekler için AJAX başlıkları ve referer yönlendirmesi.

## [1.0.1]
### Düzeltilen
- Tema değişiminde anında yeniden çizim sorunu.

## [1.0.0]
- İlk sürüm: başlık listeleme, entry okuma, Paging 3 ön yükleme, Gemini AI özetleri.