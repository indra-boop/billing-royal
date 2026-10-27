package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.data.local.CatalogDao
import id.jerco.billingroyal.data.local.PriceListEntity
import id.jerco.billingroyal.data.local.PriceListItemEntity
import id.jerco.billingroyal.data.local.ServiceEntity
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.PriceListDetailDto
import id.jerco.billingroyal.data.remote.PriceListItemDto
import id.jerco.billingroyal.data.remote.PriceListSummaryDto
import id.jerco.billingroyal.data.remote.ServiceDto
import id.jerco.billingroyal.data.remote.apiCall
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Katalog service dan price list (cache read-only untuk tampilan). */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: BillingApi,
    private val dao: CatalogDao,
    private val scope: ScopeProvider,
) {
    suspend fun services(q: String): ApiResult<List<ServiceDto>> {
        val query = q.trim()
        val key = scope.scopeKey
        return when (val result = apiCall { api.services(query.ifEmpty { null }) }) {
            is ApiResult.Success -> {
                if (query.isEmpty()) {
                    dao.deleteServices(key)
                    dao.upsertServices(result.data.map { ServiceEntity(key, it.id, it.item_code, it.category, it.name, it.unit) })
                }
                result
            }
            is ApiResult.Failure -> {
                if (result.error is AppError.Network) {
                    val cached = dao.searchServices(key, query)
                    if (cached.isNotEmpty()) {
                        return ApiResult.Success(cached.map { ServiceDto(it.id, it.itemCode, it.category, it.name, it.unit) })
                    }
                }
                result
            }
        }
    }

    suspend fun priceLists(): ApiResult<List<PriceListSummaryDto>> {
        val key = scope.scopeKey
        return when (val result = apiCall { api.priceLists() }) {
            is ApiResult.Success -> {
                dao.deletePriceLists(key)
                dao.upsertPriceLists(result.data.map { PriceListEntity(key, it.id, it.name, it.item_count) })
                result
            }
            is ApiResult.Failure -> {
                if (result.error is AppError.Network) {
                    val cached = dao.priceLists(key)
                    if (cached.isNotEmpty()) {
                        return ApiResult.Success(cached.map { PriceListSummaryDto(it.id, it.name, it.itemCount) })
                    }
                }
                result
            }
        }
    }

    suspend fun priceList(id: Long): ApiResult<PriceListDetailDto> {
        val key = scope.scopeKey
        return when (val result = apiCall { api.priceList(id) }) {
            is ApiResult.Success -> {
                dao.deletePriceListItems(key, id)
                dao.upsertPriceListItems(
                    result.data.items.map {
                        PriceListItemEntity(key, id, it.service_id, it.item_code, it.category, it.service_name, it.unit, it.price.toPlainString())
                    },
                )
                result
            }
            is ApiResult.Failure -> {
                if (result.error is AppError.Network) {
                    val cached = dao.priceListItems(key, id)
                    if (cached.isNotEmpty()) {
                        return ApiResult.Success(
                            PriceListDetailDto(
                                id = id,
                                items = cached.map {
                                    PriceListItemDto(0, it.serviceId, it.itemCode, it.category, it.serviceName, it.unit, BigDecimal(it.price))
                                },
                            ),
                        )
                    }
                }
                result
            }
        }
    }

    /** Peta service_id -> harga dari price list. Price list null = peta kosong (harga tidak diketahui). */
    suspend fun priceMap(priceListId: Long?): ApiResult<Map<Long, BigDecimal>> {
        if (priceListId == null) return ApiResult.Success(emptyMap())
        return when (val result = priceList(priceListId)) {
            is ApiResult.Success -> ApiResult.Success(result.data.items.associate { it.service_id to it.price })
            is ApiResult.Failure -> result
        }
    }
}
