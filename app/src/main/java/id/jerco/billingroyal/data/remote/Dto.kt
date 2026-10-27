@file:UseSerializers(BigDecimalSerializer::class, LenientLongSerializer::class)

package id.jerco.billingroyal.data.remote

import id.jerco.billingroyal.core.BigDecimalSerializer
import id.jerco.billingroyal.core.LenientLongSerializer
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------- Envelope

@Serializable
data class Envelope<T>(val data: T, val meta: Meta? = null)

@Serializable
data class Meta(
    val page: Long? = null,
    val per_page: Long? = null,
    val total: Long? = null,
    val last_page: Long? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(
    val code: String = "",
    val message: String = "",
    val details: JsonElement? = null,
)

// ---------------------------------------------------------------- Auth / profil

@Serializable
data class LoginRequest(val username: String, val password: String, val device_name: String) {
    // Password tidak boleh ikut tercetak lewat toString()/log.
    override fun toString(): String = "LoginRequest(username=$username, password=<redacted>)"
}

@Serializable
data class BusinessRef(val id: Long, val name: String = "", val code: String = "")

@Serializable
data class UserDto(
    val id: Long,
    val username: String = "",
    val full_name: String = "",
    val role: String = "",
    val business: BusinessRef,
)

@Serializable
data class LoginData(val token: String, val expires_at: String? = null, val user: UserDto) {
    // Token tidak boleh ikut tercetak lewat toString()/log.
    override fun toString(): String = "LoginData(user=${user.id}, token=<redacted>)"
}

@Serializable
data class LogoutData(val revoked: Boolean = false)

@Serializable
data class HealthDto(val status: String = "", val version: String? = null, val time: String? = null)

@Serializable
data class ProfileDto(
    val id: Long,
    val name: String = "",
    val code: String = "",
    val address: String? = null,
    val bank_name: String? = null,
    val bank_account: String? = null,
    val bank_holder: String? = null,
    val logo_data: String? = null,
)

// ---------------------------------------------------------------- Customer / katalog

@Serializable
data class CustomerDto(
    val id: Long,
    val name: String = "",
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val price_list_id: Long? = null,
    val price_list_name: String? = null,
    val created_at: String? = null,
)

@Serializable
data class DeletedData(val deleted: Boolean = false)

@Serializable
data class ServiceDto(
    val id: Long,
    val item_code: String? = null,
    val category: String = "",
    val name: String = "",
    val unit: String = "",
    val created_at: String? = null,
)

@Serializable
data class PriceListSummaryDto(
    val id: Long,
    val name: String = "",
    val item_count: Long = 0,
    val created_at: String? = null,
)

@Serializable
data class PriceListItemDto(
    val id: Long = 0,
    val service_id: Long,
    val item_code: String? = null,
    val category: String = "",
    val service_name: String = "",
    val unit: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
)

@Serializable
data class PriceListDetailDto(
    val id: Long,
    val name: String = "",
    val created_at: String? = null,
    val items: List<PriceListItemDto> = emptyList(),
)

// ---------------------------------------------------------------- Invoice

@Serializable
data class InvoiceItemDto(
    val id: Long = 0,
    val service_id: Long? = null,
    val description: String = "",
    val qty: BigDecimal = BigDecimal.ZERO,
    val unit: String = "",
    val unit_price: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
)

/** Dipakai untuk item daftar maupun detail (detail menyertakan [items]). */
@Serializable
data class InvoiceDto(
    val id: Long,
    val invoice_number: String = "",
    val invoice_date: String = "",
    val customer_id: Long = 0,
    val customer_name: String = "",
    val status: String = "",
    val notes: String? = null,
    val quantity: BigDecimal = BigDecimal.ZERO,
    val subtotal: BigDecimal = BigDecimal.ZERO,
    val paid_amount: BigDecimal = BigDecimal.ZERO,
    val balance: BigDecimal = BigDecimal.ZERO,
    val created_at: String? = null,
    val updated_at: String? = null,
    val items: List<InvoiceItemDto> = emptyList(),
)

/** Body POST /invoices. Nilai null tidak dikirim (explicitNulls = false), sehingga unit_price hilang bila null. */
@Serializable
data class InvoiceCreateRequest(
    val customer_id: Long,
    val invoice_date: String,
    val status: String = InvoiceStatus.DRAFT,
    val notes: String? = null,
    val items: List<InvoiceItemRequest>,
)

@Serializable
data class InvoiceItemRequest(
    val service_id: Long,
    val qty: BigDecimal,
    val unit_price: BigDecimal? = null,
)

@Serializable
data class StatusRequest(val status: String)

object InvoiceStatus {
    const val DRAFT = "Draft"
    const val SENT = "Sent"
    const val PAID = "Paid"
    const val CANCELLED = "Cancelled"
    val ALL = listOf(DRAFT, SENT, PAID, CANCELLED)
}

// ---------------------------------------------------------------- Expense

@Serializable
data class ExpenseDto(
    val id: Long,
    val business_id: Long? = null,
    val expense_date: String = "",
    val category: String = "",
    val description: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val payment_method: String = "",
    val payee: String? = null,
    val notes: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
)

object PaymentMethods {
    val ALL = listOf("Tunai", "Transfer", "QRIS", "Lainnya")
}

// ---------------------------------------------------------------- Laporan

@Serializable
data class ReportSummaryDto(
    val invoice_count: Long = 0,
    val total_quantity: BigDecimal = BigDecimal.ZERO,
    val invoice_total: BigDecimal = BigDecimal.ZERO,
    val paid_total: BigDecimal = BigDecimal.ZERO,
    val balance_total: BigDecimal = BigDecimal.ZERO,
)

@Serializable
data class InvoiceReportDto(
    val summary: ReportSummaryDto = ReportSummaryDto(),
    val invoices: List<InvoiceDto> = emptyList(),
)

object PaymentScope {
    const val ALL = "all"
    const val PAID = "paid"
    const val UNPAID = "unpaid"
}
