# Asumsi dan bagian yang belum diimplementasi

## Status verifikasi (jujur)

- Terverifikasi dengan menjalankan di mesin build (JDK 21, Android SDK platform 37.0, build-tools 37.0.0):
  `./gradlew assembleDebug test` berhasil (76 unit test lulus, 0 gagal) dan `assembleRelease` dengan
  `-Pbilling.baseUrl.release=https://api.example.com/billing/api/v1/` menghasilkan APK (tanpa keystore: unsigned).
  Pada build awal (sebelum perbaikan review) APK release sempat ditandatangani dengan keystore uji sementara dan lolos
  `apksigner verify`; langkah itu belum diulang setelah perbaikan.
  `assembleRelease` dengan placeholder / `http://` / tanpa `/` akhir / ada tanda kutip GAGAL seperti yang diharapkan.
- TIDAK terverifikasi: aplikasi belum dijalankan di perangkat/emulator (tidak ada emulator di mesin build), sehingga
  alur UI, Keystore AES/GCM, Room, DataStore, dan R8 (build release dengan minify) belum diuji saat runtime.
  Aturan proguard ditulis untuk Retrofit/kotlinx.serialization tetapi perlu uji manual pada APK release.
- Workflow GitHub Actions belum pernah dijalankan di GitHub (SHA action diverifikasi lewat `gh api`, sintaks tidak dijalankan).
  Environment `release` harus dibuat manual di GitHub sebelum job release dapat berjalan.
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
      Batasan ini TETAP ada (review R-06/T10): bila proses mati setelah hasil tidak pasti, kirim ulang memakai key baru.
    - Invoice: kolom `uncertainSince` pada draft Room menandai percobaan kirim yang hasilnya belum pasti (ditulis SEBELUM request,
      dihapus hanya saat sukses, draft dikosongkan, atau gagal pasti pada percobaan pertama). Selama ada, tombol Simpan menampilkan
      dialog dan tidak mengirim; "Kirim ulang" butuh dua konfirmasi dan memakai key yang sama untuk payload yang sama.
    - Dialog "Status penyimpanan belum pasti" juga dipakai untuk customer dan expense (bukan hanya invoice). 5xx selain 503 dianggap hasil tidak pasti.
      Kegagalan yang PASTI belum terkirim (UnknownHost, ConnectException/NoRoute, TLS handshake) bukan hasil tidak pasti: pesan "belum tersimpan".
      Timeout (termasuk timeout koneksi) tetap diperlakukan tidak pasti karena tidak bisa dibedakan dari timeout baca.
    - Setelah hasil tidak pasti pada invoice, daftar invoice di-refresh dengan filter customer + bulan tanggal invoice, lalu disaring di klien:
      tanggal invoice sama dengan tanggal yang dikirim ATAU dibuat hari ini (API tidak punya filter tanggal harian).
11. Update customer/expense (PUT, seluruh field) bersifat idempoten sehingga tidak memakai Idempotency-Key dan tidak memakai dialog hasil-tidak-pasti.
    Expense yang tidak ditemukan dikembalikan server sebagai 422: bila PUT/DELETE gagal 422 tanpa rincian field, klien memeriksa dengan GET;
    bila GET 404/422, form ditandai "sudah tidak ada di server" dengan tombol kembali ke daftar.
12. Pengeluaran: seluruh halaman bulan dimuat (per_page=100, maksimal 50 halaman) agar "total bulan" akurat; tidak ada infinite scroll di layar ini.
    503 `module_not_ready` ditampilkan sebagai "Modul pengeluaran belum aktif".
13. Daftar customer/invoice memakai per_page=20 dan infinite scroll; fallback cache Room hanya untuk daftar customer, katalog service, price list, dan profil saat jaringan gagal (bukan untuk invoice/expense/laporan).
14. Profil (`/profile`, logo ~1 MB) diambil sekali setelah login, logo didecode ke file cache; layar lain memakai cache Room dan hanya mengambil ulang bila belum ada.
15. Cache dipisah per `user_id:business_id` lewat kolom `scope` di setiap tabel; saat 401/403 account_inactive/logout semua tabel (`clearAllTables`), DataStore, kunci Keystore, dan file logo dihapus.
    Login baru juga menghapus data akun sebelumnya terlebih dahulu.
    - Penghapusan fail-safe per langkah (token -> Room -> logo; satu langkah gagal tidak menghentikan yang lain) dan tidak dapat dibatalkan.
      `restore()` tanpa sesi membersihkan sisa cache yatim (proses mati di tengah wipe).
    - Penghapusan HANYA bila token request yang ditolak == token aktif; 401 hanya dari kode `unauthenticated`/`invalid_token`
      (403 hanya `account_inactive`). Server yang membalas 401 tanpa kode (proxy/gateway) TIDAK menghapus sesi: pengguna melihat
      error biasa dan dapat mencoba lagi.
    - `generation` sesi naik tiap wipe; repository mengambil "tiket" (scope + generasi) SEBELUM request dan melewatkan penulisan cache
      bila generasi berubah. Masih ada jendela sangat sempit antara pemeriksaan dan penulisan DAO (tidak atomik).
    - Draft invoice lokal (termasuk yang belum pasti) IKUT terhapus saat sesi berakhir; Login menampilkan pesan agar pengguna memeriksa daftar invoice.
16. Rekap: rentang maksimal 31 hari inklusif; pengguna tidak dapat mengirim request di luar rentang itu. Filter customer pada rekap didukung repository tetapi tidak ditampilkan di UI.
17. Logging: HttpLoggingInterceptor hanya di source set debug (`okhttp-logging` = `debugImplementation`), level BASIC (metode, URL, kode, ukuran; tanpa header/body), `Authorization` dan query `q` diredaksi, `/auth/login` tidak dicatat. `toString()` token dan password diredaksi.
    R8 juga menghapus `Log.w/e`. OkHttp tidak mengikuti redirect (`followRedirects=false`): API v1 tidak diharapkan mengalihkan.
18. `allowBackup=false` dan `data_extraction_rules` mengecualikan semua data dari backup/transfer.
19. Tidak ada data nyata di kode; tidak ada `@Preview` Compose sehingga tidak ada data contoh sama sekali.
20. Ikon aplikasi adalah placeholder vektor sederhana.
21. Label status di UI dan teks bagikan berbahasa Indonesia (Draft, Terkirim, Lunas, Dibatalkan); nilai wire ke API tetap `Draft/Sent/Paid/Cancelled`.
    Teks bagikan TIDAK memuat status internal; memuat "Status pembayaran" (Lunas/Dibayar sebagian/Belum dibayar) dari angka server, atau
    "DIBATALKAN". Membagikan invoice Draft meminta konfirmasi.
22. `FLAG_SECURE` aktif di Login, Akun, dan Detail invoice (penghitung referensi agar transisi layar tidak melepasnya).
    Daftar/form customer, pengeluaran, dan rekap belum diamankan.
23. Database Room v2 (migrasi eksplisit 1->2). Downgrade saja yang boleh mengosongkan database (cache + draft).

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

## Preview harga (BOQ/RAB review): keputusan dan yang belum dikerjakan

- **Aturan pembulatan server BELUM DIKETAHUI dan butuh konfirmasi pemilik/backend** (BR-01): apakah pembulatan dilakukan per baris,
  hanya pada subtotal, ke berapa desimal, dan mode pembulatannya (HALF_UP/bankir/dst). Klien SENGAJA tidak menambahkan aturan
  karangan: preview menghitung harga x qty apa adanya (bisa lebih dari 2 desimal) dan layar melabelinya
  "Perkiraan ... server yang menentukan subtotal dan total final (termasuk pembulatan)". Begitu aturan dikonfirmasi, tambahkan
  ke `InvoicePreview.compute` beserta test pembandingan dengan respons server nyata.
- Draft yang dipulihkan mengambil ulang customer dari server (`GET /customers/{id}`) sebelum preview dipercaya. Bila price list
  berubah, harga dimuat ulang dan operator diberi tahu; bila customer hilang, harus dipilih ulang; bila tidak bisa diperiksa
  (offline), preview ditandai "mungkin tidak terbaru". Draft TIDAK menyimpan snapshot harga (sengaja: harga server yang berlaku),
  sehingga perubahan harga tidak dapat ditampilkan sebagai selisih rupiah, hanya sebagai pemberitahuan.
- Permintaan harga memakai `LatestOnly` (hanya hasil permintaan terbaru diterapkan); dites di `LatestOnlyTest`.
- BR-05 (field uang respons yang hilang menjadi Rp 0): tidak diubah. Menjadikan field uang wajib di DTO akan merusak decoding bila
  kontrak API ternyata mengizinkan null/absen; perlu konfirmasi kontrak dari pemilik API.
- BR-09 (batas atas qty/nominal), BR-11 (penanda preview dari cache), BR-14 (format nilai server >2 desimal), BR-15 (number vs string
  pada body create): tidak diubah; perlu kebijakan/kontrak dari pemilik API. BR-01 lihat di atas.

## Temuan review yang SENGAJA tidak diperbaiki di cabang ini

- Enkripsi Room/logo (PRV-01/T8: SQLCipher), retensi/TTL cache, dan certificate pinning: butuh keputusan kebijakan/rotasi sertifikat.
- `gradle/verification-metadata.xml` (verifikasi checksum dependency): perlu dibuat dengan `--write-verification-metadata` di jaringan penuh.
- Idempotency-Key customer/expense tetap di memori (R-06).
- Pembulatan preview per baris (F2/BR-01), format kirim number-vs-string invoice (F1), batas atas nominal, K-2 (pencatatan pembayaran),
  K-3/K-4 (profil/rekening basi), role-based UI (A-02), `expires_at` belum ditegakkan di klien (T13), `strings.xml`/tema/ikon (SMV-01/02/05).
- Pesan error server ditampilkan apa adanya (A-08/SMV-12).
- Daftar customer/pengeluaran tidak otomatis menyegarkan saat kembali dari form (Home sudah).
