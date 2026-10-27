package id.jerco.billingroyal.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LogoDataUrlTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test
    fun `png dan jpeg yang valid diterima`() {
        assertContentEquals(png, LogoDataUrl.decode("data:image/png;base64," + b64(png)))
        assertContentEquals(jpeg, LogoDataUrl.decode("data:image/jpeg;base64," + b64(jpeg)))
        assertNotNull(LogoDataUrl.decode("DATA:IMAGE/PNG;BASE64," + b64(png)))
    }

    @Test
    fun `tipe selain png jpeg ditolak`() {
        for (prefix in listOf("data:image/gif;base64,", "data:image/svg+xml;base64,", "data:text/html;base64,", "data:image/webp;base64,")) {
            assertNull(LogoDataUrl.decode(prefix + b64(png)), prefix)
        }
    }

    @Test
    fun `tanpa prefix data url ditolak`() {
        assertNull(LogoDataUrl.decode(b64(png)))
        assertNull(LogoDataUrl.decode("image/png;base64," + b64(png)))
        assertNull(LogoDataUrl.decode("data:image/png,${b64(png)}"))
        assertNull(LogoDataUrl.decode(null))
        assertNull(LogoDataUrl.decode(""))
    }

    @Test
    fun `byte awal harus cocok dengan tipe yang dinyatakan`() {
        assertNull(LogoDataUrl.decode("data:image/png;base64," + b64(jpeg)))
        assertNull(LogoDataUrl.decode("data:image/jpeg;base64," + b64("<html>".toByteArray())))
    }

    @Test
    fun `base64 rusak dan hasil kosong ditolak`() {
        assertNull(LogoDataUrl.decode("data:image/png;base64,@@@@"))
        assertNull(LogoDataUrl.decode("data:image/png;base64,"))
    }

    @Test
    fun `tepat 2 MiB diterima, lebih dari itu ditolak`() {
        val atLimit = png + ByteArray(LogoDataUrl.MAX_BYTES - png.size)
        assertNotNull(LogoDataUrl.decode("data:image/png;base64," + b64(atLimit)))
        val over = png + ByteArray(LogoDataUrl.MAX_BYTES - png.size + 1)
        assertNull(LogoDataUrl.decode("data:image/png;base64," + b64(over)))
    }

    @Test
    fun `string sangat panjang ditolak sebelum decode`() {
        val huge = "data:image/png;base64," + "A".repeat(LogoDataUrl.MAX_CHARS)
        assertNull(LogoDataUrl.decode(huge))
    }
}
