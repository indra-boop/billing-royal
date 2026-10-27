package id.jerco.billingroyal.core

import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.InvoiceStatus
import id.jerco.billingroyal.data.repo.DraftItem
import java.math.BigDecimal

/** Preview total LOKAL (hanya untuk tampilan; server yang authoritative untuk subtotal final). */
data class InvoicePreview(
    val lines: List<Line>,
    val subtotal: BigDecimal,
    val hasMissingPrice: Boolean,
) {
    /** Asal harga satu baris. UNKNOWN = harga belum/gagal dimuat (jangan diperingatkan sebagai "tanpa harga"). */
    enum class PriceState { OK, NOT_IN_PRICE_LIST, ZERO_PRICE, UNKNOWN }

    data class Line(
        val item: DraftItem,
        val qty: BigDecimal?,
        val unitPrice: BigDecimal,
        val amount: BigDecimal,
        val priceMissing: Boolean,
        val state: PriceState = if (priceMissing) PriceState.NOT_IN_PRICE_LIST else PriceState.OK,
    )

    /** Jumlah baris yang harganya Rp 0 / tidak ditemukan (tidak menghitung UNKNOWN). */
    val problemCount: Int get() = lines.count { it.state == PriceState.NOT_IN_PRICE_LIST || it.state == PriceState.ZERO_PRICE }

    companion object {
        /**
         * Preview LOKAL. Sengaja TIDAK ada aturan pembulatan di sini: amount = harga x qty apa adanya (bisa 4 desimal),
         * subtotal = jumlah nilai itu. Aturan pembulatan server belum diketahui (lihat ASSUMPTIONS.md), jadi layar
         * hanya menyebut angka ini perkiraan; server yang menentukan total final.
         *
         * @param pricesKnown false bila harga belum dimuat atau gagal dimuat: baris tanpa override diberi state UNKNOWN.
         */
        fun compute(
            items: List<DraftItem>,
            prices: Map<Long, BigDecimal>,
            flags: FeatureFlags,
            pricesKnown: Boolean = true,
        ): InvoicePreview {
            val lines = items.map { item ->
                val qty = MoneyParser.parseQuantity(item.qtyText)
                val override = if (flags.priceOverride && item.overridePriceText.isNotBlank()) {
                    MoneyParser.parseAmount(item.overridePriceText)
                } else {
                    null
                }
                val listed = prices[item.serviceId]
                val price = override ?: listed ?: BigDecimal.ZERO
                val amount = if (qty != null) price.multiply(qty) else BigDecimal.ZERO
                val state = when {
                    override != null -> if (override.signum() == 0) PriceState.ZERO_PRICE else PriceState.OK
                    !pricesKnown -> PriceState.UNKNOWN
                    listed == null -> PriceState.NOT_IN_PRICE_LIST
                    listed.signum() == 0 -> PriceState.ZERO_PRICE
                    else -> PriceState.OK
                }
                Line(item, qty, price, amount, priceMissing = price.signum() == 0, state = state)
            }
            return InvoicePreview(
                lines = lines,
                subtotal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount },
                hasMissingPrice = lines.any { it.state == PriceState.NOT_IN_PRICE_LIST || it.state == PriceState.ZERO_PRICE },
            )
        }
    }
}

object InvoiceActions {
    /**
     * Status tujuan yang boleh dikirim app. Seluruhnya di balik feature flag:
     *  - "Sent" bila salah satu flag status aktif (Draft -> Sent)
     *  - "Paid" hanya bila ENABLE_MARK_PAID (dari Sent)
     *  - "Cancelled" hanya bila ENABLE_CANCEL_INVOICE (dari Draft/Sent)
     */
    fun availableTargets(current: String, flags: FeatureFlags): List<String> {
        val out = ArrayList<String>()
        when (current) {
            InvoiceStatus.DRAFT -> {
                if (flags.statusChange) out += InvoiceStatus.SENT
                if (flags.cancelInvoice) out += InvoiceStatus.CANCELLED
            }
            InvoiceStatus.SENT -> {
                if (flags.markPaid) out += InvoiceStatus.PAID
                if (flags.cancelInvoice) out += InvoiceStatus.CANCELLED
            }
        }
        return out
    }
}

object InvoiceShareText {
    /**
     * Baris status untuk customer. Status internal ("Draft") TIDAK dikirim karena menyesatkan: invoice yang
     * sudah dibagikan terlihat belum final. Yang ditampilkan adalah status PEMBAYARAN dari angka server,
     * atau "DIBATALKAN" bila invoice dibatalkan.
     */
    fun paymentStatusLine(invoice: InvoiceDto): String? {
        if (invoice.status == InvoiceStatus.CANCELLED) return "Status: ${InvoiceStatus.label(InvoiceStatus.CANCELLED).uppercase()}"
        return when {
            invoice.subtotal.signum() > 0 && invoice.balance.signum() <= 0 -> "Status pembayaran: Lunas"
            invoice.paid_amount.signum() > 0 && invoice.balance.signum() > 0 -> "Status pembayaran: Dibayar sebagian"
            invoice.subtotal.signum() > 0 -> "Status pembayaran: Belum dibayar"
            else -> null
        }
    }

    /**
     * @param includeBank false: nomor rekening/atas nama TIDAK ikut dibagikan (T16). Nama dan alamat bisnis tetap ada.
     */
    fun build(invoice: InvoiceDto, profile: ProfileEntity?, includeBank: Boolean = true): String = buildString {
        profile?.let { appendLine(it.name) }
        profile?.address?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine("INVOICE ${invoice.invoice_number}")
        AppDates.parseApiDate(invoice.invoice_date)?.let { appendLine("Tanggal: ${AppDates.display(it)}") }
        appendLine("Customer: ${invoice.customer_name}")
        paymentStatusLine(invoice)?.let { appendLine(it) }
        appendLine()
        invoice.items.forEach { item ->
            appendLine(
                "- ${item.description}: ${MoneyFormatter.formatQty(item.qty)} ${item.unit} x " +
                    "${MoneyFormatter.format(item.unit_price)} = ${MoneyFormatter.format(item.amount)}",
            )
        }
        appendLine()
        appendLine("Subtotal: ${MoneyFormatter.format(invoice.subtotal)}")
        appendLine("Dibayar: ${MoneyFormatter.format(invoice.paid_amount)}")
        appendLine("Sisa: ${MoneyFormatter.format(invoice.balance)}")
        invoice.notes?.takeIf { it.isNotBlank() }?.let { appendLine("Catatan: $it") }
        val bank = profile
        if (includeBank && bank != null && !bank.bankAccount.isNullOrBlank()) {
            appendLine()
            appendLine("Pembayaran ke rekening:")
            append("${bank.bankName.orEmpty()} ${bank.bankAccount}")
            if (!bank.bankHolder.isNullOrBlank()) append(" a.n. ${bank.bankHolder}")
            appendLine()
        }
    }.trimEnd()
}
