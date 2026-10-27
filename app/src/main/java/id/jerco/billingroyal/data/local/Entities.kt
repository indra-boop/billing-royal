package id.jerco.billingroyal.data.local

import androidx.room.Entity

/*
 * Cache read-only untuk tampilan + draft invoice lokal.
 * Setiap baris membawa [scope] = "<user_id>:<business_id>" sehingga cache dipisah per user dan bisnis.
 * Nilai uang disimpan sebagai String desimal (BigDecimal.toPlainString), bukan tipe floating point.
 */

@Entity(tableName = "customers", primaryKeys = ["scope", "id"])
data class CustomerEntity(
    val scope: String,
    val id: Long,
    val name: String,
    val email: String?,
    val phone: String?,
    val address: String?,
    val priceListId: Long?,
    val priceListName: String?,
    val createdAt: String?,
)

@Entity(tableName = "services", primaryKeys = ["scope", "id"])
data class ServiceEntity(
    val scope: String,
    val id: Long,
    val itemCode: String?,
    val category: String,
    val name: String,
    val unit: String,
)

@Entity(tableName = "price_lists", primaryKeys = ["scope", "id"])
data class PriceListEntity(
    val scope: String,
    val id: Long,
    val name: String,
    val itemCount: Long,
)

@Entity(tableName = "price_list_items", primaryKeys = ["scope", "priceListId", "serviceId"])
data class PriceListItemEntity(
    val scope: String,
    val priceListId: Long,
    val serviceId: Long,
    val itemCode: String?,
    val category: String,
    val serviceName: String,
    val unit: String,
    val price: String,
)

@Entity(tableName = "profile", primaryKeys = ["scope"])
data class ProfileEntity(
    val scope: String,
    val businessId: Long,
    val name: String,
    val code: String,
    val address: String?,
    val bankName: String?,
    val bankAccount: String?,
    val bankHolder: String?,
    val logoPath: String?,
)

/** Draft invoice lokal (satu per scope). Menyimpan Idempotency-Key bersama draft. */
@Entity(tableName = "invoice_drafts", primaryKeys = ["scope"])
data class InvoiceDraftEntity(
    val scope: String,
    val customerId: Long?,
    val customerName: String?,
    val priceListId: Long?,
    val invoiceDate: String,
    val notes: String,
    /** JSON daftar item draft (lihat DraftItem). */
    val itemsJson: String,
    /** UUID v4 yang dibuat SEKALI saat Simpan ditekan; null bila belum pernah submit. */
    val idempotencyKey: String?,
    /** Hash payload yang terkait dengan [idempotencyKey]; bila payload berubah, key baru dibuat. */
    val payloadHash: String?,
    val updatedAt: Long,
    /**
     * Epoch millis saat percobaan kirim dimulai dan hasilnya belum pasti (timeout/putus/proses mati).
     * Selama tidak null, dialog "Status penyimpanan belum pasti" muncul lagi walau proses sempat mati.
     * Dihapus hanya saat invoice berhasil dibuat atau draft dikosongkan/ditolak server (percobaan pertama).
     */
    val uncertainSince: Long? = null,
)

/**
 * Idempotency-Key aksi create (customer/expense) yang SEDANG berjalan atau hasilnya belum pasti (T10).
 * Satu baris per (scope, kind). Ada di Room sehingga ikut terhapus oleh clearAllTables() saat logout/401/403,
 * dan terpisah per user+bisnis lewat [scope]. [payloadHash] = SHA-256 dari JSON kanonik body request.
 */
@Entity(tableName = "pending_idempotency", primaryKeys = ["scope", "kind"])
data class PendingIdempotencyEntity(
    val scope: String,
    /** "customer_create" atau "expense_create". */
    val kind: String,
    val idempotencyKey: String,
    val payloadHash: String,
    /** Epoch millis saat key dibuat; key yang lebih tua dari TTL tidak dipakai ulang. */
    val createdAt: Long,
)
