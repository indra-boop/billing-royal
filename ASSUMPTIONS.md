# Asumsi dan bagian yang belum diimplementasi

## Status verifikasi (jujur)

- Terverifikasi dengan menjalankan di mesin build (JDK 21, Android SDK platform 37.0, build-tools 37.0.0):
  `./gradlew clean assembleDebug test assembleRelease` berhasil; 45 unit test lulus; APK release ditandatangani
  dengan keystore uji sementara dan lolos `apksigner verify`.
- TIDAK terverifikasi: aplikasi belum dijalankan di perangkat/emulator (tidak ada emulator di mesin build), sehingga
  alur UI, Keystore AES/GCM, Room, DataStore, dan R8 (build release dengan minify) belum diuji saat runtime.
  Aturan proguard ditulis untuk Retrofit/kotlinx.serialization tetapi perlu uji manual pada APK release.
- Workflow GitHub Actions divalidasi sintaks YAML-nya saja; belum pernah dijalankan di GitHub.
- Backend tidak tersedia, jadi seluruh integrasi hanya diuji lewat MockWebServer terhadap kontrak yang diberikan.

## Asumsi

1. Versi library: hanya versi stable yang terverifikasi ada di Maven Central / Google Maven (lihat `gradle/libs.versions.toml`).
   Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.2, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00, Room 2.8.5.
2. `targetSdk`/`compileSdk` = 37 (API stable terbaru yang tersedia di SDK manager saat build); `minSdk` = 26.
3. `applicationId` diatur di `gradle.properties` (`billing.applicationId`); `namespace` kode tetap `id.jerco.billingroyal`.
4. Datetime server tanpa zona diperlakukan sebagai Asia/Makassar; tanggal default form = hari ini WITA.
5. Id dan angka bulat dari server didecode toleran (number atau string); uang/qty memakai BigDecimal dari number atau string.
6. Tanggal tampil `dd MMM yyyy` memakai singkatan bulan Indonesia yang ditetapkan eksplisit (Jan, Feb, Mar, Apr, Mei, Jun, Jul, Agu, Sep, Okt, Nov, Des)
   agar deterministik di semua perangkat. Format uang `Rp 15.000` memakai simbol locale id-ID yang ditetapkan eksplisit
   (pemisah ribuan titik, desimal koma); pecahan hanya ditampilkan bila bukan nol.
7. Parser nominal: titik = ribuan, koma = desimal; "15000.50" (titik + 1-2 digit) diterima sebagai desimal. Qty menerima "." atau "," sebagai desimal, maksimal 2 desimal, harus > 0.
8. Perubahan status invoice (PATCH) dianggap seluruhnya di balik flag: "Sent" tampil bila ENABLE_MARK_PAID atau ENABLE_CANCEL_INVOICE aktif;
   "Paid" hanya dengan ENABLE_MARK_PAID (dari Sent); "Cancelled" hanya dengan ENABLE_CANCEL_INVOICE (dari Draft/Sent). Semua dengan dialog konfirmasi.
   Repository juga menolak status di luar flag (pagar kedua di sisi klien).
9. Invoice baru selalu `status: "Draft"`. `unit_price` hanya dikirim bila ENABLE_PRICE_OVERRIDE aktif dan field harga khusus diisi; selain itu server memakai price list customer.
10. Idempotency-Key (UUID v4):
    - Invoice: dibuat saat Simpan ditekan, disimpan di draft Room bersama hash payload SEBELUM request dikirim; dipakai ulang bila payload sama, diganti bila payload berubah.
    - Customer dan expense: key disimpan di memori ViewModel form (tidak ada draft lokal untuk keduanya); hilang bila layar form ditutup.
    - Dialog "Status penyimpanan belum pasti" juga dipakai untuk customer dan expense (bukan hanya invoice). 5xx selain 503 dianggap hasil tidak pasti.
    - Setelah hasil tidak pasti pada invoice, daftar invoice di-refresh dengan filter customer + bulan tanggal invoice, lalu disaring di klien:
      tanggal invoice sama dengan tanggal yang dikirim ATAU dibuat hari ini (API tidak punya filter tanggal harian).
11. Update customer/expense (PUT, seluruh field) bersifat idempoten sehingga tidak memakai Idempotency-Key dan tidak memakai dialog hasil-tidak-pasti.
12. Pengeluaran: seluruh halaman bulan dimuat (per_page=100, maksimal 50 halaman) agar "total bulan" akurat; tidak ada infinite scroll di layar ini.
    503 `module_not_ready` ditampilkan sebagai "Modul pengeluaran belum aktif".
13. Daftar customer/invoice memakai per_page=20 dan infinite scroll; fallback cache Room hanya untuk daftar customer, katalog service, price list, dan profil saat jaringan gagal (bukan untuk invoice/expense/laporan).
14. Profil (`/profile`, logo ~1 MB) diambil sekali setelah login, logo didecode ke file cache; layar lain memakai cache Room dan hanya mengambil ulang bila belum ada.
15. Cache dipisah per `user_id:business_id` lewat kolom `scope` di setiap tabel; saat 401/403 account_inactive/logout semua tabel (`clearAllTables`), DataStore, kunci Keystore, dan file logo dihapus.
    Login baru juga menghapus data akun sebelumnya terlebih dahulu.
16. Rekap: rentang maksimal 31 hari inklusif; pengguna tidak dapat mengirim request di luar rentang itu. Filter customer pada rekap didukung repository tetapi tidak ditampilkan di UI.
17. Logging: HttpLoggingInterceptor hanya di debug, level BASIC (metode, URL, kode, ukuran; tanpa header/body), `Authorization` diredaksi, `/auth/login` tidak dicatat. `toString()` token dan password diredaksi.
18. `allowBackup=false` dan `data_extraction_rules` mengecualikan semua data dari backup/transfer.
19. Tidak ada data nyata di kode; tidak ada `@Preview` Compose sehingga tidak ada data contoh sama sekali.
20. Ikon aplikasi adalah placeholder vektor sederhana.

## Belum diimplementasi / batasan

- Belum diuji pada perangkat/emulator dan belum ada UI test (Compose/Espresso) maupun test ViewModel.
- Tidak ada unit test untuk Room DAO (butuh instrumented test) dan Keystore cipher (butuh perangkat).
- Tidak ada endpoint `/health` di UI (hanya tersedia di API client).
- Tidak ada filter customer pada rekap di UI; tidak ada ekspor PDF/cetak; hanya "Bagikan" teks.
- Tidak ada pembuatan invoice langsung Paid/Cancelled (sengaja, sesuai aturan).
- Draft lokal hanya untuk invoice (satu draft per user+bisnis).
- Dukungan tema gelap ada, tetapi warna badge status tidak disesuaikan untuk mode gelap.
- Tidak ada lokalisasi selain Bahasa Indonesia; teks UI ditulis langsung di Compose (bukan strings.xml), kecuali nama app.
- Sertifikat pinning tidak diterapkan.
