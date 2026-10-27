package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.core.MoneyParser
import id.jerco.billingroyal.data.local.DraftDao
import id.jerco.billingroyal.data.local.InvoiceDraftEntity
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.InvoiceCreateRequest
import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.InvoiceItemRequest
import id.jerco.billingroyal.data.remote.InvoiceStatus
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.remote.StatusRequest
import id.jerco.billingroyal.data.remote.apiCall
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Item draft (disimpan lokal sebagai JSON). Qty/harga override disimpan sebagai teks input. */
@Serializable
data class DraftItem(
    val serviceId: Long,
    val name: String,
    val unit: String,
    val qtyText: String,
    val overridePriceText: String = "",
)

/** Draft invoice lokal + Idempotency-Key yang dibuat SEKALI saat Simpan ditekan. */
data class InvoiceDraft(
    val customerId: Long?,
    val customerName: String?,
    val priceListId: Long?,
    val invoiceDate: LocalDate,
    val notes: String,
    val items: List<DraftItem>,
    val idempotencyKey: String? = null,
    val payloadHash: String? = null,
)

@Singleton
class InvoiceRepository @Inject constructor(
    private val api: BillingApi,
    private val draftDao: DraftDao,
    private val scope: ScopeProvider,
    private val flags: FeatureFlags,
) {
    // ---------------------------------------------------------------- baca

    suspend fun list(
        q: String,
        month: YearMonth?,
        customerId: Long?,
        status: String?,
        page: Int,
        perPage: Int = PER_PAGE,
    ): ApiResult<Paged<InvoiceDto>> {
        val result = apiCall {
            api.invoices(
                q = q.trim().ifEmpty { null },
                month = month?.let { AppDates.apiMonth(it) },
                customerId = customerId,
                status = status,
                page = page,
                perPage = perPage,
            )
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(pagedFrom(result.data, result.meta, page, perPage))
            is ApiResult.Failure -> result
        }
    }

    suspend fun get(id: Long): ApiResult<InvoiceDto> = apiCall { api.invoice(id) }

    // ---------------------------------------------------------------- tulis

    /**
     * Validasi sisi klien lalu bentuk body POST. Status SELALU "Draft"; unit_price hanya dikirim
     * bila ENABLE_PRICE_OVERRIDE aktif dan pengguna mengisi harga. Tidak ada business_id.
     */
    fun buildRequest(draft: InvoiceDraft): ApiResult<InvoiceCreateRequest> {
        val customerId = draft.customerId ?: return ApiResult.Failure(clientValidation("Pilih customer terlebih dahulu."))
        if (draft.items.isEmpty()) return ApiResult.Failure(clientValidation("Tambahkan minimal satu item."))
        if (draft.items.size > MAX_ITEMS) return ApiResult.Failure(clientValidation("Maksimal $MAX_ITEMS item per invoice."))
        if (draft.notes.length > MAX_NOTES) return ApiResult.Failure(clientValidation("Catatan maksimal $MAX_NOTES karakter."))

        val items = ArrayList<InvoiceItemRequest>(draft.items.size)
        for ((index, item) in draft.items.withIndex()) {
            val qty = MoneyParser.parseQuantity(item.qtyText)
                ?: return ApiResult.Failure(clientValidation("Qty item ke-${index + 1} harus lebih dari 0 dengan maksimal 2 desimal."))
            val override = if (flags.priceOverride && item.overridePriceText.isNotBlank()) {
                MoneyParser.parseAmount(item.overridePriceText)
                    ?: return ApiResult.Failure(clientValidation("Harga item ke-${index + 1} tidak valid."))
            } else {
                null
            }
            items += InvoiceItemRequest(service_id = item.serviceId, qty = qty, unit_price = override)
        }
        return ApiResult.Success(
            InvoiceCreateRequest(
                customer_id = customerId,
                invoice_date = AppDates.apiDate(draft.invoiceDate),
                status = InvoiceStatus.DRAFT,
                notes = draft.notes.trim().ifEmpty { null },
                items = items,
            ),
        )
    }

    /**
     * Mengirim invoice. Idempotency-Key (UUID v4) dibuat sekali per payload, disimpan bersama draft
     * SEBELUM request dikirim, dan dipakai ulang bila pengguna mengirim ulang payload yang sama.
     * TIDAK ada auto-retry: pada hasil tidak pasti pemanggil harus meminta pengguna memeriksa dulu.
     */
    suspend fun submit(draft: InvoiceDraft): ApiResult<InvoiceDto> {
        val request = when (val built = buildRequest(draft)) {
            is ApiResult.Success -> built.data
            is ApiResult.Failure -> return built
        }
        val hash = sha256(NetworkFactory.json.encodeToString(request))
        val key = if (draft.idempotencyKey != null && draft.payloadHash == hash) {
            draft.idempotencyKey
        } else {
            UUID.randomUUID().toString()
        }
        saveDraft(draft.copy(idempotencyKey = key, payloadHash = hash))

        val result = apiCall { api.createInvoice(key, request) }
        if (result is ApiResult.Success) draftDao.delete(scope.scopeKey)
        return result
    }

    /** Ubah status. Hanya status yang diizinkan flag yang boleh dikirim; selain itu ditolak di sisi klien. */
    suspend fun updateStatus(id: Long, status: String): ApiResult<InvoiceDto> {
        val allowed = when (status) {
            InvoiceStatus.SENT -> flags.statusChange
            InvoiceStatus.PAID -> flags.markPaid
            InvoiceStatus.CANCELLED -> flags.cancelInvoice
            else -> false
        }
        if (!allowed) return ApiResult.Failure(clientValidation("Perubahan status ke \"$status\" dinonaktifkan."))
        return apiCall { api.updateInvoiceStatus(id, StatusRequest(status)) }
    }

    // ---------------------------------------------------------------- draft lokal

    suspend fun loadDraft(): InvoiceDraft? = draftDao.get(scope.scopeKey)?.let { e ->
        InvoiceDraft(
            customerId = e.customerId,
            customerName = e.customerName,
            priceListId = e.priceListId,
            invoiceDate = AppDates.parseApiDate(e.invoiceDate) ?: AppDates.today(),
            notes = e.notes,
            items = try {
                NetworkFactory.json.decodeFromString<List<DraftItem>>(e.itemsJson)
            } catch (_: Exception) {
                emptyList()
            },
            idempotencyKey = e.idempotencyKey,
            payloadHash = e.payloadHash,
        )
    }

    suspend fun saveDraft(draft: InvoiceDraft) {
        draftDao.upsert(
            InvoiceDraftEntity(
                scope = scope.scopeKey,
                customerId = draft.customerId,
                customerName = draft.customerName,
                priceListId = draft.priceListId,
                invoiceDate = AppDates.apiDate(draft.invoiceDate),
                notes = draft.notes,
                itemsJson = NetworkFactory.json.encodeToString(draft.items),
                idempotencyKey = draft.idempotencyKey,
                payloadHash = draft.payloadHash,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearDraft() = draftDao.delete(scope.scopeKey)

    companion object {
        const val PER_PAGE = 20
        const val MAX_ITEMS = 200
        const val MAX_NOTES = 5000

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
