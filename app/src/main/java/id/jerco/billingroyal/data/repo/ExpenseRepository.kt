package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.core.MoneyFormatter
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.ExpenseDto
import id.jerco.billingroyal.data.remote.PaymentMethods
import id.jerco.billingroyal.data.remote.apiCall
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ExpenseInput(
    val date: LocalDate,
    val category: String,
    val description: String,
    val amount: BigDecimal,
    val paymentMethod: String,
    val payee: String = "",
    val notes: String = "",
) {
    /** Validasi sisi klien; mengembalikan pesan error atau null. */
    fun validate(): String? = when {
        category.isBlank() -> "Kategori wajib diisi."
        category.trim().length > 100 -> "Kategori maksimal 100 karakter."
        description.isBlank() -> "Deskripsi wajib diisi."
        description.trim().length > 255 -> "Deskripsi maksimal 255 karakter."
        amount.signum() <= 0 -> "Nominal harus lebih dari 0."
        amount.stripTrailingZeros().scale() > 2 -> "Nominal maksimal 2 desimal."
        paymentMethod !in PaymentMethods.ALL -> "Pilih metode bayar."
        payee.trim().length > 255 -> "Penerima maksimal 255 karakter."
        notes.trim().length > 5000 -> "Catatan maksimal 5000 karakter."
        else -> null
    }

    /** Body lengkap (semua field). amount dikirim sebagai STRING desimal, mis. "15000.00". */
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "expense_date" to JsonPrimitive(AppDates.apiDate(date)),
            "category" to JsonPrimitive(category.trim()),
            "description" to JsonPrimitive(description.trim()),
            "amount" to JsonPrimitive(MoneyFormatter.toWireAmount(amount)),
            "payment_method" to JsonPrimitive(paymentMethod),
            "payee" to payee.trim().ifEmpty { null }.toJson(),
            "notes" to notes.trim().ifEmpty { null }.toJson(),
        ),
    )
}

data class ExpenseMonth(val items: List<ExpenseDto>, val total: BigDecimal)

@Singleton
class ExpenseRepository @Inject constructor(
    private val api: BillingApi,
    private val flags: FeatureFlags,
) {
    /** Mengambil seluruh halaman bulan tersebut agar total bulan akurat. */
    suspend fun month(month: YearMonth): ApiResult<ExpenseMonth> {
        val all = ArrayList<ExpenseDto>()
        var page = 1
        while (page <= MAX_PAGES) {
            when (val result = apiCall { api.expenses(AppDates.apiMonth(month), page, PER_PAGE) }) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    all += result.data
                    val paged = pagedFrom(result.data, result.meta, page, PER_PAGE)
                    if (!paged.hasMore || result.data.isEmpty()) break
                }
            }
            page++
        }
        val total = all.fold(BigDecimal.ZERO) { acc, e -> acc + e.amount }
        return ApiResult.Success(ExpenseMonth(all, total))
    }

    suspend fun get(id: Long): ApiResult<ExpenseDto> = apiCall { api.expense(id) }

    suspend fun create(idempotencyKey: String, input: ExpenseInput): ApiResult<ExpenseDto> {
        input.validate()?.let { return ApiResult.Failure(clientValidation(it)) }
        return apiCall { api.createExpense(idempotencyKey, input.toJson()) }
    }

    /** PUT dengan seluruh field. Catatan: expense tidak ditemukan dikembalikan server sebagai 422. */
    suspend fun update(id: Long, input: ExpenseInput): ApiResult<ExpenseDto> {
        input.validate()?.let { return ApiResult.Failure(clientValidation(it)) }
        return apiCall { api.updateExpense(id, input.toJson()) }
    }

    suspend fun delete(id: Long): ApiResult<Unit> {
        if (!flags.delete) return ApiResult.Failure(clientValidation("Hapus pengeluaran dinonaktifkan."))
        return when (val result = apiCall { api.deleteExpense(id) }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 50
    }
}
