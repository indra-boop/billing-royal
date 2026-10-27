package id.jerco.billingroyal.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionExpiryTest {
    // 2026-10-27 12:00 WITA = 04:00Z
    private val now = Instant.parse("2026-10-27T04:00:00Z")

    @Test
    fun `waktu tanpa zona dibaca sebagai Asia-Makassar`() {
        assertEquals(Instant.parse("2026-10-27T04:00:00Z"), SessionExpiry.parse("2026-10-27 12:00:00"))
    }

    @Test
    fun `ISO dengan offset atau Z dibaca sesuai zonanya`() {
        assertEquals(Instant.parse("2026-10-27T04:00:00Z"), SessionExpiry.parse("2026-10-27T12:00:00+08:00"))
        assertEquals(Instant.parse("2026-10-27T04:00:00Z"), SessionExpiry.parse("2026-10-27T04:00:00Z"))
    }

    @Test
    fun `lewat melebihi toleransi dianggap kedaluwarsa`() {
        assertTrue(SessionExpiry.isExpired("2026-10-27 11:00:00", now))
    }

    @Test
    fun `dalam toleransi selisih jam belum dianggap kedaluwarsa`() {
        assertFalse(SessionExpiry.isExpired("2026-10-27 11:57:00", now)) // lewat 3 menit < 5 menit
        assertTrue(SessionExpiry.isExpired("2026-10-27 11:57:00", now, skew = Duration.ZERO))
    }

    @Test
    fun `masa depan null kosong dan tak terbaca bukan kedaluwarsa`() {
        assertFalse(SessionExpiry.isExpired("2026-12-31 00:00:00", now))
        for (v in listOf(null, "", "  ", "kemarin", "2026-13-45 99:99:99")) {
            assertFalse(SessionExpiry.isExpired(v, now), "v=$v")
            assertNull(SessionExpiry.parse(v))
        }
    }
}
