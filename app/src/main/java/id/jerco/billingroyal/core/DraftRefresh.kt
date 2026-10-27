package id.jerco.billingroyal.core

import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.CustomerDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Hasil pemeriksaan ulang customer milik draft yang dipulihkan (BR-03). */
sealed interface DraftCustomerCheck {
    /** Customer sama, price list sama. */
    data object Unchanged : DraftCustomerCheck

    /** Price list customer di server berbeda dari yang tersimpan di draft: harga lama TIDAK boleh dipakai. */
    data class PriceListChanged(val customer: CustomerDto) : DraftCustomerCheck

    /** Customer tidak ditemukan lagi (404/422): operator harus memilih ulang. */
    data object CustomerGone : DraftCustomerCheck

    /** Tidak bisa diperiksa (jaringan/server): harga ditandai mungkin tidak terbaru. */
    data object Unverified : DraftCustomerCheck

    companion object {
        fun evaluate(draftPriceListId: Long?, result: ApiResult<CustomerDto>): DraftCustomerCheck = when (result) {
            is ApiResult.Success ->
                if (result.data.price_list_id != draftPriceListId) PriceListChanged(result.data) else Unchanged
            is ApiResult.Failure -> {
                val e = result.error
                if (e is AppError.Api && (e.status == 404 || e.status == 422)) CustomerGone else Unverified
            }
        }
    }
}

/**
 * Menjalankan permintaan "terbaru saja": permintaan baru membatalkan yang lama, dan hasil permintaan yang sudah
 * digantikan tidak pernah diterapkan (mencegah harga customer A menimpa harga customer B, BR-02).
 */
class LatestOnly(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0L

    fun <T> launch(block: suspend () -> T, onResult: (T) -> Unit) {
        job?.cancel()
        val mine = ++generation
        job = scope.launch {
            try {
                val result = block()
                if (mine == generation) onResult(result)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
