# TVKu — Arsitektur & Roadmap Pengembangan

Versi 1.1 · 15 November 2026 · Status: **M1 selesai (5/5 suite tes SQL lulus) · M2 berikutnya**
Repo: https://github.com/edulazycreator-beep/TVKu

---

## 1. Hasil inspeksi lingkungan

| Komponen | Status di sandbox pengembangan | Dampak |
|---|---|---|
| JDK | OpenJDK 21 ✅ | Cukup untuk AGP/Gradle |
| Gradle | 8.14.3 terpasang ✅ | — |
| Android SDK / build-tools | **Tidak ada** ❌ | — |
| `dl.google.com`, `maven.google.com` | **Diblokir kebijakan jaringan (403)** ❌ | SDK, AGP, AndroidX, Compose, Media3 tidak bisa diunduh |
| Maven Central, Gradle Plugin Portal | **Diblokir (403)** ❌ | Kotlin, Hilt, dll. tidak bisa diunduh |
| GitHub / raw.githubusercontent.com | Bisa diakses ✅ | Data iptv-org bisa dibaca untuk *discovery* |
| npm, PyPI | Bisa diakses ✅ | Deno, Supabase CLI, tooling dashboard tersedia |
| PostgreSQL 16 (server lokal) | Tersedia ✅ | Migrasi & RLS bisa diuji nyata di sini |
| Host stream acak, Supabase cloud, Firebase | **Diblokir** ❌ | Validasi playback & deploy tidak bisa dari sandbox |
| Repositori TVKu | Dibuat dan tersambung ✅ | CI GitHub Actions bisa dipakai |
| Repo `WorldTV` milik Anda | Aplikasi **macOS SwiftUI** | Bukan basis Android; hanya referensi konsep (parser M3U, validator) |

### Konsekuensi (dijelaskan terbuka)

1. **APK tidak bisa dikompilasi di sandbox ini.** Semua build, unit test, dan instrumented test Android dijalankan di **GitHub Actions** (runner GitHub punya akses penuh ke Google Maven). Setiap milestone Android baru dinyatakan "lulus build" setelah workflow CI benar-benar hijau — bukan sebelumnya.
2. **Validasi stream tidak bisa dijalankan dari sandbox.** Pipeline validasi berjalan di GitHub Actions (terjadwal) dan/atau mesin admin. Sampai pipeline itu benar-benar berjalan, semua channel tetap `DISCOVERED`/`PENDING_REVIEW`.
3. **Backend database bisa diuji nyata sekarang** di Postgres 16 lokal dengan stub skema `auth` Supabase. Karena itu milestone pertama adalah skema + RLS (kontrak data yang dipakai Android, Edge Functions, dan dashboard).

---

## 2. Arsitektur sistem

```
┌───────────────────────────┐        HTTPS + App Check token
│  Android app (Kotlin)     │ ───────────────────────────────┐
│  Compose · Media3 · Room  │                                 ▼
└───────────────────────────┘                  ┌──────────────────────────────┐
                                               │ Supabase Edge Functions (v1) │
┌───────────────────────────┐   Supabase Auth  │  register-installation       │
│  Admin dashboard (web)    │ ───────────────▶ │  get-entitlement / start-trial│
│  React + TS, admin role   │   + MFA          │  create-donation             │
└───────────────────────────┘                  │  channels / countries / ...  │
                                               │  admin/* (role check)        │
┌───────────────────────────┐  service token   └──────────────┬───────────────┘
│  Stream validation runner │ ──────────────────────────────▶ │ SECURITY DEFINER
│  GitHub Actions (cron)    │                                  ▼ functions only
│  Tier A: HTTP/manifest/   │                  ┌──────────────────────────────┐
│          ffprobe          │                  │ Supabase PostgreSQL + RLS    │
│  Tier B: Media3 on emulator│                 │  channels, streams, trials,  │
└───────────────────────────┘                  │  donations, entitlements,    │
                                               │  health logs, audit logs     │
                                               └──────────────────────────────┘
```

Prinsip keamanan inti:
- Android **tidak pernah** menulis langsung ke tabel. Semua mutasi lewat Edge Function → fungsi SQL `SECURITY DEFINER` yang memvalidasi kepemilikan.
- Waktu trial/premium **selalu** `now()` server. Jam Android tidak dipakai.
- `service_role` hanya ada di Supabase dan GitHub Secrets.
- Klien publik hanya bisa membaca katalog yang sudah **VERIFIED + izin disetujui**.

### 2.1 Android — struktur modul (diadaptasi)

Struktur di prompt diadaptasi ke pola modul Gradle yang sudah terbukti (gaya *Now in Android*), dengan `data/` dan `domain/` dipindah ke `core/` agar fitur tidak saling bergantung:

```
app/                       — Application, MainActivity, NavHost, Hilt root
build-logic/convention/    — convention plugins (android-library, compose, hilt, feature)
gradle/libs.versions.toml  — version catalog tunggal
core/
  common/      — dispatcher, Result, util
  model/       — model domain murni Kotlin (Channel, Stream, Entitlement, ...)
  designsystem/— token warna/tipografi/spasi/bentuk/animasi + komponen
  ui/          — komponen UI lintas fitur (ChannelCard, StatusBadge, EmptyState)
  network/     — klien Edge Function (Ktor/OkHttp + kotlinx.serialization), App Check
  database/    — Room (cache katalog, favorit, riwayat)
  datastore/   — DataStore (preferensi)
  data/        — repository implementasi + mapper + sync
  domain/      — use case
  player/      — PlayerManager (instance tunggal Media3), state machine, retry/backoff
  security/    — InstallationIdentity (UUID + secret di Keystore)
  testing/     — fakes, test rules
feature/
  onboarding, home, live, player, search, countries, categories,
  favorites, history, donation, premium, settings
```

Keputusan teknis Android:
- **minSdk 26** (Android 8.0): diperlukan untuk perilaku `ANDROID_ID` yang ter-scope per aplikasi (lihat D1) dan menyederhanakan PiP; cakupan perangkat tetap sangat luas.
- **targetSdk / AGP / Kotlin / Compose BOM / Media3**: versi stabil terbaru, diverifikasi saat M2 dari dokumentasi resmi (tidak ditebak).
- **Satu instance ExoPlayer** dikelola `PlayerManager` berumur proses, dilepas saat tidak dipakai — mencegah multi-instance dan kebocoran memori.
- Status pemutar adalah *state machine* eksplisit: `Idle → Connecting → Buffering ⇄ Playing → RecoverableError → (retry backoff) → Unavailable / GeoRestricted / UnsupportedFormat`. Badge **LIVE** hanya tampil saat `Player.isPlaying == true` dan frame pertama sudah dirender.

### 2.2 Backend

- **Supabase PostgreSQL**: 10 tabel wajib + `admin_users`, `donation_events`, `premium_entitlement_events` untuk audit, enum status, index, FK, unique constraint, RLS di semua tabel.
- **Edge Functions (Deno/TS)** di bawah prefix `/v1/`: validasi input (zod), rate limit per instalasi/IP (tabel token-bucket), idempotency key untuk approval donasi, log terstruktur.
- **Katalog**: `GET /v1/channels` mengembalikan snapshot ringkas dengan `ETag` + `updated_since` untuk sinkron delta → Room. Tidak ada unduhan playlist M3U di klien.

### 2.3 Admin dashboard

React + TypeScript + Vite, login Supabase Auth dengan **MFA wajib**, peran di tabel `admin_users` (`owner`, `admin`, `reviewer`). Semua aksi admin memanggil Edge Function `admin/*` yang memeriksa peran di server dan menulis `admin_audit_logs`. Bisa di-host statis (GitHub Pages/Netlify) karena tidak memegang rahasia.

### 2.4 Pipeline channel: discovery → izin → validasi → publikasi

```
iptv-org / sumber resmi ──▶ DISCOVERED ──(dedupe, normalisasi)──▶ PENDING_REVIEW
                                                         │
                     admin menilai izin sumber ◀─────────┘   permission_status
                                                         │
          Tier A (otomatis, GitHub Actions):             ▼
          URL/SSRF guard → DNS → HTTP → redirect → content-type →
          parse HLS/DASH → unduh segmen → ffprobe decode            ──▶ TESTING
                                                         │
          Tier B (produksi): Media3 di emulator Android, ≥60 dtk,
          cek frame video + sampel audio terdekode, reconnect      ──▶ VERIFIED / DEGRADED /
                                                                       OFFLINE / GEO_RESTRICTED
Publikasi = validation_status VERIFIED  AND  permission_status disetujui admin
```

- Status validasi: `DISCOVERED, PENDING_REVIEW, TESTING, VERIFIED, DEGRADED, GEO_RESTRICTED, OFFLINE, REJECTED, REMOVED`.
- Status izin (terpisah): `UNREVIEWED, OFFICIAL_PUBLIC_SOURCE, EXPLICIT_PERMISSION, DENIED, TAKEDOWN`. Sengaja tidak ada status "dapat diakses publik", karena akses publik bukan izin.
- Kegagalan sementara (DNS/CDN) dihitung dengan jendela berulang sebelum turun status; channel dipulihkan otomatis setelah lolos uji ulang. Fallback hanya antar-stream milik **channel yang sama**.
- Setiap uji tercatat di `stream_health_logs` dengan tanggal, durasi, resolusi terdeteksi, dan alasan gagal — tidak ada hasil yang diisi manual.

---

## 3. Keputusan desain yang perlu persetujuan Anda

**D1 — Trial tidak reset saat "Clear data".**
UUID acak per instalasi ikut terhapus saat data aplikasi dihapus, jadi UUID saja **tidak bisa** memenuhi aturan ini. Usulan: kirim juga `HMAC-SHA256(ANDROID_ID, pepper_server)` sebagai *sinyal anti-penyalahgunaan trial saja*. Sejak Android 8, `ANDROID_ID` ter-scope per kunci penandatangan aplikasi + pengguna + perangkat, bertahan saat clear data/reinstall, berubah saat factory reset. Ini **bukan** IMEI/serial/MAC, tidak bisa dipakai lintas aplikasi, dan hanya disimpan dalam bentuk hash. Wajib diungkap di kebijakan privasi. Alternatif tanpa ini: trial bisa diulang dengan clear data.
→ *Rekomendasi: setujui.*

**D2 — Rahasia instalasi.** UUID muncul di pesan WhatsApp, jadi UUID tidak boleh menjadi kredensial. Saat registrasi server memberi `installation_secret` acak (disimpan hanya hash-nya di DB, di Android disimpan terenkripsi Keystore). Pesan WhatsApp memakai kode pendek `TVK-XXXX-XXXX`, bukan UUID penuh.

**D3 — Dataset awal yang disetujui bisa berjumlah 0.** Saya bisa mengumpulkan kandidat dan bukti (mis. stream yang di-embed di situs resmi penyiar publik), tetapi keputusan izin hukum adalah keputusan Anda sebagai pemilik produk. Tidak ada channel yang dipublikasikan tanpa review admin.

**D4 — Sudut pandang geo.** Runner GitHub Actions berlokasi di AS; status `GEO_RESTRICTED` hanya valid dari lokasi itu. Untuk Asia Tenggara, validator bisa juga dijalankan dari mesin/VPS Anda (skrip yang sama).

---

## 4. Roadmap milestone

| # | Milestone | Isi | Diverifikasi dengan | Bergantung pada |
|---|---|---|---|---|
| M0 | Fondasi rencana | Inspeksi, arsitektur, roadmap | Dokumen ini | — |
| **M1** | **Skema backend & keamanan** | Migrasi SQL, enum, index, RLS, fungsi trial/donasi/premium server-side, seed config | Tes SQL nyata di Postgres 16 lokal | — |
| M2 | Kerangka Android + design system | Proyek Gradle multi-modul, version catalog, tema, navigasi, splash, onboarding, CI | Workflow GitHub Actions hijau (build + unit test + lint) | **Repo GitHub** |
| M3 | Inti streaming | PlayerManager Media3, HLS/DASH, state machine, retry/backoff, track subtitle/audio, fullscreen, PiP | Unit test state machine + instrumented test di emulator CI dengan stream uji milik sendiri | M2 |
| M4 | Discovery | Parser M3U + importer iptv-org → `DISCOVERED`, dedupe, normalisasi negara/kategori/bahasa | Unit test parser; laporan jumlah *discovered* | M1 |
| M5 | Validasi stream | Tier A (TS/Deno + ffprobe, SSRF guard) & Tier B (Media3 di emulator), laporan validasi | Tes terhadap fixture HLS/DASH lokal (valid, 404, timeout, manifest rusak, segmen hilang, codec tak didukung) | M1, M3, repo |
| M6 | Edge Functions | Semua endpoint `/v1/*`, skema request/response, rate limit, idempotensi | Tes Deno terhadap Postgres lokal | M1, Supabase |
| M7 | Fitur penemuan Android | Home, Live TV, Negara, Kategori, Search, Favorit, Riwayat, offline | Unit + UI test di CI | M2, M6 |
| M8 | Trial & premium | Identitas instalasi, trial 5 jam, donasi + WhatsApp, sinkron entitlement | Unit + integrasi | M6 |
| M9 | Admin dashboard | Overview, channel, stream monitor, donasi, user, konfigurasi | Tes komponen + e2e | M6 |
| M10 | Keamanan & QA | App Check, audit RLS, privasi, uji stabilitas | Laporan uji nyata | semua |
| M11 | Rilis | Signing, APK rilis, dokumentasi instalasi, laporan akhir | APK dari CI | kredensial rilis |

---

## 5. Milestone pertama: M1 (dimulai sekarang)

Alasan dimulai dari backend: ini satu-satunya lapisan yang bisa **diuji nyata** di lingkungan saat ini, dan menjadi kontrak data untuk Android, Edge Functions, dan dashboard.

Kriteria selesai M1:
- Semua migrasi berjalan bersih di Postgres 16 dari database kosong.
- Tes otomatis membuktikan: anon hanya membaca katalog yang dipublikasikan; pengguna tidak bisa menyetujui donasi sendiri, mengubah premium, atau mengubah kedaluwarsa trial; trial dihitung dari waktu server (18.000 detik) dan tidak bisa dimulai dua kali; approval donasi idempoten; setiap aksi admin tercatat di audit log.

---

## 6. Kredensial & akses eksternal yang dibutuhkan

| Kebutuhan | Untuk | Kapan | Cara diberikan |
|---|---|---|---|
| ~~Repo GitHub `TVKu`~~ | Kode, CI build APK, validator | ✅ Selesai | — |
| Proyek Supabase (URL, anon key, project ref) | Backend online | M6 | anon key boleh di app; nilai lain di GitHub Secrets |
| Supabase service role key + DB password + access token CLI | Deploy migrasi & functions | M6 | **Hanya** GitHub Secrets, jangan dikirim di chat |
| Proyek Firebase + `google-services.json` | App Check / Play Integrity, Analytics opsional | M10 | GitHub Secret (base64) |
| Keystore rilis | Penandatanganan APK | M11 | Anda buat sendiri; simpan sebagai GitHub Secrets |
| Nomor WhatsApp admin | Alur donasi | M8 | Diisi di dashboard (`app_config`), bukan di kode |
| Instruksi pembayaran (bank/e-wallet) | Layar donasi | M8 | Diisi di dashboard |
| Email akun admin | Login dashboard | M9 | Dibuat di Supabase Auth + MFA |
| URL publik kebijakan privasi | Onboarding & Play policy | M10 | Bisa dihosting bersama dashboard |

---

## 7. Batasan yang diketahui sejak awal

- Identitas instalasi tidak pernah sempurna; factory reset atau perangkat lain akan menghasilkan trial baru. Penautan akun opsional membantu mempertahankan premium.
- Play Integrity bisa gagal untuk APK yang dipasang di luar Play Store; diperlakukan sebagai sinyal, bukan pemblokir.
- Target performa (startup, memori, scrolling) hanya bisa diklaim setelah diukur di perangkat nyata — perlu perangkat uji Anda di M10.
- Jumlah channel akan jauh lebih kecil dari playlist mentah karena hanya VERIFIED + disetujui yang tampil. Ini disengaja.
