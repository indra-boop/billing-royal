package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.core.CanonicalJson
import id.jerco.billingroyal.data.local.PendingIdempotencyDao
import id.jerco.billingroyal.data.local.PendingIdempotencyEntity
import id.jerco.billingroyal.data.local.SessionManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/** Jenis aksi create yang Idempotency-Key-nya disimpan. */
enum class IdempotencyKind(val wire: String) { CUSTOMER_CREATE("customer_create"), EXPENSE_CREATE("expense_create") }

/**
 * Idempotency-Key customer/expense yang bertahan walau proses mati (T10).
 *
 * - Key dibuat SEKALI per payload (hash SHA-256 dari JSON kanonik) dan disimpan di Room SEBELUM request dikirim.
 * - Payload yang sama dalam [TTL_MILLIS] memakai ulang key yang sama; payload berbeda membuat key baru.
 *   TTL mencegah pengiriman sengaja (dua pengeluaran identik di hari berbeda) memakai key kedaluwarsa.
 * - Dipisah per scope user+bisnis; ikut terhapus saat logout/401/403 karena berada di database yang di-wipe.
 * - Tidak ada baris bila belum login (scope "none"): key sementara tidak disimpan.
 */
@Singleton
class IdempotencyStore @Inject constructor(
    private val dao: PendingIdempotencyDao,
    private val scope: ScopeProvider,
) {
    suspend fun keyFor(kind: IdempotencyKind, payload: JsonObject, nowMillis: Long = System.currentTimeMillis()): String {
        val ticket = scope.ticket()
        val hash = CanonicalJson.sha256Hex(payload)
        if (ticket.key == SessionManager.NO_SCOPE) return UUID.randomUUID().toString()
        val existing = dao.get(ticket.key, kind.wire)
        reusable(existing, hash, nowMillis)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        // Jangan menulis bila sesi berakhir sementara itu (wipe/login baru).
        if (scope.isCurrent(ticket)) {
            dao.upsert(PendingIdempotencyEntity(ticket.key, kind.wire, fresh, hash, nowMillis))
        }
        return fresh
    }

    /** Dipanggil setelah create sukses: aksi berikutnya adalah permintaan baru. */
    suspend fun clear(kind: IdempotencyKind) {
        val ticket = scope.ticket()
        if (ticket.key != SessionManager.NO_SCOPE) dao.delete(ticket.key, kind.wire)
    }

    companion object {
        const val TTL_MILLIS = 24L * 60 * 60 * 1000

        fun reusable(existing: PendingIdempotencyEntity?, hash: String, nowMillis: Long): String? =
            existing?.takeIf { it.payloadHash == hash && nowMillis - it.createdAt in 0..TTL_MILLIS }?.idempotencyKey
    }
}
