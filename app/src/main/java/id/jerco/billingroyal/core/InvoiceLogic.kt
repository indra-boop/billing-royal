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
    data class Line(val item: DraftItem, val qty: BigDecimal?, val unitPrice: BigDecimal, val amount: BigDecimal, val priceMissing: Boolean)

    companion object {
        fun compute(items: List<DraftItem>, prices: Map<Long, BigDecimal>, flags: FeatureFlags): InvoicePreview {
            val lines = items.map { item ->
                val qty = MoneyParser.parseQuantity(item.qtyText)
                val override = if (flags.priceOverride && item.overridePriceText.isNotBlank()) {
                    MoneyParser.parseAmount(item.overridePriceText)
                } else {
                    null
                }
                val price = override ?: prices[item.serviceId] ?: BigDecimal.ZERO
                val amount = if (qty != null) price.multiply(qty) else BigDecimal.ZERO
                Line(item, qty, price, amount, priceMissing = price.signum() == 0)
            }
            return InvoicePreview(
                lines = lines,
                subtotal = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount },
                hasMissingPrice = lines.any { it.priceMissing },
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

    fun build(invoice: InvoiceDto, profile: ProfileEntity?): String = buildString {
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
        if (bank != null && !bank.bankAccount.isNullOrBlank()) {
            appendLine()
            appendLine("Pembayaran ke rekening:")
            append("${bank.bankName.orEmpty()} ${bank.bankAccount}")
            if (!bank.bankHolder.isNullOrBlank()) append(" a.n. ${bank.bankHolder}")
            appendLine()
        }
    }.trimEnd()
}
