# D3 — Kandidat Channel untuk Review Izin

Versi 1 · 15 November 2026 · Sumber data: iptv-org (`index.m3u` + database), diambil hari ini dari raw.githubusercontent.com
Skrip: `scripts/stream-discovery/discover_iptv_org.py` (bisa dijalankan ulang kapan saja)

> **Penting.** Dokumen ini berisi **petunjuk untuk review**, bukan daftar channel yang disetujui. Belum ada satu pun channel yang diuji pemutarannya (validator Tier B dibangun di M5) dan belum ada satu pun yang izinnya disetujui. Status semua kandidat di database tetap `DISCOVERED` / `UNREVIEWED` sampai Anda memutuskan.

---

## 1. Hasil discovery otomatis

| Kategori | Jumlah entri | Arti |
|---|---:|---|
| Total entri playlist iptv-org | **11.502** | Semua URL unik |
| ❌ Lewat layanan redirect pihak ketiga (mis. `jmp2.uk`) | 1.170 | Bukan sumber penyiar; tidak diimpor |
| ❌ Butuh User-Agent / Referer palsu | 830 | Indikasi stream dilindungi; tidak diimpor |
| ❌ URL berisi token / tanda tangan / kedaluwarsa | 120 | Link bertoken; dilarang oleh aturan proyek |
| ❌ Channel sudah tutup | 71 | — |
| ❌ Protokol tak didukung (RTMP, RTSP, SRT, MMS) | 15 | Tidak bisa diputar aplikasi |
| ⚠️ Perlu bukti manual | 8.612 | Host stream bukan domain penyiar (CDN atau pihak ketiga). Bisa resmi (mis. DW di Akamai), bisa juga relay tidak sah. Tidak bisa dibedakan otomatis. |
| ✅ **Kandidat domain resmi** | **684** | Host stream berada di domain situs resmi penyiar itu sendiri |

Dari 684 kandidat: **623 HTTPS**, **616 channel**, **104 negara**. Daftar lengkap ada di file `review_candidates.csv` (dikirim terpisah, tidak di-commit ke repo publik).

"Domain resmi" adalah **sinyal teknis terkuat yang bisa dideteksi otomatis**, tetapi **bukan izin**. Penyiar bisa saja menyediakan stream hanya untuk pemutar di situsnya sendiri, hanya untuk penonton dalam negeri, atau melarang penggunaan ulang di aplikasi pihak ketiga.

---

## 2. Shortlist yang disarankan untuk direview lebih dulu

Dipilih dari kandidat domain resmi + penyiar internasional yang diketahui menyiarkan gratis ke seluruh dunia. Kolom "Langkah izin" adalah apa yang perlu dilakukan sebelum status bisa diubah ke `OFFICIAL_PUBLIC_SOURCE` atau `EXPLICIT_PERMISSION`.

### Prioritas A — penyiar internasional (target audiens global)

| Channel | Negara | Bukti teknis | Langkah izin |
|---|---|---|---|
| France 24 (AR, ES) | FR | Stream di `live.france24.com` | Baca syarat penggunaan France Médias Monde; hubungi tim distribusi untuk izin tertulis penggunaan di aplikasi |
| TV5Monde | FR | `ott.tv5monde.com` | Cek syarat; feed ini berlabel "France/Belgium/Switzerland/Monaco", kemungkinan dibatasi wilayah |
| Al Arabiya, Al Arabiya Business | AE | `live.alarabiya.net` | Cek syarat penggunaan / minta izin ke MBC Group |
| Asharq News | SA | `live-news.asharq.com` | Cek syarat / minta izin |
| Sky News Arabia | AE | `stream.skynewsarabia.com` | Cek syarat / minta izin |
| TVE Internacional (America / Asia-Oceania / Europe) | ES | `rtvelivestream*.rtve.es` | Feed internasional RTVE; cek syarat RTVE Play |
| CGTN Spanish / Russian | CN | `*.cgtn.com` | Path URL berisi string panjang; perlu dicek apakah itu token sesi. Cek syarat CGTN |

**Perlu bukti manual (bukan di domain sendiri, tapi penyiar internasional yang terkenal gratis):** DW, Al Jazeera English, NHK World-Japan, TRT World, Euronews, Arirang, CNA. Stream mereka umumnya di CDN (Akamai, dll.), jadi untuk masing-masing perlu dicatat bukti bahwa URL itu memang yang dipakai halaman live resmi mereka, lalu izinnya diperiksa.

### Prioritas B — relevan untuk Asia Tenggara

| Channel | Negara | Bukti teknis | Langkah izin |
|---|---|---|---|
| TVRI (30 feed: nasional dan regional) | ID | `ott-balancer.tvri.go.id` (domain pemerintah) | Lembaga Penyiaran Publik; minta izin tertulis ke TVRI untuk penggunaan di aplikasi |
| Thai Parliament Television | TH | `tv-live.tpchannel.org` | Siaran parlemen; cek syarat |

Untuk Brunei (RTB) tidak ditemukan kandidat domain resmi di data iptv-org; perlu dicari manual jika diinginkan.

### Prioritas C — penyiar publik domestik (kemungkinan dibatasi wilayah atau lisensi)

ARD-alpha, NDR (DE), La 1 / La 2 / 24 Horas (ES), stasiun anggota PBS (US). Stream di domain sendiri, tetapi umumnya ditujukan untuk penonton dalam negeri dan memuat program berlisensi. **Disarankan tidak diprioritaskan** kecuali ada izin tertulis.

### Disarankan dikecualikan

- **Stream promosi / acara olahraga** (mis. "Formula 1 AntenaPlay", "NHL AntenaPlay"): hak siar olahraga, risiko tinggi.
- **Radio** (RNE, Radio 3, Fox News Radio): bukan channel TV.
- **Media yang terkena sanksi atau larangan siar di sejumlah yurisdiksi** (beberapa media negara Rusia dan Iran ada di daftar kandidat): perlu cek hukum khusus; disarankan dikecualikan dari rilis pertama.

---

## 3. Alur dari kandidat ke channel publik

1. **Impor** kandidat ke database → `DISCOVERED` / `UNREVIEWED` (importer dibuat di M4; tidak ada yang tampil di aplikasi).
2. **Validasi teknis** otomatis (M5): Tier A (HTTP, manifest, segmen) → Tier B (Media3, ≥ 60 detik, video + audio terdekode, reconnect) → `VERIFIED` hanya jika semuanya lolos.
3. **Review izin** oleh Anda di dashboard admin (M9), dengan catatan bukti wajib → `OFFICIAL_PUBLIC_SOURCE` atau `EXPLICIT_PERMISSION`.
4. Channel tampil di aplikasi **hanya** jika langkah 2 dan 3 sama-sama terpenuhi.

## 4. Keputusan yang masih terbuka

**D5 — Stream HTTP (tanpa enkripsi).** 2.394 dari 11.502 entri iptv-org memakai HTTP. Aplikasi saat ini **hanya mengizinkan HTTPS**. Di shortlist, hampir semua kandidat sudah HTTPS (623 dari 684), jadi rekomendasinya: **tetap HTTPS-only** untuk rilis pertama.
