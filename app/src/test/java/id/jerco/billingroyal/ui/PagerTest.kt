package id.jerco.billingroyal.ui

import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.repo.Paged
import id.jerco.billingroyal.ui.components.Pager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PagerTest {
    private data class Row(val id: Long)

    @Test
    fun `item dengan id sama pada halaman berikutnya tidak ditampilkan dua kali`() = runTest(UnconfinedTestDispatcher()) {
        val pager = Pager<Row>(TestScope(UnconfinedTestDispatcher(testScheduler)), key = { it.id }) { page ->
            when (page) {
                1 -> ApiResult.Success(Paged(listOf(Row(1), Row(2), Row(3)), page = 1, lastPage = 2))
                // server menggeser data: Row(3) muncul lagi di halaman 2
                else -> ApiResult.Success(Paged(listOf(Row(3), Row(4)), page = 2, lastPage = 2))
            }
        }
        pager.reload()
        pager.loadMore()
        assertEquals(listOf(1L, 2L, 3L, 4L), pager.state.value.items.map { it.id })
    }
}
