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
    - Customer dan expense (T10, diperbaiki): key + hash payload disimpan di tabel Room `pending_idempotency` (satu baris per
      scope user+bisnis dan jenis aksi) SEBELUM request dikirim, sehingga bertahan walau proses mati. Hash = SHA-256 dari JSON
      kanonik body request (kunci diurutkan, bukan `toString()`). Key dipakai ulang untuk payload yang sama dalam 24 jam
      (`IdempotencyStore.TTL_MILLIS`; setelah itu key baru, agar dua entri identik yang disengaja di hari berbeda tidak memakai key
      lama), dihapus setelah create sukses, dan ikut terhapus oleh `clearAllTables()` saat logout/401/403. Migrasi Room 2->3 hanya
      menambah tabel (draft invoice tidak dibuang). Belum ada dialog "hasil tidak pasti" khusus customer/expense; yang
      dijamin hanya key yang sama saat dikirim ulang. Efek dedup bergantung pada server yang menegakkan Idempotency-Key.
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
- Hanya Bahasa Indonesia. Sebagian besar teks statis di layar sudah di `res/values/strings.xml`; teks dengan interpolasi (`$var`), pesan galat
  di ViewModel/repository, label status (`InvoiceStatus.label`), dan teks bagikan (`InvoiceShareText`) masih di kode (lihat bagian visual di bawah).
- Sertifikat pinning tidak diterapkan (T3, keputusan pemilik: lihat bagian "Keputusan pemilik yang tertunda").

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

## Tampilan, tema, dan identitas visual (review social-media-visual)

Dikerjakan: label status Indonesia + konfirmasi Bagikan untuk invoice Draft DAN Dibatalkan (SMV-03/06); sebagian besar teks statis
Login/Home/Akun/Invoice/Customer/Pengeluaran + dialog/komponen bersama dipindah ke `strings.xml` (SMV-05, bertahap); tema terang/gelap penuh
(`values-night`, skema M3 lengkap, warna badge/peringatan punya varian gelap; pencocokan warna status masih lewat string literal "Draft"/"Sent"/...) dan dynamic color di Android 12+
(SMV-02/08/09/18); Splash Screen API (`androidx.core:core-splashscreen:1.2.0`, versi stabil terbaru di Google Maven per 2026-10-27; layar Splash Compose
tetap ada untuk pemeriksaan sesi); ikon adaptive dengan layer monochrome dan `roundIcon` terpisah (SMV-11); logo bisnis di Home (cadangan: tanda merek
placeholder) dan tanda merek di Login (SMV-04); tombol utama tinggi minimum 48dp, `Role.Button` pada baris daftar, `contentDescription` null untuk
logo yang berdampingan dengan nama bisnis (SMV-17/20); spinner tombol Login 20dp (SMV-07); hint/isError pada nominal pengeluaran (SMV-15);
satu fungsi `shareNow` dan judul chooser "Bagikan invoice" (SMV-16).

Batasan dan keputusan yang membutuhkan pemilik:

- **Aset logo laundry asli dari pemilik DIBUTUHKAN.** Ikon launcher (huruf "B" di atas biru `#0B5FA5`), tanda merek di Login, dan warna seed adalah
  PLACEHOLDER buatan, bukan merek. Jangan dirilis ke publik. Ganti `drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`,
  `values/colors.xml` (`brand_seed`), dan seed di `ui/theme/Theme.kt` setelah aset tersedia.
- Logo bisnis dari server tidak tersedia sebelum login (data lokal dihapus saat keluar/sesi berakhir, sesuai aturan 10), jadi Login dan Splash memakai
  tanda merek, bukan logo bisnis. Home memakai logo bisnis dari cache profil bila ada.
- Dynamic color diaktifkan (`USE_DYNAMIC_COLOR = true` di `Theme.kt`) karena warna merek belum ada; ubah ke `false` bila ingin warna merek tetap
  setelah palet asli tersedia. Skema terang/gelap cadangan dipakai di Android < 12.
- Belum dikerjakan: teks berinterpolasi dan pesan di ViewModel ke `strings.xml`/plurals (perlu penataan format), ikon pembeda pada badge status
  (SMV-10), pesan galat server yang bahasanya tidak terjamin (SMV-12, butuh kontrak dari API), penyeragaman istilah campuran seperti "Customer"/"Draft"
  (SMV-13: keputusan istilah ada pada pemilik), token spacing/radius (SMV-19), dan `@Preview` (SMV-20).
- Belum diuji pada perangkat/emulator: tampilan, splash, dynamic color, dan TalkBack hanya lulus kompilasi dan unit test.

## Hardening dari review Hacker (T4, T10, T13, T14, T16, CI)

- **T13 `expires_at`:** `SessionManager.restore()` membersihkan sesi + data lokal bila `expires_at` sudah lewat, lalu Login menampilkan pesan
  "sesi berakhir". Waktu tanpa zona dibaca sebagai Asia/Makassar (asumsi #4) dengan toleransi 5 menit; nilai kosong/tak terbaca dianggap
  BELUM kedaluwarsa. Server tetap authoritative: token yang menurut klien masih berlaku tetap bisa ditolak 401. Bila zona server ternyata
  berbeda dari WITA, sesi bisa dibersihkan lebih awal sebanyak selisih zona (butuh konfirmasi format `expires_at` dari pemilik API).
- **T14 logo:** hanya `data:image/png;base64,` / `data:image/jpeg;base64,`, maksimal 2 MiB setelah decode, magic bytes harus cocok; selain itu logo
  diabaikan (bukan galat). Base64 tanpa prefix yang sebelumnya diterima sekarang ditolak.
- **T16 Bagikan:** setiap Bagikan melewati dialog konfirmasi; bila profil punya rekening ada pilihan "Sertakan rekening pembayaran"
  (bawaan aktif, kecuali invoice Lunas/Dibatalkan).
- **T4 rantai pasok:** `gradle/wrapper/gradle-wrapper.properties` punya `distributionSha256Sum`, dan `gradle/verification-metadata.xml` (SHA-256,
  479 komponen) dibuat dengan `--write-verification-metadata sha256` lalu diuji (`clean assembleDebug test` dan `assembleRelease` dengan
  `--no-build-cache` lulus). Catatan: checksum itu "Generated by Gradle" (dipercaya saat pertama kali diunduh), BUKAN diverifikasi terhadap
  sumber independen; `verify-signatures` tidak diaktifkan. Setelah mengubah dependency/plugin: `./gradlew --write-verification-metadata sha256
  assembleDebug assembleRelease test -Pbilling.baseUrl.release=https://host.example/billing/api/v1/` lalu tinjau diff-nya.
- **CI** (`.github/workflows/android.yml`, belum pernah dijalankan di GitHub): `gradle/actions/wrapper-validation` di kedua job (SHA sama dengan
  setup-gradle, jalur `wrapper-validation/action.yml` diverifikasi ada pada SHA itu lewat `gh api`); job release memakai `cache-read-only: true`;
  keempat secret signing diperiksa sekaligus; `umask 077` sebelum decode keystore; `KEYSTORE_PATH` hanya lewat `env:` pada langkah build (tidak
  lagi ke `GITHUB_ENV`); `cancel-in-progress` hanya untuk push (run release tidak dibatalkan di tengah signing); `retention-days` 7/30.
  Tidak dikerjakan: membandingkan fingerprint sertifikat hasil `apksigner --print-certs` dengan variabel repo (C4 di review) dan Dependabot untuk
  `github-actions`; keduanya butuh nilai/pengaturan dari pemilik repo.

## Keputusan pemilik yang tertunda (sengaja tidak diubah)

- **T3 certificate pinning:** tidak diterapkan. Pin yang salah atau kedaluwarsa mengunci semua operator dari backend; butuh pin cadangan dan
  rencana rotasi sertifikat dari pemilik backend sebelum diaktifkan (`<pin-set>` dengan expiration atau `CertificatePinner`).
- **T8 enkripsi Room/logo (SQLCipher):** tidak diterapkan; cache (termasuk profil dan rekening) dan draft berada di SQLite plaintext dalam sandbox
  aplikasi, tidak ikut backup, dan dihapus saat logout/401/403. Risikonya hanya pada perangkat root/forensik. SQLCipher menambah dependency native,
  ukuran APK, dan manajemen kunci; butuh keputusan kebijakan data dari pemilik.
- **T15 `applicationId`:** TIDAK diubah (tetap `id.jerco.billingroyal`, placeholder di `gradle.properties`); jangan menambah
  `applicationIdSuffix` tanpa persetujuan pemilik karena memengaruhi identitas paket/Play Store. Mitigasi yang ada: debug APK retensi 7 hari.
- **C2 secret di Environment `release`:** pengaturan di GitHub (Settings > Environments, required reviewers, secret dan variabel
  `PROD_BASE_URL`) harus dilakukan pemilik repo; workflow sudah memakai `environment: release` dan hanya berjalan dari `main`.

## Temuan review yang SENGAJA tidak diperbaiki di cabang ini

- Enkripsi Room/logo (PRV-01/T8: SQLCipher), retensi/TTL cache, dan certificate pinning: butuh keputusan kebijakan/rotasi sertifikat.
- Pembulatan preview per baris (F2/BR-01), format kirim number-vs-string invoice (F1), batas atas nominal, K-2 (pencatatan pembayaran),
  K-3/K-4 (profil/rekening basi), role-based UI (A-02), 
- Pesan error server ditampilkan apa adanya (A-08/SMV-12).
- (Diperbaiki di bagian UI/UX di bawah) daftar customer/invoice/pengeluaran kini menyegarkan saat layar kembali aktif.

## Review UI/UX (ui-designer 20 temuan, ux-designer 23 temuan): yang dikerjakan dan yang ditunda

Semua perubahan hanya di lapisan tampilan; aturan wajib 1-11 tidak diubah. **Belum diuji di perangkat/emulator** (hanya kompilasi + unit test;
tidak ada UI test): tata letak, fokus/TalkBack, IME/autofill, `imePadding` pada bilah Simpan, dan warna kontras perlu dicek manual.

**Dikerjakan**
- UI-01 `Surface(color = background)` membungkus `AppNavHost`; UI-02 spinner `SavingButton` memakai `LocalContentColor` dan tombol tidak lagi redup
  saat menyimpan (klik diblokir, `stateDescription` "Menyimpan"); UI-03 `contentPadding` bawah 88dp di daftar ber-FAB; UI-06 baris picker/daftar min 48dp
  dan field cari otomatis fokus + `ImeAction.Search` + tombol Hapus teks; UI-07/08 galat dekat sumbernya dengan `liveRegion`, `ImeAction`, kapitalisasi,
  autofill (`contentType`) dan tombol teks Tampilkan/Sembunyikan kata sandi; UI-09/UX-13 rentang Rekap tidak valid menyembunyikan data lama;
  UI-10 banner offline sama di semua daftar (`LoadStateView(fromCache)`) dan Akun memakai sesi cache; UI-11 ukuran spinner seragam; UI-13 aksi hapus
  berwarna error + konfirmasi `destructive`; UI-15 empty state punya tombol ajakan; UI-16 catatan "harga belum final" tidak lagi berwarna error;
  UI-17 teks baris daftar dibatasi 2 baris + ellipsis; UI-18 `strings.xml` dibersihkan (tanpa `*`, spasi pinggir, string tak terpakai); UI-19 Home
  memakai tombol tonal dan Akun pindah ke app bar; UI-04 warna status/peringatan mengikuti mode terang/gelap aplikasi (`LocalDarkTheme`).
- UX-01 Home selalu menampilkan menu; ringkasan punya status sendiri + Coba lagi; kartu "Lanjutkan draft invoice" dari data lokal.
- UX-02 (sebagian): pesan Login berasal dari `SessionNoticeCard` (ringkas, bisa ditutup, `liveRegion`). **Keputusan pemilik:** mempertahankan draft
  saat forced logout (401/403) bertentangan dengan aturan wipe Room/DataStore, maka TIDAK diubah; penghapusan tetap sesuai spesifikasi.
- UX-03/15 dialog "belum pasti": jam dibuat + subtotal + penanda "kemungkinan besar ini", tombol "Ini invoicenya, hapus draft" dengan konfirmasi,
  catatan pencarian hanya 50 invoice terbaru, tombol "Periksa invoice" utama, "Kirim ulang..." butuh konfirmasi kedua, "Nanti" menjelaskan draft tetap ada.
- UX-04 Splash gagal jaringan: Coba lagi; tidak ada wipe otomatis; "Keluar dan hapus data perangkat" meminta konfirmasi.
- UX-06/17 bilah bawah tetap (perkiraan + Simpan + galat + alasan Simpan nonaktif) di Buat Invoice dan form; hapus item bisa Urungkan (Snackbar).
- UX-07 (sebagian) "Buat invoice" dari Daftar kembali ke layar Buat yang ada di bawahnya (tanpa menumpuk). UX-08 daftar menyegarkan saat kembali aktif.
- UX-09 Back saat menyimpan meminta konfirmasi; pesan "koneksi lambat" setelah 8 detik. UX-10 `liveRegion`/heading pada galat, notifikasi, status.
- UX-11 Login: IME Next/Done, autofill, Tampilkan/Sembunyikan, pesan 401/429 diterjemahkan. UX-12 (sebagian) peringatan "Buang perubahan?" saat Back.
- UX-16 (sebagian) catatan "20 customer pertama" di picker. UX-18 contentDescription "Hapus <nama item>". UX-19 Akun tetap bisa Keluar saat offline (data cache).
- UX-20 (sebagian) istilah "Hapus draft" seragam. UX-23 pratinjau "Dibaca sebagai" untuk nominal pengeluaran.

**Ditunda (alasan)**
- UX-05 pilih banyak item sekaligus / pintasan customer terakhir: perubahan alur, perlu keputusan produk.
- UX-04 butir "masuk Home saat offline": bertentangan dengan online-first/server authoritative.
- UX-07 teruskan filter (customer/tanggal) ke Daftar Invoice dan UX-16 tombol "Tambah customer baru" di picker: perlu perubahan argumen navigasi/alur form.
- UX-12 pemulihan isian form setelah proses dimatikan sistem: butuh penyimpanan draft form (bertentangan dengan aturan tanpa data sensitif lokal tambahan bila tidak diputuskan pemilik).
- UX-14 satu tombol Bagikan tanpa dialog untuk Draft: dialog T16 (review Hacker) sengaja dipertahankan.
- UX-20 glosarium istilah penuh dan UX-22 penanda cache pada picker: perlu keputusan istilah / data sumber cache.
- UX-21/UI-20/UI-17 (font scaling, layar lebar, `adjustResize` + `imePadding`): perlu verifikasi di perangkat; UI-05 sisa string Kotlin (pesan galat VM
  dan beberapa dialog Bagikan) belum dipindah ke `strings.xml`; UI-12 tinggi tombol dirapikan sebagian; UI-14 format uang tidak diubah.
- UI-04 skema warna lengkap (extended color roles/harmonize) dan keputusan dynamic color tetap ON (menunggu pemilik; warna status tetap tetap).
- Ikon/logo: opsi A/B/C menunggu pemilik; aset konsep hanya referensi, ikon tidak diganti. `applicationId` tidak diubah.

**Keputusan pemilik yang masih terbuka:** logo asli, aturan pembulatan server, certificate pinning, SQLCipher, GitHub Environment `release`,
dynamic color ON/OFF, dua splash (API 31+ vs bawah), mempertahankan draft saat forced logout (konflik dengan aturan wipe).
