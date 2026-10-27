package id.jerco.billingroyal.data

import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.ErrorMapper
import id.jerco.billingroyal.data.remote.displayMessage
import id.jerco.billingroyal.data.remote.isModuleNotReady
import id.jerco.billingroyal.data.remote.isOutcomeUncertain
import id.jerco.billingroyal.data.remote.requiresRelogin
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class ErrorMapperTest {

    private fun body(code: String, message: String, details: String = "{}") =
        """{"error":{"code":"$code","message":"$message","details":$details}}"""

    @Test
    fun `memetakan envelope error dan menampilkan message server apa adanya`() {
        val e = ErrorMapper.fromHttp(401, body("invalid_token", "Token tidak valid atau sudah kedaluwarsa."))
        assertEquals(401, e.status)
        assertEquals("invalid_token", e.code)
        assertEquals("Token tidak valid atau sudah kedaluwarsa.", e.message)
        assertTrue(e.requiresRelogin)
    }

    @Test
    fun `401 unauthenticated dan invalid_token memicu relogin tetapi invalid_credentials tidak`() {
        assertTrue(ErrorMapper.fromHttp(401, body("unauthenticated", "x")).requiresRelogin)
        assertTrue(ErrorMapper.fromHttp(401, body("invalid_token", "x")).requiresRelogin)
        assertFalse(ErrorMapper.fromHttp(401, body("invalid_credentials", "Username atau password salah.")).requiresRelogin)
    }

    @Test
    fun `403 account_inactive memicu relogin, 403 lain tidak`() {
        assertTrue(ErrorMapper.fromHttp(403, body("account_inactive", "Akun nonaktif.")).requiresRelogin)
        assertFalse(ErrorMapper.fromHttp(403, body("forbidden", "Tidak diizinkan.")).requiresRelogin)
    }

    @Test
    fun `422 validation_error membawa detail per field`() {
        val e = ErrorMapper.fromHttp(
            422,
            body("validation_error", "Data tidak valid.", """{"name":"Nama wajib diisi.","items":["Minimal 1 item.","lain"],"x":{"a":1}}"""),
        )
        assertEquals("validation_error", e.code)
        assertEquals("Nama wajib diisi.", e.fieldErrors["name"])
        assertEquals("Minimal 1 item.", e.fieldErrors["items"])
        assertFalse(e.fieldErrors.containsKey("x"))
        assertFalse(e.isOutcomeUncertain)
    }

    @Test
    fun `429 dan 503 memakai pesan server`() {
        val e429 = ErrorMapper.fromHttp(429, body("too_many_attempts", "Terlalu banyak percobaan. Coba lagi nanti."))
        assertEquals("Terlalu banyak percobaan. Coba lagi nanti.", e429.message)
        assertFalse(e429.isOutcomeUncertain)
        val e503 = ErrorMapper.fromHttp(503, body("module_not_ready", "Modul belum siap."))
        assertTrue(e503.isModuleNotReady)
        assertFalse(e503.isOutcomeUncertain) // 503: server menolak sebelum memproses
    }

    @Test
    fun `500 dianggap hasil tidak pasti`() {
        assertTrue(ErrorMapper.fromHttp(500, body("internal_error", "Kesalahan server.")).isOutcomeUncertain)
    }

    @Test
    fun `body bukan JSON memakai pesan cadangan`() {
        val e = ErrorMapper.fromHttp(502, "<html>Bad Gateway</html>")
        assertEquals(502, e.status)
        assertEquals("", e.code)
        assertTrue(e.message.contains("502"))
        assertEquals(500, ErrorMapper.fromHttp(500, null).status)
        assertTrue(ErrorMapper.fromHttp(404, "").message.contains("404"))
    }

    @Test
    fun `envelope tanpa message memakai pesan cadangan`() {
        val e = ErrorMapper.fromHttp(404, """{"error":{"code":"not_found"}}""")
        assertEquals("not_found", e.code)
        assertTrue(e.message.contains("404"))
    }

    @Test
    fun `HttpException dipetakan dari body error`() {
        val response = Response.error<Any>(
            409,
            body("resource_in_use", "Customer sudah memiliki invoice.").toResponseBody("application/json".toMediaType()),
        )
        val e = ErrorMapper.map(HttpException(response))
        assertIs<AppError.Api>(e)
        assertEquals(409, e.status)
        assertEquals("resource_in_use", e.code)
        assertEquals("Customer sudah memiliki invoice.", e.message)
    }

    @Test
    fun `kesalahan jaringan dipetakan dan dianggap tidak pasti`() {
        val timeout = ErrorMapper.map(SocketTimeoutException("timeout"))
        assertIs<AppError.Network>(timeout)
        assertTrue(timeout.timeout)
        assertTrue(timeout.isOutcomeUncertain)
        assertIs<AppError.Network>(ErrorMapper.map(UnknownHostException("host")))
        assertIs<AppError.Network>(ErrorMapper.map(IOException("putus")))
        assertTrue(ErrorMapper.map(IOException("putus")).isOutcomeUncertain)
    }

    @Test
    fun `respons tak terbaca dan error tak terduga`() {
        val s = ErrorMapper.map(SerializationException("rusak"))
        assertIs<AppError.Unexpected>(s)
        assertTrue(s.isOutcomeUncertain)
        assertIs<AppError.Unexpected>(ErrorMapper.map(IllegalStateException("x")))
    }

    @Test
    fun `401 dengan body kosong, non-JSON, atau kode tak dikenal TIDAK menghapus sesi`() {
        assertFalse(ErrorMapper.fromHttp(401, null).requiresRelogin)
        assertFalse(ErrorMapper.fromHttp(401, "").requiresRelogin)
        assertFalse(ErrorMapper.fromHttp(401, "<html>Unauthorized (proxy)</html>").requiresRelogin)
        assertFalse(ErrorMapper.fromHttp(401, body("something_else", "x")).requiresRelogin)
        assertFalse(ErrorMapper.isSessionTerminating(401, ""))
        assertTrue(ErrorMapper.isSessionTerminating(401, "unauthenticated"))
        assertTrue(ErrorMapper.isSessionTerminating(401, "invalid_token"))
        assertTrue(ErrorMapper.isSessionTerminating(403, "account_inactive"))
        assertFalse(ErrorMapper.isSessionTerminating(403, ""))
    }

    @Test
    fun `DNS gagal, koneksi ditolak, dan TLS handshake gagal ditandai belum terkirim`() {
        listOf(
            ErrorMapper.map(UnknownHostException("h")),
            ErrorMapper.map(java.net.ConnectException("refused")),
            ErrorMapper.map(javax.net.ssl.SSLHandshakeException("tls")),
        ).forEach {
            val n = assertIs<AppError.Network>(it)
            assertTrue(n.notSent)
            assertFalse(n.isOutcomeUncertain)
        }
        assertFalse((ErrorMapper.map(SocketTimeoutException("t")) as AppError.Network).notSent)
    }

    @Test
    fun `displayMessage menyertakan rincian per field 422`() {
        val e = ErrorMapper.fromHttp(
            422,
            body("validation_error", "Data tidak valid.", """{"name":"Nama wajib diisi.","items.0.qty":"Qty harus lebih dari 0."}"""),
        )
        val text = e.displayMessage
        assertTrue(text.startsWith("Data tidak valid."))
        assertTrue(text.contains("• Name: Nama wajib diisi."), text)
        assertTrue(text.contains("• Item 1 (qty): Qty harus lebih dari 0."), text)
        // tanpa rincian: sama dengan pesan biasa
        assertEquals("Data tidak valid.", ErrorMapper.fromHttp(422, body("validation_error", "Data tidak valid.")).displayMessage)
    }
}
