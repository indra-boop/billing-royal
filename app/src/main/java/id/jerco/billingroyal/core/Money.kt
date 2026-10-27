package id.jerco.billingroyal.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * Parser input uang/qty dari pengguna. Seluruh hasil berupa BigDecimal (tidak pernah tipe floating point).
 *
 * Aturan locale id-ID untuk nominal uang:
 *  - titik = pemisah ribuan, koma = pemisah desimal ("15.000" = 15000, "15.000,50" = 15000.50)
 *  - bentuk "15000.50" (titik dengan 1-2 digit di belakang) diterima sebagai desimal
 *  - awalan "Rp" dan spasi diabaikan
 */
object MoneyParser {
    private val GROUPED_INT = Regex("^\\d{1,3}(\\.\\d{3})+$")
    private val PLAIN_INT = Regex("^\\d+$")
    private val DOT_DECIMAL = Regex("^\\d+\\.\\d{1,2}$")
    private val QTY = Regex("^\\d+([.,]\\d{1,2})?$")

    fun parseAmount(input: String?, maxScale: Int = 2): BigDecimal? {
        if (input == null) return null
        val s = input.replace("Rp", "", ignoreCase = true)
            .replace(" ", "")
            .replace("\u00A0", "")
        if (s.isEmpty()) return null
        if (s.any { !it.isDigit() && it != '.' && it != ',' }) return null

        val result: BigDecimal = when {
            ',' in s -> {
                if (s.count { it == ',' } > 1) return null
                val intPart = s.substringBefore(',')
                val fracPart = s.substringAfter(',')
                if (fracPart.isEmpty() || fracPart.length > maxScale || !fracPart.all { it.isDigit() }) return null
                val intDigits = when {
                    intPart.isEmpty() -> return null
                    PLAIN_INT.matches(intPart) -> intPart
                    GROUPED_INT.matches(intPart) -> intPart.replace(".", "")
                    else -> return null
                }
                BigDecimal("$intDigits.$fracPart")
            }
            '.' in s -> when {
                GROUPED_INT.matches(s) -> BigDecimal(s.replace(".", ""))
                DOT_DECIMAL.matches(s) && s.substringAfter('.').length <= maxScale -> BigDecimal(s)
                else -> return null
            }
            PLAIN_INT.matches(s) -> BigDecimal(s)
            else -> return null
        }
        return result
    }

    /** Qty: angka positif, maksimal 2 desimal, pemisah desimal "." atau ",". */
    fun parseQuantity(input: String?): BigDecimal? {
        val s = input?.trim() ?: return null
        if (!QTY.matches(s)) return null
        val value = BigDecimal(s.replace(',', '.'))
        return if (value.signum() > 0) value else null
    }
}

/** Format tampilan uang locale id-ID: "Rp 15.000". Deterministik (simbol ditetapkan eksplisit). */
object MoneyFormatter {
    private fun symbols() = DecimalFormatSymbols().apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }

    fun format(value: BigDecimal): String {
        val negative = value.signum() < 0
        val abs = value.abs()
        val hasFraction = abs.stripTrailingZeros().scale() > 0
        val pattern = if (hasFraction) "#,##0.00" else "#,##0"
        val text = DecimalFormat(pattern, symbols()).apply { roundingMode = RoundingMode.HALF_UP }.format(abs)
        return (if (negative) "-Rp " else "Rp ") + text
    }

    /** Qty untuk tampilan, mis. "1,5" atau "3". */
    fun formatQty(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        val plain = if (stripped.scale() < 0) stripped.setScale(0) else stripped
        return plain.toPlainString().replace('.', ',')
    }

    /** String desimal untuk dikirim ke server, mis. "15000.00". */
    fun toWireAmount(value: BigDecimal): String = value.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}
