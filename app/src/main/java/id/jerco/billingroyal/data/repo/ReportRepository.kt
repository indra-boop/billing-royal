package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.InvoiceReportDto
import id.jerco.billingroyal.data.remote.PaymentScope
import id.jerco.billingroyal.data.remote.apiCall
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepository @Inject constructor(private val api: BillingApi) {

    /**
     * Respons tidak dipaginasi: date_from dan date_to SELALU dikirim, rentang maksimal 31 hari
     * (inklusif kedua ujung).
     */
    suspend fun invoices(
        from: LocalDate,
        to: LocalDate,
        paymentScope: String = PaymentScope.ALL,
        customerId: Long? = null,
    ): ApiResult<InvoiceReportDto> {
        validateRange(from, to)?.let { return ApiResult.Failure(clientValidation(it)) }
        return apiCall {
            api.invoiceReport(AppDates.apiDate(from), AppDates.apiDate(to), customerId, paymentScope)
        }
    }

    companion object {
        const val MAX_DAYS = 31L

        fun validateRange(from: LocalDate, to: LocalDate): String? = when {
            to.isBefore(from) -> "Tanggal akhir tidak boleh sebelum tanggal awal."
            ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS -> "Rentang tanggal maksimal $MAX_DAYS hari."
            else -> null
        }
    }
}
