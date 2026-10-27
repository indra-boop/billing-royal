package id.jerco.billingroyal.ui.components

import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.repo.Paged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Status layar umum: Loading, Error (dengan Coba lagi), Content (Empty ditentukan oleh isi). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Content<T>(val data: T) : Load<T>
}

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    val hasMore: Boolean = false,
    val fromCache: Boolean = false,
    val page: Int = 0,
) {
    fun toLoad(): Load<List<T>> = when {
        loading && items.isEmpty() -> Load.Loading
        error != null && items.isEmpty() -> Load.Error(error)
        else -> Load.Content(items)
    }
}

/** Pemuat list berhalaman (infinite scroll + pull-to-refresh). Muat ulang membatalkan permintaan sebelumnya. */
class Pager<T>(
    private val scope: CoroutineScope,
    /** Kunci unik item; item dengan kunci sama (server menggeser halaman saat data berubah) tidak ditampilkan dua kali. */
    private val key: ((T) -> Any)? = null,
    private val fetch: suspend (page: Int) -> ApiResult<Paged<T>>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state

    private var job: Job? = null

    /** [pull] = true dari gestur tarik-untuk-segarkan (daftar lama tetap tampil). */
    fun reload(pull: Boolean = false) {
        job?.cancel()
        _state.update {
            if (pull) it.copy(refreshing = true, error = null, loadMoreError = null, loadingMore = false)
            else PagedState(loading = true)
        }
        job = scope.launch {
            when (val result = fetch(1)) {
                is ApiResult.Success -> _state.value = PagedState(
                    items = dedup(result.data.items),
                    loading = false,
                    hasMore = result.data.hasMore,
                    fromCache = result.data.fromCache,
                    page = result.data.page,
                )
                is ApiResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.error.message)
                }
            }
        }
    }

    private fun dedup(list: List<T>): List<T> = key?.let { k -> list.distinctBy(k) } ?: list

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.refreshing || s.loadingMore || !s.hasMore || s.error != null) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        val previous = job
        job = scope.launch {
            previous?.join()
            when (val result = fetch(s.page + 1)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        items = dedup(it.items + result.data.items),
                        loadingMore = false,
                        hasMore = result.data.hasMore,
                        page = result.data.page,
                        fromCache = it.fromCache || result.data.fromCache,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, loadMoreError = result.error.message) }
            }
        }
    }
}
