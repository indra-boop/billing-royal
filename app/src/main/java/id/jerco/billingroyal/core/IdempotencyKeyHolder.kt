package id.jerco.billingroyal.core

import java.security.MessageDigest
import java.util.UUID

/**
 * Menjaga Idempotency-Key (UUID v4) untuk satu aksi Simpan.
 * Key dibuat SEKALI untuk satu payload dan dipakai ulang pada setiap pengiriman ulang payload yang sama.
 * Bila payload berubah (pengguna mengedit form), key baru dibuat karena itu permintaan yang berbeda.
 */
class IdempotencyKeyHolder {
    private var key: String? = null
    private var payloadHash: String? = null

    @Synchronized
    fun keyFor(payload: String): String {
        val hash = sha256(payload)
        val existing = key
        if (existing != null && payloadHash == hash) return existing
        val fresh = UUID.randomUUID().toString()
        key = fresh
        payloadHash = hash
        return fresh
    }

    /** Dipanggil setelah sukses: aksi berikutnya adalah permintaan baru. */
    @Synchronized
    fun reset() {
        key = null
        payloadHash = null
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
