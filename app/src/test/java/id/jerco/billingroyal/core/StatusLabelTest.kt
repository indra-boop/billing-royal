package id.jerco.billingroyal.core

import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.InvoiceStatus
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusLabelTest {
    @Test
    fun `label status Indonesia, nilai wire tetap Inggris`() {
        assertEquals("Draft", InvoiceStatus.label(InvoiceStatus.DRAFT))
        assertEquals("Terkirim", InvoiceStatus.label(InvoiceStatus.SENT))
        assertEquals("Lunas", InvoiceStatus.label(InvoiceStatus.PAID))
        assertEquals("Dibatalkan", InvoiceStatus.label(InvoiceStatus.CANCELLED))
        assertEquals("Entah", InvoiceStatus.label("Entah"))
        assertEquals(listOf("Draft", "Sent", "Paid", "Cancelled"), InvoiceStatus.ALL, "nilai wire tidak boleh berubah")
        assertEquals("Tandai Terkirim", InvoiceStatus.actionLabel(InvoiceStatus.SENT))
        assertEquals("Tandai Lunas", InvoiceStatus.actionLabel(InvoiceStatus.PAID))
    }

    private fun invoice(status: String, subtotal: String, paid: String, balance: String) = InvoiceDto(
        id = 1, invoice_number = "INV-1", invoice_date = "2026-10-27", customer_id = 1, customer_name = "Customer Fiktif",
        status = status, subtotal = BigDecimal(subtotal), paid_amount = BigDecimal(paid), balance = BigDecimal(balance),
    )

    @Test
    fun `teks bagikan tidak memuat Status Draft yang menyesatkan`() {
        val text = InvoiceShareText.build(invoice("Draft", "10000", "0", "10000"), null)
        assertFalse(text.contains("Status: Draft"))
        assertFalse(text.contains("Draft"))
        assertTrue(text.contains("Status pembayaran: Belum dibayar"))
    }

    @Test
    fun `status pembayaran dihitung dari angka server`() {
        assertTrue(InvoiceShareText.build(invoice("Sent", "10000", "10000", "0"), null).contains("Status pembayaran: Lunas"))
        assertTrue(InvoiceShareText.build(invoice("Sent", "10000", "4000", "6000"), null).contains("Status pembayaran: Dibayar sebagian"))
        val cancelled = InvoiceShareText.build(invoice("Cancelled", "10000", "0", "10000"), null)
        assertTrue(cancelled.contains("Status: DIBATALKAN"))
        assertFalse(cancelled.contains("Cancelled"))
    }

    private val profile = id.jerco.billingroyal.data.local.ProfileEntity(
        scope = "5:9", businessId = 9, name = "Laundry Fiktif", code = "LF", address = "Jl. Fiktif 1",
        bankName = "Bank Fiktif", bankAccount = "000111222", bankHolder = "Pemilik Fiktif", logoPath = null,
    )

    @Test
    fun `rekening ikut dibagikan secara bawaan dan bisa dimatikan`() {
        val inv = invoice("Sent", "10000", "0", "10000")
        val with = InvoiceShareText.build(inv, profile)
        assertTrue(with.contains("000111222") && with.contains("Bank Fiktif") && with.contains("Pemilik Fiktif"))
        val without = InvoiceShareText.build(inv, profile, includeBank = false)
        assertFalse(without.contains("000111222"))
        assertFalse(without.contains("Bank Fiktif"))
        assertFalse(without.contains("Pemilik Fiktif"))
        assertFalse(without.contains("rekening"))
        assertTrue(without.contains("Laundry Fiktif") && without.contains("INV-1"), "isi invoice dan nama bisnis tetap ada")
    }
}
