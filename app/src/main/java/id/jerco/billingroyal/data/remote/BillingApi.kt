package id.jerco.billingroyal.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Kontrak REST API v1 (path relatif terhadap BASE_URL dari BuildConfig).
 * Tidak ada business_id di request mana pun; tenant ditentukan server dari token.
 */
interface BillingApi {

    @GET("health")
    suspend fun health(): Envelope<HealthDto>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Envelope<LoginData>

    @POST("auth/logout")
    suspend fun logout(): Envelope<LogoutData>

    /** Cabut token tertentu (dipakai setelah data lokal dihapus, atau bila token gagal disimpan). */
    @POST("auth/logout")
    suspend fun logoutToken(@Header("Authorization") authorization: String): Envelope<LogoutData>

    @GET("me")
    suspend fun me(): Envelope<UserDto>

    @GET("profile")
    suspend fun profile(): Envelope<ProfileDto>

    // ---- customers
    @GET("customers")
    suspend fun customers(
        @Query("q") q: String?,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Envelope<List<CustomerDto>>

    @POST("customers")
    suspend fun createCustomer(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: JsonObject,
    ): Envelope<CustomerDto>

    @GET("customers/{id}")
    suspend fun customer(@Path("id") id: Long): Envelope<CustomerDto>

    /** PUT dengan SELURUH field (PATCH tidak pernah dipakai untuk customer). */
    @PUT("customers/{id}")
    suspend fun updateCustomer(@Path("id") id: Long, @Body body: JsonObject): Envelope<CustomerDto>

    @DELETE("customers/{id}")
    suspend fun deleteCustomer(@Path("id") id: Long): Envelope<DeletedData>

    // ---- katalog
    @GET("services")
    suspend fun services(@Query("q") q: String?): Envelope<List<ServiceDto>>

    @GET("price-lists")
    suspend fun priceLists(): Envelope<List<PriceListSummaryDto>>

    @GET("price-lists/{id}")
    suspend fun priceList(@Path("id") id: Long): Envelope<PriceListDetailDto>

    // ---- invoices
    @GET("invoices")
    suspend fun invoices(
        @Query("q") q: String?,
        @Query("month") month: String?,
        @Query("customer_id") customerId: Long?,
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Envelope<List<InvoiceDto>>

    @GET("invoices/{id}")
    suspend fun invoice(@Path("id") id: Long): Envelope<InvoiceDto>

    @POST("invoices")
    suspend fun createInvoice(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: InvoiceCreateRequest,
    ): Envelope<InvoiceDto>

    @PATCH("invoices/{id}/status")
    suspend fun updateInvoiceStatus(@Path("id") id: Long, @Body body: StatusRequest): Envelope<InvoiceDto>

    // ---- expenses
    @GET("expenses")
    suspend fun expenses(
        @Query("month") month: String?,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): Envelope<List<ExpenseDto>>

    @POST("expenses")
    suspend fun createExpense(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: JsonObject,
    ): Envelope<ExpenseDto>

    @GET("expenses/{id}")
    suspend fun expense(@Path("id") id: Long): Envelope<ExpenseDto>

    /** PUT dengan SELURUH field (PATCH tidak pernah dipakai untuk expense). */
    @PUT("expenses/{id}")
    suspend fun updateExpense(@Path("id") id: Long, @Body body: JsonObject): Envelope<ExpenseDto>

    @DELETE("expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: Long): Envelope<DeletedData>

    // ---- laporan
    @GET("reports/invoices")
    suspend fun invoiceReport(
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
        @Query("customer_id") customerId: Long?,
        @Query("payment_scope") paymentScope: String,
    ): Envelope<InvoiceReportDto>
}
