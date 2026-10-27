package id.jerco.billingroyal.core

import java.util.Base64

/**
 * Validasi + decode logo bisnis dari server (T14), murni (bisa dites tanpa Android).
 *
 * Aturan: hanya `data:image/png;base64,` atau `data:image/jpeg;base64,` (huruf besar/kecil diabaikan), ukuran hasil decode
 * maksimal [MAX_BYTES] (2 MiB), dan byte awal harus cocok dengan tipe yang dinyatakan (PNG/JPEG). String yang terlalu panjang
 * ditolak SEBELUM didecode. Apa pun yang tidak lolos menghasilkan null (logo diabaikan, bukan galat).
 */
object LogoDataUrl {
    const val MAX_BYTES = 2 * 1024 * 1024

    /** Batas panjang string: 4/3 dari MAX_BYTES + prefix + toleransi pemisah baris MIME. */
    const val MAX_CHARS = MAX_BYTES / 3 * 4 + 4 + 128 + MAX_BYTES / 76 * 2

    private const val PNG_PREFIX = "data:image/png;base64,"
    private const val JPEG_PREFIX = "data:image/jpeg;base64,"
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    fun decode(dataUrl: String?): ByteArray? {
        if (dataUrl.isNullOrBlank() || dataUrl.length > MAX_CHARS) return null
        val isPng = dataUrl.regionMatches(0, PNG_PREFIX, 0, PNG_PREFIX.length, ignoreCase = true)
        val isJpeg = !isPng && dataUrl.regionMatches(0, JPEG_PREFIX, 0, JPEG_PREFIX.length, ignoreCase = true)
        if (!isPng && !isJpeg) return null
        val payload = dataUrl.substring(if (isPng) PNG_PREFIX.length else JPEG_PREFIX.length)
        val bytes = try {
            Base64.getMimeDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return if (startsWith(bytes, if (isPng) PNG_MAGIC else JPEG_MAGIC)) bytes else null
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
}
