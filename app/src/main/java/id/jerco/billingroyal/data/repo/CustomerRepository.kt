package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.data.local.CustomerDao
import id.jerco.billingroyal.data.local.CustomerEntity
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.CustomerDto
import id.jerco.billingroyal.data.remote.apiCall
import id.jerco.billingroyal.core.FeatureFlags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class CustomerInput(
    val name: String,
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val priceListId: Long? = null,
) {
    /** Body lengkap (semua field) untuk POST dan PUT. Field kosong dikirim sebagai null. */
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name.trim()),
            "email" to email.trim().ifEmpty { null }.toJson(),
            "phone" to phone.trim().ifEmpty { null }.toJson(),
            "address" to address.trim().ifEmpty { null }.toJson(),
            "price_list_id" to (priceListId?.let { JsonPrimitive(it) } ?: JsonNull),
        ),
    )
}

internal fun String?.toJson() = if (this == null) JsonNull else JsonPrimitive(this)

@Singleton
class CustomerRepository @Inject constructor(
    private val api: BillingApi,
    private val dao: CustomerDao,
    private val scope: ScopeProvider,
    private val flags: FeatureFlags,
) {
    suspend fun list(q: String, page: Int, perPage: Int = PER_PAGE): ApiResult<Paged<CustomerDto>> {
        val query = q.trim()
        val ticket = scope.ticket() // diambil SEBELUM request: scope/generasi tidak boleh dibaca setelah respons
        val key = ticket.key
        val result = apiCall { api.customers(query.ifEmpty { null }, page, perPage) }
        return when (result) {
            is ApiResult.Success -> {
                // Cache hanya untuk tampilan: simpan hasil daftar tanpa filter, dan hanya bila sesi masih sama.
                if (query.isEmpty() && scope.isCurrent(ticket)) {
                    if (page == 1) dao.deleteAll(key)
                    dao.upsert(result.data.map { it.toEntity(key) })
                }
                ApiResult.Success(pagedFrom(result.data, result.meta, page, perPage))
            }
            is ApiResult.Failure -> {
                if (result.error is AppError.Network) {
                    val cached = dao.search(key, query, perPage, (page - 1) * perPage)
                    if (cached.isNotEmpty() || page > 1) {
                        // Halaman cache terakhir ditandai tidak memiliki lanjutan bila kurang dari perPage.
                        val hasMore = cached.size >= perPage
                        return ApiResult.Success(
                            Paged(cached.map { it.toDto() }, page, if (hasMore) page + 1 else page, fromCache = true),
                        )
                    }
                }
                result
            }
        }
    }

    suspend fun get(id: Long): ApiResult<CustomerDto> {
        val ticket = scope.ticket()
        val result = apiCall { api.customer(id) }
        cacheIfCurrent(ticket, result)
        return result
    }

    suspend fun create(idempotencyKey: String, input: CustomerInput): ApiResult<CustomerDto> {
        val ticket = scope.ticket()
        val result = apiCall { api.createCustomer(idempotencyKey, input.toJson()) }
        cacheIfCurrent(ticket, result)
        return result
    }

    /** PUT dengan seluruh field. */
    suspend fun update(id: Long, input: CustomerInput): ApiResult<CustomerDto> {
        val ticket = scope.ticket()
        val result = apiCall { api.updateCustomer(id, input.toJson()) }
        cacheIfCurrent(ticket, result)
        return result
    }

    suspend fun delete(id: Long): ApiResult<Unit> {
        if (!flags.delete) return ApiResult.Failure(clientValidation("Hapus customer dinonaktifkan."))
        val ticket = scope.ticket()
        return when (val result = apiCall { api.deleteCustomer(id) }) {
            is ApiResult.Success -> {
                if (scope.isCurrent(ticket)) dao.deleteById(ticket.key, id)
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    private suspend fun cacheIfCurrent(ticket: ScopeTicket, result: ApiResult<CustomerDto>) {
        if (result is ApiResult.Success && scope.isCurrent(ticket)) dao.upsert(listOf(result.data.toEntity(ticket.key)))
    }

    private fun CustomerDto.toEntity(scope: String) =
        CustomerEntity(scope, id, name, email, phone, address, price_list_id, price_list_name, created_at)

    private fun CustomerEntity.toDto() =
        CustomerDto(id, name, email, phone, address, priceListId, priceListName, createdAt)

    companion object {
        const val PER_PAGE = 20
    }
}
