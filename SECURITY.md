# Security Policy

## Supported version

| Version | Supported |
| --- | --- |
| 4.x | Yes |
| 3.x | Security fixes only |

## Reporting a vulnerability

Gunakan fitur **Report a vulnerability / Security advisory** pada repository ini jika tersedia. Jangan membuka issue publik untuk celah yang dapat menyebabkan kehilangan atau kebocoran data.

Jangan pernah melampirkan backup jurnal asli. Buat fixture anonim dengan nilai dummy yang hanya mereproduksi masalah.

## Security model

- Aplikasi tidak meminta permission internet.
- WebView hanya memuat asset lokal dan memblokir navigasi/network eksternal.
- JavaScript bridge hanya diekspos kepada halaman aplikasi yang dibundel.
- Backup eksternal hanya ditulis ke lokasi yang dipilih pengguna melalui SAF.
- Database Room tidak menggunakan destructive migration fallback.
