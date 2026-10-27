package id.jerco.billingroyal.core

import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.CustomerDto
import id.jerco.billingroyal.data.repo.DraftItem
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope

class DraftCustomerCheckTest {
    private fun customer(priceList: Long?) = CustomerDto(id = 1, name = "Customer Fiktif", price_list_id = priceList)

    @Test
    fun `price list sama maka tidak ada perubahan`() {
        assertEquals(DraftCustomerCheck.Unchanged, DraftCustomerCheck.evaluate(3, ApiResult.Success(customer(3))))
    }

    @Test
    fun `price list berubah maka harga lama tidak boleh dipakai`() {
        val r = DraftCustomerCheck.evaluate(3, ApiResult.Success(customer(9)))
        assertEquals(9L, assertIs<DraftCustomerCheck.PriceListChanged>(r).customer.price_list_id)
    }

    @Test
    fun `price list dicabut di server dianggap berubah`() {
        assertIs<DraftCustomerCheck.PriceListChanged>(DraftCustomerCheck.evaluate(3, ApiResult.Success(customer(null))))
    }

    @Test
    fun `customer 404 atau 422 dianggap hilang`() {
        for (st in listOf(404, 422)) {
            val err = AppError.Api(st, "not_found", "x")
            assertEquals(DraftCustomerCheck.CustomerGone, DraftCustomerCheck.evaluate(3, ApiResult.Failure(err)))
        }
    }

    @Test
    fun `gagal jaringan atau 500 berarti belum terverifikasi, bukan hilang`() {
        val net = ApiResult.Failure(AppError.Network("offline"))
        assertEquals(DraftCustomerCheck.Unverified, DraftCustomerCheck.evaluate(3, net))
        val srv = ApiResult.Failure(AppError.Api(500, "server_error", "x"))
        assertEquals(DraftCustomerCheck.Unverified, DraftCustomerCheck.evaluate(3, srv))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyTest {
    /** Skenario BR-02: customer A (lambat) lalu customer B (cepat); harga A tiba terakhir dan TIDAK boleh diterapkan. */
    @Test
    fun `respons lama yang tiba terakhir tidak menimpa respons terbaru`() = runTest {
        val scope: CoroutineScope = TestScope(StandardTestDispatcher(testScheduler))
        val latest = LatestOnly(scope)
        val applied = mutableListOf<String>()
        val slowA = CompletableDeferred<String>()
        val fastB = CompletableDeferred<String>()

        latest.launch({ slowA.await() }) { applied += it }
        latest.launch({ fastB.await() }) { applied += it }
        advanceUntilIdle()
        fastB.complete("harga-B")
        advanceUntilIdle()
        slowA.complete("harga-A") // tiba terlambat
        advanceUntilIdle()

        assertEquals(listOf("harga-B"), applied)
    }

    @Test
    fun `permintaan lama yang selesai sebelum yang baru dimulai tetap diterapkan`() = runTest {
        val latest = LatestOnly(TestScope(StandardTestDispatcher(testScheduler)))
        val applied = mutableListOf<Int>()
        latest.launch({ 1 }) { applied += it }
        advanceUntilIdle()
        latest.launch({ 2 }) { applied += it }
        advanceUntilIdle()
        assertEquals(listOf(1, 2), applied)
    }

    @Test
    fun `permintaan lama yang tidak kooperatif pun tidak diterapkan setelah diganti`() = runTest {
        val latest = LatestOnly(TestScope(StandardTestDispatcher(testScheduler)))
        val applied = mutableListOf<String>()
        val gateA = CompletableDeferred<Unit>()
        // block A tidak mengecek pembatalan (mengabaikan cancel lewat NonCancellable-like pola: selesai normal)
        latest.launch({ runCatching { gateA.await() }.let { "A" } }) { applied += it }
        advanceUntilIdle()
        latest.launch({ "B" }) { applied += it }
        advanceUntilIdle()
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("B"), applied)
    }
}

class InvoicePreviewStateTest {
    private val flags = FeatureFlags()
    private fun item(id: Long = 1, qty: String = "2", override: String = "") =
        DraftItem(id, "Layanan", "kg", qty, overridePriceText = override)

    @Test
    fun `harga belum dimuat bukan dianggap tanpa harga`() {
        val p = InvoicePreview.compute(listOf(item()), emptyMap(), flags, pricesKnown = false)
        assertEquals(InvoicePreview.PriceState.UNKNOWN, p.lines.single().state)
        assertEquals(0, p.problemCount)
        assertFalse(p.hasMissingPrice)
    }

    @Test
    fun `harga dimuat tetapi service tidak ada di price list`() {
        val p = InvoicePreview.compute(listOf(item()), mapOf(99L to BigDecimal("5000")), flags)
        assertEquals(InvoicePreview.PriceState.NOT_IN_PRICE_LIST, p.lines.single().state)
        assertEquals(1, p.problemCount)
        assertTrue(p.hasMissingPrice)
    }

    @Test
    fun `harga nol di price list ditandai ZERO_PRICE`() {
        val p = InvoicePreview.compute(listOf(item()), mapOf(1L to BigDecimal.ZERO), flags)
        assertEquals(InvoicePreview.PriceState.ZERO_PRICE, p.lines.single().state)
    }

    @Test
    fun `harga normal dan subtotal apa adanya tanpa pembulatan`() {
        val p = InvoicePreview.compute(listOf(item(qty = "2,5")), mapOf(1L to BigDecimal("3333.33")), flags)
        assertEquals(InvoicePreview.PriceState.OK, p.lines.single().state)
        assertEquals(BigDecimal("8333.325"), p.subtotal) // tidak dibulatkan: aturan server belum diketahui
    }
}

class LocaleDeterminismTest {
    @Test
    fun `format dan parse tidak bergantung pada locale perangkat`() {
        val saved = Locale.getDefault()
        try {
            for (loc in listOf(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN"), Locale.GERMANY, Locale.US)) {
                Locale.setDefault(loc)
                assertEquals("Rp 15.000,50", MoneyFormatter.format(BigDecimal("15000.5")), "locale $loc")
                assertEquals(BigDecimal("15000.50"), MoneyParser.parseAmount("Rp 15.000,50"), "locale $loc")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `digit non-ASCII dan awalan selain Rp ditolak`() {
        assertNull(MoneyParser.parseAmount("١٥٠٠٠")) // digit Arab-Indic
        assertNull(MoneyParser.parseAmount("१५०००")) // digit Devanagari
        assertNull(MoneyParser.parseAmount("USD 15.000"))
        assertNull(MoneyParser.parseAmount("15.000 Rp"))
    }
}
