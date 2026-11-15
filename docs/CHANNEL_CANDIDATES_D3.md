# D3 — Kandidat Channel untuk Review Izin

Versi 4 · 15 November 2026 · Sumber: iptv-org (`index.m3u` + database) dan daftar kurasi TVKu
Skrip: `scripts/stream-discovery/discover_iptv_org.py` → `scripts/database-import/build_candidate_seed.py`

> **Penting.** Isinya adalah **petunjuk untuk direview**, bukan channel yang sudah disetujui. Semua kandidat masuk ke database sebagai `DISCOVERED` / `UNREVIEWED`, dengan channel dalam keadaan nonaktif. Tes otomatis (`test_candidate_seed.sql`) memastikan tidak satu pun kandidat yang tampil di aplikasi. Channel baru muncul setelah lolos pemeriksaan **dan** izinnya Anda setujui.

---

## 1. Tiga cara TVKu menampilkan channel

| Jenis | Cara kerja di aplikasi | Bukti teknis yang dibutuhkan | Contoh |
|---|---|---|---|
| **Stream langsung** (HLS/DASH) | Diputar di player TVKu (Media3) | Tier B: diputar ≥ 60 detik, video dan audio terdekode, reconnect berhasil | TVRI regional, Metro TV |
| **Embed YouTube resmi** | Player resmi YouTube di dalam TVKu | YouTube Data API: channel benar, sedang live, embed diizinkan | KompasTV, iNews, tvOne |
| **Tautan platform resmi** | TVKu membuka aplikasi atau situs resmi (Vidio, RCTI+, dll.) | Halaman resmi merespons HTTP 2xx/3xx | Indosiar, SCTV, ANTV, RCTI |

Ketiganya tetap harus melewati review izin oleh Anda. Di aplikasi, tautan platform resmi diberi label jelas, misalnya "Tonton di Vidio", dan tidak pernah ditampilkan seolah diputar di TVKu.

---

## 2. Hasil discovery

| Kategori | Entri | Keterangan |
|---|---:|---|
| Total entri iptv-org | **11.502** | |
| ❌ Host berupa alamat IP mentah | 1.407 | Termasuk relay RCTI, SCTV, Indosiar, GTV, dan TVRI |
| ❌ Lewat layanan redirect pihak ketiga | 1.170 | |
| ❌ Butuh User-Agent atau Referer palsu | 830 | |
| ❌ Host relay yang dikenal | 422 | |
| ❌ Link bertoken atau berkedaluwarsa | 95 | |
| ❌ Channel sudah tutup / protokol tidak didukung | 86 | |
| ❌ **Hak siar berbayar atau acara** (ESPN, beIN, DAZN, F1, NHL, NBA, dll.) | 14 | Tidak dimasukkan dengan cara apa pun |
| ⚠️ Perlu bukti manual | 6.478 | |
| ✅ **Kandidat stream** | **1.000** | 682 di domain resmi, 310 di domain bermerek, 8 di domain pemerintah |

Ditambah 22 entri kurasi, database diisi **1.016 channel** dan **1.017 sumber**.

---

## 3. Indonesia

### Stream langsung — 44 kandidat
- **TVRI**: 30 feed regional dan nasional, ditambah TVRI World (domain pemerintah `tvri.go.id`).
- **RRI Net** (`rri.go.id`) dan **Metro TV** (`edge.medcom.id`, Media Group).
- Domain bermerek nama channel: Jogja TV, Rodja TV, DAAI TV, Lingkar TV, Rinjani TV.
- Domain situs resmi: Al-Iman TV, Salira TV, Dens Food Channel, Biznet Adventure/Kids/Lifestyle.
- TVKU Semarang (lihat bagian 6).

### Embed YouTube resmi — 5
| Channel | Handle | Status handle |
|---|---|---|
| KompasTV | `@kompastv` | ✅ terkonfirmasi (Socialblade, HypeAuditor) |
| Metro TV | `@metrotvnews` | ✅ terkonfirmasi (HypeAuditor) |
| iNews | `@OfficialiNews` | ✅ terkonfirmasi (HypeAuditor) |
| tvOne | `@tvOneNews` | ⚠️ belum terkonfirmasi |
| CNN Indonesia | `@CNNIndonesiaOfficial` | ⚠️ belum terkonfirmasi |

### Tautan platform resmi — 11 (TV swasta besar)
| Channel | Dibuka di | URL resmi |
|---|---|---|
| Indosiar | Vidio | `vidio.com/live/indosiar` |
| SCTV | SCTV | `sctv.co.id/live-streaming/` |
| ANTV | ANTV Klik | `antvklik.com` |
| RCTI, MNCTV, GTV | RCTI+ | `rctiplus.com/tv/…` |
| Trans TV | Trans TV | `transtv.co.id/live` |
| Trans7 | Trans7 | `trans7.co.id/live-streaming` |
| NET TV | NET | `netmedia.co.id/live-streaming` |
| BTV | BeritaSatu | `beritasatu.com/btv-live-streaming` |
| TVRI Sport | TVRI | `tvri.go.id/live` |

**Mengapa Indosiar, SCTV, ANTV, RCTI, dan sejenisnya tidak bisa diputar langsung di TVKu:**
- Live streaming resmi mereka hanya ada di platform milik grup masing-masing: Vidio (Emtek), RCTI+/MNC Now (MNC), dan situs Trans Media.
- Platform tersebut memakai login, DRM, dan pembatasan wilayah.
- Satu-satunya stream yang bisa diputar langsung di iptv-org berasal dari relay alamat IP tanpa izin.

Memutar atau meng-embed siaran mereka di dalam TVKu butuh **perjanjian kerja sama** dengan grup media terkait. Sampai ada kerja sama, tautan ke platform resmi adalah cara yang legal.

---

## 4. Olahraga

| Sumber | Jenis | Catatan |
|---|---|---|
| **TVRI Sport** | Tautan resmi (`tvri.go.id/live`) | Penyiar publik. Acara tertentu bisa diblokir di siaran web karena hak siar. |
| MNB Sport (MN), TDM Sports (MO) | Stream | Penyiar publik, domain sendiri |
| TJK TV 1/2 (TR), Trace Sport Stars (FR), Sportitalia Solocalcio, TR Sport (IT), dll. | Stream | Hak siar perlu dicek satu per satu |
| MRTV Sports (MM) | Stream | ⚠️ Penyiar negara Myanmar; perlu cek sanksi. Disarankan dikecualikan. |

ESPN, beIN, DAZN, TNT Sports, Eurosport, Fox Sports, serta stream acara seperti F1, NHL, dan NBA **dikecualikan otomatis**. Embed tidak mengubah status hukumnya.

---

## 5. Yang dibutuhkan untuk menjalankan pemeriksaan

- **YouTube Data API key** sebagai GitHub Secret `YOUTUBE_API_KEY`. Satu pemeriksaan memakai 3 unit kuota.
- **Proyek Supabase** untuk menyimpan hasil pemeriksaan dan mengaktifkan katalog.

---

## 6. Temuan penting: nama "TVKU" sudah dipakai

Ada stasiun TV lokal bernama **TVKU** di Semarang (`tvku.tv`). Sebelum rilis di Play Store, sebaiknya cek apakah nama "TVKu" terdaftar sebagai merek di PDKI (pdki-indonesia.dgip.go.id).

---

## 7. Alur dari kandidat ke channel publik

1. **Seed** (sudah ada): semua sumber masuk sebagai `DISCOVERED` / `UNREVIEWED`, channel nonaktif.
2. **Pemeriksaan otomatis** sesuai jenis sumber (bagian 1). Status `VERIFIED` hanya diberikan berdasarkan bukti.
3. **Review izin** oleh Anda dengan catatan bukti.
4. **Aktifkan** channel → tampil di aplikasi.

---

## 8. Channel IPTV di aplikasi (15 November 2026)

Atas permintaan pemilik ("Buatkan yang dari iptv saja"), stream iptv-org kini diputar langsung di TVKu dengan Media3. Alurnya berjalan otomatis lewat workflow **Stream validation** (manual, plus jadwal mingguan):

1. **Discovery** dari data iptv-org → 1.000 kandidat.
2. **Kebijakan publikasi** (`scripts/stream-validation/publication_policy.json`), dengan 602 kandidat lolos:
   - Diterima: host stream di domain resmi penyiar atau domain pemerintah.
   - Domain bermerek hanya diterima untuk channel yang disetujui satu per satu (Jogja TV, Rodja TV, DAAI TV, Lingkar TV, Rinjani TV).
   - Ditolak:
     - 93 non-HTTPS.
     - 249 domain bermerek yang belum disetujui.
     - 25 feed khusus mitra distribusi (Samsung TV Plus, Pluto, dll.).
     - 31 karena kebijakan sanksi (IRIB/Press TV, NTV/Gazprom, VGTRK, MRTV, Krimea).
3. **Tier A** (probe manifest, dari runner AS): 602 diperiksa, 430 berhasil, 45 dibatasi wilayah, 62 gagal permanen, 65 gagal sementara.
4. **Tier B** (emulator Android API 34, konfigurasi Media3 yang sama dengan aplikasi):
   - Kriteria: setiap stream diputar ≥ 60 detik, video dan audio terdekode, dan reconnect berhasil.
   - Hasil: **392 dari 430 lolos**, 33 gagal sementara, 3 gagal permanen, 1 dibatasi wilayah, 1 format tidak didukung.
5. **Katalog** hanya memuat stream yang lolos Tier B: 406 channel, terdiri dari 390 dengan stream terverifikasi, 4 YouTube resmi, dan 12 tautan platform.
   - Indonesia mendapat 36 stream terverifikasi.
6. **Tes end-to-end aplikasi** di emulator: stream terverifikasi tampil di Home dan benar-benar diputar di player TVKu (status "Playing", resolusi 720p terukur).

Bukti per stream ada di rilis `stream-validation` (`validation-report.json`, `tier-a.json`, `policy-report.json`, `verified-streams.json`).

**Batasan yang perlu diketahui:**
- Domain resmi adalah bukti asal stream, **bukan lisensi**. Penyiar dapat meminta penghapusan kapan saja; channel tersebut cukup ditambahkan ke daftar pengecualian di kebijakan.
- Pengujian dilakukan dari server di AS. Ada stream yang lolos di sana tetapi bisa dibatasi wilayah di negara lain, atau sebaliknya. Karena itu aplikasi menampilkan status yang jujur ("dibatasi wilayah" atau "offline") dan tidak pernah menampilkan LIVE palsu.
- Stream bisa mati sewaktu-waktu. Validasi ulang mingguan menghapus stream yang tidak lagi lolos.
