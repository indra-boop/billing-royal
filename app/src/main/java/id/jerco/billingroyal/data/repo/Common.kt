package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.Meta

/** Sumber kunci cache ("user_id:business_id"). Diimplementasikan oleh SessionManager. */
interface ScopeProvider {
    val scopeKey: String
}

data class Paged<T>(
    val items: List<T>,
    val page: Int,
    val lastPage: Int,
    val total: Long? = null,
    val fromCache: Boolean = false,
) {
    val hasMore: Boolean get() = page < lastPage
}

internal fun <T> pagedFrom(items: List<T>, meta: Meta?, requestedPage: Int, perPage: Int): Paged<T> {
    val page = meta?.page?.toInt() ?: requestedPage
    val last = meta?.last_page?.toInt()
        ?: meta?.total?.let { ((it + perPage - 1) / perPage).toInt() }
        ?: if (items.size >= perPage) page + 1 else page
    return Paged(items, page, maxOf(last, page), meta?.total)
}

/** Kesalahan validasi sisi klien (tidak mengirim request). */
fun clientValidation(message: String): AppError = AppError.Api(422, "client_validation", message)
