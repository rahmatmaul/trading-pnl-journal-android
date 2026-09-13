# Security Policy

## Supported version

| Version | Supported |
| --- | --- |
| 4.x | Yes |

## Reporting a vulnerability

Gunakan fitur **Report a vulnerability / Security advisory** pada repository ini jika tersedia. Jangan membuka issue publik untuk celah yang dapat menyebabkan kehilangan atau kebocoran data.

Jangan pernah melampirkan backup jurnal asli. Buat fixture anonim dengan nilai dummy yang hanya mereproduksi masalah.

## Security model

- Akses internet hanya digunakan oleh native sync layer ketika Google Drive diaktifkan.
- WebView hanya memuat asset lokal dan memblokir navigasi/network eksternal.
- JavaScript bridge hanya diekspos kepada halaman aplikasi yang dibundel.
- Backup eksternal hanya ditulis ke lokasi yang dipilih pengguna melalui SAF.
- Database Room tidak menggunakan destructive migration fallback.
- OAuth credential, token, dan file `.secrets` tidak boleh dimasukkan ke repository.
- Ticket sync bersifat idempotent dan blob gambar diverifikasi menggunakan SHA-256.
