<p align="center">
  <img src="docs/banner.svg" alt="Trading PnL Journal V3.1 for Android" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/rahmatmaul/trading-pnl-journal-android/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/rahmatmaul/trading-pnl-journal-android/actions/workflows/android.yml/badge.svg"></a>
  <img alt="Version 3.1.0" src="https://img.shields.io/badge/version-3.1.0-087BFF">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Storage" src="https://img.shields.io/badge/Storage-Room%20%2B%20SQLite-4479A1?logo=sqlite&logoColor=white">
  <img alt="Offline" src="https://img.shields.io/badge/Internet%20permission-none-111116">
</p>

<p align="center">
  Jurnal trading Android yang cepat, visual, dan benar-benar offline.<br>
  Tidak ada akun, server, iklan, tracker, atau database cloud.
</p>

<p align="center">
  <a href="https://github.com/rahmatmaul/trading-pnl-journal-android/raw/main/release/TradingPnLJournal-v3.1.0.apk"><strong>Download APK V3.1.0</strong></a>
  ·
  <a href="#cara-install"><strong>Cara install</strong></a>
  ·
  <a href="#build-dari-source"><strong>Build source</strong></a>
  ·
  <a href="CHANGELOG.md"><strong>Changelog</strong></a>
</p>

## Trading PnL Journal V3.1

<p align="center"><img src="docs/app-icon-v25-master.png" alt="Trading PnL Journal folded journal icon" width="128" /></p>

V3.1 memoles jurnal menjadi **Trading Performance OS** yang terasa lebih hidup tetapi tetap ringan.
Quick Log mempertahankan pencatatan dalam hitungan detik, sementara Full Story,
Trade Replay, Adaptive Playbook, Weekly Review, dan analitik lanjutan membantu
menjelaskan mengapa performa terjadi—bukan hanya menampilkan angka akhirnya.

Motion Engine V3.1 memakai transisi keluar–masuk yang mengikuti arah navigasi,
spring settle yang singkat, serta stagger hanya pada elemen penting. Seluruh gerak
utama memakai `transform` dan `opacity`, sedangkan blur kaca dibatasi ke layer
pilihan agar tetap mulus di Android WebView.

<p align="center">
  <img src="docs/screenshots/v3.1/journal.png" alt="V3.1 Journal with selective glass and directional motion" width="19%" />
  <img src="docs/screenshots/v3.0/full-story.png" alt="V3.0 Full Story" width="19%" />
  <img src="docs/screenshots/v3.0/insights.png" alt="V3.0 advanced Insights" width="19%" />
  <img src="docs/screenshots/v3.0/playbook.png" alt="V3.0 Adaptive Playbook" width="19%" />
  <img src="docs/screenshots/v3.0/trade-replay.png" alt="V3.0 Trade Replay" width="19%" />
</p>

<p align="center"><sub>Screenshot aktual: V3.1 Journal, Full Story, Insights, Adaptive Playbook, dan Trade Replay.</sub></p>

### Yang baru di V3.1

- **Directional motion**: layar lama keluar lebih dahulu, lalu layar baru masuk
  dari arah navigasi dengan spring settle yang halus.
- **Purposeful movement**: hero, signal, card utama, modal, toast, dan bottom nav
  punya respons gerak singkat tanpa animasi dekoratif yang terus berjalan.
- **Clearer glass**: permukaan lebih transparan, edge highlight lebih tegas, dan
  ambient blue/violet membuat efek blur terbaca tanpa tampak abu-abu.
- **Android-first performance**: blur berat pada backdrop dihapus dan animasi
  dibatasi ke properti yang dapat dikomposisi GPU.
- **Reduced motion**: preferensi aksesibilitas Android tetap dihormati.

### Fondasi fitur V3.0

- **Quick Log / Full Story switch**: pilih pencatatan super cepat atau review
  lengkap tanpa berpindah alur.
- **Adaptive Playbook**: setup terbentuk otomatis dari data trade, lengkap dengan
  grade, sample confidence, win rate, dan average PnL.
- **Trade Replay**: baca ulang thesis, execution, result, lesson, serta chart
  before/after dalam satu alur visual.
- **Discipline Score**: mengukur kualitas kebiasaan dari konteks, review,
  execution score, dan bukti chart—tidak dipengaruhi besar profit.
- **Weekly Review**: ringkasan mingguan dan satu rekomendasi tindakan berikutnya.
- **Advanced Insights**: profit factor, expectancy, maximum drawdown, average R,
  review completion, setup/session edge, dan biaya setiap mistake tag.
- Command-center dashboard dan visual hierarchy baru dengan motion yang tetap
  ringan untuk Android WebView.

## Fitur final

| Area | Kemampuan |
| --- | --- |
| Quick / Full Log | Pencatatan hasil dalam detik atau review lengkap dalam alur yang sama |
| Trade Story | Setup, session, emotion, planned/realized R, execution score, mistake tags, lesson, dan review status |
| Trade Replay | Alur thesis, execution, result, lesson, serta before/after evidence |
| Adaptive Playbook | Setup grade, sample confidence, coverage, win rate, dan average PnL |
| Chart evidence | Hingga 6 screenshot per trade sebagai Before/thesis atau After/result |
| Journal | Net performance, discipline score, expectancy, profit factor, streak, recent trades, dan review queue |
| Calendar | Ringkasan harian, jumlah trade, win rate, dan net PnL bulanan |
| Insights | Profit factor, expectancy, max drawdown, Average R, mistake cost, consistency, setup edge, dan session edge |
| Weekly Review | Debrief fokus dan next best action berdasarkan data minggu berjalan |
| Trade management | Add, edit, delete, duplicate, detail, filter periode/result/symbol, dan sorting |
| Risk settings | Starting balance, profit target, max drawdown, dan daily loss warning |
| Personalization | Light, dark, atau mengikuti tema Android |
| Data portability | Full package, JSON, CSV, Merge import, Replace import, dan automatic recovery |
| Offline | Asset UI lokal, Room/SQLite, tanpa permission internet |

## Data yang tidak hilang saat aplikasi ditutup

Sumber kebenaran jurnal adalah **Room + SQLite**, bukan `localStorage`. Tombol
**Save trade** baru menampilkan sukses setelah transaksi database selesai.

Data internal bertahan saat berpindah halaman, aplikasi ditutup, dihapus dari
Recents, di-force-stop, perangkat reboot, dan APK dengan signature yang sama
dipasang sebagai update. Android tetap menghapus private app data saat uninstall,
jadi portable backup tetap penting.

## Arsitektur

```mermaid
flowchart LR
    UI[Bundled HTML/CSS/JS] -->|AndroidJournal bridge| K[Kotlin shell]
    K --> R[JournalRepository]
    R --> ROOM[Room]
    ROOM --> SQL[(SQLite)]
    K --> IMG[Private chart images]
    R --> SAF[Storage Access Framework]
    IMG --> PKG[.tpjbackup ZIP]
    SQL --> PKG
    SAF --> PKG
```

WebView hanya memuat asset lokal dari `appassets.androidplatform.net`. Request
ke origin lain diblokir, DOM storage dimatikan, dan manifest tidak meminta
permission `INTERNET`. Gambar chart disimpan di private app storage; metadata dan
SHA-256 checksum disimpan di Room.

## Backup dan restore

### Full package — direkomendasikan

File `.tpjbackup` adalah ZIP tervalidasi yang membawa:

- `journal.json` berisi trade, settings, dan metadata attachment;
- chart JPEG, PNG, atau WebP;
- ukuran file serta SHA-256 checksum untuk mendeteksi kerusakan.

**Merge** menambahkan ID trade yang belum ada. **Replace** memvalidasi seluruh
paket sebelum mengganti jurnal dalam satu transaksi. Jalur ZIP diperiksa untuk
mencegah path traversal, ukuran file dibatasi, dan file sementara dibersihkan
jika import gagal.

JSON tetap tersedia untuk backup lama V1/V2 dan pertukaran tanpa gambar. CSV
ditujukan untuk spreadsheet dan memuat metadata review. Untuk automatic recovery,
buka **Settings → Automatic recovery → Choose backup folder** lalu pilih folder
melalui Android Storage Access Framework. Aplikasi menyimpan hingga lima snapshot.

## Cara install

1. Download [`TradingPnLJournal-v3.1.0.apk`](https://github.com/rahmatmaul/trading-pnl-journal-android/raw/main/release/TradingPnLJournal-v3.1.0.apk).
2. Buka file melalui File Manager di Android.
3. Jika diminta, aktifkan **Install unknown apps** hanya untuk File Manager yang
   digunakan.
4. Tekan **Install**, buka aplikasi, lalu pilih automatic recovery folder.

Untuk update dari versi lama, pasang APK baru langsung di atas aplikasi lama dan
**jangan uninstall versi lama terlebih dahulu**. Migrasi Room eksplisit
mempertahankan trade yang sudah tersimpan. Buat backup sebelum update sebagai
langkah berjaga-jaga.

## Build dari source

Prasyarat: JDK 17/21, Android SDK Platform 35, dan Build Tools 35+.

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Linux/macOS:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

APK debug tersedia di `app/build/outputs/apk/debug/app-debug.apk`. Asset font
mentah tidak disertakan dalam repository; source tetap dapat dibangun dan akan
menggunakan system sans-serif sebagai fallback bila asset lokal tidak tersedia.

## Riwayat versi

| Versi | Fokus update |
| --- | --- |
| **V3.1** | Motion Engine baru, directional spring transitions, micro-interactions, dan selective glass blur |
| **V3.0** | Trading Performance OS, Full Story, Trade Replay, Adaptive Playbook, discipline score, dan advanced Insights |
| **V2.5** | Visual polish, typography, adaptive icon, dan motion yang lebih mulus |
| **V2.0** | Quick Log, Trade Story, chart evidence, Insights, dan `.tpjbackup` |
| **V1.0** | Fondasi offline, Room/SQLite, CRUD, JSON/CSV, calendar, dan stats |

Rincian setiap rilis tersedia di [CHANGELOG.md](CHANGELOG.md).

## Pengujian

Suite JVM/Robolectric mencakup 15 test untuk CRUD, duplicate, reopen persistence,
settings, Win/Loss sign normalization, JSON legacy/V2, CSV escaping, malformed
backup, duplicate merge, atomic replace, attachment cascade, package checksum
round-trip, dan migrasi database V1→V2 tanpa kehilangan trade.

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

## Privasi dan keamanan

- Tidak ada analytics, telemetry, iklan, login, atau remote crash reporting.
- Tidak ada broad storage permission maupun permission internet.
- Data jurnal berada di perangkat dan folder backup yang pengguna pilih.
- Import selalu divalidasi sebelum data aktif diubah.

## Kontribusi

Bug report dan ide fitur dipersilakan. Baca [CONTRIBUTING.md](CONTRIBUTING.md)
sebelum membuka pull request. Untuk masalah keamanan, ikuti
[SECURITY.md](SECURITY.md) dan jangan pernah unggah backup jurnal asli.

## Lisensi

Belum ada lisensi penggunaan ulang yang diberikan. Source tersedia untuk
ditinjau, tetapi hak penggunaan, modifikasi, dan distribusi belum diberikan
sampai maintainer memilih lisensi proyek.
