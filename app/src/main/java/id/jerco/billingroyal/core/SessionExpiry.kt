package id.jerco.billingroyal.core

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Pemeriksaan `expires_at` di klien (T13). Ini hanya optimasi: token yang jelas sudah lewat tidak perlu dikirim ke
 * server. SERVER TETAP AUTHORITATIVE: nilai yang tidak bisa dibaca dianggap belum kedaluwarsa, dan token yang
 * menurut klien masih berlaku tetap bisa ditolak server (401) dan ditangani seperti biasa.
 *
 * Format yang dibaca: "yyyy-MM-dd HH:mm:ss" tanpa zona (diperlakukan Asia/Makassar, lihat ASSUMPTIONS.md) atau ISO-8601
 * dengan offset/Z. [skew] memberi toleransi selisih jam perangkat/server.
 */
object SessionExpiry {
    val DEFAULT_SKEW: Duration = Duration.ofMinutes(5)

    fun parse(expiresAt: String?): Instant? {
        if (expiresAt.isNullOrBlank()) return null
        val text = expiresAt.trim()
        AppDates.parseServerDateTime(text)?.let { return it.toInstant() }
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun isExpired(expiresAt: String?, now: Instant, skew: Duration = DEFAULT_SKEW): Boolean {
        val at = parse(expiresAt) ?: return false
        return now.isAfter(at.plus(skew))
    }
}
