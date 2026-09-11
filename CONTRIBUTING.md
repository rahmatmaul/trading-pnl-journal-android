# Contributing

Terima kasih ingin membantu Trading PnL Journal.

## Sebelum membuat perubahan

1. Cari issue yang sudah ada agar pekerjaan tidak duplikat.
2. Untuk perubahan besar, buka feature request dan jelaskan dampaknya terhadap kompatibilitas backup serta keamanan data.
3. Jangan memasukkan backup jurnal asli, credential, key signing, atau data pribadi ke issue, commit, maupun test fixture.

## Menjalankan proyek

Gunakan JDK 17/21 dan Android SDK Platform 35:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Di Windows gunakan `gradlew.bat`.

## Pull request checklist

- Perubahan mempertahankan operasi offline tanpa permission `INTERNET`.
- Data persisten tetap melalui Room/SQLite, bukan browser storage.
- Migrasi schema Room bersifat eksplisit dan tidak destructive.
- Backup lama tetap dapat diimpor atau perubahan format didokumentasikan.
- Test ditambahkan untuk perubahan perilaku.
- Seluruh test dan lint lulus.

Prioritas proyek selalu: jangan kehilangan data jurnal.
