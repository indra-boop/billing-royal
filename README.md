# Billing Royal (Android)

Aplikasi operator/admin laundry berbasis Kotlin + Jetpack Compose (Material 3) yang terhubung ke
backend PHP REST API v1 yang sudah ada. Online-first: cache hanya untuk tampilan, setiap aksi tulis harus online.

## Persyaratan

- JDK 17 atau lebih baru (proyek dibuild dan diuji dengan JDK 21)
- Android SDK: platform `android-37.0` dan build-tools `37.0.0` (Android Studio mengunduhnya otomatis)
- Gradle wrapper sudah disertakan (Gradle 9.8.2)

Buat `local.properties` bila membuild di luar Android Studio:

```
sdk.dir=/path/ke/Android/Sdk
```

## Build lokal

```bash
./gradlew assembleDebug      # menghasilkan app/build/outputs/apk/debug/app-debug.apk
./gradlew test               # unit test (parser uang, mapper error API, repository invoice via MockWebServer)
./gradlew assembleRelease    # APK release (tanpa tanda tangan bila variabel keystore tidak diatur)
```

## Mengatur BASE_URL

Semua konfigurasi ada di satu tempat: `gradle.properties`.

```properties
billing.baseUrl.debug=https://STAGING-HOST/billing/api/v1/
billing.baseUrl.release=https://PROD-HOST/billing/api/v1/
```

- Nilai bawaan adalah placeholder dan WAJIB diganti. URL harus HTTPS dan diakhiri `/`.
- `BASE_URL` dibaca lewat `BuildConfig.BASE_URL` (per build type). Tidak ada URL lain di kode.
- Dapat ditimpa tanpa mengubah file: `./gradlew assembleDebug -Pbilling.baseUrl.debug=https://host-anda/billing/api/v1/`
- HTTPS wajib di semua build type (`network_security_config.xml`: cleartext dilarang, termasuk di release).

### applicationId

`billing.applicationId` di `gradle.properties` (bawaan `id.jerco.billingroyal`, placeholder). Hanya perlu diganti di sana;
`namespace` kode sumber tetap `id.jerco.billingroyal` sehingga tidak ada refactor paket.

### Feature flag

Semua default `false`, sembunyikan tombol terkait saat nonaktif:

| Properti `gradle.properties` | BuildConfig | Efek bila `true` |
|---|---|---|
| `billing.flag.markPaid` | `ENABLE_MARK_PAID` | tombol status Sent/Paid (PATCH status) |
| `billing.flag.cancelInvoice` | `ENABLE_CANCEL_INVOICE` | tombol status Cancelled |
| `billing.flag.priceOverride` | `ENABLE_PRICE_OVERRIDE` | field harga satuan khusus; `unit_price` dikirim hanya bila ini aktif |
| `billing.flag.delete` | `ENABLE_DELETE` | tombol hapus customer/pengeluaran |

## Membuat keystore release

```bash
keytool -genkeypair -v \
  -keystore billing-royal-release.jks \
  -alias billingroyal \
  -keyalg RSA -keysize 2048 -validity 10000
```

Simpan file `.jks` dan kata sandinya di tempat aman. JANGAN di-commit (sudah ada di `.gitignore`).

Build release bertanda tangan secara lokal:

```bash
export KEYSTORE_PATH=/path/ke/billing-royal-release.jks
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS=billingroyal
export KEY_PASSWORD='...'
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

### Secrets GitHub Actions

Ubah keystore menjadi base64 satu baris:

```bash
base64 -w0 billing-royal-release.jks   # Linux (macOS: base64 -i billing-royal-release.jks)
```

Lalu isi Settings > Secrets and variables > Actions:

| Secret | Isi |
|---|---|
| `KEYSTORE_BASE64` | keluaran base64 di atas |
| `KEYSTORE_PASSWORD` | kata sandi keystore |
| `KEY_ALIAS` | alias kunci (mis. `billingroyal`) |
| `KEY_PASSWORD` | kata sandi kunci |

## GitHub Actions

`.github/workflows/android.yml`:

- Setiap `push`: unit test + `assembleDebug`, artifact `app-debug.apk`.
- `workflow_dispatch` (Run workflow): unit test + `assembleRelease` ditandatangani dengan secrets di atas,
  diverifikasi dengan `apksigner`, artifact `app-release.apk`.

## Arsitektur singkat

- MVVM: `ViewModel` + `StateFlow`; satu Repository per domain (auth, customer, catalog, invoice, expense, report).
- Hilt untuk DI; Retrofit + OkHttp + kotlinx.serialization; semua uang dan qty `BigDecimal` (serializer kustom).
- Token: AES/GCM dengan kunci Android Keystore, disimpan di DataStore. Password tidak pernah disimpan.
- Room: cache read-only (customer, service, price list, profil) dan draft invoice lokal, dipisah per `user_id:business_id`.
- 401 (`unauthenticated`/`invalid_token`) dan 403 `account_inactive`: token, seluruh tabel Room, DataStore, dan file logo dihapus, lalu kembali ke Login.
- Setiap POST create mengirim `Idempotency-Key` (UUID v4) yang dibuat sekali per payload dan dipakai ulang saat kirim ulang.
  Pada timeout/network error setelah submit invoice tidak ada auto-retry; pengguna diminta memeriksa daftar invoice dulu.
- Logging HTTP hanya di debug, level BASIC (tanpa header/body); header `Authorization` diredaksi dan `/auth/login` tidak dicatat.

Lihat `ASSUMPTIONS.md` untuk asumsi dan bagian yang belum diimplementasi.
