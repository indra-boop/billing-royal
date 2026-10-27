package id.jerco.billingroyal.core

import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.repo.DraftItem
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlin.test.assertFailsWith

class SerializersAndLogicTest {

    @Test
    fun `BigDecimal didecode dari number dan string tanpa kehilangan presisi`() {
        val json = """
            {"id":"7","invoice_number":"INV-1","quantity":2.50,"subtotal":"15000.50",
             "paid_amount":0,"balance":123456789012345678.99,"unknown_field":true}
        """.trimIndent()
        val inv = NetworkFactory.json.decodeFromString<InvoiceDto>(json)
        assertEquals(7L, inv.id)
        assertEquals(BigDecimal("2.50"), inv.quantity)
        assertEquals(BigDecimal("15000.50"), inv.subtotal)
        assertEquals(BigDecimal("0"), inv.paid_amount)
        assertEquals(BigDecimal("123456789012345678.99"), inv.balance)
    }

    @Test
    fun `nilai uang non-angka ditolak`() {
        assertFailsWith<SerializationException> {
            NetworkFactory.json.decodeFromString<InvoiceDto>("""{"id":1,"subtotal":"abc"}""")
        }
        assertFailsWith<SerializationException> {
            NetworkFactory.json.decodeFromString<InvoiceDto>("""{"id":1,"subtotal":null}""")
        }
    }

    @Test
    fun `BigDecimal diencode sebagai JSON number`() {
        val text = NetworkFactory.json.encodeToString(
            id.jerco.billingroyal.data.remote.InvoiceItemRequest(service_id = 3, qty = BigDecimal("1.5")),
        )
        assertEquals("""{"service_id":3,"qty":1.5}""", text)
    }

    @Test
    fun `preview subtotal memakai BigDecimal dan menandai harga kosong`() {
        val items = listOf(
            DraftItem(1, "Cuci kiloan", "kg", "2,5"),
            DraftItem(2, "Setrika", "kg", "1"),
            DraftItem(3, "Tanpa harga", "pcs", "4"),
        )
        val prices = mapOf(1L to BigDecimal("7000"), 2L to BigDecimal("5000.50"))
        val preview = InvoicePreview.compute(items, prices, FeatureFlags())
        assertEquals(BigDecimal("22500.50"), preview.subtotal)
        assertTrue(preview.hasMissingPrice)
        assertTrue(preview.lines[2].priceMissing)
    }

    @Test
    fun `override harga hanya dipakai bila flag aktif`() {
        val items = listOf(DraftItem(1, "Cuci", "kg", "2", overridePriceText = "10.000"))
        val prices = mapOf(1L to BigDecimal("7000"))
        assertEquals(BigDecimal("14000"), InvoicePreview.compute(items, prices, FeatureFlags(priceOverride = false)).subtotal)
        assertEquals(BigDecimal("20000"), InvoicePreview.compute(items, prices, FeatureFlags(priceOverride = true)).subtotal)
    }

    @Test
    fun `aksi status dibalik feature flag`() {
        assertTrue(InvoiceActions.availableTargets("Draft", FeatureFlags()).isEmpty())
        assertTrue(InvoiceActions.availableTargets("Sent", FeatureFlags()).isEmpty())
        assertEquals(listOf("Sent"), InvoiceActions.availableTargets("Draft", FeatureFlags(markPaid = true)))
        assertEquals(listOf("Paid"), InvoiceActions.availableTargets("Sent", FeatureFlags(markPaid = true)))
        assertEquals(listOf("Paid", "Cancelled"), InvoiceActions.availableTargets("Sent", FeatureFlags(markPaid = true, cancelInvoice = true)))
        assertTrue(InvoiceActions.availableTargets("Paid", FeatureFlags(markPaid = true, cancelInvoice = true)).isEmpty())
    }

    @Test
    fun `tanggal tampil dan parse server WITA`() {
        assertEquals("05 Okt 2026", AppDates.display(LocalDate.of(2026, 10, 5)))
        val dt = AppDates.parseServerDateTime("2026-10-27 20:30:00")!!
        assertEquals("Asia/Makassar", dt.zone.id)
        assertEquals(8 * 3600, dt.offset.totalSeconds)
        assertFalse(AppDates.parseApiDate("bukan tanggal") != null)
    }
}
