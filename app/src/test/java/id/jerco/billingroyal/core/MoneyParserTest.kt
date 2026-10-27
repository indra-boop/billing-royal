package id.jerco.billingroyal.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyParserTest {

    private fun amount(text: String?) = MoneyParser.parseAmount(text)

    @Test
    fun `angka polos`() {
        assertEquals(BigDecimal("15000"), amount("15000"))
        assertEquals(BigDecimal("0"), amount("0"))
    }

    @Test
    fun `titik sebagai pemisah ribuan id-ID`() {
        assertEquals(BigDecimal("15000"), amount("15.000"))
        assertEquals(BigDecimal("1500000"), amount("1.500.000"))
    }

    @Test
    fun `koma sebagai pemisah desimal`() {
        assertEquals(BigDecimal("15000.50"), amount("15.000,50"))
        assertEquals(BigDecimal("0.5"), amount("0,5"))
        assertEquals(BigDecimal("1500.00"), amount("1500,00"))
    }

    @Test
    fun `titik desimal gaya mesin diterima bila 1-2 digit`() {
        assertEquals(BigDecimal("15000.50"), amount("15000.50"))
        assertEquals(BigDecimal("1.5"), amount("1.5"))
    }

    @Test
    fun `awalan Rp dan spasi diabaikan`() {
        assertEquals(BigDecimal("15000"), amount("Rp 15.000"))
        assertEquals(BigDecimal("15000"), amount("  rp15.000 "))
    }

    @Test
    fun `presisi tidak hilang pada nilai besar`() {
        assertEquals(BigDecimal("123456789012345.67"), amount("123.456.789.012.345,67"))
    }

    @Test
    fun `input tidak valid ditolak`() {
        listOf("", " ", "abc", "-15000", "15.00.0", "1,2,3", "15,", ",50", "15,555", "15.0000", "1e3", "15.000,5,0", "12.34.567")
            .forEach { assertNull(amount(it), "seharusnya ditolak: '$it'") }
        assertNull(amount(null))
    }

    @Test
    fun `qty menerima titik atau koma dengan maksimal 2 desimal`() {
        assertEquals(BigDecimal("2"), MoneyParser.parseQuantity("2"))
        assertEquals(BigDecimal("2.5"), MoneyParser.parseQuantity("2,5"))
        assertEquals(BigDecimal("2.75"), MoneyParser.parseQuantity("2.75"))
        assertEquals(BigDecimal("0.01"), MoneyParser.parseQuantity("0.01"))
    }

    @Test
    fun `qty harus lebih dari nol dan maksimal 2 desimal`() {
        listOf("0", "0.00", "-1", "1.234", "1.000", "", "abc", "1,5,5", ".5", "5.").forEach {
            assertNull(MoneyParser.parseQuantity(it), "seharusnya ditolak: '$it'")
        }
    }

    @Test
    fun `format uang id-ID`() {
        assertEquals("Rp 15.000", MoneyFormatter.format(BigDecimal("15000")))
        assertEquals("Rp 15.000", MoneyFormatter.format(BigDecimal("15000.00")))
        assertEquals("Rp 1.500.000", MoneyFormatter.format(BigDecimal("1500000")))
        assertEquals("Rp 0", MoneyFormatter.format(BigDecimal.ZERO))
        assertEquals("Rp 15.000,50", MoneyFormatter.format(BigDecimal("15000.5")))
        assertEquals("-Rp 2.500", MoneyFormatter.format(BigDecimal("-2500")))
    }

    @Test
    fun `format qty`() {
        assertEquals("3", MoneyFormatter.formatQty(BigDecimal("3.00")))
        assertEquals("1,5", MoneyFormatter.formatQty(BigDecimal("1.50")))
        assertEquals("100", MoneyFormatter.formatQty(BigDecimal("1E+2")))
    }

    @Test
    fun `nominal kirim sebagai string desimal 2 digit`() {
        assertEquals("15000.00", MoneyFormatter.toWireAmount(BigDecimal("15000")))
        assertEquals("15000.50", MoneyFormatter.toWireAmount(BigDecimal("15000.5")))
    }

    @Test
    fun `round trip format lalu parse`() {
        val value = BigDecimal("1234567.89")
        assertEquals(value, MoneyParser.parseAmount(MoneyFormatter.format(value)))
    }
}
