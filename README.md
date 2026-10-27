# USB Flasher

Aplikasi Android (tanpa root) untuk menulis file image (ISO/IMG) ke flashdisk lewat kabel OTG, dan membaca flashdisk menjadi file image. Fungsinya mirip balena Etcher (DD mode) dan Win32 DiskImager.

## Fitur
- **Tulis ke USB**: tulis image ke flashdisk secara raw, dengan opsi verifikasi setelah menulis.
- **Baca dari USB**: simpan isi flashdisk menjadi file `.img`, bisa dibatasi ukurannya (MB).
- Progres, kecepatan, dan estimasi waktu tersisa; bisa dibatalkan.
- Tanpa library eksternal: komunikasi USB Mass Storage (Bulk-Only Transport + SCSI) ditulis langsung memakai Android USB Host API.

## Batasan
- Hanya mode raw. Image hybrid (Linux, OpenWrt, Raspberry Pi, dll.) bisa boot; ISO Windows biasa tidak.
- Kapasitas flashdisk maksimal 2 TB (memakai READ CAPACITY 10).
- HP harus mendukung USB Host/OTG.
- Belum diuji luas di banyak perangkat. Gunakan dengan hati-hati: menulis akan **menghapus seluruh isi flashdisk**.

## Build
Tanpa Android Studio. Di Ubuntu/Debian:

```bash
sudo apt install openjdk-21-jdk-headless aapt apksigner dalvik-exchange libandroid-23-java zipalign
./build.sh
```

Hasilnya `USB-Flasher.apk`, ditandatangani dengan kunci debug yang dibuat otomatis (`debug.keystore`, tidak di-commit). Karena kuncinya berbeda dari APK rilis, APK hasil build sendiri tidak bisa menimpa versi dari kunci lain; uninstall dulu.

GitHub Actions (`.github/workflows/build.yml`) membangun APK otomatis di setiap push dan menyimpannya sebagai artifact.

## Struktur
- `src/id/flasher/usb/MassStorage.java`: akses USB mass storage (CBW/CSW, SCSI READ/WRITE).
- `src/id/flasher/usb/MainActivity.java`: UI dan alur tulis/baca.
- `res/`: ikon (vector) dan string.
- `build.sh`: skrip build.

## Lisensi
MIT
